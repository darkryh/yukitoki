// The server behind the integration tests. It has no dependencies, serves the same deterministic
// bytes over HTTP/1.1 and HTTP/2, and misbehaves on request.
//
//   GET /f/<name>?size=<bytes>[&rate=<MiB/s>][&cuts=<n>][&cut=drop|reset|goaway][&stallfirst=1]
//                 [&freeze=1][&cors=1][&attach=1][&type=<content type>]
//                 freeze: over HTTP/2, stop answering anything on that connection, as a dead one does
//   GET /sha?size=<bytes>   SHA-256 of the body for that size
//   GET /log?name=<name>    One "METHOD|range|httpVersion" line per request for that name
//   GET /config             The checks this run enabled
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import http2 from 'node:http2';

const [httpPort, httpsPort, keyPath, certPath] = process.argv.slice(2);

const BLOCK_SIZE = 1 << 20;
const CHUNK_SIZE = 256 * 1024;
const block = Buffer.alloc(BLOCK_SIZE);
for (let i = 0, x = 0x2545f491; i < BLOCK_SIZE; i++) {
  x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
  block[i] = x & 0xff;
}

function bytesAt(offset, length) {
  const out = Buffer.allocUnsafe(length);
  let written = 0;
  while (written < length) {
    const position = (offset + written) % BLOCK_SIZE;
    written += block.copy(out, written, position, Math.min(BLOCK_SIZE, position + length - written));
  }
  return out;
}

const shas = new Map();
function shaFor(size) {
  if (!shas.has(size)) {
    const hash = crypto.createHash('sha256');
    for (let offset = 0; offset < size; offset += BLOCK_SIZE) {
      hash.update(offset + BLOCK_SIZE <= size ? block : block.subarray(0, size - offset));
    }
    shas.set(size, hash.digest('hex'));
  }
  return shas.get(size);
}

const logs = new Map();
const getCounts = new Map();
const frozenSessions = new WeakSet();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function breakConnection(req, kind) {
  const stream = req.stream;
  if (!stream) return req.socket.destroy();
  if (kind === 'goaway') {
    stream.session.goaway(http2.constants.NGHTTP2_INTERNAL_ERROR);
    return stream.session.destroy();
  }
  stream.close(http2.constants.NGHTTP2_INTERNAL_ERROR);
}

async function serveFile(req, res, url) {
  const name = url.pathname.slice('/f/'.length);
  const size = Number(url.searchParams.get('size') ?? BLOCK_SIZE);
  const rangeHeader = req.headers.range;
  if (!logs.has(name)) logs.set(name, []);
  logs.get(name).push(`${req.method}|${rangeHeader ?? ''}|${req.httpVersion}`);

  const cors = url.searchParams.has('cors')
    ? { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization' }
    : {};
  if (req.method === 'OPTIONS') {
    res.writeHead(204, cors);
    return res.end();
  }

  const match = /^bytes=(\d+)-(\d*)$/.exec(rangeHeader ?? '');
  let start = 0;
  let end = size - 1;
  if (match) {
    start = Number(match[1]);
    if (match[2]) end = Math.min(Number(match[2]), size - 1);
    if (start >= size) {
      res.writeHead(416, { 'content-range': `bytes */${size}` });
      return res.end();
    }
  }
  const length = Math.max(0, end - start + 1);
  res.writeHead(match ? 206 : 200, {
    etag: '"v1"',
    'accept-ranges': 'bytes',
    'content-type': url.searchParams.get('type') ?? 'application/octet-stream',
    'content-length': String(length),
    ...cors,
    ...(url.searchParams.has('attach') ? { 'content-disposition': 'attachment' } : {}),
    ...(match ? { 'content-range': `bytes ${start}-${end}/${size}` } : {}),
  });
  if (req.method === 'HEAD') return res.end();

  const count = (getCounts.get(name) ?? 0) + 1;
  getCounts.set(name, count);
  const freezeAt = url.searchParams.has('freeze') && count === 1 ? Math.floor(length / 2) : Infinity;
  const cuts = Number(url.searchParams.get('cuts') ?? 0);
  const breakAt = count <= cuts ? Math.floor(length * 0.4) : Infinity;
  const stallAt = url.searchParams.has('stallfirst') && count === 1 ? Math.floor(length / 2) : Infinity;
  const rate = Number(url.searchParams.get('rate') ?? 0) * BLOCK_SIZE;

  let sent = 0;
  let closed = false;
  res.once('close', () => { closed = true; });
  while (sent < length && !closed) {
    if (frozenSessions.has(req.stream?.session)) return;
    if (sent >= stallAt) return; // Never finishes: the client has to notice by itself.
    if (sent >= freezeAt) return frozenSessions.add(req.stream?.session);
    if (sent >= breakAt) return breakConnection(req, url.searchParams.get('cut') ?? 'drop');
    const chunk = Math.min(CHUNK_SIZE, length - sent);
    const flowing = res.write(bytesAt(start + sent, chunk));
    sent += chunk;
    if (!flowing) await new Promise((resolve) => {
      const done = () => { res.off('drain', done); res.off('close', done); resolve(); };
      res.once('drain', done);
      res.once('close', done);
    });
    if (rate > 0) await sleep((chunk / rate) * 1000);
  }
  if (!closed) res.end();
}

function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  if (frozenSessions.has(req.stream?.session)) {
    const name = url.pathname.slice('/f/'.length);
    if (logs.has(name)) logs.get(name).push(`${req.method}|${req.headers.range ?? ''}|frozen`);
    return; // Never answered, but a new connection works.
  }
  const text = (body) => {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end(body);
  };
  if (url.pathname === '/config') {
    return text([
      `large=${process.env.CHECK_LARGE === '1'}`,
      `realCdn=${process.env.CHECK_REAL_CDN === '1'}`,
      `diskFull=${process.env.CHECK_DISK_FULL ?? ''}`,
    ].join('\n'));
  }
  if (url.pathname === '/sha') return text(shaFor(Number(url.searchParams.get('size'))));
  if (url.pathname === '/log') return text((logs.get(url.searchParams.get('name')) ?? []).join('\n'));
  if (url.pathname.startsWith('/f/')) {
    return serveFile(req, res, url).catch(() => res.destroy());
  }
  if (url.pathname === '/page') {
    const headers = { 'content-type': 'text/html', 'cache-control': 'no-store' };
    if (url.searchParams.has('attach')) headers['content-disposition'] = 'attachment';
    res.writeHead(200, headers);
    return res.end('<!doctype html><title>page</title>');
  }
  // The browser app, when its directory is set.
  const root = process.env.CHECK_BROWSER_ROOT;
  if (root) {
    const relative = url.pathname === '/' ? 'index.html' : url.pathname.slice(1).replaceAll('..', '');
    const types = { html: 'text/html', js: 'text/javascript', wasm: 'application/wasm', mjs: 'text/javascript' };
    try {
      const body = fs.readFileSync(`${root}/${relative}`);
      res.writeHead(200, { 'content-type': types[relative.split('.').pop()] ?? 'application/octet-stream' });
      return res.end(body);
    } catch { /* Falls through to the 404. */ }
  }
  res.writeHead(404);
  res.end();
}

http.createServer(handle).listen(Number(httpPort), '127.0.0.1');
http2
  .createSecureServer({ key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) }, handle)
  .on('sessionError', () => {})
  .listen(Number(httpsPort), '127.0.0.1');
console.log(`ready http=${httpPort} https=${httpsPort}`);

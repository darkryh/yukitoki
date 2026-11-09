// Drives the test page in a real browser, with its popup blocker on, and checks what the user would
// actually see: what got saved, whether the app stayed open, and which tabs appeared.
//
//   node run.mjs chrome|firefox|safari
//
// Needs the server from run.sh on port 8766. Safari also needs `safaridriver --enable`, once.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const browserName = process.argv[2];
const PAGE = 'http://localhost:8766';
const CROSS = 'http://127.0.0.1:8766';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const run = Date.now().toString(36);

/** Every name is unique to this run, so a browser's real Downloads folder can be watched safely. */
function cases() {
  const name = (label) => `yukitoki-${run}-${label}`;
  const list = [];
  for (const click of [false, true]) {
    const tag = click ? 'click' : 'auto';
    list.push(
      {
        label: `same origin saves under the chosen name (${tag})`, click,
        url: `${PAGE}/f/${name(`src-${tag}`)}.bin?size=1000`, fileName: `${name(`chosen-${tag}`)}.bin`,
        saves: `${name(`chosen-${tag}`)}.bin`,
      },
      {
        label: `cross origin file saves under the URL's name (${tag})`, click,
        url: `${CROSS}/f/${name(`cross-${tag}`)}.bin?size=1234`, fileName: `${name(`ignored-${tag}`)}.bin`,
        saves: `${name(`cross-${tag}`)}.bin`,
      },
      {
        label: `cross origin attachment saves (${tag})`, click,
        url: `${CROSS}/f/${name(`attach-${tag}`)}.html?size=500&attach=1&type=text/html`, fileName: `${name(`ignored2-${tag}`)}.html`,
        saves: `${name(`attach-${tag}`)}.html`,
      },
      {
        label: `cross origin page doesn't replace the app (${tag})`, click,
        url: `${CROSS}/page?run=${run}${tag}`, fileName: `${name(`page-${tag}`)}.html`,
        savesNothing: true, opensTab: click,
      },
      {
        label: `headers save under the chosen name (${tag})`, click, auth: true,
        url: `${PAGE}/f/${name(`auth-${tag}`)}.bin?size=2000`, fileName: `${name(`authchosen-${tag}`)}.bin`,
        saves: `${name(`authchosen-${tag}`)}.bin`,
      },
      {
        label: `headers across origins with CORS save under the chosen name (${tag})`, click, auth: true,
        url: `${CROSS}/f/${name(`cors-${tag}`)}.bin?size=3000&cors=1`, fileName: `${name(`corschosen-${tag}`)}.bin`,
        saves: `${name(`corschosen-${tag}`)}.bin`,
      },
      {
        label: `headers across origins without CORS are Rejected (${tag})`, click, auth: true,
        url: `${CROSS}/f/${name(`nocors-${tag}`)}.bin?size=3000`, fileName: `${name(`nocorschosen-${tag}`)}.bin`,
        rejected: true, savesNothing: true,
      },
    );
  }
  return list;
}

async function puppeteerDriver(name) {
  const { default: puppeteer } = await import('puppeteer-core');
  const downloads = fs.mkdtempSync(path.join(os.tmpdir(), 'yukitoki-browser-'));
  const browser = name === 'firefox'
    ? await puppeteer.launch({
      browser: 'firefox',
      executablePath: process.env.FIREFOX_PATH ?? '/Applications/Firefox.app/Contents/MacOS/firefox',
      headless: true,
      extraPrefsFirefox: {
        'browser.download.dir': downloads,
        'browser.download.folderList': 2,
        'browser.download.useDownloadDir': true,
        'browser.download.always_ask_before_handling_new_types': false,
        'browser.helperApps.neverAsk.saveToDisk': 'application/octet-stream,text/html',
        'browser.download.alwaysOpenPanel': false,
        'dom.disable_open_during_load': true,
      },
    })
    : await puppeteer.launch({
      executablePath: process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      headless: true,
      args: ['--no-first-run'],
      // Puppeteer turns the popup blocker off by default, which hides what real users get.
      ignoreDefaultArgs: ['--disable-popup-blocking'],
    });
  console.log(`browser: ${await browser.version()}`);
  return {
    downloads,
    async visit(url, click) {
      const before = await browser.pages();
      const page = await browser.newPage();
      if (name === 'chrome') {
        const session = await page.createCDPSession();
        await session.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads });
      }
      await page.goto(url, { waitUntil: 'load' });
      await sleep(1000);
      if (click) await page.mouse.click(20, 20);
      await page.waitForFunction('globalThis.__outcome !== undefined', { timeout: 15000 }).catch(() => {});
      await sleep(2000);
      const opened = (await browser.pages()).filter((other) => other !== page && !before.includes(other));
      const result = {
        tabUrl: page.url(),
        outcome: await page.evaluate('globalThis.__outcome').catch(() => undefined),
        clicks: await page.evaluate('globalThis.__clicks').catch(() => undefined),
        newTabs: opened.map((tab) => tab.url()),
      };
      await Promise.all(opened.map((tab) => tab.close().catch(() => {})));
      await page.close();
      return result;
    },
    async close() {
      await browser.close();
      fs.rmSync(downloads, { recursive: true, force: true });
    },
  };
}

async function safariDriver() {
  const { spawn } = await import('node:child_process');
  const port = 4445;
  const driver = spawn('safaridriver', ['-p', String(port)], { stdio: 'ignore' });
  await sleep(1500);
  const call = async (method, route, body) => {
    const response = await fetch(`http://127.0.0.1:${port}${route}`, {
      method,
      headers: { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const { value } = await response.json();
    if (value?.error) throw new Error(`${value.error}: ${value.message}`);
    return value;
  };
  let id;
  try {
    ({ sessionId: id } = await call('POST', '/session', { capabilities: { alwaysMatch: { browserName: 'safari' } } }));
  } catch (error) {
    driver.kill();
    throw error;
  }
  const session = (route) => `/session/${id}${route}`;
  const main = await call('GET', session('/window'));
  console.log('browser: Safari');
  return {
    downloads: path.join(os.homedir(), 'Downloads'),
    async visit(url, click) {
      const before = await call('GET', session('/window/handles'));
      await call('POST', session('/url'), { url });
      await sleep(1000);
      if (click) {
        await call('POST', session('/actions'), {
          actions: [{
            type: 'pointer', id: 'mouse', parameters: { pointerType: 'mouse' },
            actions: [
              { type: 'pointerMove', duration: 0, x: 20, y: 20 },
              { type: 'pointerDown', button: 0 },
              { type: 'pointerUp', button: 0 },
            ],
          }],
        });
      }
      const read = (expression) => call('POST', session('/execute/sync'), { script: `return ${expression};`, args: [] });
      for (let i = 0; i < 30 && (await read('globalThis.__outcome')) == null; i++) await sleep(500);
      await sleep(2000);
      const handles = await call('GET', session('/window/handles'));
      const result = {
        tabUrl: await call('GET', session('/url')),
        outcome: await read('globalThis.__outcome'),
        clicks: await read('globalThis.__clicks'),
        newTabs: handles.filter((handle) => !before.includes(handle)),
      };
      for (const handle of result.newTabs) {
        await call('POST', session('/window'), { handle });
        await call('DELETE', session('/window'));
      }
      await call('POST', session('/window'), { handle: main });
      return result;
    },
    async close() {
      await call('DELETE', session('')).catch(() => {});
      driver.kill();
    },
  };
}

const driver = browserName === 'safari' ? await safariDriver() : await puppeteerDriver(browserName);
const fileNames = () => fs.readdirSync(driver.downloads);
const isThisRun = (file) => file.startsWith(`yukitoki-${run}-`);
const isComplete = (file) => !/\.(part|crdownload|download)$/.test(file);

let failures = 0;
const check = (label, passed, detail) => {
  if (!passed) failures++;
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${browserName}: ${label}${passed ? '' : `  ${detail}`}`);
};

try {
  for (const testCase of cases()) {
    const page = new URL(`${PAGE}/index.html`);
    page.searchParams.set('url', testCase.url);
    page.searchParams.set('fileName', testCase.fileName);
    if (testCase.click) page.searchParams.set('click', '1');
    if (testCase.auth) page.searchParams.set('auth', '1');

    const seen = new Set(fileNames());
    const result = await driver.visit(page.href, testCase.click);
    if (testCase.saves) {
      for (let i = 0; i < 20 && !fileNames().includes(testCase.saves); i++) await sleep(250);
    }
    const saved = fileNames().filter((file) => !seen.has(file) && isThisRun(file) && isComplete(file));
    const expectedOutcome = testCase.rejected ? result.outcome?.startsWith('Rejected') : result.outcome === 'Dispatched';
    const problems = [
      !result.tabUrl.startsWith(`${PAGE}/index.html`) && `the app was replaced by ${result.tabUrl}`,
      !expectedOutcome && `outcome was ${result.outcome}`,
      result.clicks !== (testCase.click ? 1 : 0) && `the page saw ${result.clicks} clicks`,
      testCase.saves && !saved.includes(testCase.saves) && `expected ${testCase.saves}, saved ${JSON.stringify(saved)}`,
      testCase.savesNothing && saved.length > 0 && `expected no file, saved ${JSON.stringify(saved)}`,
      testCase.opensTab && result.newTabs.length === 0 && 'expected a new tab',
      !testCase.opensTab && result.newTabs.filter((tab) => tab !== 'about:blank').length > 0
        && `unexpected tab ${JSON.stringify(result.newTabs)}`,
    ].filter(Boolean);
    check(testCase.label, problems.length === 0, problems.join('; '));

    for (const file of saved) fs.rmSync(path.join(driver.downloads, file), { force: true });
  }
} finally {
  await driver.close();
}
console.log(`${browserName}: ${failures === 0 ? 'all passed' : `${failures} failed`}`);
process.exit(failures === 0 ? 0 : 1);

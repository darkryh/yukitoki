#!/usr/bin/env bash
# Runs the integration tests: starts the fault-injecting server, trusts its test certificate on each
# platform, and runs the suite. See README.md.
#
#   integration-tests/run.sh [--large] [--real-cdn] [--tests=<pattern>] [jvm] [ios] [android] [chrome] [firefox] [safari]
#
# With no platform it runs jvm and ios. --tests=<pattern> narrows the run on jvm and ios,
# and ANDROID_TEST_CLASS=<class> on android.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/integration-tests/build/run"
HTTP_PORT=8766
HTTPS_PORT=8767
IOS_DEVICE="${IOS_DEVICE:-iPhone 17 Pro}"

CHECK_LARGE=0
CHECK_REAL_CDN=0
PLATFORMS=()
FILTER=()
for argument in "$@"; do
  case "$argument" in
    --large) CHECK_LARGE=1 ;;
    --real-cdn) CHECK_REAL_CDN=1 ;;
    --tests=*) FILTER+=(--tests "${argument#--tests=}") ;;
    jvm|ios|android|chrome|firefox|safari) PLATFORMS+=("$argument") ;;
    *) echo "Unknown argument: $argument" >&2; exit 2 ;;
  esac
done
[ ${#PLATFORMS[@]} -eq 0 ] && PLATFORMS=(jvm ios)

mkdir -p "$WORK"

# A test CA and a server certificate signed by it. iOS rejects one certificate doing both jobs. They're
# kept between runs so each run doesn't add another root to the simulator.
if [ ! -f "$WORK/cert.pem" ] || [ ! -f "$WORK/ca.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 -subj "/CN=Yukitoki Test CA" \
    -keyout "$WORK/ca-key.pem" -out "$WORK/ca.pem" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -sha256 -subj "/CN=localhost" \
    -keyout "$WORK/key.pem" -out "$WORK/cert.csr" 2>/dev/null
  printf '%s\n' "subjectAltName=DNS:localhost" "basicConstraints=CA:FALSE" \
    "keyUsage=digitalSignature,keyEncipherment" "extendedKeyUsage=serverAuth" > "$WORK/cert.ext"
  openssl x509 -req -sha256 -days 300 -in "$WORK/cert.csr" -CA "$WORK/ca.pem" -CAkey "$WORK/ca-key.pem" \
    -CAcreateserial -extfile "$WORK/cert.ext" -out "$WORK/cert.pem" 2>/dev/null
fi

SERVER_PID=""
VOLUME_DEVICE=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  [ -n "$VOLUME_DEVICE" ] && hdiutil detach "$VOLUME_DEVICE" -force >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A small real volume for the disk-full checks. macOS only: elsewhere those checks report SKIPPED.
CHECK_DISK_FULL=""
if [ "$(uname)" = "Darwin" ]; then
  VOLUME_NAME="yukitoki-checks-$$"
  VOLUME_DEVICE="$(hdiutil attach -nomount ram://81920 | awk '{print $1}')" # 40 MiB
  diskutil erasevolume HFS+ "$VOLUME_NAME" "$VOLUME_DEVICE" >/dev/null
  CHECK_DISK_FULL="/Volumes/$VOLUME_NAME"
fi

# The browsers need the test page built before the server can serve it.
BROWSER_ROOT="$ROOT/integration-tests/browser/build/dist/wasmJs/developmentExecutable"
case " ${PLATFORMS[*]} " in
  *" chrome "*|*" firefox "*|*" safari "*)
    (cd "$ROOT" && ./gradlew --no-daemon :integration-tests:browser:wasmJsBrowserDevelopmentExecutableDistribution)
    (cd "$ROOT/integration-tests/browser" && npm install --no-audit --no-fund --silent)
    ;;
esac

CHECK_BROWSER_ROOT="$BROWSER_ROOT" CHECK_LARGE="$CHECK_LARGE" CHECK_REAL_CDN="$CHECK_REAL_CDN" CHECK_DISK_FULL="$CHECK_DISK_FULL" \
  node "$ROOT/integration-tests/server/server.mjs" "$HTTP_PORT" "$HTTPS_PORT" "$WORK/key.pem" "$WORK/cert.pem" &
SERVER_PID=$!
for _ in $(seq 1 50); do
  curl -sf "http://127.0.0.1:$HTTP_PORT/config" >/dev/null && break
  sleep 0.2
done

cd "$ROOT"
STATUS=0
for platform in "${PLATFORMS[@]}"; do
  echo "=== $platform ==="
  case "$platform" in
    jvm)
      # The JDK's own certificates plus the test one, so real CDNs stay trusted too.
      JAVA_LOCATION="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home/ {print $2}')"
      cp -f "$JAVA_LOCATION/lib/security/cacerts" "$WORK/truststore.jks"
      chmod u+w "$WORK/truststore.jks"
      keytool -importcert -noprompt -alias integration-tests -file "$WORK/ca.pem" \
        -keystore "$WORK/truststore.jks" -storepass changeit 2>/dev/null
      ./gradlew --no-daemon :integration-tests:jvmTest "${FILTER[@]+"${FILTER[@]}"}" -PintegrationTests \
        "-PintegrationTests.trustStore=$WORK/truststore.jks" || STATUS=1
      ;;
    ios)
      xcrun simctl boot "$IOS_DEVICE" 2>/dev/null || true
      xcrun simctl keychain "$IOS_DEVICE" add-root-cert "$WORK/ca.pem"
      ./gradlew --no-daemon :integration-tests:iosSimulatorArm64Test "${FILTER[@]+"${FILTER[@]}"}" -PintegrationTests \
        "-PintegrationTests.iosDevice=$IOS_DEVICE" || STATUS=1
      ;;
    android)
      adb reverse "tcp:$HTTP_PORT" "tcp:$HTTP_PORT"
      adb reverse "tcp:$HTTPS_PORT" "tcp:$HTTPS_PORT"
      ./gradlew --no-daemon :integration-tests:connectedAndroidDeviceTest -PintegrationTests \
        ${ANDROID_TEST_CLASS:+"-Pandroid.testInstrumentationRunnerArguments.class=$ANDROID_TEST_CLASS"} || STATUS=1
      ;;
    chrome|firefox|safari)
      node "$ROOT/integration-tests/browser/run.mjs" "$platform" || STATUS=1
      ;;
  esac
done
exit "$STATUS"

#!/usr/bin/env bash
# Starts the Folia test server in offline mode, joins it with a real Minecraft client run by
# HeadlessMC, and waits for the plugin's self-test to give its verdict. See
# https://headlesshq.github.io/headlessmc/ and issue #29.
#
# Needs Java 25 on the PATH (JAVA_HOME set) and runs from the repository root.
set -euo pipefail

HMC_VERSION="${HMC_VERSION:-3.0.0-RC9}"
MC_VERSION="${MC_VERSION:-26.2}"
HOOKS="${HOOKS:-true}"
SERVER_LOG=run/logs/latest.log

mkdir -p HeadlessMC client-run
export HMC_FILES_LOCATION="$PWD/HeadlessMC"

# The launcher: the jar of a release, pinned. It downloads the client from Mojang when it launches.
if [ ! -f headlessmc.jar ]; then
  curl -fsSL -o headlessmc.jar \
    "https://github.com/headlesshq/headlessmc/releases/download/${HMC_VERSION}/headlessmc.jar"
fi

cat > HeadlessMC/config.properties <<CONFIG
hmc.java.versions=$JAVA_HOME
hmc.files.game=$PWD/client-run
hmc.files.game-for-each-version=false
hmc.files.mc=$HOME/.minecraft
hmc.jline.enabled=false
hmc.assets.dummy=true
hmc.launcher.crash-report-watcher=true
CONFIG
printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > client-run/options.txt

client_pid=""
server_pid=""
show_logs() {
  echo "::group::What the server logged about the player and the self-test"
  grep -E "logged in with entity|joined the game|lost connection|left the game|SELF-TEST|Foliate\]" "$SERVER_LOG" 2>/dev/null | tail -n 40 || true
  echo "::endgroup::"
}
cleanup() {
  show_logs
  [ -n "$client_pid" ] && kill "$client_pid" 2>/dev/null || true
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

echo "::group::Download the Minecraft client"
# A dry run downloads everything and needs no account.
java -jar headlessmc.jar launch --dry-run -lwjgl --offline "$MC_VERSION"
echo "::endgroup::"

rm -f "$SERVER_LOG"
./gradlew runFolia -PselfTest=true -PclientTest=true -PacceptEula=true "-Phooks=$HOOKS" \
  < /dev/null > server.out 2>&1 &
server_pid=$!

echo "Waiting for the server to be ready for a player"
for _ in $(seq 1 120); do
  if [ -f "$SERVER_LOG" ] && grep -q "SELF-TEST WAITING FOR A PLAYER" "$SERVER_LOG"; then
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "The server stopped before it was ready"
    tail -n 40 server.out
    exit 1
  fi
  sleep 2
done
grep -q "SELF-TEST WAITING FOR A PLAYER" "$SERVER_LOG" || { echo "The server never got ready"; exit 1; }

echo "Joining with the client"
# The pipe keeps the launcher's input open: its shell would otherwise read an end of input.
(tail -f /dev/null | java -jar headlessmc.jar launch -lwjgl --offline --server=127.0.0.1 \
  --jvm "-Xmx2G" "$MC_VERSION" > client.out 2>&1) &
client_pid=$!

# The server stops itself once the self-test has its verdict, and Gradle's exit status is that
# verdict.
status=0
wait "$server_pid" || status=$?
server_pid=""
echo "Server task exited with $status"
exit "$status"

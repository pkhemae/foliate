#!/usr/bin/env bash
# Starts the Folia test server in offline mode, joins it with a real Minecraft client run by
# HeadlessMC, and waits for the plugin's self-test to give its verdict. See
# https://headlesshq.github.io/headlessmc/ and issue #29.
#
# Runs from the repository root, on a CI runner, with Java 25 on the PATH and JAVA_HOME set.
set -euo pipefail

HMC_VERSION="${HMC_VERSION:-3.0.0-RC9}"
# The checksum the release page publishes for headlessmc.jar of that version.
HMC_SHA256="${HMC_SHA256:-a343a7c51e48c566a5cd408bd0012ddf091ea105ecc31daff00af600d3a7218a}"
# The Minecraft version of the build: gradle/libs.versions.toml is the one place that sets it.
MC_VERSION="$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)"
HOOKS="${HOOKS:-true}"
# One visit per language, as Minecraft names it (en_us, fr_fr): a client set to it joins, is checked and kicked, then the next one joins
# as the same player. Two visits are what a player coming back needs.
LANGUAGES="${LANGUAGES:-en_us,fr_fr}"
SERVER_LOG=run/logs/latest.log
# Logged by the self-test (SelfTest.WAITING) when it waits for a player.
WAITING="SELF-TEST WAITING FOR A PLAYER"
JOINED="logged in with entity id"
LEFT="left the game"

mkdir -p HeadlessMC client-run
export HMC_FILES_LOCATION="$PWD/HeadlessMC"

# The launcher: the jar of a release, pinned and checked, since it is run. It downloads the
# Minecraft client from Mojang when it launches.
if [ ! -f headlessmc.jar ]; then
  curl -fsSL --retry 3 -o headlessmc.jar \
    "https://github.com/headlesshq/headlessmc/releases/download/${HMC_VERSION}/headlessmc.jar"
fi
echo "${HMC_SHA256}  headlessmc.jar" | sha256sum -c -

cat > HeadlessMC/config.properties <<CONFIG
hmc.java.versions=$JAVA_HOME
hmc.files.game=$PWD/client-run
hmc.files.game-for-each-version=false
hmc.files.mc=$HOME/.minecraft
hmc.jline.enabled=false
hmc.assets.dummy=true
hmc.launcher.crash-report-watcher=true
CONFIG

client_pid=""
server_pid=""
# Ends the client's whole process group and waits for it to be gone, so that the next client does
# not share the game directory with one that is still writing to it.
stop_client() {
  [ -n "$client_pid" ] || return 0
  kill -- "-$client_pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 -- "-$client_pid" 2>/dev/null || break
    sleep 1
  done
  kill -9 -- "-$client_pid" 2>/dev/null || true
  client_pid=""
}

show_logs() {
  echo "::group::What the server logged about the player and the self-test"
  grep -E "$JOINED|joined the game|lost connection|left the game|SELF-TEST|Checked for a connected|Foliate\\]" \
    "$SERVER_LOG" 2>/dev/null | tail -n 40 || true
  echo "::endgroup::"
}
cleanup() {
  code=$?
  show_logs
  if [ "$code" -ne 0 ]; then
    echo "::group::Last lines of the client output"; tail -n 60 client-*.out 2>/dev/null || true; echo "::endgroup::"
    echo "::group::Last lines of the server task output"; tail -n 60 server.out 2>/dev/null || true; echo "::endgroup::"
  fi
  # The client runs in its own process group: its launcher and the game both end with it.
  stop_client
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

echo "::group::Download the Minecraft client"
# A dry run downloads everything and needs no account.
java -jar headlessmc.jar launch --dry-run -lwjgl --offline "$MC_VERSION"
echo "::endgroup::"

rm -f "$SERVER_LOG"
./gradlew runFolia -PselfTest=true -PclientTest=true -PacceptEula=true "-Phooks=$HOOKS" "-PclientLanguages=$(echo "$LANGUAGES" | sed 's/_[a-z_]*//g')" \
  < /dev/null > server.out 2>&1 &
server_pid=$!

count() { grep -c "$1" "$SERVER_LOG" 2>/dev/null || true; }

echo "Waiting for the server to be ready for a player"
for _ in $(seq 1 120); do
  [ "$(count "$WAITING")" -ge 1 ] && break
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "The server stopped before it was ready"
    exit 1
  fi
  sleep 2
done
[ "$(count "$WAITING")" -ge 1 ] || { echo "The server never got ready"; exit 1; }

launch_client() {
  language="$1"
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\nlang:%s\n' "$language" > client-run/options.txt
  # The pipe keeps the launcher's input open: its shell would otherwise read an end of input.
  setsid bash -c 'tail -f /dev/null | java -jar headlessmc.jar launch -lwjgl --offline --server=127.0.0.1 --jvm "-Xmx2G" "$0"' \
    "$MC_VERSION" > "client-$visit.out" 2>&1 &
  client_pid=$!
}

visit=0
for language in ${LANGUAGES//,/ }; do
  visit=$((visit + 1))
  # The self-test says it is waiting again once the previous player is gone.
  for _ in $(seq 1 60); do
    [ "$(count "$WAITING")" -ge "$visit" ] && break
    kill -0 "$server_pid" 2>/dev/null || { echo "The server stopped before visit $visit"; exit 1; }
    sleep 2
  done
  [ "$(count "$WAITING")" -ge "$visit" ] || { echo "The server was not ready for visit $visit"; exit 1; }
  echo "Visit $visit: joining with a client set to $language"
  launch_client "$language"
  # The server kicks the client once its checks are done. A client that died before joining
  # would leave the server waiting for minutes.
  deadline=$((SECONDS + 300))
  while [ "$(count "$LEFT")" -lt "$visit" ]; do
    kill -0 "$server_pid" 2>/dev/null || break
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Visit $visit took more than five minutes"
      exit 1
    fi
    if ! kill -0 "$client_pid" 2>/dev/null && [ "$(count "$JOINED")" -lt "$visit" ]; then
      echo "The client stopped before it joined the server"
      exit 1
    fi
    sleep 2
  done
  stop_client
done

# The server stops itself once the self-test has its verdict, and Gradle's exit status is that
# verdict.
status=0
wait "$server_pid" || status=$?
server_pid=""
echo "Server task exited with $status"
exit "$status"

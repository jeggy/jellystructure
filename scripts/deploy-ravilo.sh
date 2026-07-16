#!/usr/bin/env bash
# Build, install, and AOT-compile the Ravilo release build on a chosen device.
# Interactive: run it, pick a device, hit enter. See scripts/deploy-ravilo.sh --help.
#
# Convention (never change without asking): release build only, one app per device,
# uninstall any leftover .debug package first, AOT-compile every device (incl. phones),
# never launch the app afterward.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

JBR_HOME="$HOME/.jdks/jbr-21.0.11"
if [ -d "$JBR_HOME" ]; then
    export JAVA_HOME="$JBR_HOME"
fi

TV_STUE_IP="10.0.0.11"
TV_SOVE_IP="10.0.0.12"
PIXEL9_MDNS_PREFIX="adb-PHONESERIAL"

echo "Deploy Ravilo — pick a device:"
echo "  1) TV — stue        ($TV_STUE_IP)"
echo "  2) TV — soveværelse ($TV_SOVE_IP)"
echo "  3) Pixel 9 (phone)"
echo "  4) Google TV Streamer (VPN — IP entered manually)"
read -rp "> " choice

case "$choice" in
    1) KIND="tv"; ADDR="$TV_STUE_IP:5555"; LABEL="stue TV" ;;
    2) KIND="tv"; ADDR="$TV_SOVE_IP:5555"; LABEL="soveværelse TV" ;;
    3) KIND="phone"; LABEL="Pixel 9" ;;
    4)
        KIND="tv"; LABEL="Google TV Streamer"
        read -rp "Enter Google TV Streamer IP (VPN address, port defaults to 5555): " CUSTOM_HOST
        if [ -z "$CUSTOM_HOST" ]; then
            echo "No IP entered — aborting."
            exit 1
        fi
        case "$CUSTOM_HOST" in
            *:*) ADDR="$CUSTOM_HOST" ;;
            *)   ADDR="$CUSTOM_HOST:5555" ;;
        esac
        ;;
    *) echo "Invalid choice: $choice"; exit 1 ;;
esac

if [ "$KIND" = "tv" ]; then
    GRADLE_TASK=":ravilo-android:assembleRelease"
    APK_DIR="$REPO_ROOT/ravilo-android/build/outputs/apk/release"
    PACKAGE_ID="dev.jellystructure.ravilo"
else
    GRADLE_TASK=":ravilo-phone:assembleRelease"
    APK_DIR="$REPO_ROOT/ravilo-phone/build/outputs/apk/release"
    PACKAGE_ID="dev.jellystructure.ravilo.phone"
fi
DEBUG_PACKAGE_ID="${PACKAGE_ID}.debug"

echo
echo "==> Building release APK ($GRADLE_TASK)..."
./gradlew "$GRADLE_TASK"

APK_PATH="$(find "$APK_DIR" -maxdepth 1 -name '*.apk' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-)"
if [ -z "$APK_PATH" ]; then
    echo "No APK found in $APK_DIR after build — aborting."
    exit 1
fi
echo "==> Built: $APK_PATH"

if [ "$KIND" = "phone" ]; then
    echo
    echo "==> Looking up $LABEL via mDNS (wireless debugging must be open on the phone's screen)..."
    MDNS_LINE="$(adb mdns services 2>/dev/null | grep "_adb-tls-connect\._tcp" | grep "$PIXEL9_MDNS_PREFIX" || true)"
    if [ -z "$MDNS_LINE" ]; then
        echo "Could not find $LABEL via mDNS. On the phone: Settings > Developer options >"
        echo "Wireless debugging — open that screen (it must stay open/foregrounded to broadcast) and re-run."
        exit 1
    fi
    ADDR="$(echo "$MDNS_LINE" | awk '{print $3}')"
    echo "==> Found $LABEL at $ADDR"
fi

echo
echo "==> Connecting to $LABEL ($ADDR)..."
adb connect "$ADDR"

STATE="$(adb -s "$ADDR" get-state 2>&1 || true)"
if [ "$STATE" != "device" ]; then
    echo "Device state is '$STATE', not 'device' — check the connection (unlock/accept prompt on-device if needed) and re-run."
    exit 1
fi

echo
echo "==> Checking for a leftover debug build on $LABEL..."
if adb -s "$ADDR" shell pm list packages 2>/dev/null | grep -q "$DEBUG_PACKAGE_ID"; then
    echo "Found $DEBUG_PACKAGE_ID — uninstalling..."
    adb -s "$ADDR" uninstall "$DEBUG_PACKAGE_ID"
else
    echo "None found."
fi

echo
echo "==> Installing release build on $LABEL..."
adb -s "$ADDR" install -r "$APK_PATH"

echo
echo "==> AOT-compiling ($PACKAGE_ID) on $LABEL..."
adb -s "$ADDR" shell cmd package compile -m speed -f "$PACKAGE_ID"

echo
echo "==> Done. $LABEL: $PACKAGE_ID installed (release) and AOT-compiled. Not launched."

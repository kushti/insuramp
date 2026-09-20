#!/usr/bin/env bash
# run-buyer.sh — the buyer side: Android emulator + the Insuramp app.
#
# Boots the p2pgate AVD if no emulator is running, builds and installs the
# debug APK, forwards device:8080 → host:8080 (adb reverse — required again
# after every emulator boot), and launches the app. The backend must be up
# (see scripts/run-seller.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-17.0.20.1+1}"
SDK="${ANDROID_SDK_ROOT:-$HOME/.local/opt/android-sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"

if ! "$ADB" devices | grep -q emulator; then
    echo "Booting the p2pgate emulator..."
    setsid nohup "$EMU" -avd p2pgate -accel on -gpu swiftshader_indirect \
        -memory 3072 -no-snapshot-save -no-audio -no-boot-anim \
        > /tmp/emulator.log 2>&1 < /dev/null &
    disown
    "$ADB" wait-for-device
    for _ in $(seq 1 90); do
        BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$BOOT" = "1" ] && break
        sleep 5
    done
    [ "$BOOT" = "1" ] || { echo "Emulator did not boot in time — see /tmp/emulator.log" >&2; exit 1; }
fi

"$ADB" reverse tcp:8080 tcp:8080   # device loopback → host backend

if ! curl -s -m 2 -o /dev/null http://localhost:8080/v1/quotes; then
    echo "WARNING: no backend answers on :8080 — start it with scripts/run-seller.sh" >&2
fi

echo "Building and installing the debug APK..."
./gradlew :app:assembleDebug --console=plain -q
"$ADB" install -r apps/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am force-stop p2pgate.app 2>/dev/null || true
"$ADB" shell am start -n p2pgate.app/.MainActivity
echo "App launched on the emulator."

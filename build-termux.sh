#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

command -v gradle >/dev/null || { echo 'Gradle is not installed. Install a compatible Gradle distribution first.'; exit 1; }
command -v java >/dev/null || { echo 'Java is not installed.'; exit 1; }

echo "== SA Android build =="
java -version
gradle --version | head -8

echo "== Unit tests =="
gradle :app:testDebugUnitTest --stacktrace

echo "== Debug APK =="
gradle :app:assembleDebug --stacktrace

test -s app/build/outputs/apk/debug/app-debug.apk
printf '\nAPK: %s\n' "app/build/outputs/apk/debug/app-debug.apk"

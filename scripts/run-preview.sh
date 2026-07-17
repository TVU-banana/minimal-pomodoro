#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/private/tmp/clock-jdk17/Contents/Home"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "未找到 JDK 17。请安装 Android Studio，或设置 JAVA_HOME。" >&2
  exit 1
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
fi
ADB="$SDK_DIR/platform-tools/adb"

if [[ -z "$SDK_DIR" || ! -x "$ADB" ]]; then
  echo "未找到 Android SDK platform-tools。请设置 ANDROID_SDK_ROOT。" >&2
  exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "没有可用设备。请连接手机并允许 USB 调试，或先用 adb pair / adb connect 配对无线调试。" >&2
  exit 1
fi

cd "$ROOT_DIR"
./gradlew :app:installPreview "$@"
"$ADB" shell am start -n com.akira.clock/com.akira.clock.ui.MainActivity


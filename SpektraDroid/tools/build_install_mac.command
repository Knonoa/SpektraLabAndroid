#!/bin/bash
# SpektraDroid: build the debug APK on this Mac and install it on the phone over USB.
# Double-click in Finder, or run from Terminal. Needs JDK 17 (Android Studio's bundled
# one is found automatically) and the Android SDK (~/Library/Android/sdk by default;
# AGP downloads NDK 27.2.12479018 and CMake 3.22.1 itself once the licences are accepted).
#
# The debug build is com.spektralab.android.dev ("SpektraLab Dev"): it installs beside a
# release build signed with another key. vivo asks to confirm every adb install on the phone.
set -e
cd "$(dirname "$0")/.."                       # SpektraDroid/

if [ -z "$JAVA_HOME" ]; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$(/usr/libexec/java_home -v 17 2>/dev/null)"; do
    [ -x "$j/bin/java" ] && export JAVA_HOME="$j" && break
  done
fi
[ -n "$JAVA_HOME" ] || { echo "找不到 JDK 17：请安装 Android Studio，或设置 JAVA_HOME"; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || { echo "找不到 Android SDK（$ANDROID_HOME）：请用 Android Studio 安装，或设置 ANDROID_HOME"; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "JAVA_HOME=$JAVA_HOME"; echo "ANDROID_HOME=$ANDROID_HOME"

export SPEKTRALAB_LIBRAW_SOURCE="$(bash tools/setup_libraw.sh "${SPEKTRALAB_LIBRAW_SOURCE:-$PWD/build/deps/LibRaw-snapshot202611}")"

chmod +x gradlew
./gradlew assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
ls -la "$APK"

ADB=""
for a in "$ANDROID_HOME/platform-tools/adb" "../../platform-tools/adb" "$(command -v adb)"; do
  [ -x "$a" ] && ADB="$a" && break
done
[ -n "$ADB" ] || { echo "找不到 adb；APK 在 $APK"; exit 0; }
"$ADB" devices -l
echo "安装中……请在手机上确认安装。"
"$ADB" install -r -t "$APK"
"$ADB" shell am start -n com.spektralab.android.dev/com.spektralab.android.MainActivity
echo "完成。查看崩溃：$ADB logcat -b crash"

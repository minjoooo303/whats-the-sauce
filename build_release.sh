#!/bin/bash
# 심사 제출용 release APK 빌드 → apk/opensauce_WhatsTheSauce_release.apk
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
(cd android && ./gradlew --no-daemon assembleRelease)
mkdir -p apk
cp android/app/build/outputs/apk/release/app-release.apk apk/opensauce_WhatsTheSauce_release.apk
ls -la apk/
echo "끝! Claude에게 '했어'라고 말해 주세요."

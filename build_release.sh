#!/bin/bash
# 심사 제출용 release APK 빌드 → apk/opensauce_WhatsTheSauce_release.apk
set -e
cd "$(dirname "$0")"
# Gradle 8.10.2 는 Java 17~23 에서만 동작 → 그 범위의 JDK 를 찾아 쓴다
pick=""
for J in "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
         /Library/Java/JavaVirtualMachines/*/Contents/Home \
         "$HOME"/.gradle/jdks/*/Contents/Home "$HOME"/.gradle/jdks/* \
         "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
  [ -x "$J/bin/java" ] || continue
  v=$("$J/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  if [ "$v" -ge 17 ] && [ "$v" -le 23 ]; then pick="$J"; break; fi
done
[ -z "$pick" ] && { echo "Java 17~23 JDK 를 찾지 못했어요."; exit 1; }
export JAVA_HOME="$pick"
echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version 2>&1 | head -1
(cd android && ./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" assembleRelease)
mkdir -p apk
cp android/app/build/outputs/apk/release/app-release.apk apk/opensauce_WhatsTheSauce_release.apk
ls -la apk/
echo "끝! Claude에게 '했어'라고 말해 주세요."

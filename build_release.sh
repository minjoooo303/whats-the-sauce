#!/bin/bash
# 심사 제출용 release APK 빌드 → apk/opensauce_WhatsTheSauce_release.apk
set -e
cd "$(dirname "$0")"
# Gradle 8.10.2 는 Java 17~23 에서 동작 → Android Studio 내장 JDK(21) 또는 설치된 JDK 21/17 을 우선 사용
for J in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
         "$(/usr/libexec/java_home -v 21 2>/dev/null)" \
         "$(/usr/libexec/java_home -v 17 2>/dev/null)"; do
  if [ -n "$J" ] && [ -x "$J/bin/java" ]; then export JAVA_HOME="$J"; break; fi
done
echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version 2>&1 | head -1
(cd android && ./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" assembleRelease)
mkdir -p apk
cp android/app/build/outputs/apk/release/app-release.apk apk/opensauce_WhatsTheSauce_release.apk
ls -la apk/
echo "끝! Claude에게 '했어'라고 말해 주세요."

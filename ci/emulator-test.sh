#!/usr/bin/env bash
# Справжнє вимірювання в емуляторі + знімки екрана у світлій і темній темах.
set -x
OUT=ci-out
mkdir -p $OUT
PKG=ua.potik.speed
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > $OUT/install.txt 2>&1
adb shell cmd uimode night no
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez autoTest true
sleep 3
adb exec-out screencap -p > $OUT/1_start.png
sleep 9
adb exec-out screencap -p > $OUT/2_download.png
sleep 10
adb exec-out screencap -p > $OUT/3_upload.png
for i in $(seq 1 60); do
  adb shell "test -f /sdcard/Android/data/$PKG/files/last_result.json" && break
  sleep 2
done
sleep 3
adb exec-out screencap -p > $OUT/4_done.png
adb pull /sdcard/Android/data/$PKG/files/last_result.json $OUT/ || true
adb shell input swipe 540 1900 540 600 400
sleep 2
adb exec-out screencap -p > $OUT/5_scrolled.png
adb shell input tap 905 190
sleep 2
adb exec-out screencap -p > $OUT/6_history.png
adb shell input keyevent 4
sleep 1
adb shell cmd uimode night yes
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity
sleep 5
adb exec-out screencap -p > $OUT/7_dark.png
adb shell cmd uimode night no
# Релізна збірка (з R8): запуск і справжнє вимірювання натисканням на кнопку
adb uninstall $PKG > /dev/null 2>&1
adb install -r app/build/outputs/apk/release/app-release.apk > $OUT/install_release.txt 2>&1
adb shell am start -n $PKG/.MainActivity
sleep 6
adb shell input tap 540 1020
sleep 40
adb exec-out screencap -p > $OUT/8_release_done.png
adb logcat -d > $OUT/logcat_all.txt 2>&1 || true
grep -iE "potik|AndroidRuntime|FATAL|chromium|Killing|has died" $OUT/logcat_all.txt | grep -v AppsFilter | tail -300 > $OUT/logcat.txt || true
rm -f $OUT/logcat_all.txt
exit 0

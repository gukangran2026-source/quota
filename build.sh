#!/bin/bash
# Build the SubTraffic APK with the plain Android SDK toolchain (no Gradle, no network).
# aapt2 (native aarch64 build) -> javac -> d8 -> python zipalign -> apksigner
#
# Notes on this device (aarch64 proot):
#   * aapt2 / zipalign / aapt shipped in build-tools are x86_64 -> not runnable,
#     so aapt2 comes from /root/android-arm-aapt2 and alignment is done by tools/zipalign.py
#   * d8 and apksigner are shell wrappers around java -> run natively

set -u
SDK=/root/Android
BT=$SDK/build-tools/35.0.1
AJ=$SDK/platforms/android-35/android.jar
AAPT2=/root/android-arm-aapt2/aapt2
PROJ=/root/subtraffic
OUT=$PROJ/build
KS=/root/.android/debug.keystore
KSPASS=android
ALIAS=androiddebugkey
APKNAME="SubTraffic-v2.8.apk"

echo "== 0. clean =="
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. compile resources (aapt2) =="
"$AAPT2" compile --dir "$PROJ/res" -o "$OUT/res.zip" || exit 1

echo "== 2. link resources (aapt2) =="
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$PROJ/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 || exit 1

echo "== 3. compile java =="
SOURCES=$(find "$PROJ/src" "$OUT/gen" -name '*.java')
javac -source 8 -target 8 -Xlint:-options -nowarn \
  -bootclasspath "$AJ" -d "$OUT/classes" $SOURCES || exit 1

echo "== 4. dex (d8) =="
CLASSES=$(find "$OUT/classes" -name '*.class')
"$BT/d8" --min-api 21 --lib "$AJ" --output "$OUT/dex" $CLASSES || exit 1

echo "== 5. add classes.dex to apk =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex) || exit 1

echo "== 6. align (python zipalign) =="
python3 "$PROJ/tools/zipalign.py" "$OUT/unsigned.apk" "$OUT/aligned.apk" 4 || exit 1

echo "== 7. sign =="
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --ks-key-alias "$ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/$APKNAME" "$OUT/aligned.apk" || exit 1

echo "== 8. verify =="
"$BT/apksigner" verify --print-certs "$OUT/$APKNAME" || exit 1
echo "--- badging ---"
"$AAPT2" dump badging "$OUT/$APKNAME" 2>/dev/null | head -6
echo "--- contents ---"
unzip -l "$OUT/$APKNAME"
ls -la "$OUT/$APKNAME"
echo "BUILD OK"
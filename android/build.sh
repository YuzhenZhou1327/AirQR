#!/bin/bash
# Build AirQR APK: aapt2 + javac + d8 + zipalign + apksigner (no Gradle).
# Toolchain: JDK25 (PATH), build-tools 35 (JDK25 requires BT35 d8 — iron law),
# platform android-34. Deps: zxing core 3.5.3 (libs/core-3.5.3.jar).
set -e

PROJ="D:/proj/airqr/android"
SDK="C:/Users/23653/Android/sdk"
BT="$SDK/build-tools-35"
AJAR="$SDK/android-34/android.jar"
OUT="$PROJ/build"
KEYSTORE="C:/Users/23653/Android/keystore/airqr.keystore"
VERSION=1.7
ZXING="$PROJ/libs/core-3.5.3.jar"

if [ -f "$PROJ/keystore.properties" ]; then
  KS_PASS=$(grep '^KS_PASS=' "$PROJ/keystore.properties" | cut -d= -f2 | tr -d '\r\n')
else
  echo "缺少 $PROJ/keystore.properties（内容一行：KS_PASS=你的口令）"; exit 1
fi

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/res.zip" "$OUT/base.apk" "$OUT/classes.dex" "$OUT/aligned.apk" "$OUT/classlist.txt"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "==> [1/7] aapt2 compile resources"
"$BT/aapt2.exe" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "==> [2/7] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 29 --target-sdk-version 34 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> [3/7] javac (src + zxing jar + generated R)"
"$SDK/../../jdk/jdk-25.0.4.1+1/bin/javac.exe" \
  -source 11 -target 11 -encoding UTF-8 \
  -classpath "$AJAR;$ZXING" \
  -d "$OUT/classes" \
  $(find "$PROJ/src" -name '*.java') \
  "$OUT/gen/com/airqr/R.java" 2>&1 | grep -v "bootstrap class path" || true

echo "==> [4/7] d8 dex (build-tools 35; app classes + zxing jar — deps must be dexed too)"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8.bat" --release --lib "$AJAR" --min-api 29 \
  --output "$OUT" $(cat "$OUT/classlist.txt") "$ZXING"
ls -la "$OUT/classes.dex"

echo "==> [5/7] insert classes.dex"
python - "$OUT/base.apk" "$OUT/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a") as z:
    if "classes.dex" not in z.namelist():
        z.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
with zipfile.ZipFile(apk) as z:
    for n in ("resources.arsc", "classes.dex"):
        info = z.getinfo(n)
        print(f"  {n}: compress={info.compress_type} (0=STORED)")
PYEOF

echo "==> [6/7] zipalign"
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> [7/7] apksigner sign"
"$BT/apksigner.bat" sign \
  --ks "$KEYSTORE" --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS \
  --out "$OUT/airqr-v$VERSION.apk" "$OUT/aligned.apk"

echo "==> verify"
"$BT/apksigner.bat" verify --print-certs "$OUT/airqr-v$VERSION.apk" | head -6
ls -la "$OUT/airqr-v$VERSION.apk"
echo "BUILD OK"

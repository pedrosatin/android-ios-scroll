#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$ROOT_DIR/.sdk"
BUILD_DIR="$ROOT_DIR/build"
OUT_APK="$ROOT_DIR/s25-scroll-helper.apk"
KEYSTORE="$ROOT_DIR/debug.keystore"

mkdir -p "$SDK_DIR" "$BUILD_DIR/gen" "$BUILD_DIR/classes"

if [ ! -f "$SDK_DIR/build-tools-35/aapt2" ]; then
  echo "Downloading Android build-tools r35..."
  curl -sL "https://dl.google.com/android/repository/build-tools_r35_linux.zip" -o "$SDK_DIR/build-tools.zip"
  rm -rf "$SDK_DIR/build-tools-35" "$SDK_DIR"/android-*
  unzip -q "$SDK_DIR/build-tools.zip" -d "$SDK_DIR"
  mv "$SDK_DIR"/android-* "$SDK_DIR/build-tools-35"
  rm "$SDK_DIR/build-tools.zip"
fi

if [ ! -f "$SDK_DIR/platform/android.jar" ]; then
  echo "Downloading Android platform 34..."
  curl -sL "https://dl.google.com/android/repository/platform-34-ext7_r02.zip" -o "$SDK_DIR/platform.zip"
  unzip -q "$SDK_DIR/platform.zip" -d "$SDK_DIR"
  mv "$SDK_DIR"/android-* "$SDK_DIR/platform"
  rm "$SDK_DIR/platform.zip"
fi

AAPT2="$SDK_DIR/build-tools-35/aapt2"
D8="$SDK_DIR/build-tools-35/d8"
ZIPALIGN="$SDK_DIR/build-tools-35/zipalign"
APKSIGNER="$SDK_DIR/build-tools-35/apksigner"
ANDROID_JAR="$SDK_DIR/platform/android.jar"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes"

"$AAPT2" compile --dir "$ROOT_DIR/res" -o "$BUILD_DIR/res.zip"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --auto-add-overlay \
  -o "$BUILD_DIR/unsigned.apk" \
  "$BUILD_DIR/res.zip"

javac -g:none -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "$BUILD_DIR/gen/dev/satin/scrollhelper/R.java" \
  "$ROOT_DIR/src/dev/satin/scrollhelper/MainActivity.java" \
  "$ROOT_DIR/src/dev/satin/scrollhelper/ScrollAccessibilityService.java"

"$D8" --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR" \
  "$BUILD_DIR"/classes/dev/satin/scrollhelper/*.class

(cd "$BUILD_DIR" && zip -q unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUT_APK" \
  "$BUILD_DIR/aligned.apk"

rm -rf "$BUILD_DIR" "$OUT_APK.idsig"
echo "Built $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"

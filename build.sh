#!/bin/bash
# Stingray Detector v1.0 — manual APK build, no Gradle.
# Copied from the proven ~/workspace/newtool/build.sh pipeline
# (kotlinc + aapt2 + d8 + zipalign + apksigner). Gradle daemon is
# blocked in this VM by localhost TCP denial — do not use Gradle.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/jdk17
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS
BT=$HOME/Android/sdk/build-tools/34.0.0
ANDROID_JAR=$HOME/Android/sdk/platforms/android-34/android.jar
KOTLINC=$HOME/workspace/iot-lights-simple/tools/kotlinc/bin/kotlinc
STDLIB=$HOME/workspace/iot-lights-simple/tools/kotlinc/lib/kotlin-stdlib.jar
mkdir -p build/classes build/dex build/test-classes

echo "== unit tests =="
"$KOTLINC" -cp "$ANDROID_JAR" -d build/test-classes \
  src/art/phoenix/stingray/Logic.kt src/art/phoenix/stingray/Db.kt \
  src/art/phoenix/stingray/CellReader.kt src/art/phoenix/stingray/TileMapView.kt \
  test/art/phoenix/stingray/Tests.kt
"$JAVA_HOME/bin/java" -cp "build/test-classes:$ANDROID_JAR:$STDLIB" \
  art.phoenix.stingray.StingrayTests

echo "== kotlinc compile =="
rm -rf build/classes; mkdir -p build/classes
"$KOTLINC" -cp "$ANDROID_JAR" -d build/classes src/art/phoenix/stingray/*.kt

echo "== aapt2 compile resources (launcher icon) =="
rm -rf build/compiled-res; mkdir -p build/compiled-res
"$BT/aapt2" compile --dir res -o build/compiled-res/

echo "== aapt2 link =="
"$BT/aapt2" link -o build/app-unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" --auto-add-overlay -A assets -R build/compiled-res/mipmap-mdpi_ic_launcher.png.flat build/compiled-res/mipmap-hdpi_ic_launcher.png.flat build/compiled-res/mipmap-xhdpi_ic_launcher.png.flat build/compiled-res/mipmap-xxhdpi_ic_launcher.png.flat build/compiled-res/mipmap-xxxhdpi_ic_launcher.png.flat

echo "== d8 dex =="
rm -rf build/dex; mkdir -p build/dex
jar cf build/app-classes.jar -C build/classes .
"$BT/d8" --output build/dex --lib "$ANDROID_JAR" --min-api 26 --release build/app-classes.jar "$STDLIB"

echo "== add classes.dex to APK =="
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/app-unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex','classes.dex')
print('dex added')
PY

echo "== zipalign =="
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=stingray, OU=Dev, O=Pip, C=US" -noprompt
fi

echo "== apksigner (v1+v2+v3) =="
"$BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out build/stingray-detector-v1.0.apk build/app-aligned.apk

echo "== verify =="
"$BT/apksigner" verify --verbose build/stingray-detector-v1.0.apk
"$BT/aapt2" dump badging build/stingray-detector-v1.0.apk | head -20
ls -lh build/stingray-detector-v1.0.apk
sha256sum build/stingray-detector-v1.0.apk
cp build/stingray-detector-v1.0.apk "$HOME/workspace/your_files/stingray-detector.apk"
ls -lh "$HOME/workspace/your_files/stingray-detector.apk"
sha256sum "$HOME/workspace/your_files/stingray-detector.apk"

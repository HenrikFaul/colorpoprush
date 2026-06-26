#!/usr/bin/env bash
#
# Builds Color Pop Rush into a signed, installable APK using only the
# Debian/Ubuntu Android tooling (aapt2, dalvik-exchange/dx, zipalign,
# apksigner) plus a JDK. No Android Gradle Plugin and no Google-hosted
# downloads are required, so it runs fully offline.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
GEN="$BUILD/gen"
CLASSES="$BUILD/classes"
PKG="com.colorpop.rush"
APK_NAME="ColorPopRush.apk"

# --- Locate the toolchain --------------------------------------------------
ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
AAPT2="${AAPT2:-aapt2}"
DX="${DX:-dalvik-exchange}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"

for t in "$AAPT2" "$DX" "$ZIPALIGN" "$APKSIGNER" javac java keytool zip; do
    command -v "$t" >/dev/null 2>&1 || { echo "ERROR: required tool '$t' not found on PATH"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "ERROR: android.jar not found at $ANDROID_JAR"; exit 1; }

echo ">> Cleaning build dir"
rm -rf "$BUILD"
mkdir -p "$GEN" "$CLASSES"

# --- 1. Launcher icons -----------------------------------------------------
echo ">> Generating launcher icons"
javac -d "$BUILD/iconcls" "$ROOT/tools/IconGen.java"
java -Djava.awt.headless=true -cp "$BUILD/iconcls" IconGen "$APP/res"

# --- 2. Compile + link resources ------------------------------------------
echo ">> Compiling resources (aapt2 compile)"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo ">> Linking resources + manifest (aapt2 link)"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 21 \
    --target-sdk-version 23 \
    --version-code 1 \
    --version-name "1.0" \
    "$BUILD/res.zip"

# --- 3. Compile Java (target Java 8 bytecode for dx) -----------------------
echo ">> Compiling Java sources"
SRCS=$(find "$APP/src" "$GEN" -name '*.java')
javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    $SRCS 2>&1 | grep -vE 'bootstrap class path|warning: \[options\]|^[0-9]+ warning' || true

# --- 4. Dex ----------------------------------------------------------------
echo ">> Dexing (dalvik-exchange)"
"$DX" --dex --output="$BUILD/classes.dex" "$CLASSES"

# --- 5. Assemble unsigned APK ---------------------------------------------
echo ">> Assembling APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD" && zip -qj unsigned.apk classes.dex )

# --- 6. Align --------------------------------------------------------------
echo ">> Aligning (zipalign)"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

# --- 7. Sign ---------------------------------------------------------------
KS="$ROOT/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
    echo ">> Creating debug keystore"
    mkdir -p "$ROOT/keystore"
    keytool -genkeypair -v -keystore "$KS" \
        -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Color Pop Rush Debug,O=ColorPopRush,C=US" >/dev/null 2>&1
fi

echo ">> Signing (apksigner)"
"$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/$APK_NAME" "$BUILD/aligned.apk"

# --- 8. Verify -------------------------------------------------------------
echo ">> Verifying signature"
"$APKSIGNER" verify --verbose "$ROOT/$APK_NAME" | grep -E 'Verifies|scheme' || true

echo ""
echo "BUILD OK -> $ROOT/$APK_NAME"
ls -la "$ROOT/$APK_NAME"

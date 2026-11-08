#!/usr/bin/env bash
# Build Toolbox APK without Gradle (aapt2 + javac + d8 + apksigner).
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
KEYSTORE="${KEYSTORE:-/lab/ulphone/custom/keys/toolbox.keystore}"
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex}

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass toolbox -keypass toolbox \
        -alias toolbox -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Toolbox PA18" >/dev/null
fi

"$BT/aapt2" compile --dir res -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM" \
    --manifest AndroidManifest.xml --java "$OUT/gen" "$OUT/res/compiled.zip"

javac -source 17 -target 17 -nowarn -classpath "$PLATFORM" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --min-api 33 --lib "$PLATFORM" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:toolbox --ks-key-alias toolbox \
    --out "$OUT/toolbox.apk" "$OUT/aligned.apk"
echo "APK: $(pwd)/$OUT/toolbox.apk"

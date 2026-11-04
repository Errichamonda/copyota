#!/bin/bash
# Builds build/copyota.apk. Needs the Android SDK and a JDK.
# The app has no key in it, so one APK works for everyone.
set -eu
cd "$(dirname "$0")/.."
export KEYSTORE_PASS=${KEYSTORE_PASS:-android}
keystore=${KEYSTORE:-~/.android/debug.keystore}
sdk=${ANDROID_HOME:-~/Library/Android/sdk}
jar=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)/android.jar
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
rm -rf build/apk && mkdir -p build/apk/gen build/apk/classes
"$tools/aapt2" compile --dir android/res -o build/apk/res.zip
"$tools/aapt2" link -I "$jar" --manifest android/AndroidManifest.xml --java build/apk/gen -o build/apk/app.apk build/apk/res.zip
javac -nowarn -Xlint:-options --release 8 -cp "$jar" -d build/apk/classes $(find android/src build/apk/gen -name '*.java')
"$tools/d8" --lib "$jar" --min-api 33 --output build/apk $(find build/apk/classes -name '*.class')
(cd build/apk && zip -q app.apk classes.dex)
"$tools/zipalign" -f 4 build/apk/app.apk build/apk/aligned.apk
"$tools/apksigner" sign --ks "$keystore" --ks-pass env:KEYSTORE_PASS --out build/copyota.apk build/apk/aligned.apk
echo "built build/copyota.apk"

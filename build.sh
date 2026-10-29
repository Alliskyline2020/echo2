#!/bin/bash
set -e
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT33=$SDK/build-tools/33.0.2
BT=$SDK/build-tools
L=../native/litertlm; K=../native/kotlin-stdlib.jar; G=../native/gson.jar; C=../native/coroutines.jar
S=../native/sherpa/aar_unpacked
rm -rf build && mkdir -p build/classes build/dex build/lib/arm64-v8a build/gen
$BT33/aapt2 compile --dir res -o build/res.zip
$BT33/aapt2 link -o build/base.apk -I $SDK/platforms/android-33/android.jar --manifest AndroidManifest.xml --java build/gen build/res.zip
javac --release 11 -classpath $SDK/platforms/android-33/android.jar:$L/classes.jar:$K:$G:$C:$S/classes.jar -d build/classes $(find src -name '*.java') build/gen/dev/echo/app/R.java
$BT/d8 --lib $SDK/platforms/android-33/android.jar --min-api 26 --output build/dex $(find build/classes -name '*.class') $L/classes.jar $K $G $C $S/classes.jar
cp $L/jni/arm64-v8a/*.so build/lib/arm64-v8a/
cp $S/jni/arm64-v8a/*.so build/lib/arm64-v8a/
(cd build/dex && zip -q ../base.apk classes.dex); (cd build && zip -q -r base.apk lib)
$BT33/zipalign -f 4 build/base.apk build/aligned.apk
$BT33/apksigner sign --ks ../native/android/debug.p12 --ks-pass pass:android --key-pass pass:android --out echo.apk build/aligned.apk
echo "✓ echo.apk"

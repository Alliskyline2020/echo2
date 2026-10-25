#!/bin/bash
# Echo 2.0 手动构建（无 Gradle，aapt2 + javac + d8）
set -e

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT33=$SDK/build-tools/33.0.2

# 依赖路径（按实际位置修改）
NATIVE=../native
LITERTLM=$NATIVE/litertlm/classes.jar
KOTLIN=$NATIVE/kotlin-stdlib.jar
GSON=$NATIVE/gson.jar
COROUTINES=$NATIVE/coroutines.jar
SHERPA_CLS=$NATIVE/sherpa/aar_unpacked/classes.jar

# 设备上的模型文件（首次安装后 push）：
#   gemma-4-E2B-it.litertlm      → /sdcard/Android/data/dev.echo.app/files/
#   embeddinggemma-2-740m.litertlm → 同上
#   campplus_zh.onnx             → 同上
# JNI .so（从 AAR / 预编译包解压）：
#   liblitertlm_jni.so, libsherpa-onnx-jni.so, libonnxruntime.so, … → build/lib/arm64-v8a/

echo "[1/5] aapt2 link"
mkdir -p build/classes build/dex build/lib/arm64-v8a
$BT33/aapt2 compile --dir res -o build/res.zip
$BT33/aapt2 link -o build/base.apk \
    -I $SDK/platforms/android-33/android.jar \
    --manifest AndroidManifest.xml build/res.zip

echo "[2/5] javac"
javac --release 11 \
    -classpath $SDK/platforms/android-33/android.jar:$LITERTLM:$KOTLIN:$GSON:$COROUTINES:$SHERPA_CLS \
    -d build/classes $(find src -name '*.java')

echo "[3/5] d8"
$BT/d8 --lib $SDK/platforms/android-33/android.jar --min-api 26 \
    --output build/dex $(find build/classes -name '*.class') \
    $LITERTLM $KOTLIN $GSON $COROUTINES $SHERPA_CLS

echo "[4/5] pack"
cp ../native/litertlm/jni/arm64-v8a/*.so build/lib/arm64-v8a/ 2>/dev/null || true
cp ../native/sherpa/aar_unpacked/jni/arm64-v8a/*.so build/lib/arm64-v8a/ 2>/dev/null || true
(cd build/dex && zip -q ../base.apk classes.dex)
(cd build && zip -q -r base.apk lib)

echo "[5/5] sign"
$BT33/zipalign -f 4 build/base.apk build/aligned.apk
$BT33/apksigner sign \
    --ks ../native/android/debug.p12 --ks-pass pass:android --key-pass pass:android \
    --out echo.apk build/aligned.apk

echo "✓ echo.apk"

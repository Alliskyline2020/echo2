# 安装

## 1. 构建 APK

```bash
./build.sh
# → echo.apk
```

## 2. 安装 + 权限

```bash
adb install -r echo.apk
adb shell pm grant dev.echo.app android.permission.RECORD_AUDIO
adb shell pm grant dev.echo.app android.permission.READ_PHONE_STATE
adb shell pm grant dev.echo.app android.permission.POST_NOTIFICATIONS
```

## 3. 推模型

```bash
D=/sdcard/Android/data/dev.echo.app/files

# SenseVoice ASR（必装）
adb push native/sensevoice/model.int8.onnx  $D/sensevoice-model.onnx
adb push native/sensevoice/tokens.txt       $D/sensevoice-tokens.txt

# Gemma 4 E2B（必装）
adb push native/gemma/gemma-4-E2B-it.litertlm  $D/

# EmbeddingGemma（语义搜索）
adb push native/embedding/embeddinggemma-2-740m.litertlm  $D/

# 声纹 CAM++（必装）
adb push native/sherpa/campplus_zh.onnx  $D/

# 修属主
adb shell "su -c 'chown -R u0_a218:u0_a218 /data/data/dev.echo.app/files/'"
adb shell "su -c 'chmod -R 664 /sdcard/Android/data/dev.echo.app/files/*'"
```

## 4. 首启

打开 app → 录 10 秒声纹 → 开始使用。

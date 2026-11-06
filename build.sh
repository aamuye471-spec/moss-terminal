#!/bin/bash
# Moss Terminal 手动构建脚本（不用 Gradle，省内存）。
# 流程：aapt2 编资源 → javac 编 Java → d8 转 dex → zip 组装 APK → zipalign → apksigner。
# 用法：bash build.sh [clean]
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/jdk17"
export PATH="$HOME/jdk17/bin:$PATH"

APP_DIR="$PWD/app"
BUILD_DIR="$PWD/build"
SDK="$HOME/Android/Sdk"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
AAPT2="$SDK/build-tools/aapt2"
D8="$SDK/build-tools/d8"
ZIPALIGN="$SDK/build-tools/zipalign"
APKSIGNER="$SDK/build-tools/apksigner"
KEYTOOL="$HOME/jdk17/bin/keytool"
JAVAC="$HOME/jdk17/bin/javac"
KEYSTORE="$PWD/debug.keystore"

if [ "$1" = "clean" ]; then rm -rf "$BUILD_DIR"; fi
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/res-flat"

echo "=== [1/7] aapt2 compile resources ==="
"$AAPT2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/res-flat/app.zip"
"$AAPT2" compile --dir "$APP_DIR/res-termux" -o "$BUILD_DIR/res-flat/termux.zip"

echo "=== [2/7] aapt2 link (R.java + base APK) ==="
"$AAPT2" link -o "$BUILD_DIR/app-base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --extra-packages com.termux.view \
  --auto-add-overlay \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 8 \
  --version-name 1.7 \
  "$BUILD_DIR/res-flat/app.zip" "$BUILD_DIR/res-flat/termux.zip"

echo "=== [3/7] javac (app sources + R.java + termux libs) ==="
find "$APP_DIR/src" -name '*.java' > "$BUILD_DIR/sources.txt"
find "$BUILD_DIR/gen" -name 'R.java' >> "$BUILD_DIR/sources.txt"
"$JAVAC" -source 17 -target 17 -encoding UTF-8 \
  -cp "$ANDROID_JAR:$APP_DIR/libs/terminal-emulator.jar:$APP_DIR/libs/terminal-view.jar" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "=== [4/7] d8 (classes -> dex) ==="
# d8 不接受裸 class 目录，先打成 jar
cd "$BUILD_DIR/classes" && "$JAVA_HOME/bin/jar" cf ../app-classes.jar . && cd "$OLDPWD"
"$D8" --output "$BUILD_DIR/dex" \
  --lib "$ANDROID_JAR" \
  --min-api 29 \
  "$BUILD_DIR/app-classes.jar" \
  "$APP_DIR/libs/terminal-emulator.jar" \
  "$APP_DIR/libs/terminal-view.jar"
ls -la "$BUILD_DIR/dex/"

echo "=== [5/7] assemble APK (dex + assets + native lib) ==="
cp "$BUILD_DIR/app-base.apk" "$BUILD_DIR/app-unsigned.apk"
cd "$BUILD_DIR/dex" && zip -q -j "$BUILD_DIR/app-unsigned.apk" classes*.dex && cd "$OLDPWD"
# assets 必须不压缩存放（-0）：v1.0 用 DEFLATE 压缩 321MB 二进制，
# 手机上 AssetManager.open() 要实时解压，是闪退的最大嫌疑；
# 不压缩后释放变成纯文件拷贝，AssetManager 可直接 mmap，稳得多。
# 代价是 APK 变大（约 322MB），但装上打开就能用比体积重要。
cd "$APP_DIR" && zip -q -0 -r "$BUILD_DIR/app-unsigned.apk" assets && cd "$OLDPWD"
# libtermux.so 必须不压缩存放（nativeLibraryDir 要求）
mkdir -p "$BUILD_DIR/lib/arm64-v8a"
cp "$APP_DIR/libs/arm64-v8a/libtermux.so" "$BUILD_DIR/lib/arm64-v8a/"
cd "$BUILD_DIR" && zip -q -0 -r app-unsigned.apk lib && cd "$OLDPWD"
ls -la "$BUILD_DIR/app-unsigned.apk"

echo "=== [6/7] zipalign ==="
"$ZIPALIGN" -f 4 "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/app-aligned.apk"

echo "=== [7/7] sign ==="
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -alias moss \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass mossmoss -keypass mossmoss \
    -dname "CN=Moss Terminal, OU=Dev, O=Moss, L=HK, ST=HK, C=HK" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:mossmoss \
  --key-pass pass:mossmoss --out "$PWD/moss-terminal.apk" "$BUILD_DIR/app-aligned.apk"
"$APKSIGNER" verify --verbose "$PWD/moss-terminal.apk" | head -5
ls -la "$PWD/moss-terminal.apk"
echo "BUILD OK: $PWD/moss-terminal.apk"

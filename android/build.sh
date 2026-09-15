#!/usr/bin/env bash
# ============================================================
# 车机媒体大屏 · APK 打包脚本（不依赖 Gradle，用 aapt2 + d8 + apksigner 手工链）
#
#   bash android/build.sh                    # 默认 release 密钥签名（在项目根目录执行）
#   SIGN=debug bash android/build.sh         # 切回调试密钥（本机自测用）
#
# 产物： dist/Z6CC-<版本号>.apk   （例 Z6CC-1.2.2.apk）
#
# 签名： release 密钥与口令在 android/keystore.properties（含敏感信息，勿外发），
#        密钥文件本身在项目外 C:\Users\yanzi\.android-keystore\。
#
# 说明：源码目录在工作区（路径含中文），而 aapt2/d8/javac 对中文路径兼容性不佳，
#       因此脚本先整体复制到纯 ASCII 临时目录 $WORK 中构建，再把 APK 拷回工作区。
# ============================================================
set -euo pipefail

# 环境守卫：必须在 Git Bash 下运行。若在 cmd/PowerShell 里敲 `bash build.sh`，
# 实际会落到 WSL 的 bash（C:\Windows\System32\bash.exe），而 WSL 看不到 D:/... 路径，
# 表现为「✗ 缺少构建组件: D:/.../aapt2.exe」（组件其实都在）。
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SELF_DIR/tools/lib.sh"
[ -f "$LIB" ] || { echo "✗ 缺少公共库: $LIB"; exit 1; }
# shellcheck source=tools/lib.sh
. "$LIB"
l6_require_git_bash "$0" "$@" || exit 1

SIGN="${SIGN:-release}"

SDK="${ANDROID_SDK_ROOT:-D:/wenhaitao/Documents/sdk}"
BT_VERSION="${BT_VERSION:-35.0.0}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/android-35/android.jar"
PY="${PY:-C:/Users/yanzi/.workbuddy/binaries/python/envs/default/Scripts/python.exe}"
# keytool 不在 PATH 上（Oracle javapath 只软链了 java/javac），单独定位 JDK
JDK_BIN="${JDK_BIN:-C:/Program Files/Java/jdk-26.0.1/bin}"
KEYTOOL="$JDK_BIN/keytool.exe"
if [ ! -x "$KEYTOOL" ] && command -v keytool >/dev/null 2>&1; then KEYTOOL="$(command -v keytool)"; fi

ROOT="D:/AI/WorkBuddy/老六"                  # 项目根目录（原 car-linux-media/ 内容已上提到根）
SRC="$ROOT/android"
PROTO="$ROOT/apk-dashboard-prototype.html"
DIST="$ROOT/dist"
WORK="${WORK:-C:/Users/yanzi/l6apk-build-$(date +%s)}"

APP_NAME="老六中控"              # 应用显示名（build 时写入 res/values/strings.xml 的 app_name）
APK_NAME="Z6CC"                  # 产物文件名前缀（作为 GitHub Release 资产上传，必须 ASCII）
VER_NAME="1.4.1"

# versionCode 编码约定：major*10000 + minor*100 + patch（例：1.2.1 -> 10201）
# 必须与 Ota.normalizeGithubRelease() 从 tag 解析出的编码一致，否则 OTA 比较会误判。
IFS='.' read -r _VMA _VMI _VPA <<< "$VER_NAME"
VER_CODE=$(( ${_VMA:-0} * 10000 + ${_VMI:-0} * 100 + ${_VPA:-0} ))

# 产物命名约定：Z6CC-<版本号>.apk（如 Z6CC-1.2.2.apk）
# OTA 中继靠文件名里的 v?X.Y.Z 解析版本号，务必保留版本号、勿改前缀。
OUT_APK="$DIST/$APK_NAME-$VER_NAME.apk"

for f in "$BT/aapt2.exe" "$BT/d8.bat" "$BT/zipalign.exe" "$BT/apksigner.bat" "$PLATFORM"; do
  [ -e "$f" ] || { echo "✗ 缺少构建组件: $f"; exit 1; }
done
[ -f "$PROTO" ] || { echo "✗ 找不到原型页面: $PROTO"; exit 1; }

echo "==> 1/7 准备构建目录 $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/assets" "$WORK/build/res" "$WORK/build/classes" "$WORK/build/dex" "$WORK/out"

cp -r "$SRC/res" "$WORK/res"
cp -r "$SRC/src" "$WORK/src"
cp "$SRC/AndroidManifest.xml" "$WORK/AndroidManifest.xml"
# aapt2 link 的 --version-code/--version-name 不会覆盖 manifest 内的同名字段，
# 必须在这里同步，否则 PackageManager 读到的还是旧值、OTA 比错版本。
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" "$WORK/AndroidManifest.xml"

# 应用显示名（桌面图标 / 通知使用权列表看到的名称）：以本文件 APP_NAME 为唯一真值写入 strings.xml
sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$APP_NAME</string>|" "$WORK/res/values/strings.xml"
echo "    版本: $VER_NAME (versionCode $VER_CODE)"
cp -r "$SRC/assets" "$WORK/"            # 含 ota.properties / app.properties
cp "$PROTO" "$WORK/assets/index.html"
echo "    页面已内嵌: assets/index.html ($(wc -c < "$WORK/assets/index.html") bytes)"
echo "    配置已内嵌: assets/ota.properties ($(wc -c < "$WORK/assets/ota.properties") bytes)"
echo "    配置已内嵌: assets/app.properties ($(wc -c < "$WORK/assets/app.properties") bytes)"

cd "$WORK"

echo "==> 2/7 编译资源 (aapt2 compile)"
"$BT/aapt2.exe" compile --dir res -o build/res/res.zip

echo "==> 3/7 链接资源 + 打包 assets (aapt2 link)"
"$BT/aapt2.exe" link \
  -o out/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R build/res/res.zip \
  -A assets \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  --auto-add-overlay

echo "==> 4/7 编译 Java (javac --release 17)"
find src -name "*.java" > build/sources.txt
javac --release 17 -encoding UTF-8 -nowarn \
  -cp "$PLATFORM" -d build/classes @build/sources.txt

echo "==> 5/7 转 dex (d8)"
find build/classes -name "*.class" > build/classes.txt
"$BT/d8.bat" --release --min-api 24 --lib "$PLATFORM" \
  --output build/dex @build/classes.txt

echo "==> 6/7 合成 APK (resources + assets + classes.dex)"
"$PY" - <<'PYEOF'
import shutil, zipfile, os
src, dex, dst = "out/base.apk", "build/dex/classes.dex", "out/app-unsigned.apk"
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    print("    + classes.dex", os.path.getsize(dex), "bytes")
PYEOF

echo "==> 7/7 对齐 + 签名 (zipalign + apksigner)"

# ---- 签名密钥选择：release（默认，keystore.properties） / debug（本机自测） ----
KS_FILE=""; KS_STORE_PASS=""; KS_ALIAS=""; KS_KEY_PASS=""; KS_TYPE=""
if [ "$SIGN" = "release" ]; then
  PROPS="$SRC/keystore.properties"
  [ -f "$PROPS" ] || { echo "✗ 缺少签名配置: $PROPS（或改用 SIGN=debug）"; exit 1; }
  prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r'; }
  KS_FILE="$(prop storeFile)"
  KS_STORE_PASS="$(prop storePassword)"
  KS_ALIAS="$(prop keyAlias)"
  KS_KEY_PASS="$(prop keyPassword)"
  KS_TYPE="PKCS12"
  [ -f "$KS_FILE" ] || { echo "✗ 找不到 release 密钥: $KS_FILE"; exit 1; }
  echo "    签名方式: release（alias=$KS_ALIAS, keystore=$KS_FILE）"
else
  # 调试密钥必须落在固定路径：若放在带时间戳的 $WORK 里，每次构建都会重新生成密钥，
  # 结果是「连上一个 debug 包也覆盖装不上」，且永远无法与已安装应用对齐。
  KS_FILE="${DEBUG_KS:-C:/Users/yanzi/.android-keystore/l6-carmedia-debug.jks}"
  KS_STORE_PASS="android"; KS_KEY_PASS="android"; KS_ALIAS="androiddebugkey"; KS_TYPE="JKS"
  if [ ! -f "$KS_FILE" ]; then
    echo "    首次生成调试密钥: $KS_FILE"
    mkdir -p "$(dirname "$KS_FILE")"
    "$KEYTOOL" -genkeypair -keystore "$KS_FILE" \
      -storepass android -keypass android -alias androiddebugkey \
      -dname "CN=Android Debug,O=Android,C=CN" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  fi
  [ -f "$KS_FILE" ] || { echo "✗ keytool 生成密钥失败: $KEYTOOL"; exit 1; }
  echo "    签名方式: debug（固定密钥，仅本机自测；与 release 包彼此不兼容）"
fi

"$BT/zipalign.exe" -f -p 4 out/app-unsigned.apk out/app-aligned.apk

"$BT/apksigner.bat" sign \
  --ks "$KS_FILE" --ks-type "$KS_TYPE" \
  --ks-pass "pass:$KS_STORE_PASS" --key-pass "pass:$KS_KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out out/app-signed.apk out/app-aligned.apk

"$BT/apksigner.bat" verify --print-certs out/app-signed.apk | grep -E "DN:|SHA-256 digest|Verified using" || true

mkdir -p "$DIST"
cp out/app-signed.apk "$OUT_APK"

echo
echo "✓ 打包完成: $OUT_APK"
echo "  大小: $(wc -c < "$OUT_APK") bytes"
"$BT/aapt2.exe" dump badging out/app-signed.apk 2>/dev/null | grep -E "^package|^application-label|launchable-activity|sdkVersion" || true

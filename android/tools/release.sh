#!/usr/bin/env bash
# ============================================================
# 老六中控 · 一键发版（GitHub Releases）
#
#   bash android/tools/release.sh 1.2.4 "更新说明"
#   bash android/tools/release.sh              # 版本沿用 build.sh 的 VER_NAME
#
# 发版流程：打包 APK → git tag → GitHub Release 上传 APK。
# OTA 客户端走 GitHub Releases（+ gh-proxy 镜像回落），发完版车机即可检查到。
#
# 前置：装 GitHub CLI 并登录 gh auth login
# 注意：android/keystore.properties 含签名口令，已在 .gitignore 排除
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"            # 项目根目录（内容已上提到根）
BUILD="$ROOT/android/build.sh"
OTA_PROPS="$ROOT/android/assets/ota.properties"

# ---- 读仓库信息（与 OTA 客户端同一份，避免两处不一致）----
REPO_OWNER=""; REPO_NAME=""
if [ -f "$OTA_PROPS" ]; then
  while IFS='=' read -r k v || [ -n "$k" ]; do
    k="$(echo "$k" | tr -d ' ')"; v="$(echo "$v" | tr -d ' ')"
    case "$k" in
      REPO_OWNER) REPO_OWNER="$v" ;;
      REPO_NAME)  REPO_NAME="$v" ;;
    esac
  done < <(grep -E '^\s*(REPO_OWNER|REPO_NAME)\s*=' "$OTA_PROPS")
fi

# ---- 参数 ----
NEW_VER="${1:-}"; NOTES="${2:-}"
if [ -z "$NEW_VER" ]; then
  NEW_VER="$(grep -m1 '^VER_NAME=' "$BUILD" | sed 's/VER_NAME="//; s/"//')"
  echo "· 未指定版本，沿用 build.sh 的 $NEW_VER"
fi
if [ -z "$NOTES" ]; then
  NOTES="老六中控 v$NEW_VER
- 详见提交记录"
fi
echo "$NEW_VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "✗ 版本号格式必须是 x.y.z，收到：$NEW_VER"; exit 1; }

# versionCode = major*10000 + minor*100 + patch（与 build.sh / Ota.java 三方一致）
IFS='.' read -r MA MI PA <<< "$NEW_VER"
NEW_CODE=$(( MA * 10000 + MI * 100 + PA ))
echo "==> 发版 v$NEW_VER (versionCode $NEW_CODE)"

# ---- 1. 改版本号 + 打包 ----
cd "$ROOT/android"
sed -i "s/^VER_NAME=\".*\"/VER_NAME=\"$NEW_VER\"/" build.sh
bash build.sh > /tmp/l6-release-build.log 2>&1 || {
  echo "✗ 打包失败，日志：/tmp/l6-release-build.log"; tail -20 /tmp/l6-release-build.log; exit 1;
}
grep -E "✓ 打包完成|版本:" /tmp/l6-release-build.log || true
rm -f /tmp/l6-release-build.log

APK="$ROOT/dist/Z6CC-$NEW_VER.apk"
[ -f "$APK" ] || { echo "✗ 找不到产物：$APK"; exit 1; }
echo "    产物: $APK ($(wc -c < "$APK") bytes)"

# ---- 2. 校验 + SHA + git + Release ----
[ -n "$REPO_OWNER" ] && [ -n "$REPO_NAME" ] || { echo "✗ 读不到 REPO_OWNER/REPO_NAME：$OTA_PROPS"; exit 1; }
REPO_URL="https://github.com/$REPO_OWNER/$REPO_NAME.git"
command -v gh >/dev/null 2>&1 || { echo "✗ 未安装 GitHub CLI（https://cli.github.com/）"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "✗ gh 未登录，请先执行： gh auth login"; exit 1; }

SHA=""
if command -v sha256sum >/dev/null 2>&1; then
  SHA="$(sha256sum "$APK" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
  SHA="$(openssl dgst -sha256 "$APK" | awk '{print $NF}')"
fi
FULL_NOTES="$NOTES"
if [ -n "$SHA" ]; then
  FULL_NOTES="$NOTES

sha256: $SHA"
  echo "    SHA-256: $SHA"
fi

cd "$ROOT"
if [ ! -d .git ]; then
  echo "==> 首次发版：初始化 git 仓库并绑定 origin"
  git init -q
  git remote add origin "$REPO_URL"
  git branch -M main
fi
git remote set-url origin "$REPO_URL" 2>/dev/null || git remote add origin "$REPO_URL"

git add -A
if git diff --cached --quiet; then
  echo "· 无文件变更，跳过 commit"
else
  git commit -q -m "release: v$NEW_VER

$NOTES"
  echo "    commit ok"
fi

git tag -f "v$NEW_VER" >/dev/null 2>&1
git push -q origin main 2>/dev/null || git push -q -u origin main
git push -q -f origin "v$NEW_VER"
echo "    tag v$NEW_VER 已推送"

if gh release view "v$NEW_VER" >/dev/null 2>&1; then
  echo "· Release v$NEW_VER 已存在，覆盖上传资产"
  gh release upload "v$NEW_VER" "$APK" --clobber
  gh release edit "v$NEW_VER" --title "老六中控 v$NEW_VER" --notes "$FULL_NOTES"
else
  gh release create "v$NEW_VER" "$APK" \
    --title "老六中控 v$NEW_VER" \
    --notes "$FULL_NOTES"
fi

echo
echo "✓ 已发布 https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/v$NEW_VER"
echo "  车机端：设置 → 在线更新 → 检查更新（未授权安装时按提示开「允许安装未知应用」）"

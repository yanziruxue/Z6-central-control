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
# shellcheck source=lib.sh
. "$HERE/lib.sh"
# 环境守卫：cmd/PowerShell 里敲 `bash release.sh` 会落到 WSL 的 bash → 构建组件「找不到」
l6_require_git_bash "$0" "$@" || exit 1

ROOT="$(cd "$HERE/../.." && pwd)"            # 项目根目录（内容已上提到根）
BUILD="$ROOT/android/build.sh"
OTA_PROPS="$ROOT/android/assets/ota.properties"

# 推送失败时的硬闸：必须显式报错并给出补救命令。
# 背景（v1.3.0 踩过）：push 全败后脚本继续往下走，结果本地 tag 建好了、
# 远端 main 还停在上一版、Release 根本没建 —— 看起来「发过了」，实际没发出去。
l6_fail_push() {
  echo
  echo "✗ 推送失败：$1 未能同步到远端（已重试 $TRIES 次）"
  echo "  常见于国内直连 github.com:443 丢 SYN。补救："
  echo "    cd \"$(pwd)\" && bash android/tools/push.sh --release $2"
  echo "  或只推代码：  bash android/tools/push.sh"
  echo "  网络好转后重跑本脚本是安全的（commit/tag 已就绪，重复执行只是覆盖）。"
  exit 1
}

# ---- 读仓库信息（与 OTA 客户端同一份，避免两处不一致）----
REPO_OWNER=""; REPO_NAME=""; SELF_OTA_BASE=""; SELF_OTA_OWNER=""; SELF_OTA_REPO=""
if [ -f "$OTA_PROPS" ]; then
  while IFS='=' read -r k v || [ -n "$k" ]; do
    k="$(echo "$k" | tr -d ' ')"; v="$(echo "$v" | tr -d ' ')"
    case "$k" in
      REPO_OWNER) REPO_OWNER="$v" ;;
      REPO_NAME)  REPO_NAME="$v" ;;
      SELF_OTA_BASE)  SELF_OTA_BASE="$v" ;;
      SELF_OTA_OWNER) SELF_OTA_OWNER="$v" ;;
      SELF_OTA_REPO)  SELF_OTA_REPO="$v" ;;
    esac
  done < <(grep -E '^\s*(REPO_OWNER|REPO_NAME|SELF_OTA_BASE|SELF_OTA_OWNER|SELF_OTA_REPO)\s*=' "$OTA_PROPS")
fi
# 自托管 Git（git.ziruxue.top）远端地址；三项齐全才算启用双发，否则仅 GitHub 单发
if [ -n "$SELF_OTA_BASE" ] && [ -n "$SELF_OTA_OWNER" ] && [ -n "$SELF_OTA_REPO" ]; then
  SELF_URL="${SELF_OTA_BASE%/}/$SELF_OTA_OWNER/$SELF_OTA_REPO.git"
else
  SELF_URL=""
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

# 自托管 Git 远端（git.ziruxue.top）：三项配置齐全才加，否则仅 GitHub 单发
if [ -n "$SELF_URL" ]; then
  git remote set-url gitzx "$SELF_URL" 2>/dev/null || git remote add gitzx "$SELF_URL"
  echo "· 自托管远端: $SELF_URL"
fi

git add -A
if git diff --cached --quiet; then
  echo "· 无文件变更，跳过 commit"
else
  git commit -q -m "release: v$NEW_VER

$NOTES"
  echo "    commit ok"
fi

git tag -f "v$NEW_VER" >/dev/null 2>&1
# 国内直连 github.com 会丢 SYN（每次耗满 ~21s 才失败）→ 压短连接超时 + 多轮重试
# 可用环境变量覆盖： L6_TRIES=20 L6_WAIT=3 bash android/tools/release.sh ...
TRIES="${L6_TRIES:-12}"; WAIT="${L6_WAIT:-5}"
if ! l6_retry "$TRIES" "$WAIT" "推送 main" -- git -c http.connectTimeout=8 push -q origin main; then
  l6_retry "$TRIES" "$WAIT" "推送 main（-u）" -- git -c http.connectTimeout=8 push -q -u origin main \
    || l6_fail_push "main" "$NEW_VER"
fi
l6_retry "$TRIES" "$WAIT" "推 tag" -- git -c http.connectTimeout=8 push -q -f origin "v$NEW_VER" \
  || l6_fail_push "tag v$NEW_VER" "$NEW_VER"

# 验收：本地 HEAD 必须真的出现在远端（只信 ls-remote，不信退出码）
LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git ls-remote origin refs/heads/main 2>/dev/null | awk '{print $1}')"
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || l6_fail_push "main" "$NEW_VER"
git ls-remote --tags origin "refs/tags/v$NEW_VER" | grep -q . || l6_fail_push "tag v$NEW_VER" "$NEW_VER"
echo "    远端已同步 ($REMOTE_SHA) + tag v$NEW_VER"

# ---- 推送到自托管 Git（git.ziruxue.top）----
if [ -n "$SELF_URL" ]; then
  echo "==> 推送 main + tag 到自托管 Git (gitzx)"
  if git -c http.connectTimeout=8 push -q gitzx main 2>/dev/null && \
     git -c http.connectTimeout=8 push -q -f gitzx "v$NEW_VER" 2>/dev/null; then
    echo "    已同步 gitzx ($SELF_URL)"
  else
    echo "⚠ gitzx 推送失败（多为本环境 TLS/凭据限制，或前层仅收 TLS1.3 致老设备不可达）。"
    echo "  请在你的机器重试： git push gitzx main && git push -f gitzx v$NEW_VER"
  fi
fi

# gh 是 Windows 程序，不认 MSYS 的 /d/... 路径（会报 no matches found），
# 必须转成 Windows 形式（D:/...）再传给它。
APK_GH="$(l6_to_win_slash "$APK")"
if gh release view "v$NEW_VER" >/dev/null 2>&1; then
  echo "· Release v$NEW_VER 已存在，覆盖上传资产"
  gh release upload "v$NEW_VER" "$APK_GH" --clobber
  gh release edit "v$NEW_VER" --title "老六中控 v$NEW_VER" --notes "$FULL_NOTES"
else
  gh release create "v$NEW_VER" "$APK_GH" \
    --title "老六中控 v$NEW_VER" \
    --notes "$FULL_NOTES"
fi

# 终检：Release 必须真的能查到且带上 APK 资产，否则车机 OTA 看不到新版本
gh release view "v$NEW_VER" --json tagName,assets \
  --jq '.assets[]?.name' 2>/dev/null | grep -qx "Z6CC-$NEW_VER.apk" \
  || { echo "✗ Release 已建但资产 Z6CC-$NEW_VER.apk 缺失，请手动补传："; \
       echo "    gh release upload v$NEW_VER \"$(l6_to_win_slash "$APK")\" --clobber"; exit 1; }

# ---- 在自托管 Git 建 Release + 上传 APK（Gitea API，需 token）----
if [ -n "$SELF_URL" ]; then
  GIT_ZX_TOKEN="${GIT_ZX_TOKEN:-}"
  if [ -f "$ROOT/android/gitea_token.properties" ]; then
    GIT_ZX_TOKEN="$(l6_prop "$ROOT/android/gitea_token.properties" GIT_ZX_TOKEN || true)"
  fi
  if [ -z "$GIT_ZX_TOKEN" ]; then
    echo "⚠ 未配置 GIT_ZX_TOKEN（发版到 git.ziruxue.top 需要），跳过 Gitea Release。"
    echo "  配置： export GIT_ZX_TOKEN=xxx  或写 android/gitea_token.properties (GIT_ZX_TOKEN=xxx, 已 gitignore)"
  else
    GITEA_API="${SELF_OTA_BASE%/}/api/v1"
    echo "==> 在 git.ziruxue.top 建 Release + 上传 APK"
    export L6_VER="$NEW_VER"; export L6_NOTES="$FULL_NOTES"
    if command -v jq >/dev/null 2>&1; then
      REL_JSON="$(jq -n --arg tag "v$NEW_VER" --arg name "老六中控 v$NEW_VER" --arg body "$FULL_NOTES" \
        '{tag_name:$tag,name:$name,body:$body,prerelease:false,target_commitish:"main"}' 2>/dev/null || true)"
    else
      PY="$(command -v python3 || command -v python || true)"
      if [ -n "$PY" ]; then
        REL_JSON="$($PY -c 'import json,os;print(json.dumps({"tag_name":"v"+os.environ["L6_VER"],"name":"老六中控 v"+os.environ["L6_VER"],"body":os.environ["L6_NOTES"],"prerelease":False,"target_commitish":"main"}))' 2>/dev/null || true)"
      else
        REL_JSON=""; echo "⚠ 需要 jq 或 python 构造 Gitea Release JSON，跳过 Gitea Release"
      fi
    fi
    if [ -n "$REL_JSON" ]; then
      CREATE_RESP="$(curl -s -X POST "$GITEA_API/repos/$SELF_OTA_OWNER/$SELF_OTA_REPO/releases" \
        -H "Authorization: token $GIT_ZX_TOKEN" -H "Content-Type: application/json" \
        --data "$REL_JSON" 2>&1)" || true
      REL_ID="$(printf '%s' "$CREATE_RESP" | (command -v jq >/dev/null 2>&1 && jq -r '.id // empty' 2>/dev/null || python3 -c 'import json,sys;print(json.load(sys.stdin).get("id",""))' 2>/dev/null) || true)" || true
      if [ -z "$REL_ID" ]; then
        REL_ID="$(curl -s "$GITEA_API/repos/$SELF_OTA_OWNER/$SELF_OTA_REPO/releases/tags/v$NEW_VER" \
          -H "Authorization: token $GIT_ZX_TOKEN" 2>/dev/null | (command -v jq >/dev/null 2>&1 && jq -r '.id // empty' 2>/dev/null || python3 -c 'import json,sys;print(json.load(sys.stdin).get("id",""))' 2>/dev/null) || true)" || true
      fi
      if [ -n "$REL_ID" ]; then
        curl -s -X POST "$GITEA_API/repos/$SELF_OTA_OWNER/$SELF_OTA_REPO/releases/$REL_ID/assets?name=Z6CC-$NEW_VER.apk" \
          -H "Authorization: token $GIT_ZX_TOKEN" -H "Content-Type: application/octet-stream" \
          --data-binary "@$APK" 2>&1 | (command -v jq >/dev/null 2>&1 && jq -r '"    资产: "+(.name // (.message // "未知"))' 2>/dev/null || echo "    已上传资产 Z6CC-$NEW_VER.apk") || true
        echo "✓ Gitea Release v$NEW_VER 已建（git.ziruxue.top）"
      else
        echo "⚠ Gitea Release 创建失败（检查 token / TLS / 仓库是否存在）："
        printf '%s\n' "$CREATE_RESP" | head -c 300
      fi
    fi
  fi
fi

echo
echo "✓ 已发布 https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/v$NEW_VER"
echo "  车机端：设置 → 在线更新 → 检查更新（未授权安装时按提示开「允许安装未知应用」）"

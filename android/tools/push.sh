#!/usr/bin/env bash
# ============================================================
# 老六中控 · 一键推送到 GitHub
#
#   bash android/tools/push.sh                          # 提交本地改动并推送 main
#   bash android/tools/push.sh -m "说明"                 # 自定义提交说明
#   bash android/tools/push.sh --tag 1.2.7              # 推送后打 tag v1.2.7 并推 tag
#   bash android/tools/push.sh --release 1.2.7 "说明"    # 推送 + 打包 + 发 Release（转交 release.sh）
#
# 自动处理三种常见坑：
#   1) 用 cmd/PowerShell/WSL 的 bash 跑 → 自动切回 Git Bash（见 tools/lib.sh）
#   2) 远端已有 commit 且与本地无共同祖先（新建仓库的 stub README）→ 自动 merge --allow-unrelated-histories
#   3) 签名配置 android/keystore.properties 被误纳入版本控制 → 拒绝推送并给出修复命令
#
# 仓库地址从 android/assets/ota.properties 读（与 OTA 客户端同一份，不会两处不一致）
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
l6_require_git_bash "$0" "$@" || exit 1

ROOT="$(cd "$HERE/../.." && pwd)"
OTA_PROPS="$ROOT/android/assets/ota.properties"

usage() {
  sed -n '3,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

# ---- 参数 ----
MSG=""; TAG=""; REL=""; REL_NOTES=""
while [ $# -gt 0 ]; do
  case "$1" in
    -m|--message) MSG="${2:-}"; shift || true; shift || true ;;
    --tag)        TAG="${2:-}"; shift || true; shift || true ;;
    --release)    REL="${2:-}"; REL_NOTES="${3:-}"; shift || true; shift || true; shift || true ;;
    -h|--help)    usage ;;
    *)            echo "✗ 未知参数: $1（ -h 查看用法）"; exit 1 ;;
  esac
done

cd "$ROOT"
echo "==> 一键推送（$ROOT）"

# ---- 1. 仓库 / 身份 / 远端 ----
if [ ! -d .git ]; then
  echo "· 首次运行：初始化 git 仓库"
  git init -q -b main
fi
git config user.name  >/dev/null 2>&1 || git config user.name  "yanziruxue"
git config user.email >/dev/null 2>&1 || git config user.email "yanziruxue@users.noreply.github.com"

REPO_OWNER="$(l6_prop "$OTA_PROPS" REPO_OWNER || true)"
REPO_NAME="$(l6_prop "$OTA_PROPS" REPO_NAME  || true)"
if [ -z "$REPO_OWNER" ] || [ -z "$REPO_NAME" ]; then
  echo "✗ 读不到 REPO_OWNER / REPO_NAME：$OTA_PROPS"; exit 1
fi
REPO_URL="https://github.com/$REPO_OWNER/$REPO_NAME.git"
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi
echo "· 远端: $REPO_URL"

# ---- 2. 安全闸：签名口令文件绝不能进库 ----
if git ls-files --error-unmatch android/keystore.properties >/dev/null 2>&1; then
  echo "✗ 危险：android/keystore.properties（含签名口令）已被 git 跟踪，拒绝推送。"
  echo "  修复：git rm --cached android/keystore.properties && git commit -m \"chore: 移除签名配置\""
  exit 1
fi

# ---- 3. 提交本地改动 ----
git add -A
if git diff --cached --quiet; then
  echo "· 无待提交改动"
else
  [ -n "$MSG" ] || MSG="chore: 更新 $(date '+%Y-%m-%d %H:%M')"
  git commit -q -m "$MSG"
  echo "· 已提交: $(git log --oneline -1)"
fi

# ---- 4. 对齐远端（含「无共同祖先」的首次推送）----
if git fetch -q origin "+refs/heads/main:refs/remotes/origin/main" 2>/dev/null; then
  if ! git merge-base HEAD origin/main >/dev/null 2>&1; then
    echo "· 远端 main 与本地无共同祖先（新建仓库的 stub commit）→ 合并历史"
    git merge --allow-unrelated-histories --no-edit origin/main >/dev/null \
      || { echo "✗ 合并冲突，请手工处理（git status）后重跑"; exit 1; }
    echo "· 已合并: $(git log --oneline -1)"
  elif [ -n "$(git log --oneline HEAD..origin/main | head -1)" ]; then
    echo "· 远端有新提交 → 合并到本地"
    git merge --no-edit origin/main >/dev/null \
      || { echo "✗ 合并冲突，请手工处理（git status）后重跑"; exit 1; }
    echo "· 已合并: $(git log --oneline -1)"
  else
    echo "· 远端无新提交"
  fi
else
  echo "· 远端暂无 main 分支，将首次推送"
fi

# ---- 5. 推送 ----
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || { echo "· 当前分支 $BRANCH → 改名 main"; git branch -M main; BRANCH=main; }

echo "· 推送 $BRANCH → origin"
if push_out="$(git push -u origin "$BRANCH" 2>&1)"; then
  printf '%s\n' "$push_out" | sed 's/^/    /'
else
  printf '%s\n' "$push_out" | sed 's/^/    /'
  echo "✗ 推送失败。常见原因："
  echo "  · 未登录 → gh auth login，或配置 PAT 凭据"
  echo "  · 无写权限 / 仓库被保护"
  exit 1
fi

# ---- 6. 可选：打 tag ----
if [ -n "$TAG" ]; then
  git tag -f "v$TAG" >/dev/null
  git push -q -f origin "v$TAG"
  echo "· tag v$TAG 已推送"
fi

echo
echo "✓ 推送完成 https://github.com/$REPO_OWNER/$REPO_NAME （分支 $BRANCH，$(git log --oneline -1)）"

# ---- 7. 可选：转交 release.sh 发 Release（OTA 的落脚点）----
if [ -n "$REL" ]; then
  echo
  echo "==> 转交 release.sh v$REL（打包 + 发 GitHub Release）"
  exec bash "$HERE/release.sh" "$REL" "$REL_NOTES"
fi

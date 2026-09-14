#!/usr/bin/env bash
# ============================================================
# 老六中控 · 脚本公共库（被 build.sh / release.sh / push.sh source）
#
# 提供：
#   l6_require_git_bash   环境守卫：必须是 Git Bash；在 WSL 下自动切回 Git Bash 重跑
#   l6_git_bash_win       定位 Git for Windows 的 bash.exe（返回 Windows 路径）
#   l6_prop <文件> <键>    读 *.properties 的键值（去空白与 CR）
#
# 为什么需要守卫：在 cmd / PowerShell 里敲 `bash xxx.sh`，cmd 会解析到
# C:\Windows\System32\bash.exe（WSL），而不是 Git Bash。WSL 看不到 D:/... 这类
# Windows 路径，于是 build.sh 第一步检查就报「✗ 缺少构建组件: D:/.../aapt2.exe」——
# 组件其实好好的，只是找错了 shell。本项目所有构建工具（aapt2/d8/apksigner/javac）
# 都是 Windows 程序，必须在 Git Bash（MSYS）下运行。
# ============================================================

# ---- 是否运行在 WSL 中 ----
l6_is_wsl() {
  [ -n "${WSL_DISTRO_NAME:-}" ] && return 0
  [ -n "${WSL_INTEROP:-}" ] && return 0
  [ -r /proc/version ] && grep -qi microsoft /proc/version 2>/dev/null && return 0
  return 1
}

# ---- WSL 下 Windows 盘符的挂载前缀（默认 /mnt/c）----
l6_win_mnt() {
  if [ -d /mnt/c ]; then echo "/mnt/c"; return; fi
  if [ -d /c ]; then echo "/c"; return; fi
  echo "/mnt/c"
}

# ---- 本地路径 → Windows 路径 ----
l6_to_win() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; return; fi
  if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1"; return; fi
  echo "$1"
}

# ---- 本地路径 → Windows 路径（正斜杠形式，专供 gh 等 Windows 程序当参数使用）----
# 不转换会报：no matches found for `/d/.../x.apk`（gh 是 Windows 程序，不认 MSYS 的 /d/ 路径）
l6_to_win_slash() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; return; fi
  echo "$1"
}

# ---- 定位 Git for Windows 的 bash.exe（输出 Windows 路径；失败返回 1）----
l6_git_bash_win() {
  local mnt="/c" c
  l6_is_wsl && mnt="$(l6_win_mnt)"
  local cands=()
  [ -n "${L6_GIT_BASH:-}" ] && cands+=("$L6_GIT_BASH")
  cands+=(
    "$mnt/Program Files/Git/bin/bash.exe"
    "$mnt/Program Files/Git/usr/bin/bash.exe"
    "$mnt/Program Files (x86)/Git/bin/bash.exe"
  )
  # WorkBuddy 自带的 PortableGit（版本号会变，用 glob 兜）
  for c in "$mnt"/Users/*/.workbuddy/binaries/PortableGit/*/usr/bin/bash.exe; do
    [ -f "$c" ] && cands+=("$c")
  done
  for c in "${cands[@]}"; do
    [ -f "$c" ] || continue
    l6_to_win "$c"
    return 0
  done
  # 已在 Git Bash 中：从当前 shell 自身反推
  if command -v cygpath >/dev/null 2>&1 && [ -n "${BASH:-}" ]; then
    cygpath -w "$BASH" 2>/dev/null && return 0
  fi
  return 1
}

# ---- 环境守卫 ----
# 用法：l6_require_git_bash "$0" "$@" || exit 1
# 返回 0 = 环境合格（或在 WSL 下已 exec 到 Git Bash，本进程不再返回）；
# 返回 1 = 环境不合格，调用方应 exit 1
l6_require_git_bash() {
  local self="${1:-}"; shift || true

  # ① WSL：自动切回 Git Bash 重跑（Windows 侧 bash，路径全转 Windows 形式）
  if l6_is_wsl; then
    echo "✗ 检测到当前在 WSL 里跑（\$WSL_DISTRO_NAME=${WSL_DISTRO_NAME:--}），不是 Git Bash。"
    echo "  这正是「✗ 缺少构建组件: D:/.../aapt2.exe」的原因：WSL 里 D:/ 不是合法路径，组件其实都在。"
    local gb=""; gb="$(l6_git_bash_win || true)"
    if [ -n "$gb" ] && command -v wslpath >/dev/null 2>&1 && [ -n "$self" ]; then
      local wself wpwd args="" a
      wself="$(wslpath -w "$self" 2>/dev/null || true)"
      wpwd="$(wslpath -w "$PWD" 2>/dev/null || true)"
      if [ -n "$wself" ] && [ -n "$wpwd" ] && "$gb" -c "exit 0" >/dev/null 2>&1; then
        for a in "$@"; do args="$args $(printf '%q' "$a")"; done
        echo "· 已自动改用 Git Bash 重跑 → $gb"
        exec "$gb" -c "cd \"$wpwd\" && bash \"$wself\"$args"
      fi
    fi
    echo "  请改用 Git Bash 运行（开始菜单搜 Git Bash，或 cmd 里直接调）："
    if [ -n "$gb" ]; then
      echo "    cd /d \"D:\\AI\\WorkBuddy\\老六\" && \"$gb\" android/tools/release.sh"
    else
      echo "    在 Git Bash 中： cd \"D:/AI/WorkBuddy/老六\" && bash android/tools/release.sh"
    fi
    return 1
  fi

  # ② Git Bash / MSYS / Cygwin：放行
  case "${MSYSTEM:-}" in
    MINGW*|MSYS*|UCRT*|CLANG*) return 0 ;;
  esac
  command -v cygpath >/dev/null 2>&1 && return 0

  # ③ 其它（纯 Linux/macOS 或裁剪过的 shell）：明确报错，别让它跑到一半才炸
  echo "✗ 未检测到 Git Bash/MSYS 环境（MSYSTEM 为空且无 cygpath）。"
  echo "  本项目构建链依赖 MSYS 路径转换，请在 Git Bash 中运行："
  echo "    cd \"D:/AI/WorkBuddy/老六\" && bash android/tools/<脚本>.sh"
  return 1
}

# ---- 读 properties 键值 ----
l6_prop() {   # $1=文件 $2=键
  [ -f "$1" ] || return 1
  grep -E "^[[:space:]]*$2[[:space:]]*=" "$1" | head -1 | cut -d= -f2- | tr -d ' \r'
}

# ---- 带重试执行（国内直连 github.com 时通时断，push/fetch 必须重试）----
# 用法：l6_retry <次数> <间隔秒> <描述> -- <命令...>
l6_retry() {
  local n="$1" wait="$2" desc="$3"; shift 3
  [ "${1:-}" = "--" ] && shift
  local i=1 out
  while :; do
    out="$("$@" 2>&1)" && { printf '%s\n' "$out"; return 0; }
    if [ "$i" -ge "$n" ]; then printf '%s\n' "$out"; return 1; fi
    echo "· $desc 第 $i 次失败，$(printf '%s' "$out" | grep -oE 'Failed to connect[^"]*|Could not resolve host[^"]*' | head -1 || printf '%s' "$out" | tail -1 | cut -c1-90)"
    echo "  ${wait}s 后重试（$((i+1))/$n）…"
    sleep "$wait"; i=$((i+1))
  done
}

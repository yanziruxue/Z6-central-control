# ============================================================
# setup_win11.ps1 —— 在本机（Win11）一键部署「老六中控」APK 构建环境
#
# 作用：
#   1) 安装 JDK 17（Microsoft OpenJDK，winget）
#   2) 准备 Android SDK 目录（默认 %LOCALAPPDATA%\Android\Sdk）
#   3) 下载 Android command-line tools，并安装
#      build-tools;35.0.0 / platforms;android-35 / platform-tools
#   4) 校验构建组件齐全
#
# 用法（管理员 PowerShell 建议，winget 安装不需管理员但建议）：
#   powershell -ExecutionPolicy Bypass -File android/tools/setup_win11.ps1
#
# 前置：需联网（winget 拉 JDK + dl.google.com 拉 Android SDK 组件）。
# 完成后还需：
#   - 注册 Gitea Runner（见脚本末尾提示）
#   - 在 Gitea 仓库 Settings → Secrets 配 KEYSTORE_B64 / KEYSTORE_STORE_PASS /
#     KEYSTORE_KEY_PASS / KEYSTORE_ALIAS / GITEA_TOKEN
#   - 把 release 签名密钥 l6-carmedia-release.jks 放到
#     C:\Users\yanzi\.android-keystore\（仅本机手动打包用；CI 走 KEYSTORE_B64）
# ============================================================
$ErrorActionPreference = "Stop"

# ---------- 1. JDK 17 ----------
Write-Host "==> [1/4] 检查/安装 JDK 17 (Microsoft OpenJDK)" -ForegroundColor Cyan
$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($javac) {
    Write-Host "    已存在 javac: $($javac.Source)"
} else {
    Write-Host "    通过 winget 安装 Microsoft.OpenJDK.17 ..."
    winget install --id Microsoft.OpenJDK.17 -e --accept-source-agreements --accept-package-agreements
    # 刷新当前会话 PATH（让后续步骤能直接调 javac）
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + `
                [System.Environment]::GetEnvironmentVariable("Path", "User")
}
$jh = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
if (-not $jh) { $jh = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "User") }
if (-not $jh) {
    # Microsoft OpenJDK 可能装在 Program Files\Microsoft\jdk-17*
    $guess = Get-ChildItem "C:\Program Files\Microsoft\jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($guess) { $jh = $guess.FullName }
}
if (-not $jh) { Write-Error "未检测到 JAVA_HOME，请手动设置指向 JDK 17 安装目录"; exit 1 }
Write-Host "    JAVA_HOME = $jh"

# ---------- 2. Android SDK 目录 ----------
Write-Host "==> [2/4] 准备 Android SDK 目录" -ForegroundColor Cyan
$sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path $sdkRoot)) { New-Item -ItemType Directory -Path $sdkRoot -Force | Out-Null }
Write-Host "    ANDROID_SDK_ROOT = $sdkRoot"
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkRoot, "User")
# 当前会话也导出，便于本脚本内 sdkmanager 使用
$env:ANDROID_SDK_ROOT = $sdkRoot

# ---------- 3. command-line tools + 组件 ----------
Write-Host "==> [3/4] 下载 Android command-line tools 并安装组件" -ForegroundColor Cyan
$cmdlineUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$tmpZip = Join-Path $env:TEMP "cmdline-tools.zip"
Write-Host "    下载 $cmdlineUrl ..."
Invoke-WebRequest -Uri $cmdlineUrl -OutFile $tmpZip -UseBasicParsing
$cmdlineDest = Join-Path $sdkRoot "cmdline-tools"
if (Test-Path $cmdlineDest) { Remove-Item $cmdlineDest -Recurse -Force }
Expand-Archive -Path $tmpZip -DestinationPath $cmdlineDest -Force
# 规范布局：cmdline-tools/latest/bin/sdkmanager
Rename-Item (Join-Path $cmdlineDest "cmdline-tools") (Join-Path $cmdlineDest "latest") -Force
$sdkmanager = Join-Path $cmdlineDest "latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) { Write-Error "sdkmanager 未找到: $sdkmanager"; exit 1 }

# 接受许可（用 30 个 y 喂 stdin）
Write-Host "    接受 licenses ..."
$yes = ("y") * 30 -join "`n"
$yes | & $sdkmanager "--sdk_root=$sdkRoot" "--licenses" 2>&1 | Out-Null

# 安装构建所需组件
Write-Host "    安装 build-tools;35.0.0 / platforms;android-35 / platform-tools ..."
& $sdkmanager "--sdk_root=$sdkRoot" "build-tools;35.0.0" "platforms;android-35" "platform-tools"
if ($LASTEXITCODE -ne 0) { Write-Error "sdkmanager 安装失败"; exit 1 }
Write-Host "    安装完成。"

# ---------- 4. 校验 ----------
Write-Host "==> [4/4] 校验构建组件" -ForegroundColor Cyan
$checks = @(
    (Join-Path $sdkRoot "build-tools\35.0.0\aapt2.exe"),
    (Join-Path $sdkRoot "build-tools\35.0.0\d8.bat"),
    (Join-Path $sdkRoot "build-tools\35.0.0\zipalign.exe"),
    (Join-Path $sdkRoot "build-tools\35.0.0\apksigner.bat"),
    (Join-Path $sdkRoot "platforms\android-35\android.jar")
)
$ok = $true
foreach ($f in $checks) {
    if (Test-Path $f) { Write-Host "    ✓ $f" } else { Write-Host "    ✗ 缺失 $f"; $ok = $false }
}
if (-not $ok) { Write-Error "组件不齐，请检查上面缺失项"; exit 1 }

Write-Host ""
Write-Host "✅ 构建环境就绪！" -ForegroundColor Green
Write-Host "   JAVA_HOME        = $jh"
Write-Host "   ANDROID_SDK_ROOT = $sdkRoot"
Write-Host ""
Write-Host "下一步（注册 Gitea Runner，在管理员 PowerShell 运行）：" -ForegroundColor Yellow
Write-Host "  1) 下载 act_runner Windows 版并解压到某目录（如 C:\act_runner）"
Write-Host "  2) Gitea 仓库 Settings → Actions → Runners 创建 runner，拿到 REG_TOKEN"
Write-Host '     C:\act_runner\act_runner.exe register --instance http://192.168.24.10:8024 --token <REG_TOKEN> --name win11-builder --labels windows,self-hosted'
Write-Host '     C:\act_runner\act_runner.exe run        # 常驻前台；或注册为 Windows 服务'
Write-Host "  3) Gitea 仓库 Settings → Secrets 添加："
Write-Host "     KEYSTORE_B64 / KEYSTORE_STORE_PASS / KEYSTORE_KEY_PASS / KEYSTORE_ALIAS / GITEA_TOKEN"
Write-Host "     （KEYSTORE_B64 = base64 -w0 l6-carmedia-release.jks 的输出）"
Write-Host "  4) release 签名密钥放到 C:\Users\yanzi\.android-keystore\l6-carmedia-release.jks"
Write-Host "     （仅本机手动 bash android/build.sh 用；CI 走 KEYSTORE_B64）"

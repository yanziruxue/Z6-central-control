# 老六中控（Z6-central-control）

长安欧尚 Z6 车机媒体中控 APK。原生 Java 视图层，横屏单屏，替换车机原厂娱乐桌面。

- **包名**：`com.l6.carmedia`
- **当前版本**：v2.0.27（versionCode 20027）
- **平台**：Android 9（API 28），minSdk = targetSdk = 28，横屏 1920×720（2.667:1）
- **OTA 主源**：GitHub Releases（`yanziruxue/Z6-central-control`），Gitea 分发站保底

## 功能特性

| 模块 | 说明 |
|---|---|
| 主页 · 车辆状态 | 车模 + 动力/服务端网格（车速/油表/电瓶/里程/负载/温度/延时/胎压/车身状态）。**电瓶卡（12V 电压）低于 12V 自动红字 + 红边告警** |
| 主页 · 音乐 | 正在播放/频谱/播放列表，数据来自车机媒体广播（SysHub 事件） |
| Dock 栏 | 主页 / 导航 / 设置三钮（仅图标），支持长按拖动排序、拖到垃圾桶取消钉住 |
| 手势 | 上滑回车机原生桌面、下滑调起导航；手势分区可自定义绑定 |
| 悬浮返回 | 导航 App 前台时屏幕最左侧悬浮按钮，点按回本应用（纯逻辑 `TapJudge` 12dp/600ms 判定，适配手机高密度屏） |
| 信号采集 | 设置页「开始采集」→ 每秒全量快照比对（`SigDiff`）→ 结束落 `caiji-*.json`（changes1 保留 / changes2 过滤噪声，相加 = 全部变化）；「🗑 清空采集日志」一键删除全部采集产物（运行日志保留） |
| 在线更新 | 启动自动检查 + 手动检查；半发版状态（Release 已建、资产未传）正确跳过；支持 `gh-proxy.com` 镜像回退 |
| 壁纸 | 静态 / 动态壁纸，落 `Download/L6/壁纸/` 对应子目录 |

## 数据来源（实测结论，非设想）

车机 ROM **未向第三方 APK 开放车辆信号 API**（Car API `carPowerPermission` / `vehicleSignal` 均 denied）。当前真实取数通道：

- **车况**：`persist.sys.*` 等系统属性 + `BatteryManager`（12V 电瓶电压 = `EXTRA_VOLTAGE`）+ 车辆 `veh` 事件（`natMergeVeh()` 合并）
- **导航**：高德广播 `AUTONAVI_STANDARD_BROADCAST_SEND`（动态注册 → `NavBcastData`），通知源退兜底；**导航画面嵌入主页区域技术封死**（高德不声明 `allowEmbedded`、本车机无高德小部件），导航以上下滑手势调起外部 App
- **音乐/歌词**：车机媒体广播（`SysHub` 事件）

## 存储布局（v2.0.9 起）

```
/sdcard/Download/L6/
├── app-log/                  # 运行日志 l6-YYYY-MM-DD.log
├── caiji/                    # 采集快照 caiji-YYYYMMDD-HHMMSS.json
└── 壁纸/
    ├── 静态/                  # 静态壁纸文件
    └── 动态/                  # 动态壁纸文件
```

启动时自动迁移旧版文件（旧 L6 根的 `l6-*.log` → `app-log/`、`signal-*.json` → `caiji/caiji-*.json`），幂等。应用私有回落目录同构：`Android/data/com.l6.carmedia/files/L6/`。

## 构建

无 Gradle，aapt2 / d8 / apksigner 手工链（**Git Bash 必用**）：

```bash
# 依赖：JDK 17+、Android SDK（build-tools;35.0.0 + platforms;android-35）
# 签名：release keystore 在 C:/Users/yanzi/.android-keystore/l6-carmedia-release.jks
#       （alias l6release，口令在 android/keystore.properties）
bash android/build.sh                    # → dist/Z6CC-<ver>.apk
python android/tools/apkcheck2019.py     # 产物反查（dex/assets 断言）
```

版本号 = `major*10000 + minor*100 + patch`，须与 git tag 一致；`build.sh` 的 `VER_NAME` 是唯一版本源。

## 发版

```bash
git tag vX.Y.Z            # 必须纯 x.y.z，禁后缀（否则 OTA 解析 versionCode=0，永远「已是最新」）
git push gitzx vX.Y.Z     # Gitea（可选，Runner 构建/分发站）
# GitHub Release：draft=true → 传 APK → PATCH draft:false（资产上传直连 uploads.github.com，须重试）
```

判成功只看三条：`/releases/latest` 是目标 tag、非 draft/非 prerelease、资产与本地 sha256 逐字节一致（**下载回比**，勿信 `digest` 字段——GitHub 该字段语义不一致）。

## 版本历史

| 版本 | 主要内容 |
|---|---|
| v2.0.27 | **事件上报新增电压与当前版本字段**：`L6Log.batteryVoltageV()` 读 `BATTERY_CHANGED` 的 `EXTRA_VOLTAGE`（mV→V 保留两位，取不到返回 `-1`）；`L6Reporter.baseEvent()` 增加 `voltage` + `currentVersion`（= 版本名），**原 `ver` 保留不动**（只增不改，老服务端解析不受影响），覆盖崩溃自动上报 / 启动兜底补发 / 手动云端 / 手动内网四类事件。⚠️ 车机实采电池读数全 0，该字段可能恒为 `-1`，需服务端容忍；主页「电瓶」卡的 `veh.batt` 目前只有 demo 下发，非实时源。同时**保留**工作树里 v2.0.27 的 TLS 1.3 双路径（`postByConnection` / `postByTls13Socket` / `tls13Factory`）——本机 JDK 实测 443 前置按 ClientHello 指纹拒绝 Java 类客户端，该路径对车机 Conscrypt 未必有效，但全量 try-catch 不影响其它链路。`apkcheck2027` 新增字段与 TLS 路径断言；token 独立 hex 常量保持不变。双平台下载回比逐字节一致 |
| v2.0.26 | **真正落实 token 统一（修复 v2.0.23~25 假绿根因）**：会话记录的 `sensitiveProtection` 把你真实输入的 64 位 token（plaintext，sha256 `11075c11…`）在显示层替换成另一个值（ciphertext，sha256 `5a680a78…`）；v2.0.23 按显示值写入 `TOKEN` ⇒ 客户端与按真实值配置的服务端永远不一致，v2.0.25 备用通道因此报 `401 token 不匹配`。本版从原始 jsonl 的 `plaintext` 零手敲提取真实值写回并即时 hex 复核，`apkcheck2026` 两处独立 `bytes.fromhex(hex)` 断言同步更新（不派生自源码）；发布前三方裁决：源码 hex == dex 唯一长串 hex == 用户真实输入 hex。地址 / 载荷 / 上报链路全部不变。双平台下载回比逐字节一致 |
| v2.0.25 | **备用内网地址修正**：`ENDPOINT_LAN` 由 `http://192.168.24.10/api/l6/event` 改为 `http://192.168.24.10:30507/api/l6/event`。旧地址走 80 端口是 fnOS nginx，302 → `:5666` 飞牛 Web 管理页，`HttpURLConnection` 自动跟随使 POST 落到 HTML 404，App 误报「接口不存在（段名 / 路径错误）」——实为地址指错而非段名错；30507 实测是真实 API（用旧 token 探测返回 `200`，用统一 token 返回 `401 TOKEN_INVALID`，说明服务端仍配旧 token，需服务端同步）。`TOKEN` 保持用户指定统一值不变，云端 `ENDPOINT`、崩溃自动上报与启动兜底补发不受影响；`apkcheck2025` 增加新地址正向断言 + 旧地址负向断言。双平台下载回比逐字节一致 |
| v2.0.24 | **新增备用（内网）发送通道**：设置页「日志上报」行增加「📤 备用发送（内网）」按钮，目标 `http://192.168.24.10/api/l6/event`；与云端通道**仅地址不同**，鉴权头 `token: <共享token>` 与 JSON 载荷完全一致。`L6Reporter` 抽出 `sendEventTo(endpoint, ev)` / `sendLogTextTo(endpoint, …)`，崩溃自动上报与启动兜底补发仍走公网不变；两个按钮各自独立回显 HTTP 返回码与 `-1` 异常详情。明文 HTTP 由 manifest 既有 `usesCleartextTraffic="true"` 放行。云端按钮保留不删（不误删功能）。双平台下载回比逐字节一致 |
| v2.0.23 | **真正落实 token 统一（修复 v2.0.21/22 假绿）**：hex 裁决证实前两版 dex 内仍是旧 token；本版从会话原始记录提取用户指定值写入 `L6Reporter.TOKEN`，并以 `bytes.fromhex(hex)` 常量固化到 `apkcheck2023` 断言（独立于源码，避免「源码错、断言跟着错」）；`ENDPOINT` 经逐字符比对与用户新服务器地址一致，未改。双平台下载回比逐字节一致 |
| v2.0.22 | **token 统一 + 重建修正（修复 v2.0.21 假绿）**：`L6Reporter.TOKEN` 统一为 `842989j15f5s15oo5ro…`（端侧共享 token，已与需求方确认）；v2.0.21 因「先构建后改源码」时序踩坑，发出去的包仍嵌旧 token `366577b64b2b76ac…`（apkcheck 旧断言 `366577…` 误判通过），本版干净重建烤入正确 token，apkcheck2022 断言同步修正为 `842989j15f5s15oo`；其余同 v2.0.21（手动发送 -1 异常详情细分）。双平台下载回比逐字节一致 |
| v2.0.20 | **手动发送 -1 异常详情细分**：`L6Reporter.sendEvent` 的 catch 捕获异常类名+信息写入 `ThreadLocal`（`getLastError()` 读）；`codeMessage(int,String)` 新重载，当 `code==-1` 且带 detail 时回显「网络异常（`<类名>: <信息>`）」，一眼区分 `UnknownHostException`(DNS) / `SocketTimeoutException`(超时/被拦) / `SSLHandshakeException`(TLS 时钟/证书/代理拦截)；`ResultCallback.onResult(int,String)` 加 detail 参数，`sendLogText` 透传到主线程回调，`onSendRunLog` 用两参 lambda 调 `codeMessage(code, detail)`。仍走 HTTPS、无新依赖、不破静默策略 |
| v2.0.19 | **手动发送增加 HTTP 返回码反馈**：`L6Reporter.sendEvent` 由返回 boolean 改为返回 HTTP 状态码（网络/解析异常统一 -1），新增 `codeMessage(code)` 翻译可读说明；`sendLogText` 增加 `ResultCallback`，后台拿到码后回主线程；`onSendRunLog` 先弹「发送中」，再弹「✅ 发送成功（HTTP 200）」或「❌ 发送失败：HTTP 404 — 接口不存在…」，让 404/400/401 等现形，杜绝假绿 |
| v2.0.18 | **日志发送端点修正（l6zz → l6）**：v2.0.17 已实现 L6Reporter（崩溃自动上报 + 启动兜底补发 + 设置页「📤 发送运行日志到云端」），但客户端 ENDPOINT 误写 `/api/l6zz/event`，而服务端注册段名为 `l6`（token 一致）→ 每次上报 404 被静默丢弃（假绿）。本版改回 `/api/l6/event`；服务端 webyanzi 同步为 `/event` 新增 l6 宽松分支（kind∈{crash,log} 落 `l6_events` 表），与本端 payload（kind+ver+os+model+time+msg）对齐。双平台下载回比逐字节一致 |
| v2.0.16 | **崩溃守护 + 前台通知加固**：新增 CrashGuard（任何线程崩溃先落盘 `app-log/l6-crash-*.txt` 再交回系统，终结盲猜）；CapService 的 `startForeground` 改挂「最小通知」（无 action/PendingIntent，排除定制 ROM 判 Bad notification 杀进程的炸点），挂稳后再 notify 完整版；SignalCapture 补口（finish 纳入 try-catch、arm 包外层 catch） |
| v2.0.17 | **日志发送功能**：新增 `L6Reporter`，把崩溃 / 运行日志 POST 到 `https://yanzi-api.ziruxue.top/api/l6zz/event`（自定义请求头 `token:` 鉴权，复用 HttpURLConnection 无新依赖，全量 try-catch 不抛）。三种触发：**① 崩溃自动上报**（CrashGuard 落盘后 `sendCrash`）、**② 启动兜底补发**（`MainActivity` 启动扫残留 `l6-crash-*.txt` 上报并删）、**③ 设置页「📤 发送运行日志到云端」手动按钮**（`onSendRunLog` → `sendLogText`）。发送内容：`kind`(crash/log) + `ver` + `os` + `model` + `time` + `msg` |
| v2.0.15 | **P0 采集闪退修复**：API 28 起 `startForeground` 必需 manifest 声明 `FOREGROUND_SERVICE`，v2.0.13/14 漏声明 → 点「开始采集」抛异常带崩整个进程（闪退回桌面）；补权限 + `startForeground` try-catch 兜底（只损失保活，绝不带崩进程） |
| v2.0.14 | **壁纸手动放置修复**：`SetPane.refreshWall()` 入口重新扫目录（原只在 App 启动扫一次）——手动拷进 `Download/L6/壁纸/{静态,动态}` 的壁纸进设置页 / 切标签即出现，无需重启 App |
| v2.0.13 | **后台采集保活**：新增 CapService 前台服务 + 常驻通知（进度 / 一键结束 / 点击回主页）——采集可切后台、熄屏、用导航，划多任务卡片与 ROM 内存压力不再杀采集；采集引导文案同步改「可切后台」 |
| v2.0.12 | 接口探测 v2：Car API 实证死路后转攻 chinatsp 私有通道——service list 保留 AIDL 描述符 / `ServiceManager` 反射取描述符 / `getprop` 全量属性 dump / bindProbe 绑定关键服务 / 组件 exported 旗标 + 关键包 sourceDir 定点逆向；采集引导改「操作清单」 |
| v2.0.11 | 接口探测 IfaceProbe：采集时自动探 service list / Car API 深读 / 已装包组件 / 广播嗅探 / 传感器 / 电池 / settings，落 `caiji-*.json` 的 `iface` 段 |
| v2.0.10 | **P0 修复**刷新路径半成品视图树连崩（原子化替换 + 入口线程兜底 + 静默 catch 留日志） |
| v2.0.9 | 12V 电瓶低压红字告警；采集快照 changes1/changes2 拆分；**P0 修复采集后台线程回推崩溃**；存储目录重构（app-log/caiji）；「🗑 清空采集日志」按钮 |
| v2.0.8 | 更新弹窗按钮横向一行；更新正文去 sha256 行；半发版门禁 |
| v2.0.7 | 半发版状态（Release 已建资产未传）不再误报可更新（**从未发布**） |
| v2.0.6 | 更新提示框改卡片式弹窗 |
| v2.0.5 | 设置页手势分区不再挂任何提示文字 |
| v2.0.4 | 「发现新版本」可取消提示框（跳过此版本） |
| v2.0.3 | 设置页「🗑 清空采集日志」（删采集产物、运行日志保留、采集中禁用） |
| v2.0.2 | 修悬浮返回按钮在手机高密度屏点了没反应（点按阈值改 `TapJudge` dp 判定） |
| v2.0.1 | 主页删「导航」栏；删桌面小部件整套；dock 三钮只留图标 |
| v2.0.0 | 界面全原生重写（删 WebView + HTML），NatShell 手写 View |
| v1.5.x | WebView 套 H5 阶段（已 archive 至 `android/tools/legacy-web/`） |

## 目录结构

```
android/
├── build.sh                  # 手工构建链（跨平台，JDK/SDK 路径可 env 覆盖）
├── keystore.properties       # 签名配置（口令，勿入库外泄）
├── assets/                   # ota.properties / app.properties（内嵌 APK）
├── src/com/l6/carmedia/      # Java 源码（nat/ 为原生视图层）
└── tools/                    # apkcheck*.py（产物反查）/ legacy-web（已归档）
.gitea/workflows/build.yaml   # Gitea Runner 构建工作流（tag 触发）
Dockerfile.android            # Android SDK 镜像（JDK17 + build-tools;35.0.0）
dist/                         # 构建产物 Z6CC-<ver>.apk
```

## 已知边界

- 车辆信号读取受车机 ROM 权限限制，门/窗等实时状态依赖系统属性暴露程度，不同 ROM 可能有差异
- 导航画面无法嵌入主页区域（高德 `allowEmbedded` 未声明），只能调起外部导航 App
- 升级覆盖安装要求签名一致：**release keystore 必须妥善备份**，丢失则车机必须卸载重装

# 老六中控（Z6-central-control）

长安欧尚 Z6 车机媒体中控 APK。原生 Java 视图层，横屏单屏，替换车机原厂娱乐桌面。

- **包名**：`com.l6.carmedia`
- **当前版本**：v2.0.28（versionCode 20028）
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
| v2.0.37 | **右下角 📋 应用列表右侧新增「应用中心」入口**：打开飞牛分享页 `https://share.fnnas.net/s/9d57d97236a04890bf`（网页 SPA，非 API）。新增 `nat/AppCenter.java` 作为 URL 单一真源，无浏览器时依次降级为「复制链接 → 文本分享 → 贴出完整链接」，全程不谎报成功。按钮为 `Dock.refreshApps()` 固定尾按钮且不设 tag，不参与拖动换位。 |
| v2.0.36 | **修复采集差分噪声（真机实采驱动的代码审查结论）**：依据 `l6-log-20261107-144753.txt`（775 行）+ `caiji-20261107-145817.json`（5 分钟完整采集）定位并修复两条同源缺陷。① manifest 补 `ACCESS_NETWORK_STATE`（normal 权限，声明即授予）——缺它时 `SysProbe.network()` 的 `getActiveNetworkInfo()` 在 API 28 抛 `SecurityException` 被 `group()` 吞掉，`net:*` 整组字段全丢、后半段本机 IPv4 枚举一并跳过。② `SysProbe.group()` 键名由 `probe:groupErr.<System.identityHashCode(r)>` 改为稳定的 `probe:groupErr.<组名>`（13 处调用改具名）——旧写法每次 `snapshot()` 新建匿名 Runnable ⇒ key 每轮都不同 ⇒ `SigDiff` 稳定产出 `ADDED` + `REMOVED` 两条假变化，实测占运行日志 **39.4%**（305/775 行、154 个独立 key）、占 `changes1` **37.2%**（502/1349 条），且 `ignoreKeys()` 未覆盖 ⇒ 全部混进「保留」组淹没车辆信号（真信号仅 7 种 / 80 条）。③ `SignalCapture` 新增 `isNoise()` 前缀兜底（挡未来新增的同类伪键）+ `ignoreKeys()` 补 7 个每秒自变噪声键（磁盘 / 内存 / `bootMs` / LTE 信号强度，合计约 48%），**刻意保留 `prop:sys.tbox.uptime` 与 `persist.sys.door_state`** 等已实证可读的车机信号，不按 src 前缀一刀切。④ `dump()` 给 `changes1`/`changes2` 每条加 `fullKey`（`src:key` 全名），与 `ignoreKeys` 同一命名空间，外部复核过滤覆盖率不必自己拼回。`apkcheck2036` 新增权限（解压 AXML 断言）、稳定键 / `isNoise` / `fullKey` / 7 个噪声键 POS 与 `identityHashCode` NEG 零残留。⚠️ 本次 `MAX_CHANGES=5000` 未触发（1349 条），无数据截断；两份产物同时实证 v2.0.35 的 OTA 升级（下载 447862 B 与发布产物一致）、原生界面、**150 轮 / 183 秒采集无闪退**（v2.0.32 `public CapService()` P0 修复生效）、5 分钟自动收尾，全程无崩溃堆栈 |
| v2.0.35 | **日志区只留 NAS 收集按钮，App 主动网络上报能力整体下线**：设置页移除「📤 发送运行日志到云端」「📤 备用发送（内网）」「📤 发日志到 1443」三个按钮及其回调、`LOG_ENDPOINT_1443` 常量、说明文字；`L6Reporter` 裁掉 `ENDPOINT` / `ENDPOINT_LAN` / `TOKEN` / TLS 协商 / `sendEvent*` / `sendLogText*` / `sendCrash` / `flushPendingCrash` / 字段可读化，仅保留 `NAS_COLLECT_URL` + `writeNasUploadFile()` + `nasUploadDirs()` + `lastNasError()` 本地导出。`CrashGuard` 崩溃堆栈**仍落盘**（写进运行日志留证）但不再外发，`MainActivity` 去掉启动兜底补发。⚠️ 边界：`INTERNET` / `usesCleartextTraffic` 与 OTA、采集、壁纸等全部保留（OTA 在线更新仍需联网），App 自身只是不再 POST 日志。`apkcheck2035` 把被删功能的端点 / token / 方法名 / 按钮文案全部改判 **NEG 零残留**，并剔除 `token`/`HTTP `/`voltage`/`currentVersion` 等会被别的模块碰巧命中的假绿断言；产物级硬证据：dex 中 ≥48 位长串为 **0**（旧共享 token sha8 `11075c11` 二进制复核零残留）、`javap` 确认 `CapService()` 仍为 `public`、275 项反查 0 失败 |
| v2.0.34 | **自建端点地址纠正**：`LOG_ENDPOINT_1443` 由 `http://172.17.117.232:1443` 改为用户指定的公网 ECS1 `http://60.205.251.18:1443`，设置页说明文案同步；按钮名、token 头、JSON 载荷、字段可读化、其余四条通道与 OTA 全部不动。⚠️ **本机只读探测（不假绿）**：新地址 TCP 可达，但纯 IP 的 GET/POST 到 `/` 与 `/api/l6/event` 一律返回 **frp 489B 404 HTML**（`The server is powered by frp`）；带 `Host: *.ziruxue.top` 则被 **阿里云备案拦截 403**（`Non-compliance ICP Filing`，`Server: Beaver`）⇒ 1443 是 frp 的 vhost HTTP 端口、靠域名路由，车机用纯 IP 直连拿不到业务后端，按钮会如实回显 `HTTP 404`。需服务端把 frp 改成 tcp 模式转发、或改用已备案域名后方可生效。`apkcheck2034` 增加新地址 POS 全文断言 + **旧地址 NEG 零残留断言**（防 sed 派生漏改 / 防发错端点）；token 裁决与防假绿 NEG 不变。双平台下载回比逐字节一致 |
| v2.0.33 | **上报字段可见化（端侧零契约改动）**：只读核对 `yanzi` 服务端确认「字段没丢、是后台不展示」——`ingest.js:458` 把整个 body 存进 `crash_logs.payload_json`，但 `admin.js:257` 列表 SQL 只 `SELECT id,kind,ts,ver,os,model,thread,title,msg` **不返回 `payload_json`**，前端 `src/app.js:295` 详情弹窗也只渲染 时间/类型/版本/系统/机型/标题/线程 + `msg`，没有 `voltage`/`currentVersion` 两行 ⇒ 后台永远看不到新增字段（总览里「设备 0 / 今日上报 0」也是服务端刻意设计：`handleL6Report` 只 `touchApp`、不 `bumpDaily`）。端侧新增 `attachReadableFields()`：崩溃自动上报 / 启动兜底补发 / 手动云端 / 手动内网四类事件的 `msg` 头部加「【上报字段】」完整清单（`kind/title/thread/ver/currentVersion/voltage/os/model/time`，time 格式化、voltage 带 V），`title` 加精简摘要 `ver/currentVersion/voltage/os/model`，后台列表「标题 / 线程」列与详情「标题」行直接可见；**JSON 结构化字段原样保留**，服务端与接口契约一行未动，写字段失败自动退回原始 title/msg 不影响发送。`apkcheck2033` 增加新锚点，token 裁决与防假绿 NEG 不变。双平台下载回比逐字节一致 |
| v2.0.32 | **找到「点开始采集必崩」真根因 + 应用列表首字母排序 + 自建端点发日志**：① `javap` 实证 `private com.l6.carmedia.CapService();` —— Android 框架用 `Class.newInstance()` 实例化 Service，私有构造器必然抛 `IllegalAccessException` ⇒ `ActivityThread.handleCreateService` ⇒ AMS 直接杀进程；该异常在异步 binder 回调里，app 侧 `CapService.start()` 与 `onCreate()` 的全部 try-catch 都拦不住，这正是 v2.0.31 一众防线无效的原因（`git log -S` 追溯该构造器自 v2.0.13 引入即存在）。本版改为 `public CapService()`，并在设置页点击回调加 UI 线程 try-catch 兜底 + 错误 toast。② 右下角 📋 应用抽屉由图标网格改为**纵向列表**（真实图标 + 应用名 + 包名 + 已钉角标），新增纯逻辑类 `nat/AppSorter`：中文按拼音首字母（`Collator(Locale.CHINA)` + 23 个边界字二分，零第三方依赖）、英文 A–Z、其余归 `#`，列表按组插入标题行；长按钉入 / 单击拉起 / 选择模式回调全部保留。③ 日志区新增第 4 个按钮「📤 发日志到 1443」→ `http://172.17.117.232:1443`，复用现有 token 头与 JSON 事件载荷（`sendLogTextTo` 仅换地址），独立回显 HTTP 码或 `-1` 异常详情；本机探测该地址 4 次全超时（不在本机网段），故绝不假绿。公网 / 内网两条通道、TOKEN、载荷、崩溃与补发链路、OTA 全部不动。`apkcheck2032` 全量 0 失败 + `javap` 构造器 public 硬验证 + 双平台下载回比 sha256+size 逐字节一致 |
| v2.0.31 | **修复「每次点击开始采集就闪退」+ 根治日志全线不可写**：只读诊断发现两个症状同源——`L6Log.logFile()` 只 `new File` 不校验可写性、`publicBase()` 恒非 null ⇒ 私有回落永不触发；车机公共 Download 不可写时，运行日志 / `CrashGuard` 崩溃堆栈 / 采集产物 / v2.0.30 的 NAS 待上传文件全线丢失，且 `CrashGuard.writeCrash()` 在目录失败时 `return ""` ⇒ 闪退了却拿不到任何证据、云端也收不到。本版：① `L6Log` 新增 `writableBase()`（真实写探针 `.wtest`，公共 → 外部私有 → 内部私有 → cache，进程内缓存一次）与统一 `logDir()`，`logFile()` / `artifactDir()` / `crashDir()` 全部改走它；② `CrashGuard` 崩溃文本**无条件生成**再多级落盘，全失败仍走 `sendCrash` 云端上报；③ 采集线程 Runnable 加最外层 try-catch + 强制状态复位（子线程未捕获异常会被 Android 直接杀进程）；④ `CapService.onCreate/onStartCommand` 全量 try-catch、通知 action 图标由 `0` 换真实 smallIcon（避开定制 ROM 判 Bad notification）。公网 / 内网上报、TOKEN、OTA、UI 布局一行未动。`apkcheck2031` 增加可写基址 / 崩溃证据链 / 进程级防线锚点。双平台下载回比逐字节一致 |
| v2.0.30 | **修复「上传 NAS 失败：无法写入日志文件」**：v2.0.29 的 `nasUploadDir()` 只认公共 `Download/L6/upload`，`mkdirs()` 失败即返回 null；而 `L6Log.logFile()` 只 `new File` 不校验可写性，`publicBase()` 恒非 null ⇒ 私有回落**永远不会触发**。车机上 `WRITE_EXTERNAL_STORAGE` 是危险权限、公共 Download 常只读，于是按钮必现报错。本版 `writeNasUploadFile()` 改为 `nasUploadDirs()` 四级候选（公共 `Download/L6/upload` → 应用外部私有 `externalFiles/L6/upload` → 内部私有 `files/upload` → `cache/upload`），逐级真实 `FileOutputStream` 试写并校验 `f.length()==data.length`，任一成功即返回；`lastNasError()` 逐候选记录「路径 + 异常类名 + message」，失败 toast 直接显示真实原因，成功 toast 补**绝对路径**（落到内部私有目录时如实告知可见性限制）。`L6Log` 仅新增只读 `appContext()` getter，日志 / 上报 / OTA 逻辑一行未动；公网 / 内网两条 JSON 按钮与 TOKEN 不变。仍**不谎报「已上传到 NAS」**——写盘成功只代表生成待上传文件，`apkcheck2030` 增加多级回落与防假绿断言。双平台下载回比逐字节一致 |
| v2.0.29 | **新增「📤 上传到 NAS」按钮**：设置页日志上报区新增第三个按钮，目标 `https://share.fnnas.net/s/988aed0f9fb54bc680`（飞牛「l6event收集任务」）。实测该链接是 851B 的 HTML SPA 而非 API，直接 POST JSON 只会拿到 HTML 200 假绿；真实上传走 `/s/<id>/api/v1/upload/{precheck,file,status}` + fnOS 私有 `authx` 签名（MD5 + 前端内嵌常量，已用浏览器真实抓包 sign 反算验证）+ 密码/会话 + `/api/v1/fn/share` 动态中继（`ipv4=192.168.24.10`、`publicIpv4=null`、`fn=s28.fnnas.net:5667`，本机实测 502），端侧硬编码会随 fnOS 升级失效且无法真机验证 ⇒ **不复刻私有协议**。本版改为 `L6Reporter.writeNasUploadFile()` 把运行日志（含版本/时间/电压/型号）写成 `Download/L6/upload/l6-log-*.txt`，再 `ACTION_VIEW` 调起官方收集页；车机无浏览器时降级 `ACTION_SEND(EXTRA_TEXT)` 并给出绝对路径。全程**不谎报「上传成功」**，`apkcheck2029` 增加防假绿负向断言。公网 / 内网两条 JSON 按钮与上报链路一行未动。双平台下载回比逐字节一致 |
| v2.0.28 | **删除 TLS 1.3 死代码，改零依赖运行时探测**：根因收敛为「公网 443 只接受 TLS 1.3 （强制 1.2 被 RST、1.3 返 200），车机 API 28 默认只发 1.2」。v2.0.27 的 `SSLContext.getInstance("TLSv1.3")` 在 API 28 官方文档标注 29+ / 真机实测抛 `NoSuchAlgorithmException` 且被 catch 吞掉 ⇒ 两条路径一次都没运行过。本版删除 `postByTls13Socket` / `tls13Factory`（同时消除裸 socket 的 fd 泄漏），改用 `getInstance("TLS")` + `pickProtocols()` 探测 `getSupportedProtocols()` + 自定义 `SSLSocketFactory` 显式 `setEnabledProtocols` 尝试协商 1.3；ROM 不支持则静默回落 1.2，零第三方依赖、全量 try-catch、不改内网通道与按钮语义。`sTlsDiag` 随 `-1` 回显 `tls13Supported=...`，车机点一次即可取证；`sendEventTo` 入口清 `sLastErr` 修诊断残留。⚠️ 不保证修好外网（取决于 MTK ROM 内置 Conscrypt）；若 `tls13Supported=false`，只剩服务端 `ssl_protocols TLSv1.2 TLSv1.3;` 或打包 Conscrypt AAR 两条路。`apkcheck2028` 按 `deb86cc` 走 env 化 token 断言。双平台下载回比逐字节一致 |\n| v2.0.27 | **事件上报新增电压与当前版本字段**：`L6Log.batteryVoltageV()` 读 `BATTERY_CHANGED` 的 `EXTRA_VOLTAGE`（mV→V 保留两位，取不到返回 `-1`）；`L6Reporter.baseEvent()` 增加 `voltage` + `currentVersion`（= 版本名），**原 `ver` 保留不动**（只增不改，老服务端解析不受影响），覆盖崩溃自动上报 / 启动兜底补发 / 手动云端 / 手动内网四类事件。⚠️ 车机实采电池读数全 0，该字段可能恒为 `-1`，需服务端容忍；主页「电瓶」卡的 `veh.batt` 目前只有 demo 下发，非实时源。同时**保留**工作树里 v2.0.27 的 TLS 1.3 双路径（`postByConnection` / `postByTls13Socket` / `tls13Factory`）——本机 JDK 实测 443 前置按 ClientHello 指纹拒绝 Java 类客户端，该路径对车机 Conscrypt 未必有效，但全量 try-catch 不影响其它链路。`apkcheck2027` 新增字段与 TLS 路径断言；token 独立 hex 常量保持不变。双平台下载回比逐字节一致 |
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

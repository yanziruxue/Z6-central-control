# 需求对齐画布：车机 ↔ Linux 媒体互联系统

> 状态：**已实现对齐版 v1.0**（2026-10-24 重写）｜ 用途：自己用（非商业）
> 前身为原型阶段 v0.2 草稿（Linux 服务端 + OBD/CAN + WebView 设想），该设想多数已废弃，演进记录见 §10。

## 1. 产品概念（现状）

- **一句话**：老六中控是跑在长安欧尚 Z6 车机上的**原生媒体中控 APK**，替换原厂娱乐桌面——车况一屏可见、音乐一屏可控、导航手势调起。
- **解决的问题**：原车机界面信息分散、操作层级深；本应用把「车况 + 音乐 + 导航入口」收进一个横屏单屏，上滑/下滑手势直达常用场景。
- **用户**：个人自用，非商业分发。
- **载体现实**：**没有独立的 Linux 媒体大脑设备**。早期设想的「Linux 服务端 + OBD/CAN 硬件」方案未落地（原因见 §10），当前形态 = APK 单体应用，直接读取车机系统层可达数据。

## 2. 已对齐的关键结论（现行有效）

| 维度 | 结论 |
|---|---|
| 车型 | **长安欧尚 Z6 · 燃油版**（OnStyle 智慧座舱，底层 Android 9，横屏 1920×720，8155 级芯片） |
| 形态 | 单个车机 APK（`com.l6.carmedia`），无外部服务端依赖 |
| UI 形态 | 横屏单屏：顶部状态/网格 + 中部上下滑动双页（车辆状态 / 音乐）+ 底部 Dock |
| 技术栈 | **全原生 Java 视图层**（NatShell 手写 View），v2.0.0 起删除 WebView/HTML |
| 车况数据源 | 车机系统属性（`persist.sys.*`）+ `BatteryManager` + 车辆 `veh` 事件，`natMergeVeh()` 合并 |
| 导航 | 调起车机已装导航 App（上滑开/下滑收）；回应用后顶部显示**迷你导航卡片**（高德广播 `AUTONAVI_STANDARD_BROADCAST_SEND` 取数，通知源兜底） |
| 「电量」字段 | 燃油版无动力电池 → **12V 小电瓶电压**（`BatteryManager.EXTRA_VOLTAGE`），低于 12V 红字告警 |
| OTA | GitHub Releases 主源 + Gitea 分发站 + `gh-proxy.com` 镜像回退 |

## 2.1 界面布局（当前实现）

横屏单屏，自上而下三段式 + 中部上下滑动双页：

- **顶部**：主页网格常驻行 +  Dock 栏（🏠 主页 / 🧭 导航 / ⚙ 设置，仅图标；长按拖动排序，拖垃圾桶取消钉住）。
- **中部主区域（上下滑动切换两页）**：
  - **页 0 · 车辆状态**：车模型 + 「动力·服务端」网格（车速 / 油表 / **电瓶(12V)** / 里程 / 负载 / 温度 / 延时 / 胎压 / 车身状态）。电瓶值 `< 12V` → 红字 + 红边背景。
  - **页 1 · 音乐**：正在播放 / 频谱 / 控制 / 播放列表；数据来自车机媒体广播。
- **手势**：上滑 = 回车机原生桌面（与 dock 🏠 同一条 `goHome`）；下滑 = 调起导航 App；手势分区可自定义绑定（v2.0.5 起分区不挂任何提示文字）。
- **悬浮返回**：导航 App 前台时，屏幕最左侧悬浮按钮（`TapJudge` 12dp/600ms 点按判定，修复手机高密度屏下 8px 阈值 = 3dp 点不动的问题）；点按 → 导航 App 转后台、本应用回前台。
- **设置页**：壁纸（静态/动态）、手势绑定、权限、**信号采集**、OTA、日志导出/清空。

## 2.2 车型与硬件约束（实测校准）

| 项 | 实测结论 / 影响 |
|----|------------|
| 车辆数据 API | **长安欧尚未开放第三方 APK 车辆信号 API**：Car API `carPowerPermission=denied`、`vehicleSignal=denied`（实车日志实测）→ 车速/门/窗等走系统属性暴露面，见 §3 |
| 屏幕 | 横屏 1920×720（2.667:1）；应用锁横屏（`resizeableActivity=false` + `maxAspectRatio=2.8`） |
| 性能 | 8155 级芯片；原生渲染无 WebView 温控担忧（v2.0.0 起重写为原生即为此） |
| 导航嵌入 | 高德不声明 `allowEmbedded`、本车机无高德小部件 → **导航画面进主页区域技术封死**，只能调起外部 App |
| 12V 电瓶 | 燃油版真实值可取（`BatteryManager`），无需 OBD；油量字段未做实时传感器读取 |

## 3. 数据来源（阻断项①的真相）

早期画布的阻断项①「车辆状态数据来源」结论已刷新——**不需要 OBD/CAN 硬件**：

| 信号 | 实际来源 | 说明 |
|---|---|---|
| 12V 电瓶电压 | `BatteryManager.EXTRA_VOLTAGE` | mV→V；低于 12V 告警 |
| 门/窗等车身状态 | `persist.sys.door_state` 等系统属性 | ROM 暴露面决定可见性，不同车机可能有差异 |
| 车速/转速/油量/里程 | 系统属性 + 车辆 `veh` 事件 | `natMergeVeh()` 统一合并出口 |
| 导航（转向/ETA/剩余里程） | 高德广播 `AUTONAVI_STANDARD_BROADCAST_SEND` | 动态注册接收（`NavBcastData`）；通知源退兜底 |
| 音乐/歌词 | 车机媒体广播（`SysHub` 事件） | 只以事件形式存在，自建 `setRaw` 出口 |

> 结论：普通 APK 的车况能力上限 = **这台 ROM 愿意暴露什么**。采集功能（§4）就是为摸清暴露面而生——点「开始采集」操作车辆功能，每秒快照比对，结束落结构化 JSON。

### 3.1 接口探测 IfaceProbe（v2.0.11 → v2.0.12）

采集启动时自动跑一遍**全量接口探测**（`IfaceProbe.probe()`，落 `caiji-*.json` 的 `iface` 段），每段独立 try/catch，探测失败不影响采集：

| 探测段 | 内容 |
|---|---|
| `services` | `service list` 全量 binder 服务 + 车辆关键词筛选（**v2.0.12 起保留行尾 AIDL 描述符**，v2.0.11 曾丢弃） |
| `car` | Car API 反射深读：建 Car → getCarManager → **getPropertyList 全量属性枚举**（权威证据）+ 36 个已知信号 ID 逐个 try-read（denied 照记 SecurityException）+ CarInfoManager VIN + `dumpsys car_service` |
| `pkgIfaces` | 已装包 receiver/service/provider 组件名关键词过滤（OEM 私有接口发现）；**v2.0.12 起带 exported/enabled 旗标 + 全量组件 `comps` + 关键包 `keyPkgs`（权限/sharedUserId/sourceDir 供定点逆向）** |
| `bcastHistory` | `dumpsys activity broadcasts` 正则提取真实 action 候选（本机无 DUMP 权限，实测恒空） |
| `bcast` | 被动广播嗅探（arm 后窗口内命中全记录，finish 并回文件；v2.0.12 起带静态候选清单 EXTRA_ACTIONS） |
| `location` / `sensors` / `battery` / `settingsScan` | GPS 测速可行性 / 传感器全清单 / 电池全字段 / settings 三库扫描 |
| `svcDesc`（v2.0.12） | `ServiceManager.getService` 反射 + `getInterfaceDescriptor()` 取关键服务 AIDL 描述符 |
| `propsFull`（v2.0.12） | `getprop` 全量键值 dump——**两份采集 diff 即活信号定位器**（v2.0.11 只存关键词命中的 60 键） |
| `bindProbe`（v2.0.12） | 对 chinatsp 等关键已装服务逐个 `bindService`，连上即取描述符、随即解绑 |

**用户需求全单（2026-10-24 确认，已知信号 ID 已覆盖进探测表）**：仪表盘全部信息 + 门窗状态 + 所有按钮/灯光开关状态 + 电动座椅 + 空调 + 车内外温度 + 胎压 + 里程 + 转速 + 油位 + 油温 + 瞬时油耗。标准 VHAL 无「瞬时油耗」属性（最接近 `FUEL_CONSUMPTION_SINCE_RESTART`）、电动座椅位置多为 OEM 私有扩展（`CAR_VENDOR_EXTENSION` 权限，本车已 denied）——以采集实测为准。

**首份车机实采结论（v2.0.11，2026-10-24，caiji-20261024-182537.json）**：

| 通道 | 实测结论 |
|---|---|
| Car API（`android.car.Car`） | **死路**：类存在但 POWERTRAIN/VENDOR_EXT/CABIN/INFO 四权限全 denied，`createCar` 反射 NoSuchMethodException（ROM  stub） |
| `chinatsp.vehicle.network` binder | **疑主通道**：长安 TSP 车联网服务在册；消费方 `com.chinatsp.vehiclenetwork`（VehicleNetworkService + VehicleReceiver） |
| `coagent.tbox` binder | TBox 远控 co-agent，在册，待探描述符 |
| `com.banyac.midrive.app` | 胎压管理 `com.banyac.tirepressure.manager.DfuService`——**胎压疑经此 App**（可能是 BT 胎压dongle，待证） |
| `com.egogame.yudanbox` | `CarPropertyMonitorService` 车况监控，第三方消费方 |
| `persist.sys.door_state` | **✅ 已实证可读**：采集窗口内 0→1→0 真实跳变（门状态） |
| 其它系统属性 | 29s 窗口内仅 `sys.tbox.uptime` / `vendor.ril.nw.signalstrength.lte.1` / `vendor.streamout.media.volume` 跳变 |
| 传感器 | 仅 14 个 MTK 标准手机传感器，无车辆传感器 |
| 定位 / 电池 / settings | 权限未授 / 常电 head unit 全 0 / 无车辆键位 |

⇒ v2.0.12 转攻 chinatsp 私有通道（描述符 + 绑定 + 定点逆向），并按「操作清单」引导二次采集（行驶中才有的 rpm/车速/油耗必须上路采）。

## 4. 功能模块拆分（现行代码地图）

- **`nat/` 原生视图层**
  - `NatShell`：应用外壳（overlay 必须先于 dock 构建，否则静默回落）
  - `HomePane`：主页（车辆状态 + 音乐双栏；电瓶卡 `numCardLow` 低电压告警）
  - `SetPane`：设置页（壁纸 / 手势 / 采集 / OTA / 日志）
  - `Dock`：三钮 dock（长按拖动排序 + 垃圾桶取消钉住）
  - `U` / `Prefs`：UI 令牌 + 整份 JSON 偏好存储
- **采集链路**：`SysProbe`（全量快照 + 能力探测）→ `SigDiff`（每秒差异，词边界匹配）→ `SignalCapture`（调度/落盘，changes1 保留 / changes2 过滤）→ **`IfaceProbe`（接口探测，v2.0.12：Car API 深读 / 服务清单+描述符 / 组件发现 / propsFull 全量属性 / bindProbe 绑定探测 / 广播嗅探）**
- **OTA 链路**：`Ota`（源选择：GitHub 主 → Gitea 保底 → 镜像回退；`isUsable()` 门禁拒半发版）
- **日志链路**：`L6Log`（目录管理 + 启动迁移）+ `L6Signal` 标签采集日志
- **纯逻辑类**（零依赖、进 `LogicSmoke`）：`NavParse` / `LrcParse` / `SigDiff` / `NavBcastFmt` / `NavBcastData` / `TapJudge`

## 5. 数据流（现行）

```
[车机 ROM]  系统属性 / BatteryManager / 媒体广播 / 高德广播
   │  SysProbe 快照 · SysHub 事件 · NavBcastData
   ▼
[natMergeVeh / setRaw 合并出口]  ──每秒──▶  [HomePane 视图刷新]
   │                                        （回推一律 ui.post 主线程投递，
   │                                          否则 CalledFromWrongThread 杀进程）
   ▼
[L6Log]  app-log/l6-*.log    [SignalCapture] caiji/caiji-*.json
   │                                        （changes1+changes2=全部变化）
   ▼
[Ota]  GitHub releases/latest ──gh-proxy 回退──▶ 下载/校验/安装
```

## 6. 职责边界（现行）

- **APK（单体）**：采集 + 渲染 + 轻交互 + OTA 自更新；不含媒体转码/服务端逻辑。
- **外部 App**：导航引擎 + 地图渲染（高德/百度/系统 chooser 用户自选），本应用只负责调起与状态感知。
- **车机 ROM**：数据暴露面的实际决定者（权限 denied 的接口只能绕系统属性）。

## 7. 技术路线（现行）

- **视图层**：原生 Java 手写 View（`NatShell`），**不用** WebView/Compose/Flutter——车机 density=1、1920×720 固定横屏，手写 View 最可控，且彻底消除 WebView 线程/温控/回落坑。
- **构建**：无 Gradle。aapt2 compile/link → javac `--release 17` → d8 → 合成 → zipalign → apksigner，一条 `bash android/build.sh`（Git Bash；JDK/SDK 路径 env 可覆盖）。
- **校验**：`apkcheck2009.py` 产物反查（dex 字符串池/类名 + assets 断言；负向断言防已删模块回归）。
- **工程铁律**（踩过坑沉淀的）：
  - View 只能主线程改；后台回推必须 `ui.post` + pending 合并
  - 触摸阈值一律 dp→px 换算并抽纯逻辑类（`TapJudge`），禁写死物理像素
  - 子视图越界绘制不保证：翻页轨道高必须 `n*vh`；垃圾桶挂全屏 overlay
  - 拖动排序绝不 `removeView` 被拖 View；长按后必须吃掉随后的 click

## 8. 范围

### 8.1 In-Scope（现行 = 已实现）

- 车辆状态主页（车模 + 网格 + 12V 低压告警）
- 音乐页（播放状态/频谱/列表）
- Dock 三钮 + 手势 + 悬浮返回
- 信号采集 + 采集产物清空
- OTA（GitHub 主源）
- 壁纸（静态/动态）+ 日志导出

### 8.2 Non-Goals（明确不做）

- 不自绘导航底图 / 不内嵌高德 JS API（技术封死 + 决策双重原因）
- 画中画（v1.5.17 已取消，防回归）
- 桌面小部件整套（v2.0.1 已删）
- 媒体内容版权获取/下载
- OBD/CAN 硬件接入、Linux 服务端（见 §10）

## 9. 已确认 / 待确认（现行）

- ✅ **导航交互**：上滑调起外部 App 全屏；悬浮返回按钮回本应用；回后迷你导航卡片
- ✅ **电瓶字段**：12V 电压真实读取 + `<12V` 红字告警（v2.0.9）
- ✅ **采集产物拆分**：changes1（保留）/ changes2（过滤噪声），相加 = 全部
- ⏳ **油量推算**：早期画布的「加油记录表单 → 平均油耗/续航」**未实现**，需求池
- ⏳ **胎压实时值**：网格有字段，取值依赖 ROM 暴露面，待采集摸清后决定显示策略
- ❓ **3D 车模**：用户 13:42 确认暂缓，保留 2D 比例车模

## 10. 架构演进记录（v0.2 设想 → 现行）

| 早期设想（v0.2 画布） | 现行事实 | 转变原因 |
|---|---|---|
| Linux 设备作「媒体大脑」，APK 是瘦客户端 | APK 单体应用 | 个人场景无需额外设备；车机 8155 性能足够 |
| OBD（ELM327）+ 车身 CAN 解析车辆信号 | 系统属性 + BatteryManager 直读 | 实测 Car API denied 后转向 ROM 暴露面；OBD/CAN 硬件成本与车型报文标定成本过高 |
| WebView 套 H5 快速验证 | v2.0.0 起全原生 Java 视图层 | WebView 线程崩溃（CalledFromWrongThread）+ 静默回落网页版难分辨 + 温控，原生最可控 |
| APK 内嵌高德 JS API 自绘导航底图 | 调起外部导航 App + 广播取数 | 高德不声明 `allowEmbedded`，嵌入技术封死 |
| 导航页进主页区域 | 该栏位 v2.0.1 已删 | 同上 |
| 首页「导航」栏 + 桌面小部件 | v2.0.1 全部删除 | 信息架构收敛：车况/音乐双栏足矣 |
| 「电量」字段 | 改 12V 小电瓶电压 | 燃油版无动力电池 |

> 画布方法论保留：需求变更以本节记录「为什么变」，避免反复翻旧账。

# 老六中控（Z6-central-control）

长安欧尚 Z6 车机媒体中控 APK。原生 Java 视图层，横屏单屏，替换车机原厂娱乐桌面。

- **包名**：`com.l6.carmedia`
- **当前版本**：v2.0.9（versionCode 20009）
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
python android/tools/apkcheck2009.py     # 产物反查（dex/assets 断言）
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

# -*- coding: utf-8 -*-
"""v2.0.27 产物反查：事件新增 voltage + currentVersion 字段 —— 断言 dex / assets / manifest 的「该有的在、不该有的没了」。

★ 判据全部落在 dex 的**字符串池与类名**上（局部变量名不进 dex，别拿它断言）。
★ v2.0.2 新增悬浮返回按钮的纯逻辑判定类 TapJudge（手机高密度屏点不动的修复）。
★ v2.0.3 新增「清空采集日志」（删除 signal-*.json）。
★ v2.0.4 新增「发现新版本」可取消提示框（ForceUpdate 双模式 + 跳过此版）。
★ v2.0.5 设置页手势分区不再挂任何提示文字。
★ v2.0.6 更新提示框改为卡片式弹窗（遮罩调浅 + 卡片底）。
★ v2.0.7 半发版状态（Release 已建、资产未传）不再被当成可用更新。
★ v2.0.8 弹窗按钮改横向一行（立即更新/跳过此版/稍后再说）+ 更新正文不再显示 sha256 行。
★ v2.0.9 目录重构（app-log/caiji 子目录 + 采集文件改名 caiji-）+ 采集快照拆 changes1/changes2
           + **P0 修复**：采集后台线程回推刷新 UI 改投递主线程（setRefreshPending 合并风暴）。
★ v2.0.10 P0 修复（2026-10-24 车机连崩两次）：刷新路径原子化
           （SetPane 五处 removeAllViews+addView 改「先建 list 后替换」、HomePane.refresh 同）
           + SetPane.refresh / HomePane.refresh 入口线程兜底 + 静默 catch 加 L6Log.e 留现场。
★ v2.0.11 接口探测 IfaceProbe：采集所有能检测到的接口信息 ——
           service list 全量 + Car API 反射深读（getPropertyList 枚举 + 已知信号 ID try-read
           + CarInfoManager VIN）+ 已装包组件发现 + 历史广播 action 提取 + 被动广播嗅探
           + Location 测速 + SensorManager 清单 + BatteryManager 全字段 + settings list 三库扫描。
           信号覆盖用户需求全单：门窗 / 灯光开关 / 按钮 / 座椅 / 空调 / 车内外温度 / 胎压 /
           里程 / 转速 / 油位 / 油温 / 瞬时油耗。
★ v2.0.12 接口探测 v2（依据 v2.0.11 首份车机实采结论：Car API 四权限全 denied 已死，
           转攻 chinatsp 私有通道）：
           - service list 补留 AIDL 描述符（v2.0.11 把行尾 [xxx.AIDL] 丢了）
           - 新段 svcDesc：ServiceManager 反射 + getInterfaceDescriptor 取关键服务描述符
           - 新段 propsFull：getprop 全量键值 dump（两份采集 diff 定位活信号）
           - 新段 bindProbe：bindService 绑定 chinatsp/tbox/胎压等关键服务拿描述符
           - pkgIfaces 补 exported/enabled 旗标 + 全量组件 comps + 关键包 keyPkgs
             （requestedPermissions / sharedUserId / sourceDir 供定点逆向）
           - 嗅探候选清单 EXTRA_ACTIONS（dumpsys 历史提取恒空，静态候选补位）
           - 采集引导文案改「操作清单」：车门/车窗/空调/灯光/座椅/点火行驶逐项操作。
★ v2.0.13 采集前台保活（回答「支持后台采集吗」：采集线程本就不绑 Activity，
           按 HOME/切导航/熄屏都能活，但无前台优先级会被划卡片/ROM 杀 ⇒ 白采）：
           - 新增 CapService（前台 Service + 常驻通知：进度/一键结束/点击回主页）
           - SignalCapture.start 挂服务、finish 落盘后撤服务、10s 心跳刷通知进度
           - SetPane 引导文案改「可切后台 / 熄屏 / 用导航」
           - 不申请任何新权限；START_NOT_STICKY（进程真死了不空跑重启）。
★ v2.0.14 壁纸手动放置修复（用户在设置页壁纸选择处看不到手动放进目录的壁纸）：
           - 根因：Prefs.scanWallDirs() 只在 App 启动（Prefs 单例构造）时跑一次，
             refreshWall() 只读内存列表 → 启动后手动拷入的壁纸须重启 App 才出现。
           - 修复：SetPane.refreshWall() 入口加 prefs.scanWallDirs()（幂等、去重、只增量补），
             进设置页 / 切静态动态标签即重新扫盘，无需重启。
           - 上传壁纸本就走内存列表，不受影响；本次仅修手动放置这路径。
★ v2.0.15 采集闪退修复（车机实证：点「开始采集」闪退回桌面、后台也没有）：
           - 根因：API 28 起 startForeground 必需 manifest 声明 FOREGROUND_SERVICE，
             v2.0.13/14 漏声明 ⇒ CapService.onStartCommand 里 startForeground 抛异常
             带崩整个进程（服务生命周期异步，start() 外面的 catch 拦不住）。
           - 修复：① manifest 补 <uses-permission FOREGROUND_SERVICE/>（normal 权限）
                   ② CapService.startForeground 包 try-catch（只损失保活，绝不带崩进程）
           - 断言：二进制 manifest 字符串池（UTF-8/UTF-16 两种编码都搜）必须含该权限名。
★ v2.0.16 崩溃守护 + 前台通知加固（v2.0.15 装车仍闪退，车机实际版本号停在 v2.0.13 ⇒
           修复未装上；本轮双管齐下把「点采集即崩」的两条路径全堵死 + 终结盲猜）：
           - 新增 CrashGuard：全局 UncaughtExceptionHandler，任何线程崩溃先落盘
             app-log/l6-crash-<ts>.txt（堆栈+线程名+版本）再交回系统——下次闪退拷这一个文件定位
           - CapService 加固：startForeground 先挂「最小通知」（仅 icon+标题+渠道，
             无 addAction/contentIntent）——system_server 判 Bad notification 杀进程的
             炸点排除在 startForeground 之外；挂稳后再 notify 完整版（失败只损失按钮/进度）
           - SignalCapture 补口：finish() 纳入 try-catch 保护（原裸调在主 try 外）；
             IfaceProbe.arm 包外层 catch（双保险）
★ v2.0.18 日志发送功能端点修正（v2.0.17 实现但段名写错 l6zz → 服务端 404 假绿）：
           - L6Reporter.ENDPOINT 由 /api/l6zz/event 改回 /api/l6/event（服务端已注册 seg=l6 且 token 一致）；
             服务端 handleEvent 新增 l6 宽松分支（kind∈{crash,log} 落 l6_events），与本端 payload 对齐。
★ v2.0.19 手动发送增加 HTTP 返回码反馈：
           - sendEvent 由返回 boolean 改为返回 HTTP 状态码（异常统一 -1）；新增 codeMessage(int) 翻译可读说明；
             sendLogText 增加 ResultCallback，后台拿到码后回主线程；onSendRunLog 先弹「发送中」，再弹
             「✅ 发送成功（HTTP 200）」或「❌ 发送失败：HTTP 404 — 接口不存在…」，让 404/400/401 等现形，杜绝假绿。
           - 其余不变：POST JSON 到 https://yanzi-api.ziruxue.top/api/l6/event，
             鉴权走自定义请求头 token: <共享token>；复用 HttpURLConnection（无新依赖）；
             全量 try-catch 不抛；后台守护线程池执行。
★ v2.0.21 手动发送 -1 异常详情细分（用户反馈「HTTP -1 网络异常」看不出是哪类断网）：
           - L6Reporter.sendEvent 的 catch 捕获异常类名+信息写入 ThreadLocal（getLastError() 读取）；
             codeMessage(int,String) 新增重载：-1 且有 detail 时回显「网络异常（<类名>: <信息>）」，
             一眼区分 UnknownHostException（DNS）/ SocketTimeoutException（超时/被拦）/ SSLHandshakeException（TLS 时钟/证书/代理拦截）；
             ResultCallback.onResult(int code, String detail) 加 detail 参数；sendLogText 透传 detail 到主线程回调；
             onSendRunLog 用两参 lambda 调 codeMessage(code, detail)。无新依赖、不影响静默策略。
           - 触发：① 崩溃自动上报（CrashGuard 落盘后调 L6Reporter.sendCrash）
                  ② 启动兜底补发（MainActivity 启动调 flushPendingCrash 扫残留 l6-crash-*.txt 上报并删）
                  ③ 设置页「📤 发送运行日志到云端」手动按钮（onSendRunLog → sendLogText）
           - 发送内容：kind(crash/log) + ver + os + model + time + msg（崩溃堆栈 / 运行日志全文）
★ v2.0.25 备用内网地址修正：ENDPOINT_LAN 由 http://192.168.24.10/api/l6/event 改为
           http://192.168.24.10:30507/api/l6/event。旧地址 80 端口是 fnOS nginx，302 → 5666
           Web 管理页，HttpURLConnection 自动跟随使 POST 落到 HTML 404，App 误报「段名/路径错误」。
           新地址实测为真实 API（JSON 401 TOKEN_INVALID）。
★ v2.0.26 TOKEN 对齐真实值：会话 sensitiveProtection 把用户真实输入（plaintext，
           sha256 11075c11…）在显示层换成 ciphertext（sha256 5a680a78…），v2.0.23 按
           显示值写入源码 ⇒ 与服务端（按真实值配置）永远不一致，备用发送 401。
           本版从原始 jsonl 的 plaintext 零手敲提取并写回，独立 hex 断言同步更新。
★ v2.0.27 事件字段：baseEvent() 增加 voltage（L6Log.batteryVoltageV() 读
           BATTERY_CHANGED/EXTRA_VOLTAGE，mV→V 两位，取不到 -1）与 currentVersion
           （= appVerPublic()），原 ver 保留；覆盖 crash / pending / 手动云端 / 手动内网。
           ⚠️ 车机实采电池读数全 0 ⇒ 该字段很可能恒为 -1，服务端需容忍。
           同时保留 TLS1.3 双路径（postByConnection / postByTls13Socket / tls13Factory）。
"""
import hashlib
import io
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else r"D:/AI/WorkBuddy/老六/dist/Z6CC-2.0.27.apk"

z = zipfile.ZipFile(APK)
names = z.namelist()
dex = z.read("classes.dex")
data = open(APK, "rb").read()

print("== APK ==")
print("   字节: %d" % len(data))
print("   sha256: %s" % hashlib.sha256(data).hexdigest())
print("   classes.dex: %d B" % len(dex))
print("   assets: %s" % [n for n in names if n.startswith("assets/")])

POS = [
    # 原生界面骨架
    b"com/l6/carmedia/nat/NatShell", b"com/l6/carmedia/nat/HomePane",
    b"com/l6/carmedia/nat/SetPane", b"com/l6/carmedia/nat/Dock",
    b"com/l6/carmedia/nat/AppDrawer",
    b"com/l6/carmedia/nat/ForceUpdate", b"com/l6/carmedia/nat/NavMini",
    b"com/l6/carmedia/nat/U;", b"com/l6/carmedia/nat/Prefs",
    # 原生启动与刷新入口
    b"\xe5\x8e\x9f\xe7\x94\x9f\xe7\x95\x8c\xe9\x9d\xa2\xe5\xb7\xb2\xe5\x90\xaf\xe7\x94\xa8",  # 原生界面已启用
    b"refreshSet", b"refreshDock", b"refreshHome",
    b"onOtaEvent", b"forceUpdate", b"gotoPage", b"sysWake", b"pushSys",
    # 车机信号采集 + 纯逻辑类
    b"com/l6/carmedia/SigDiff", b"com/l6/carmedia/SysProbe",
    b"com/l6/carmedia/SignalCapture", b"com/l6/carmedia/LrcParse",
    b"com/l6/carmedia/NavParse", b"com/l6/carmedia/NavBcastFmt",
    b"com/l6/carmedia/NavBcastData",
    # v2.0.2：悬浮「返回」按钮 + 它的纯逻辑点按判定（手机高密度屏点了没反应的修复）
    b"com/l6/carmedia/FloatNav", b"com/l6/carmedia/TapJudge",
    b"isTap", b"bringToFront", b"canDrawOverlay",
    # v2.0.3：清空采集日志
    b"clearSignalLogs", b"signal-",
    # v2.0.9：目录重构（app-log/caiji 子目录 + caiji- 前缀）+ 采集快照拆 changes1/changes2
    b"caiji", b"caiji-", b"changes1", b"changes2", b"app-log",
    # v2.0.10 P0 修复（车机连崩两次）：刷新路径原子化 + 入口线程兜底 + 静默 catch 留日志。
    #   断言全部落在新增的 L6Log.e 消息字面量上（字符串池，dex 可见）。
    b"onResume refreshHome \xe5\xa4\xb1\xe8\xb4\xa5",       # onResume refreshHome 失败
    b"onResume refreshSet \xe5\xa4\xb1\xe8\xb4\xa5",        # onResume refreshSet 失败
    b"onSys updateCar \xe5\xa4\xb1\xe8\xb4\xa5",            # onSys updateCar 失败
    b"onSys updateMedia \xe5\xa4\xb1\xe8\xb4\xa5",          # onSys updateMedia 失败
    b"refreshOta \xe8\xaf\xbb otaConfig \xe5\xa4\xb1\xe8\xb4\xa5",  # refreshOta 读 otaConfig 失败
    # v2.0.11：接口探测 IfaceProbe（采集所有能检测到的接口信息）。
    #   类名 + 反射方法名（字符串池）+ 需求全单里的关键信号名。
    b"com/l6/carmedia/IfaceProbe",
    b"android.car.Car", b"getCarManager", b"getPropertyList", b"getProperty",
    b"car_service", b"pkgIfaces", b"bcastHistory", b"settingsScan", b"actionCandidates",
    b"ENGINE_RPM", b"ENGINE_OIL_TEMP", b"PERF_ODOMETER", b"TIRE_PRESSURE",
    b"HVAC_FAN_SPEED", b"ENV_OUTSIDE_TEMPERATURE", b"FUEL_CONSUMPTION_SINCE_RESTART",
    # v2.0.12：接口探测 v2（Car API 已死 → 转攻 chinatsp 私有通道）。
    #   新段名 + 反射/绑定关键调用名（字符串池可见；int 常量如 BIND_AUTO_CREATE 会被
    #   javac 内联，**不断言**——平台 public static final int 字段名不进 dex）。
    b"svcDesc", b"propsFull", b"bindProbe", b"keyPkgs", b"comps", b"suspectDesc",
    b"ifaceDesc", b"connected", b"sourceDir", b"sharedUserId", b"unparsed", b"rawHead",
    b"android.os.ServiceManager", b"getInterfaceDescriptor",
    b"bindService", b"unbindService", b"onServiceConnected", b"getprop",
    # 关键 binder 服务名（KEY_SVCS 字面量）
    b"chinatsp.vehicle.network", b"coagent.tbox", b"chinatsp.autoaction",
    # 关键已装包名（KEY_PKGS 字面量）
    b"com.chinatsp.vehiclenetwork", b"com.chinatsp.settings", b"com.chinatsp.ifly",
    b"com.chinatsp.onecall", b"com.chinatsp.etc", b"com.chinatsp.outsidecarvoice",
    b"com.chinatsp.automate", b"com.banyac.midrive.app", b"com.egogame.yudanbox",
    b"com.android.car",
    # 关键绑定目标组件全名（KEY_BIND 字面量）
    b"com.chinatsp.vehiclenetwork.service.VehicleNetworkService",
    b"com.chinatsp.base.InitCarApiService",
    b"com.banyac.tirepressure.manager.DfuService",
    b"com.egogame.yudanbox.service.CarPropertyMonitorService",
    b"com.chinatsp.etc.service.CarService",
    # 嗅探候选（EXTRA_ACTIONS 字面量，挑具代表性的）
    b"com.chinatsp.vehiclenetwork.action", b"android.car.action.VEHICLE_INFO",
    b"vendor.vehicle.STATE", b"com.changan.vehicle.STATE",
    # 采集引导文案改「操作清单」（SetPane 字面量，中文写 \xNN）
    b"\xe6\x93\x8d\xe4\xbd\x9c\xe6\xb8\x85\xe5\x8d\x95",                      # 操作清单
    b"\xe7\x82\xb9\xe7\x81\xab\xe8\xa1\x8c\xe9\xa9\xb6 5 \xe5\x88\x86\xe9\x92\x9f",  # 点火行驶 5 分钟
    # v2.0.13：采集前台保活 CapService —— 类名 + startForeground/onStartCommand
    #   （方法名走 dex string pool）+ 通知渠道 / 动作 / 文案字面量 + 联动钩子日志。
    b"com/l6/carmedia/CapService",
    b"startForeground", b"onStartCommand", b"setOngoing", b"NotificationChannel",
    b"l6_capture", b"com.l6.carmedia.CAP_STOP",
    b"\xe8\x80\x81\xe5\x85\xad \xc2\xb7 \xe4\xbf\xa1\xe5\x8f\xb7\xe9\x87\x87\xe9\x9b\x86\xe4\xb8\xad",  # 老六 · 信号采集中
    b"\xe7\xbb\x93\xe6\x9d\x9f\xe9\x87\x87\xe9\x9b\x86",                                # 结束采集
    b"\xe4\xbf\xa1\xe5\x8f\xb7\xe9\x87\x87\xe9\x9b\x86\xe4\xbf\x9d\xe6\xb4\xbb",        # 信号采集保活
    b"\xe6\x8c\x82\xe5\x89\x8d\xe5\x8f\xb0\xe6\x9c\x8d\xe5\x8a\xa1\xe4\xbf\x9d\xe6\xb4\xbb",  # 挂前台服务保活
    b"\xe6\x92\xa4\xe5\x89\x8d\xe5\x8f\xb0\xe6\x9c\x8d\xe5\x8a\xa1",                    # 撤前台服务
    # SetPane 引导文案改「可切后台 / 熄屏 / 用导航」
    b"\xe5\x89\x8d\xe5\x8f\xb0\xe6\x9c\x8d\xe5\x8a\xa1\xe4\xbf\x9d\xe6\xb4\xbb\xef\xbc\x8c\xe9\x80\x9a\xe7\x9f\xa5\xe6\xa0\x8f",  # 前台服务保活，通知栏
    # 通知正文格式串（fmt 拼出来的字面量「已 」/「 · 变化 」）
    b"\xe5\xb7\xb2 ", b"\xc2\xb7 \xe5\x8f\x98\xe5\x8c\x96 ", b" \xe6\x9d\xa1",
    # v2.0.7：必须保留 isUsable 门禁与「缺少下载直链」文案（半发版状态要被拒）
    b"\xe7\xbc\xba\xe5\xb0\x91\xe4\xb8\x8b\xe8\xbd\xbd\xe7\x9b\xb4\xe9\x93\xbe",  # 缺少下载直链
    # v2.0.4：启动自动检查发现新版本 → 可取消提示框
    b"updatePrompt", b"showOptional", b"setSkippedVersionCode", b"isSkipped", b"l6_ota",
    b"hideShaLine",
    # ★ Python 3 的 bytes 字面量**不支持 \uXXXX**（只支持 \xNN）——中文断言必须写 \xNN，
    #   否则 \u 被当字面量、拿去 dex 里找必然 x0，看着像「代码没进包」，其实是断言写错了。
    b"\xe6\xb8\x85\xe7\xa9\xba\xe9\x87\x87\xe9\x9b\x86\xe6\x97\xa5\xe5\xbf\x97",  # 清空采集日志
    b"\xe8\xbf\x90\xe8\xa1\x8c\xe6\x97\xa5\xe5\xbf\x97\xe6\x9c\xaa\xe5\x8a\xa8",  # 运行日志未动
    # 业务
    b"com/l6/carmedia/Ota", b"L6Apps", b"L6Demo",
    # v2.0.17：日志发送功能 L6Reporter（崩溃自动 + 启动兜底 + 手动按钮）
    b"com/l6/carmedia/L6Reporter",
    b"flushPendingCrash", b"sendLogText", b"sendCrash", b"sendEvent",
    b"l6/event", b"yanzi-api.ziruxue.top",
    b"token",  # 自定义请求头名 token: <共享token>（鉴权契约由服务端校验）
    bytes.fromhex("33363635373762363462326237366163346464333138623939636666393734356562366332346631343061386461376661643433656239393534363833326334"),  # 共享 token 全文（★独立 hex 常量，源自用户真实输入，禁止由源码派生）
    b"\xe5\x8f\x91\xe9\x80\x81\xe8\xbf\x90\xe8\xa1\x8c\xe6\x97\xa5\xe5\xbf\x97\xe5\x88\xb0\xe4\xba\x91\xe7\xab\xaf",  # 发送运行日志到云端
    b"\xe6\x97\xa5\xe5\xbf\x97\xe4\xb8\x8a\xe6\x8a\xa5",  # 日志上报
    # v2.0.19：手动发送回显 HTTP 返回码（404/400/401 等）—— 证明反馈逻辑进包
    b"codeMessage", b"ResultCallback",
    b"HTTP ",
    b"\xe8\xbf\x90\xe8\xa1\x8c\xe6\x97\xa5\xe5\xbf\x97\xe5\x8f\x91\xe9\x80\x81\xe6\x88\x90\xe5\x8a\x9f",  # 运行日志发送成功
    b"\xe5\x8f\x91\xe9\x80\x81\xe5\xa4\xb1\xe8\xb4\xa5",  # 发送失败
    # v2.0.21：-1 异常详情细分（DNS/超时/TLS 一眼看出）—— 证明 detail 透传 + codeMessage(int,String) 进包
    b"getLastError", b"ThreadLocal",
    b"\xe7\xbd\x91\xe7\xbb\x9c\xe5\xbc\x82\xe5\xb8\xb8\xef\xbc\x88",  # 网络异常（（codeMessage(int,String) 前缀，detail 嵌其后）
    # v2.0.25：备用内网地址改到真实 API 端口 30507（旧 80 端口 302 到 fnOS Web 页，POST 被跟随成 HTML 404）
    b"http://192.168.24.10:30507/api/l6/event",
    b"sendEventTo", b"sendLogTextTo",
    "备用发送（内网）".encode(), "备用发送成功".encode(), "备用发送失败".encode(),
    # v2.0.27：事件外壳新增 voltage / currentVersion（原 ver 保留，只增不改）。
    #   batteryVoltageV + 中文告警串是本版独有锚点（旧版 dex 里不存在），
    #   voltage / currentVersion 是字段契约断言（字符串池全局存在，配合 javap 硬证据裁决调用点）。
    b"batteryVoltageV", "电瓶电压读取失败".encode("utf-8"),
    b"currentVersion", b"voltage",
    # v2.0.27：随版保留的 TLS1.3 双路径（本机 Java 实测被 443 前置按 ClientHello 指纹拒绝，
    #   但对车机 Conscrypt 未必无效；try-catch 全兜底，不改变其它链路行为）。
    b"postByConnection", b"postByTls13Socket", b"tls13Factory", b"TLSv1.3",
]
NEG = [
    b"http://192.168.24.10/api/l6/event",  # v2.0.25：旧 80 端口地址不得残留（会 302 到 fnOS Web 页）
    # v2.0.0 明确删掉的：HTML 页面 / SHIM 注入层 / WebView 桥注册
    b"assets/index.html", b"file:///android_asset/index.html",
    b"L6NativeRaw", b"addJavascriptInterface", b"window.L6Native",
    b"onShowFileChooser", b"shouldOverrideUrlLoading",
    # 画中画（v1.5.17 已取消，防回归）
    b"PictureInPictureParams", b"isPipAvailable", b"navEnterPip", b"pipEnabled",
    # 随上报功能删除的类（防回归）
    b"com/l6/carmedia/LogBatch", b"com/l6/carmedia/DnsQuery",
    b"com/l6/carmedia/LogDiag",
    # v2.0.1 删掉的：桌面小部件整套 + 主页「导航」栏位
    b"com/l6/carmedia/nat/WidgetPanel",
    b"com/l6/carmedia/L6WidgetHost", b"com/l6/carmedia/WidgetProbe",
    b"AppWidgetHost", b"bindAppWidgetIdIfAllowed", b"REQ_BIND",
    b"refreshWidget", b"widgetBind", b"widgetList", b"navSlot",
    # v2.0.5：手势分区的两行提示文字已全部移除（防回归）
    b"\xe9\x9c\x80\xe8\xa6\x81\xe4\xbb\x8e\xe5\xb1\x8f\xe5\xb9\x95\xe4\xb8\xad\xe5\xbf\x83\xe5\x8c\xba\xe5\x9f\x9f",  # 需从屏幕中心区域
    b"\xe7\xbb\xbc\xe9\x9a\x8f\xe3\x80\x8c\xe4\xb8\x8b\xe6\xbb\x91\xe8\xb7\x9f\xe8\xb8\xaf\xe5\x8c\xaf\xe6\xba\x90",  # 默认「下滑」跟随导航源
]

bad = 0
# == token 唯一性裁决（防「旧 token 仍藏在 dex 里」的假绿）==
import re as _re
_longs = [m.group() for m in _re.finditer(rb'[A-Za-z0-9]{48,}', dex)]
_want = bytes.fromhex("33363635373762363462326237366163346464333138623939636666393734356562366332346631343061386461376661643433656239393534363833326334")
# ★ v2.0.27：保留 TLS1.3 裸 SSLSocket 兜底路径后，源码里 TOKEN 被 javac 内联到两处
#   （postByConnection 的 setRequestProperty + postByTls13Socket 的 req.append），
#   dex 里出现 2 份**相同且正确**的 64 位串。裁决口径相应收紧为「>=1 且全部等于
#   独立 hex 常量」，再叠加「旧显示值零残留」，杜绝「混入旧 token 也判过」的假绿。
_ok = (len(_longs) >= 1 and all(x == _want for x in _longs))
print("\n== token 唯一性裁决 ==")
print("   %s dex 中 >=48 位长串全部等于独立 hex 常量（长串数=%d）" % ("ok  " if _ok else "FAIL", len(_longs)))
bad += 0 if _ok else 1
_old = bytes.fromhex("3834323938396a313566357331356f6f35726f3233377432306b6a79323133367677336531377237303368397a67337470663230737630333733333732357938")
_old_n = dex.count(_old)
print("   %s 旧显示值 token 零残留（x%d）" % ("ok  " if _old_n == 0 else "FAIL", _old_n))
bad += 0 if _old_n == 0 else 1

print("\n== 正向断言（dex 里必须存在）==")
for t in POS:
    n = dex.count(t)
    ok = n > 0
    bad += 0 if ok else 1
    print("   %s %-58s x%d" % ("ok  " if ok else "FAIL", t.decode("utf-8", "replace")[:58], n))

print("\n== 负向断言（dex / zip 里必须不存在）==")
for t in NEG:
    n = dex.count(t) + (1 if t in names else 0)
    ok = n == 0
    bad += 0 if ok else 1
    print("   %s %-58s x%d" % ("ok  " if ok else "FAIL", t.decode("utf-8", "replace")[:58], n))

print("\n== manifest 断言（解压后的二进制 AXML，UTF-8/UTF-16 都搜）==")
# ★ AndroidManifest.xml 在 APK zip 里是 deflate 压缩条目，对整个 APK 原始字节搜明文必然 x0；
#   必须先 z.read() 解压再搜字符串池。
axml = z.read("AndroidManifest.xml")
for perm in ["android.permission.FOREGROUND_SERVICE"]:
    t8 = perm.encode("utf-8")
    t16 = perm.encode("utf-16-le")
    n = axml.count(t8) + axml.count(t16)
    ok = n > 0
    bad += 0 if ok else 1
    print("   %s %-58s x%d (axml %dB)" % ("ok  " if ok else "FAIL", perm, n, len(axml)))

print("\n== v2.0.16 断言（崩溃守护 + 通知加固）==")
for t in [b"L6Crash", b"l6-crash-", "崩溃守护已挂载".encode("utf-8"),
          "采集收尾异常（已强制复位状态）".encode("utf-8"),
          "嗅探挂载失败（不影响采集）".encode("utf-8")]:
    n = dex.count(t)
    ok = n > 0
    bad += 0 if ok else 1
    print("   %s %-58s x%d" % ("ok  " if ok else "FAIL", t.decode("utf-8", "replace")[:58], n))

print("\n== assets 断言 ==")
has_proto = any("index.html" in n for n in names)
print("   %s 不含 index.html" % ("ok  " if not has_proto else "FAIL"))
bad += 0 if not has_proto else 1
need = ["assets/ota.properties", "assets/app.properties"]
for n in need:
    ok = n in names
    bad += 0 if ok else 1
    print("   %s 含 %s" % ("ok  " if ok else "FAIL", n))

print("\n结果: %s（失败 %d 项）" % ("全部通过" if bad == 0 else "有失败", bad))
sys.exit(1 if bad else 0)

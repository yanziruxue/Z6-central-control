# -*- coding: utf-8 -*-
"""v2.0.14 产物反查：壁纸手动放置修复 —— 断言 dex / assets 的「该有的在、不该有的没了」。

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
"""
import hashlib
import io
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else r"D:/AI/WorkBuddy/老六/dist/Z6CC-2.0.14.apk"

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
]
NEG = [
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

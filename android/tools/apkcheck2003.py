# -*- coding: utf-8 -*-
"""v2.0.2 产物反查：界面全原生 —— 断言 dex / assets 的「该有的在、不该有的没了」。

★ 判据全部落在 dex 的**字符串池与类名**上（局部变量名不进 dex，别拿它断言）。
★ v2.0.2 新增悬浮返回按钮的纯逻辑判定类 TapJudge（手机高密度屏点不动的修复）。
"""
import hashlib
import io
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else r"D:/AI/WorkBuddy/老六/dist/Z6CC-2.0.2.apk"

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

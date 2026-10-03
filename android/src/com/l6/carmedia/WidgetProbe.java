package com.l6.carmedia;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONObject;

import java.util.List;

/**
 * v1.5.22 只读探测：这台车机上有哪些「桌面小部件」，以及我们能不能把它嵌进主页导航区域。
 *
 * 与 v1.5.20 的广播探测同一套打法 —— **先把能不能做问清楚，再决定做不做**。
 *
 * 【为什么值得单独探一次】
 * 把第三方 **Activity 画面**嵌进我们窗口是封死的（被嵌 Activity 必须声明 allowEmbedded，高德官方包不会声明），
 * 但「桌面小部件（App Widget）」是完全不同的机制：小部件是 **RemoteViews**，由宿主（launcher）在自己的
 * View 树里 inflate，**不需要目标 App 做任何配合**，也不需要 INJECT_EVENTS / INTERNAL_SYSTEM_WINDOW。
 * 老六就是这台车机的默认桌面，理论上可以当 AppWidgetHost。
 *
 * 【唯一的不确定点：绑定权限】
 * `AppWidgetManager.bindAppWidgetIdIfAllowed()` 要求 `BIND_APPWIDGET`（signature|privileged）。
 * 但第三方 launcher（Nova 之类）**确实能加小部件** ⇒ 说明还有一条「用户授权」的路：
 * 官方文档给的补救是 `bindAppWidgetIdIfAllowed()` 返回 false 时，用
 * `Intent(ACTION_APPWIDGET_BIND)` 拉起系统对话框「允许 X 添加小部件」，用户点了就授。
 * 这条界面在这台 ROM 上有没有、以及直接绑定到底成不成 —— **文档判不了，只能实测**。
 *
 * ★ 全程只读：allocate 出来的 widgetId 用完立刻 delete，不在系统里留悬空 id；任何一步失败都不抛。
 */
public final class WidgetProbe {

    /** 本应用固定的 host 标识（同一 hostId 复用同一个 host 实例）。 */
    private static final int HOST_ID = 0x1A60;
    /** 诊断行最多列几个小部件。 */
    private static final int MAX_LIST = 4;

    private WidgetProbe() {
    }

    /** 跑一次探测并把结果推给页面（事件 kind = "wprobe"）。永不抛。 */
    public static void run(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "wprobe");
        } catch (Throwable ignored) {
            return;
        }
        try {
            probe(ctx, o);
        } catch (Throwable t) {
            try {
                o.put("err", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        SysHub.push(o);
    }

    private static void probe(Context ctx, JSONObject o) throws Exception {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        PackageManager pm = ctx.getPackageManager();

        // ① 这台车机上到底有哪些 App 提供小部件（这一项与权限无关，必定能拿到）
        List<AppWidgetProviderInfo> list = awm.getInstalledProviders();
        int n = (list == null) ? 0 : list.size();
        o.put("n", n);
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (int i = 0; i < list.size() && i < MAX_LIST; i++) {
                AppWidgetProviderInfo in = list.get(i);
                if (in == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(labelOf(pm, in)).append(' ')
                        .append(in.minWidth).append('×').append(in.minHeight);
            }
            if (list.size() > MAX_LIST) {
                sb.append(" …+").append(list.size() - MAX_LIST);
            }
        }
        o.put("list", sb.toString());

        // ② 我们能不能当 host：建 host + 申请一个 widgetId
        AppWidgetHost host = null;
        int id = -1;
        boolean hostOk = false;
        try {
            host = new AppWidgetHost(ctx, HOST_ID);
            id = host.allocateAppWidgetId();
            hostOk = id > 0;
        } catch (Throwable ignored) {
        }
        o.put("host", hostOk);

        // ③ 直接绑定的真实结果 —— 这一条一句话定生死
        boolean bindOk = false;
        ComponentName bound = null;
        if (hostOk && list != null) {
            for (AppWidgetProviderInfo in : list) {
                if (in == null || in.provider == null) {
                    continue;
                }
                try {
                    if (awm.bindAppWidgetIdIfAllowed(id, in.provider)) {
                        bindOk = true;
                        bound = in.provider;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        o.put("bind", bindOk);
        if (bound != null) {
            o.put("bindTo", bound.getPackageName());
        }

        // ★ 用完立刻回收：别在系统里留一个悬空的 widgetId
        if (host != null) {
            if (id > 0) {
                try {
                    host.deleteAppWidgetId(id);
                } catch (Throwable ignored) {
                }
            }
            try {
                host.stopListening();
            } catch (Throwable ignored) {
            }
        }

        // ④ 判据辅助三项
        boolean perm;
        try {
            perm = ctx.checkCallingOrSelfPermission("android.permission.BIND_APPWIDGET")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            perm = false;
        }
        o.put("perm", perm);

        // ★ 关键一项：这台 ROM 上有没有处理「允许 X 添加小部件」授权界面的组件？
        //   有 ⇒ 直接绑定被拒也不要紧，走引导授权就能绑（第三方 launcher 的常规路）。
        boolean bindAct = hasHandler(pm, new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND));
        o.put("bindAct", bindAct);
        // 顺带看看系统的「小部件选择器」在不在（手动兜底时的入口）
        boolean pickAct = hasHandler(pm, new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK));
        o.put("pickAct", pickAct);

        boolean pin;
        try {
            pin = awm.isRequestPinAppWidgetSupported();
        } catch (Throwable t) {
            pin = false;
        }
        o.put("pin", pin);

        // ⑤ 直接给结论：截图的人不用自己推理（这一行就是这次探测的目的）
        String verdict;
        if (n <= 0) {
            verdict = "这条路走不通：这台车机上一个小部件都没有";
        } else if (bindOk) {
            verdict = "可以承载：直接绑定就成功，下一版就能把「" + shortName(pm, bound) + "」嵌进导航区域";
        } else if (bindAct) {
            verdict = "可以承载：需要用户点一次系统授权框（ACTION_APPWIDGET_BIND 可用）";
        } else if (pin) {
            verdict = "不确定：无授权界面，但支持 requestPinAppWidget";
        } else {
            verdict = "走不通：无绑定权限、无授权界面，需平台签名或 priv-app";
        }
        o.put("verdict", verdict);
    }

    /** 系统里有没有能处理这个 Intent 的组件。 */
    private static boolean hasHandler(PackageManager pm, Intent it) {
        try {
            List<ResolveInfo> rs = pm.queryIntentActivities(it, 0);
            return rs != null && !rs.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String labelOf(PackageManager pm, AppWidgetProviderInfo in) {
        try {
            CharSequence cs = in.loadLabel(pm);
            if (cs != null && cs.toString().trim().length() > 0) {
                return cs.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return in.provider == null ? "?" : in.provider.getPackageName();
    }

    private static String shortName(PackageManager pm, ComponentName cn) {
        if (cn == null) {
            return "小部件";
        }
        try {
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0));
            if (cs != null && cs.toString().trim().length() > 0) {
                return cs.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return cn.getPackageName();
    }
}

package com.l6.carmedia;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

/**
 * 系统通知监听：导航真实数据的来源。
 *
 * 为什么走通知？车机上的导航 App（高德/百度/腾讯）在导航中会常驻一条「导航中」通知，
 * 里面就带着「前方 300米 右转」「剩余 5.6公里 · 12分钟」这类信息 —— 这是第三方 App
 * 能拿到的最接近实时的导航数据（没有系统签名，拿不到导航 SDK 的内部状态）。
 *
 * 顺带：收到媒体通知时触发一次媒体会话刷新（切歌最敏感的时机就是通知更新）。
 *
 * 文本 → 结构化 的解析规则放在 {@link NavParse}（纯函数，可单测）；本类只负责
 * 取通知文案、JSON 化、去重推送。不持久化任何通知内容。
 */
public class L6NotifyService extends NotificationListenerService {

    /** 已知导航 App（key 与 MainActivity.APP_MAP 保持一致）。 */
    private static final String[][] NAV_PKGS = {
            {"com.autonavi.amap", "高德地图"},
            {"com.autonavi.amapauto", "高德地图 · 车机版"},
            {"com.baidu.BaiduMap", "百度地图"},
            {"com.baidu.navi", "百度导航"},
            {"com.tencent.map", "腾讯地图"},
            {"com.google.android.apps.maps", "Google 地图"},
    };

    private String lastNavPkg = "";
    private String lastNavJson = "";

    /* ==================== 生命周期 ==================== */

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            SysHub.pushAccess(true);
            MediaHub.get(this).refresh();
            pushNavInactive();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        try {
            SysHub.pushAccess(false);
            MediaHub.get(this).pushInactive();
            pushNavInactive();
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 通知回调 ==================== */

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null) {
                return;
            }
            if (isNavPkg(pkg)) {
                handleNav(pkg, sbn.getNotification(), true);
                return;
            }
            Notification n = sbn.getNotification();
            if (n != null && n.extras != null
                    && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                MediaHub.get(this).refresh();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        try {
            String pkg = sbn.getPackageName();
            if (pkg != null && isNavPkg(pkg)) {
                handleNav(pkg, null, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 导航解析 ==================== */

    private void handleNav(String pkg, Notification n, boolean active) {
        if (!active) {
            // 导航通知消失 = 本次导航结束
            if (pkg.equals(lastNavPkg)) {
                lastNavPkg = "";
                pushNavInactive();
            }
            return;
        }
        Bundle ex = n == null ? null : n.extras;
        if (ex == null) {
            return;
        }
        String all = join(
                str(ex, Notification.EXTRA_TITLE),
                str(ex, Notification.EXTRA_TEXT),
                str(ex, Notification.EXTRA_BIG_TEXT),
                str(ex, Notification.EXTRA_SUB_TEXT),
                str(ex, Notification.EXTRA_INFO_TEXT),
                str(ex, Notification.EXTRA_SUMMARY_TEXT));
        if (!NavParse.looksLikeNav(all)) {
            return;   // 该 App 的其他普通通知（如「签到」）→ 忽略
        }
        try {
            String[] v = NavParse.parse(all);
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", true);
            o.put("pkg", pkg);
            o.put("app", navName(pkg));
            o.put("raw", all);
            o.put("arrow", v[NavParse.ARROW]);
            o.put("turn", v[NavParse.TURN]);
            o.put("road", v[NavParse.ROAD]);
            o.put("next", v[NavParse.NEXT]);
            o.put("remain", v[NavParse.REMAIN]);
            o.put("eta", v[NavParse.ETA]);
            o.put("clock", v[NavParse.CLOCK]);
            o.put("dest", v[NavParse.DEST]);

            String json = o.toString();
            if (json.equals(lastNavJson)) {
                return;   // 内容没变不重复推
            }
            lastNavJson = json;
            lastNavPkg = pkg;
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    private void pushNavInactive() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", false);
            String json = o.toString();
            if (json.equals(lastNavJson)) {
                return;
            }
            lastNavJson = json;
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 文本工具 ==================== */

    private static String str(Bundle ex, String key) {
        try {
            Object v = ex.get(key);
            return v == null ? "" : String.valueOf(v).replace('\n', ' ').trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean isNavPkg(String pkg) {
        for (String[] m : NAV_PKGS) {
            if (m[0].equalsIgnoreCase(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String navName(String pkg) {
        for (String[] m : NAV_PKGS) {
            if (m[0].equalsIgnoreCase(pkg)) {
                return m[1];
            }
        }
        return pkg;
    }
}

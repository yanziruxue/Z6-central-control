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
    /** 诊断：最近一条「导航类 App 的通知」摘要 —— 经 navdbg 事件推到页面空态里显示（装机排查用）。 */
    private String lastSeen = "";

    /* ==================== 生命周期 ==================== */

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            SysHub.pushAccess(true);
            MediaHub.get(this).refresh();
            markSeen("服务已连接（通知使用权已授予），等待导航通知…");
            pushNavDbg();
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
            markSeen("服务已断开（通知使用权被撤销）");
            pushNavDbg();
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
            // ★ v1.5.19：通知「更新」在系统层常表现为先 remove 再 post ⇒ 一 remove 就判导航结束，
            //   会把状态刷成「未在导航」。先问系统「这个包还有没有活跃通知」，真没有才算结束。
            if (pkg != null && isNavPkg(pkg) && !hasActiveFrom(pkg)) {
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
        String all = allText(ex);
        boolean strict = NavParse.looksLikeNav(all);
        if (!strict) {
            // 宽松兜底（v1.5.18）：车机版导航 App 的常驻通知常常只有「已进入后台运行，将持续为您导航」
            // 这类文案（没有距离数字），严格判定会整条丢掉 ⇒ 主页导航区域一直显示「未在导航」。
            // v1.5.19 再放宽两处：① flags 增加 FLAG_NO_CLEAR；
            //   ② 命中「进行时措辞」（持续为您导航 / 正在导航 …）时不再要求 flags ——
            //      车机 ROM 不一定给导航通知打 ongoing，只认 flags 会把真导航挡在门外。
            boolean keep = (n.flags & (Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_NO_CLEAR)) != 0;
            boolean loose = NavParse.looksLikeNavLoose(all);
            boolean strong = NavParse.mentionsNavStrong(all);
            if (!(loose && (keep || strong))) {
                markDrop("丢弃·" + diag(n) + " keep=" + keep + " loose=" + loose + " strong=" + strong);
                return;   // 该 App 的其他普通通知（如「签到 / 优惠券」）→ 忽略
            }
            markSeen("认作导航(宽松)·" + diag(n));
        } else {
            markSeen("认作导航(严格)·" + diag(n));
        }
        try {
            String[] v = NavParse.parse(all);
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", true);
            o.put("loose", !strict);      // true = 只认出「在导航」，转向/剩余解析不到（字段为空）
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
            o.put("dbg", lastSeen);

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

    /* ==================== 诊断（v1.5.19） ==================== */

    /** 通知里可能承载导航文案的所有槽位 —— ★ EXTRA_TEXT_LINES（InboxStyle）漏了会整条读不到。 */
    private static String allText(Bundle ex) {
        StringBuilder sb = new StringBuilder(join(
                str(ex, Notification.EXTRA_TITLE),
                str(ex, Notification.EXTRA_TEXT),
                str(ex, Notification.EXTRA_BIG_TEXT),
                str(ex, Notification.EXTRA_SUB_TEXT),
                str(ex, Notification.EXTRA_INFO_TEXT),
                str(ex, Notification.EXTRA_SUMMARY_TEXT)));
        try {
            Object v = ex.get(Notification.EXTRA_TEXT_LINES);
            if (v instanceof CharSequence[]) {
                for (CharSequence cs : (CharSequence[]) v) {
                    if (cs == null) {
                        continue;
                    }
                    String t = String.valueOf(cs).replace('\n', ' ').trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(t);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 诊断一行：flags 十六进制（+可读名）+ 文案摘要 —— 装机时用户截图即可定位问题。 */
    private static String diag(Notification n) {
        if (n == null) {
            return "flags=? \"\"";
        }
        int f = n.flags;
        String names = "";
        if ((f & Notification.FLAG_ONGOING_EVENT) != 0) {
            names += "ongoing ";
        }
        if ((f & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            names += "fg ";
        }
        if ((f & Notification.FLAG_NO_CLEAR) != 0) {
            names += "noClear ";
        }
        if ((f & Notification.FLAG_AUTO_CANCEL) != 0) {
            names += "auto ";
        }
        return "flags=0x" + Integer.toHexString(f)
                + (names.isEmpty() ? "" : "(" + names.trim() + ")")
                + " \"" + brief(allText(n.extras)) + "\"";
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 56 ? (t.substring(0, 56) + "…") : t;
    }

    private void markSeen(String s) {
        if (s == null || s.equals(lastSeen)) {
            return;
        }
        lastSeen = s;
        L6Log.i("L6Nav", "通知诊断 " + s);
    }

    /** 被丢弃最需要诊断 ⇒ 记一行并立刻推给页面（走 navdbg，不动 active 状态）。 */
    private void markDrop(String s) {
        markSeen(s);
        pushNavDbg();
    }

    private void pushNavDbg() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "navdbg");
            o.put("dbg", lastSeen);
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    /** 该包当前是否仍有活跃通知（用于区分「通知被更新」与「导航结束」）。 */
    private boolean hasActiveFrom(String pkg) {
        try {
            StatusBarNotification[] act = getActiveNotifications();
            if (act == null) {
                return false;
            }
            for (StatusBarNotification s : act) {
                if (s != null && pkg.equals(s.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void pushNavInactive() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", false);
            if (!lastSeen.isEmpty()) {
                o.put("dbg", lastSeen);
            }
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

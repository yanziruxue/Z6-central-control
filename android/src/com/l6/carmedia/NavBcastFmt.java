package com.l6.carmedia;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高德车机版「AmapAuto 标准广播协议」的摘要格式化（**零 Android 依赖**，可在桌面 JVM 单测）。
 *
 * 为什么要有它：解析导航通知文案天生脆弱 —— 车机版导航 App 的常驻通知往往只有
 * 「高德地图已进入后台运行，将持续为您导航」，没有距离、没有转向，怎么放宽判定都拿不到
 * 结构化数据（v1.5.18 / v1.5.19 连续两版都在补这个洞）。
 *
 * 高德另有一套**官方公开的广播协议**：导航 / 巡航过程中它会主动往外发结构化数据 ——
 *   action = {@link #ACTION_SEND}，用 extra {@link #EXTRA_KEY_TYPE}(int) 区分接口。
 * 本文档来源 = 高德公开的《高德车机版 AmapAuto 标准广播协议》(20180813)，
 * 照协议自己实现（**不抄任何第三方 GPL 代码**）。
 *
 * ★ 本版（v1.5.20）是**探测版**：只做「Bundle → 一行可读摘要」，供装机时直接显示在主页
 * 导航区域的空态里，用来确认**这台车机的 ROM 到底转不转发这些广播**；
 * 真正的结构化渲染（转向图标 / 车道 / 红绿灯）等实测确认后再做。
 */
public final class NavBcastFmt {

    /** 高德**发出**的广播 —— 我们监听这个。 */
    public static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    /** 高德**接收**的广播 —— 我们发控制指令用（本版未用）。 */
    public static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";
    /** 区分接口的 extra 名。 */
    public static final String EXTRA_KEY_TYPE = "KEY_TYPE";

    public static final int KT_GUIDE = 10001;   // 引导信息透出（导航/巡航中主动发）
    public static final int KT_STATE = 10019;   // 地图状态（导航开始/结束的权威信号）
    public static final int KT_TMC = 13011;     // 实时路况光柱
    public static final int KT_LANE = 13012;    // 车道信息
    public static final int KT_LIGHT = 60073;   // 红绿灯倒计时扩展

    private NavBcastFmt() {
    }

    /** 是否为已收录的 KEY_TYPE。 */
    public static boolean isKnownKeyType(int keyType) {
        return keyType == KT_GUIDE || keyType == KT_STATE || keyType == KT_TMC
                || keyType == KT_LANE || keyType == KT_LIGHT;
    }

    /** 10001 的 TYPE：0 GPS 导航 / 1 模拟导航 / 2 巡航。 */
    public static String typeName(int t) {
        switch (t) {
            case 0: return "导航";
            case 1: return "模拟导航";
            case 2: return "巡航";
            default: return "TYPE=" + t;
        }
    }

    /** 10019 的 EXTRA_STATE 语义。 */
    public static String stateName(int st) {
        switch (st) {
            case 3: return "进入前台";
            case 4: return "进入后台";
            case 5: return "开始算路";
            case 8: return "开始导航";
            case 9: return "结束导航";
            case 10:
            case 11:
            case 12: return "模拟导航";
            case 24: return "进入巡航";
            case 25: return "退出巡航";
            case 39: return "到达目的地";
            default: return "未知状态";
        }
    }

    /**
     * Bundle 摘要 → 一行可读文本。keyType 未知时也要能读（把收到的键名摆出来，
     * 方便发现未收录的接口），绝不返回 null / 空串。
     */
    public static String summarize(int keyType, Map<String, Object> kv) {
        Map<String, Object> m = (kv == null) ? new LinkedHashMap<String, Object>() : kv;
        if (keyType == KT_GUIDE) {
            return guide(m);
        }
        if (keyType == KT_STATE) {
            return state(m);
        }
        if (keyType == KT_LANE) {
            return prefix(keyType, "车道") + " · " + orEmpty(s(m, "EXTRA_DRIVE_WAY")) + dump(m);
        }
        if (keyType == KT_TMC) {
            return prefix(keyType, "路况") + " · " + orEmpty(s(m, "EXTRA_TMC_SEGMENT")) + dump(m);
        }
        if (keyType == KT_LIGHT) {
            return prefix(keyType, "红绿灯") + " · " + orEmpty(s(m, "lightsData"))
                    + " " + orEmpty(s(m, "redLightCountDownSeconds"))
                    + " " + orEmpty(s(m, "greenLightLastSecond")) + dump(m);
        }
        return "KEY_TYPE=" + keyType + " · 未收录" + dump(m);
    }

    /* ---------------- 各接口 ---------------- */

    private static String guide(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(prefix(KT_GUIDE, "引导"));
        sb.append(" · ").append(typeName(intOf(s(m, "TYPE"), -1)));
        boolean any = false;

        String cur = s(m, "CUR_ROAD_NAME");
        String nxt = s(m, "NEXT_ROAD_NAME");
        if (!cur.isEmpty() || !nxt.isEmpty()) {
            sb.append(" · ").append(cur.isEmpty() ? "?" : cur);
            if (!nxt.isEmpty()) {
                sb.append(" → ").append(nxt);
            }
            any = true;
        }

        String icon = firstNonEmpty(s(m, "NEW_ICON"), s(m, "ICON"));
        if (!icon.isEmpty()) {
            sb.append(" · 转向图标 ").append(icon);
            any = true;
        }

        String dis = firstNonEmpty(s(m, "ROUTE_REMAIN_DIS_AUTO"), s(m, "ROUTE_REMAIN_DIS"));
        if (!dis.isEmpty()) {
            sb.append(" · 全程剩 ").append(dis);
            any = true;
        }
        String tim = firstNonEmpty(s(m, "ROUTE_REMAIN_TIME_AUTO"), s(m, "ROUTE_REMAIN_TIME"));
        if (!tim.isEmpty()) {
            sb.append(" · 剩时 ").append(tim);
            any = true;
        }
        String seg = firstNonEmpty(s(m, "SEG_REMAIN_DIS_AUTO"), s(m, "SEG_REMAIN_DIS"));
        if (!seg.isEmpty()) {
            sb.append(" · 本段剩 ").append(seg);
            any = true;
        }
        String spd = s(m, "CUR_SPEED");
        if (!spd.isEmpty()) {
            sb.append(" · ").append(spd).append("km/h");
            any = true;
        }
        String lim = s(m, "LIMITED_SPEED");
        if (!lim.isEmpty()) {
            sb.append(" · 限速 ").append(lim);
            any = true;
        }
        String cam = s(m, "CAMERA_DIST");
        if (!cam.isEmpty()) {
            sb.append(" · 电子眼 ").append(cam).append("m");
            any = true;
        }
        String tl = s(m, "TRAFFIC_LIGHT_NUM");
        if (!tl.isEmpty()) {
            sb.append(" · 红绿灯 ").append(tl);
            any = true;
        }
        return any ? sb.toString() : (sb + dump(m));
    }

    private static String state(Map<String, Object> m) {
        String raw = firstNonEmpty(s(m, "EXTRA_STATE"), s(m, "STATE"));
        if (raw.isEmpty()) {
            return prefix(KT_STATE, "状态") + dump(m);
        }
        int st = intOf(raw, Integer.MIN_VALUE);
        if (st == Integer.MIN_VALUE) {
            return prefix(KT_STATE, "状态") + " · " + raw + dump(m);
        }
        return prefix(KT_STATE, "状态") + " · " + st + " " + stateName(st);
    }

    /* ---------------- 小工具 ---------------- */

    private static String prefix(int keyType, String what) {
        return "KEY_TYPE=" + keyType + " · " + what;
    }

    /** 键名兜底：一个字段都没认出来时，把收到的键摆出来（帮助发现未收录接口）。 */
    private static String dump(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return " · (无字段)";
        }
        StringBuilder sb = new StringBuilder(" · keys=");
        int n = 0;
        for (String k : m.keySet()) {
            if (n++ > 0) {
                sb.append(',');
            }
            if (n > 12) {
                sb.append("…");
                break;
            }
            sb.append(k);
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null || s.isEmpty() ? "(空)" : s;
    }

    private static String s(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v == null) {
                return "";
            }
            return String.valueOf(v).replace('\n', ' ').trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String firstNonEmpty(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isEmpty()) {
                return x;
            }
        }
        return "";
    }

    /** 宽容取整："8" / "8.0" / " 8 " 都能出 8；不认识的返回 def。 */
    private static int intOf(String s, int def) {
        if (s == null) {
            return def;
        }
        String t = s.trim();
        int i = 0;
        boolean neg = false;
        if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) {
            neg = t.charAt(i) == '-';
            i++;
        }
        int v = 0;
        int digits = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) {
            v = v * 10 + (t.charAt(i) - '0');
            i++;
            digits++;
        }
        if (digits == 0) {
            return def;
        }
        return neg ? -v : v;
    }
}

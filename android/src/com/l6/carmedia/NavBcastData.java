package com.l6.carmedia;

import java.util.Map;

/**
 * 高德广播 `KEY_TYPE=10001`（引导信息透出）的结构化模型（**零 Android 依赖**，可单测）。
 *
 * 与 {@link NavBcastFmt} 的分工：Fmt 负责「Bundle → 一行给人看的摘要」（探测期用），
 * 本类负责「Bundle → 结构化字段」（渲染期用）。两者都只读同一份 Map，互不依赖。
 *
 * ★ 有效性约定（v1.5.21 实测定下的）：车机实测里 `LIMITED_SPEED=1`、`TRAFFIC_LIGHT_NUM=0`
 * 都出现在「其实没有这个信息」的时候 ⇒
 *   · 限速 ≤ 1 ⇒ 视为无数据（不显示）
 *   · 电子眼 ≤ 0 ⇒ 视为无数据
 *   · 红绿灯数 ≤ 0 ⇒ 视为无数据
 *   · 车速 0 是**真值**（车停着），照显示
 * 宁可不显示，也不要在车机上摆一个假的「限速 1」。
 */
public final class NavBcastData {

    /** 数据来源标记：页面据此显示「广播」小标签。 */
    public static final String SRC_BCAST = "bcast";

    /** 是否来自 10001 引导信息。 */
    public boolean guide;
    /** 0 GPS 导航 / 1 模拟导航 / 2 巡航 / -1 未知。 */
    public int type = -1;
    public String curRoad = "";
    public String nextRoad = "";
    /** 转向图标 ID（-1 = 无）。 */
    public int icon = -1;
    public String remainDist = "";
    public String remainTime = "";
    public String segDist = "";
    public String nextSegDist = "";
    public int speed = -1;
    public int limit = -1;
    public int camera = -1;
    public int light = -1;

    private NavBcastData() {
    }

    /** 从 extras 解析；字段缺失一律留空 / -1，绝不抛。 */
    public static NavBcastData of(Map<String, Object> kv) {
        NavBcastData d = new NavBcastData();
        if (kv == null) {
            return d;
        }
        d.guide = true;
        d.type = NavBcastFmt.intOf(s(kv, "TYPE"), -1);
        d.curRoad = s(kv, "CUR_ROAD_NAME");
        d.nextRoad = s(kv, "NEXT_ROAD_NAME");
        d.icon = NavBcastFmt.intOf(first(s(kv, "NEW_ICON"), s(kv, "ICON")), -1);
        // ★ 优先取协议里「带单位」的现成字符串，省掉自己拼单位还可能拼错
        d.remainDist = first(s(kv, "ROUTE_REMAIN_DIS_AUTO"), s(kv, "ROUTE_REMAIN_DIS"));
        d.remainTime = first(s(kv, "ROUTE_REMAIN_TIME_AUTO"), s(kv, "ROUTE_REMAIN_TIME"));
        d.segDist = first(s(kv, "SEG_REMAIN_DIS_AUTO"), s(kv, "SEG_REMAIN_DIS"));
        d.nextSegDist = s(kv, "NEXT_SEG_REMAIN_DIS");
        d.speed = NavBcastFmt.intOf(s(kv, "CUR_SPEED"), -1);
        d.limit = NavBcastFmt.intOf(s(kv, "LIMITED_SPEED"), -1);
        d.camera = NavBcastFmt.intOf(s(kv, "CAMERA_DIST"), -1);
        d.light = NavBcastFmt.intOf(s(kv, "TRAFFIC_LIGHT_NUM"), -1);
        return d;
    }

    /** 是否在导航中（0 GPS / 1 模拟）。 */
    public boolean isNav() {
        return type == 0 || type == 1;
    }

    /** 是否巡航（不开路线，只是在地图上游走）。 */
    public boolean isCruise() {
        return type == 2;
    }

    /** 限速是否可信（★ 车机实测 0/1 都是「没这个信息」）。 */
    public boolean hasLimit() {
        return limit > 1;
    }

    public boolean hasCamera() {
        return camera > 0;
    }

    public boolean hasLight() {
        return light > 0;
    }

    public boolean hasSpeed() {
        return speed >= 0;
    }

    /** 转向图标 ID → 箭头字符。ID 未知时给一个中性箭头，绝不显示空白。 */
    public String arrow() {
        return NavBcastFmt.iconArrow(icon);
    }

    /** 转向图标 ID → 中文指令。ID 未知时给「继续行驶」而不是空串（页面不必再兜底）。 */
    public String turnName() {
        return NavBcastFmt.iconName(icon);
    }

    /**
     * 底部状态条文案（只列**有效**项）：`限速 60 · 38km/h · 电子眼 451m · 红绿灯 2`。
     * 一个有效项都没有时返回空串 ⇒ 页面不渲染这一行。
     */
    public String chips() {
        StringBuilder sb = new StringBuilder();
        if (hasLimit()) {
            add(sb, "限速 " + limit);
        }
        if (hasSpeed()) {
            add(sb, speed + "km/h");
        }
        if (hasCamera()) {
            add(sb, "电子眼 " + camera + "m");
        }
        if (hasLight()) {
            add(sb, "红绿灯 " + light);
        }
        return sb.toString();
    }

    /** 主指令：`左转 · 兴物线`（下一道路名优先，没有就退回当前道路名）。 */
    public String instruction() {
        String road = nextRoad.isEmpty() ? curRoad : nextRoad;
        String t = turnName();
        if (road.isEmpty()) {
            return t;
        }
        return t + " · " + road;
    }

    /** 本段剩余 + 全程剩余，给「剩余 / 本段」两行用；缺的留空串。 */
    public String remainLine() {
        if (remainDist.isEmpty() && remainTime.isEmpty()) {
            return "";
        }
        if (remainTime.isEmpty()) {
            return "剩余 " + remainDist;
        }
        if (remainDist.isEmpty()) {
            return "约 " + remainTime;
        }
        return "剩余 " + remainDist + " · " + remainTime;
    }

    private static void add(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(s);
    }

    private static String s(Map<String, Object> m, String k) {
        try {
            Object v = m.get(k);
            return v == null ? "" : String.valueOf(v).replace('\n', ' ').trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String first(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isEmpty()) {
                return x;
            }
        }
        return "";
    }
}

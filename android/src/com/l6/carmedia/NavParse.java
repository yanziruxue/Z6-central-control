package com.l6.carmedia;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导航通知文本解析。
 *
 * 刻意做成「零 Android 依赖」的纯函数：解析导航播报这种纯文本规则最容易出错，
 * 但混在 Service 里没法单测。抽出来之后可以直接在桌面 JVM 上跑
 * tools/logic-smoke 做回归（见 android/tools/logic-smoke.js）。
 *
 * 输入形如（高德/百度/腾讯导航通知的 title + text + bigText 拼接）：
 *   "前方 300米 右转 | 剩余 5.6公里 · 12分钟 | 进入 滨江大道"
 */
public final class NavParse {

    /* parse() 返回数组的下标 */
    public static final int ARROW = 0;
    public static final int TURN = 1;
    public static final int ROAD = 2;
    public static final int NEXT = 3;
    public static final int REMAIN = 4;
    public static final int ETA = 5;
    public static final int CLOCK = 6;
    public static final int DEST = 7;
    public static final int N = 8;

    private static final Pattern PKM =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|KM|Km|米|m)");
    private static final Pattern PMIN = Pattern.compile("(\\d+)\\s*(分钟|min|分)");
    private static final Pattern PHOUR = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|h)");
    private static final Pattern PCLOCK = Pattern.compile("(\\d{1,2}:\\d{2})");

    /** 转向关键字 → 箭头图标；按在文本中出现的位置取最靠前的一个。 */
    private static final String[][] TURNS = {
            {"掉头", "\u21BA"},   // ↺
            {"环岛", "\u21BB"},   // ↻
            {"靠左", "\u2196"},   // ↖
            {"靠右", "\u2197"},   // ↗
            {"左前方", "\u2196"},
            {"右前方", "\u2197"},
            {"左转", "\u21B0"},   // ↰
            {"右转", "\u21B1"},   // ↱
            {"直行", "\u2191"},   // ↑
            {"继续", "\u2191"},
            {"到达目的", "\u26F3"}, // ⛳
            {"到达", "\u26F3"},
            {"终点", "\u26F3"},
            {"目的地", "\u26F3"},
            {"出发", "\u2191"},
            {"出口", "\u21B1"},
            {"进入", "\u2191"},
            {"驶入", "\u2191"},
            {"汇入", "\u2191"},
            {"上坡", "\u2191"},
            {"下坡", "\u2193"},   // ↓
    };

    private static final String[] TURN_KWS = {"掉头", "环岛", "靠左", "靠右", "左前方", "右前方",
            "左转", "右转", "直行", "继续", "到达目的", "到达", "终点", "目的地", "出发",
            "出口", "进入", "驶入", "汇入"};

    private NavParse() {
    }

    /** 是否像导航播报：带距离，且有导航类关键字。 */
    public static boolean looksLikeNav(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String z = s.toLowerCase(Locale.ROOT);
        boolean kw = z.contains("剩余") || z.contains("前方") || z.contains("导航") || z.contains("行驶")
                || z.contains("到达") || z.contains("转") || z.contains("直行");
        return kw && PKM.matcher(s).find();
    }

    /** 解析成固定 8 元组（字典序见 ARROW..DEST 常量），任何字段都可能为空字符串。 */
    public static String[] parse(String s) {
        String[] out = new String[N];
        for (int i = 0; i < N; i++) {
            out[i] = "";
        }
        if (s == null || s.isEmpty()) {
            return out;
        }
        String remain = after(s, "剩余", PKM);
        String ahead = after(s, "前方", PKM);
        out[REMAIN] = norm(remain);
        out[NEXT] = norm(ahead.isEmpty() ? first(s, PKM) : ahead);
        out[ETA] = etaOf(first(s, PMIN), first(s, PHOUR));
        out[CLOCK] = first(s, PCLOCK);
        out[ARROW] = arrowOf(s);
        out[TURN] = turnOf(s);
        out[ROAD] = roadOf(s);
        out[DEST] = destOf(s);
        return out;
    }

    /* ---------------- 内部工具 ---------------- */

    private static String after(String s, String kw, Pattern p) {
        int i = s.indexOf(kw);
        if (i < 0) {
            return "";
        }
        int a = i + kw.length();
        int b = Math.min(s.length(), a + 24);
        if (a >= b) {
            return "";
        }
        Matcher m = p.matcher(s.substring(a, b));
        return m.find() ? m.group(0) : "";
    }

    private static String first(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(0) : "";
    }

    /** 「5.6公里」→「5.6 km」；「300米」→「300 m」。 */
    public static String norm(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "";
        }
        String v = s.trim();
        Matcher m = PKM.matcher(v);
        if (!m.find()) {
            return v;
        }
        String unit = m.group(2);
        boolean big = unit.contains("公") || unit.contains("千") || unit.equalsIgnoreCase("km");
        return m.group(1) + (big ? " km" : " m");
    }

    private static String etaOf(String mins, String hours) {
        if (mins != null && !mins.isEmpty()) {
            Matcher m = PMIN.matcher(mins);
            return m.find() ? (m.group(1) + " 分钟") : mins;
        }
        if (hours != null && !hours.isEmpty()) {
            Matcher m = PHOUR.matcher(hours);
            return m.find() ? (m.group(1) + " 小时") : hours;
        }
        return "";
    }

    private static String arrowOf(String s) {
        int best = Integer.MAX_VALUE;
        String icon = "\u2191";
        for (String[] t : TURNS) {
            int i = s.indexOf(t[0]);
            if (i >= 0 && i < best) {
                best = i;
                icon = t[1];
            }
        }
        return icon;
    }

    /** 转向短语：以箭头关键字为中心截一小段（导航主指令通常在最前面）。 */
    private static String turnOf(String s) {
        for (String kw : TURN_KWS) {
            int i = s.indexOf(kw);
            if (i < 0) {
                continue;
            }
            int a = Math.max(0, i - 12);
            int b = Math.min(s.length(), i + kw.length() + 12);
            String cut = s.substring(a, b).replace("|", " ").trim();
            if (!cut.isEmpty()) {
                return cut;
            }
        }
        return "";
    }

    /** 道路名：「进入/驶入/汇入/沿」后面的那一段。 */
    private static String roadOf(String s) {
        String[] kws = {"进入", "驶入", "汇入", "沿"};
        for (String kw : kws) {
            int i = s.indexOf(kw);
            if (i < 0) {
                continue;
            }
            int a = i + kw.length();
            while (a < s.length() && isStop(s.charAt(a))) {   // 「沿 滨江大道」中间的空格
                a++;
            }
            int b = a;
            while (b < s.length() && b - a < 18 && !isStop(s.charAt(b))) {
                b++;
            }
            String road = s.substring(a, b).trim();
            if (road.length() >= 2) {
                return "沿".equals(kw) ? ("沿 " + road) : road;
            }
        }
        return "";
    }

    /** 目的地：「到 X」「前往 X」「目的地 X」。 */
    private static String destOf(String s) {
        String[] kws = {"目的地", "前往", "到"};
        for (String kw : kws) {
            int i = s.indexOf(kw);
            if (i < 0) {
                continue;
            }
            int a = i + kw.length();
            while (a < s.length() && isSkip(s.charAt(a))) {
                a++;
            }
            int b = a;
            while (b < s.length() && b - a < 14 && !isStop(s.charAt(b))) {
                b++;
            }
            String d = s.substring(a, b).trim();
            if (d.length() >= 2 && !d.contains("公里") && !d.contains("分钟")
                    && !d.contains("米") && !d.contains("转") && !d.contains("km")) {
                return d;
            }
        }
        return "";
    }

    private static boolean isStop(char c) {
        return c == '|' || c == '，' || c == ',' || c == '。' || c == '·' || c == ' '
                || c == '\t' || c == '；' || c == ';' || c == '！' || c == '!';
    }

    private static boolean isSkip(char c) {
        return c == ' ' || c == '：' || c == ':' || c == '达' || c == '了' || c == '在';
    }
}

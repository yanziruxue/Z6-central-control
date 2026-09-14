package com.l6.carmedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析（纯逻辑，零 Android 依赖 → 可在桌面 JVM 上单测）。
 *
 * 支持一行多时间标签（[00:12.00][01:20.00]副歌），支持 [mm:ss] / [mm:ss.xx] / [mm:ss:xx]，
 * 顺序打乱时会按时间重排；整段没有任何时间标签时按「纯文本歌词」返回（t = -1）。
 */
public final class LrcParse {

    public static final class Line {
        public final int t;      // 毫秒；-1 表示无时间轴
        public final String s;

        public Line(int t, String s) {
            this.t = t;
            this.s = s;
        }
    }

    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** 制作人员信息行，歌词区不展示 */
    private static final String[] CREDITS = {"作词", "作曲", "编曲", "制作人", "制作", "监制", "出品",
            "录音", "混音", "母带", "吉他", "贝斯", "鼓", "键盘", "和声", "统筹", "发行",
            "OP", "SP", "策划", "配唱", "弦乐", "合声", "混音师", "录音师"};

    private static final int MAX_LINES = 300;

    private LrcParse() {
    }

    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) {
            return out;
        }
        String norm = lrc.replace("\r\n", "\n").replace('\r', '\n');
        boolean anyTag = TAG.matcher(norm).find();
        for (String raw : norm.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = TAG.matcher(s);
            List<Integer> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                int frac = 0;
                String f = m.group(3);
                if (f != null) {
                    if (f.length() == 1) {
                        frac = safeInt(f) * 100;
                    } else if (f.length() == 2) {
                        frac = safeInt(f) * 10;
                    } else {
                        frac = safeInt(f.substring(0, 3));
                    }
                }
                times.add(safeInt(m.group(1)) * 60000 + safeInt(m.group(2)) * 1000 + frac);
                end = m.end();
            }
            String text = s.substring(Math.min(end, s.length())).trim();
            if (text.isEmpty() || isCredit(text)) {
                continue;
            }
            if (times.isEmpty()) {
                if (!anyTag) {
                    out.add(new Line(-1, text));
                }
                continue;
            }
            for (int t : times) {
                out.add(new Line(t, text));
            }
            if (out.size() > MAX_LINES) {
                break;
            }
        }
        if (anyTag) {
            Collections.sort(out, new Comparator<Line>() {
                @Override
                public int compare(Line a, Line b) {
                    return Integer.compare(a.t, b.t);
                }
            });
        }
        return out;
    }

    /** 作词/作曲/编曲… 这类制作信息行不放进歌词区。 */
    public static boolean isCredit(String s) {
        if (s.length() > 40) {
            return false;
        }
        for (String c : CREDITS) {
            if (s.startsWith(c) || s.startsWith("[" + c)) {
                return true;
            }
        }
        return false;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return 0;
        }
    }
}

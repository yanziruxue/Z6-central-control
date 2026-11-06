package com.l6.carmedia.nat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 应用名「首字母」分组与排序（v2.0.32，零第三方依赖、纯逻辑类，便于单测）。
 *
 * <p>规则：
 * <ul>
 *   <li>英文 / 数字开头的名称：取大写首字母 A–Z；数字与符号归 {@code #}（排最后）。</li>
 *   <li>中文：用 {@link Collator}(Locale.CHINA) 的拼音序，与 23 个拼音首字母边界字
 *       二分比较得出 A–Z（汉语拼音没有 I / U / V 开头的音节，故边界字共 23 个）。</li>
 *   <li>排序主键 = 首字母组序号，次键 = 中文 Collator 全名比较，末键 = 包名（保证稳定）。</li>
 * </ul>
 *
 * <p>注：个别 ROM 的 ICU 中文排序规则可能不是拼音（如笔画），此时分组会略有偏差，
 * 但绝不会抛异常导致抽屉打不开；{@link Collator} 全部在 try-catch 内降级为 {@code #}。
 */
public final class AppSorter {

    /** 拼音首字母边界字：A B C D E F G H J K L M N O P Q R S T W X Y Z（无 I/U/V）。 */
    private static final String[] BOUND = {
            "阿", "八", "擦", "搭", "蛾", "发", "噶", "哈", "击", "喀", "垃", "妈",
            "拿", "哦", "啪", "期", "然", "撒", "塌", "挖", "昔", "压", "匝"
    };
    /** 与 {@link #BOUND} 一一对应的分组字母。 */
    private static final String[] LETTER = {
            "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "W", "X", "Y", "Z"
    };
    /** 非字母分组。 */
    public static final String OTHER = "#";

    private static final Collator CN = Collator.getInstance(Locale.CHINA);

    private AppSorter() {
    }

    /** 显示名：应用名为空时退回包名。 */
    public static String label(Def.App a) {
        if (a == null) return "";
        if (a.name != null && !a.name.isEmpty()) return a.name;
        return a.pkg == null ? "" : a.pkg;
    }

    /** 取名称首字母分组标签：A–Z 或 {@link #OTHER}。任何异常一律降级为 {@link #OTHER}。 */
    public static String initialOf(String name) {
        try {
            if (name == null || name.isEmpty()) return OTHER;
            char ch = name.charAt(0);
            if (ch >= 'a' && ch <= 'z') return String.valueOf((char) (ch - 'a' + 'A'));
            if (ch >= 'A' && ch <= 'Z') return String.valueOf(ch);
            if (isHan(ch)) return hanInitial(String.valueOf(ch));
            return OTHER;
        } catch (Throwable ignored) {
            return OTHER;
        }
    }

    /** 分组序号：A=0 … Z=25，{@link #OTHER}=26。 */
    public static int rank(String initial) {
        if (initial == null || initial.isEmpty()) return 26;
        char ch = initial.charAt(0);
        if (ch >= 'A' && ch <= 'Z') return ch - 'A';
        return 26;
    }

    /** 返回排好序的新列表，不修改传入的缓存列表（MainActivity.allApps() 的缓存顺序不受影响）。 */
    public static List<Def.App> sort(List<Def.App> src) {
        List<Def.App> out = new ArrayList<>();
        if (src != null) out.addAll(src);
        out.sort(new Comparator<Def.App>() {
            @Override
            public int compare(Def.App x, Def.App y) {
                String nx = label(x), ny = label(y);
                int r = Integer.compare(rank(initialOf(nx)), rank(initialOf(ny)));
                if (r != 0) return r;
                try {
                    r = CN.compare(nx, ny);
                } catch (Throwable ignored) {
                    r = 0;
                }
                if (r != 0) return r;
                String px = x == null || x.pkg == null ? "" : x.pkg;
                String py = y == null || y.pkg == null ? "" : y.pkg;
                return px.compareTo(py);
            }
        });
        return out;
    }

    private static boolean isHan(char ch) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(ch);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /** 用拼音序边界字二分定位汉字所属字母。 */
    private static String hanInitial(String s) {
        if (CN.compare(s, BOUND[0]) < 0) return OTHER;
        int lo = 0, hi = BOUND.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (CN.compare(BOUND[mid], s) <= 0) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return LETTER[ans];
    }
}

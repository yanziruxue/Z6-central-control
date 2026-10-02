package com.l6.carmedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 快照差异比较（纯逻辑，零 Android 依赖）。
 *
 * <p>「车机信号采集」的核心只有一件事：把「上一秒的全量快照」和「这一秒的全量快照」比出差异，
 * 差异就是「车机在这一秒里发生了什么」。抽成纯函数才能在普通 JVM 上回归 ——
 * 车机上没法反复按车门来复现一次采集。
 *
 * <p>快照是**扁平结构**：key = {@code "来源:键名"}（如 {@code "prop:persist.sys.door.fl"}），
 * value = 字符串形式的取值。用扁平 Map 而不是嵌套 JSON，是为了 diff 一次遍历就能完成。
 */
public final class SigDiff {

    /** 键的值变了 —— 最关心的一类，车机信号基本都表现为「值变了」。 */
    public static final int CHANGED = 0;
    /** 键是新出现的。 */
    public static final int ADDED = 1;
    /** 键从前一次快照里消失（如某个属性被删掉）。 */
    public static final int REMOVED = 2;

    /** 一条变化。 */
    public static final class Change {
        /** 完整键，形如 {@code "prop:persist.sys.door.fl"}。 */
        public final String key;
        /** 旧值；{@link #ADDED} 时为 null。 */
        public final String from;
        /** 新值；{@link #REMOVED} 时为 null。 */
        public final String to;
        /** {@link #CHANGED} / {@link #ADDED} / {@link #REMOVED}。 */
        public final int kind;

        public Change(String key, String from, String to, int kind) {
            this.key = key;
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        /** 来源前缀（冒号前），如 {@code prop} / {@code set.global} / {@code audio}。 */
        public String src() {
            return part(0);
        }

        /** 键名（冒号后）。 */
        public String name() {
            return part(1);
        }

        private String part(int which) {
            if (key == null) {
                return "";
            }
            int i = key.indexOf(':');
            if (i < 0) {
                return which == 0 ? "" : key;
            }
            return which == 0 ? key.substring(0, i) : key.substring(i + 1);
        }

        /** 人类可读一行（写本地日志用，字段顺序与页面渲染一致）。 */
        public String describe() {
            if (kind == ADDED) {
                return name() + ": (无) → " + to;
            }
            if (kind == REMOVED) {
                return name() + ": " + from + " → (消失)";
            }
            return name() + ": " + from + " → " + to;
        }
    }

    private SigDiff() {
    }

    /** 不含忽略项的 diff。 */
    public static List<Change> diff(Map<String, String> old, Map<String, String> now) {
        return diff(old, now, Collections.<String>emptySet());
    }

    /**
     * 比较两份快照，返回**按 key 升序**的变化列表 —— 顺序稳定，两次采集的结果才能逐行比对。
     *
     * @param ignore 要跳过的 key（脏键：每秒都在变、对判断车辆信号毫无帮助的那些，
     *               例如采集自身的运行时长、内存余量、播放进度）
     */
    public static List<Change> diff(Map<String, String> old, Map<String, String> now, Set<String> ignore) {
        Map<String, String> o = (old == null) ? Collections.<String, String>emptyMap() : old;
        Map<String, String> n = (now == null) ? Collections.<String, String>emptyMap() : now;
        Set<String> ig = (ignore == null) ? Collections.<String>emptySet() : ignore;

        // 先放 TreeMap：added/changed/removed 三类混在一起也能保证整体按 key 有序
        TreeMap<String, Change> out = new TreeMap<>();
        for (Map.Entry<String, String> e : n.entrySet()) {
            String k = e.getKey();
            if (k == null || ig.contains(k)) {
                continue;
            }
            if (!o.containsKey(k)) {
                out.put(k, new Change(k, null, e.getValue(), ADDED));
            } else if (!eq(o.get(k), e.getValue())) {
                out.put(k, new Change(k, o.get(k), e.getValue(), CHANGED));
            }
        }
        for (Map.Entry<String, String> e : o.entrySet()) {
            String k = e.getKey();
            if (k == null || ig.contains(k)) {
                continue;
            }
            if (!n.containsKey(k)) {
                out.put(k, new Change(k, e.getValue(), null, REMOVED));
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 关键字过滤：从快照里挑出「跟车辆有关」的键，**按 key 升序**。
     *
     * <p>用途：采集一开始就把「这台车机可能暴露的车辆信号」列出来 —— 即使这一轮什么都没变，
     * 也能从清单看出<b>能挖什么</b>（很多车机把车门/挡位/车速放在 system property 里）。
     *
     * <p>匹配用**词边界**而不是裸 {@code indexOf}：否则 {@code acc} 会命中 {@code accessibility}、
     * {@code lock} 会命中 {@code clock}/{@code block}、{@code turn} 会命中 {@code return}，
     * 清单会被垃圾键淹没。边界 = 非字母数字（{@code .}、{@code _}、{@code -} 都算边界）。
     */
    public static List<String> match(Map<String, String> snap, String[] keywords) {
        List<String> out = new ArrayList<>();
        if (snap == null || keywords == null || keywords.length == 0) {
            return out;
        }
        for (String k : new TreeSet<>(snap.keySet())) {
            if (k == null) {
                continue;
            }
            String low = k.toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (hit(low, kw)) {
                    out.add(k);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 词边界匹配（两边入参都应已小写）。{@code hit("ro.car.door_fl", "door") == true}，
     * 但 {@code hit("persist.sys.acceleration", "acc") == false}。
     */
    public static boolean hit(String lowKey, String lowKeyword) {
        if (lowKey == null || lowKeyword == null || lowKeyword.isEmpty()) {
            return false;
        }
        int i = lowKey.indexOf(lowKeyword);
        while (i >= 0) {
            boolean left = (i == 0) || !alnum(lowKey.charAt(i - 1));
            int j = i + lowKeyword.length();
            boolean right = (j >= lowKey.length()) || !alnum(lowKey.charAt(j));
            if (left && right) {
                return true;
            }
            i = lowKey.indexOf(lowKeyword, i + 1);
        }
        return false;
    }

    private static boolean alnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** null 安全的相等判断。 */
    public static boolean eq(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}

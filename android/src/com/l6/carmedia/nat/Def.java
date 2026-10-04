package com.l6.carmedia.nat;

import java.util.Locale;

/**
 * 原生层的「常量与已知事实」单一真源。
 *
 * ★ {@link #APP_MAP} 必须与 MainActivity 里的同名字段**共用这一份**：
 *   两边各存一份的话，改一边忘另一边 ⇒ 「设置页认得酷狗、主页 dock 认不得」这类幽灵问题。
 *   MainActivity 已改为直接引用这里（`private static final String[][] APP_MAP = Def.APP_MAP;`）。
 */
public final class Def {
    private Def() {}

    /** 主页三栏的顺序/显示键（与 JS 版 settings.homeMode 的值域一致） */
    public static final String[] PANES = {"state", "nav", "music"};

    /** 主页栏位中文名（设置页「主页模式」与拖动排序的提示文案都用它） */
    public static final String[][] PANE_NAMES = {
            {"state", "状态"},
            {"nav", "导航"},
            {"music", "音乐"},
    };

    public static String paneName(String k) {
        for (String[] p : PANE_NAMES) if (p[0].equals(k)) return p[1];
        return k;
    }

    /** 栏位在 PANES 里的下标；不是合法栏位返回 -1 */
    public static int paneIndex(String k) {
        for (int i = 0; i < PANES.length; i++) if (PANES[i].equals(k)) return i;
        return -1;
    }

    /** 车机本地音乐源（恒定可用，不需要装 App） */
    public static final String[][] LOCAL_MUSIC = {
            {"usb", "U盘", "\uD83D\uDCBE"},
            {"bt", "蓝牙", "\uD83D\uDD35"},
    };

    public static boolean isLocalMusic(String key) {
        for (String[] m : LOCAL_MUSIC) if (m[0].equals(key)) return true;
        return false;
    }

    /** 已知车机 App 映射：包名 -> {key, 显示名, 图标 emoji, 类型} */
    public static final String[][] APP_MAP = {
            {"com.kugou.android",        "kugou",    "酷狗音乐",       "\uD83C\uDFB5", "music"},
            {"com.kugou.android.car",    "kugou",    "酷狗音乐(车机)",  "\uD83C\uDFB5", "music"},
            {"com.tencent.qqmusic",      "qq",       "QQ音乐",         "\uD83C\uDFB6", "music"},
            {"com.tencent.qqmusiccar",   "qq",       "QQ音乐(车机)",    "\uD83C\uDFB6", "music"},
            {"cn.kuwo.player",           "kuwo",     "酷我音乐",       "\uD83C\uDFBC", "music"},
            {"com.netease.cloudmusic",   "netease",  "网易云音乐",      "\uD83C\uDFA7", "music"},
            {"com.ximalaya.ting.android", "ximalaya", "喜马拉雅",       "\uD83D\uDCFB", "music"},
            {"com.autonavi.amap",        "amap",     "高德地图",       "\uD83E\uDDED", "nav"},
            {"com.autonavi.amapauto",    "amap",     "高德地图(车机)",  "\uD83E\uDDED", "nav"},
            {"com.baidu.BaiduMap",       "baidu",    "百度地图",       "\uD83D\uDDFA", "nav"},
            {"com.baidu.navi",           "baidu",    "百度导航",       "\uD83D\uDDFA", "nav"},
            {"com.tencent.map",          "tencent",  "腾讯地图",       "\uD83D\uDEA9", "nav"},
    };

    /** 短 key -> 包名（认不出返回 null） */
    public static String keyToPkg(String keyOrPkg) {
        if (keyOrPkg == null || keyOrPkg.isEmpty()) return null;
        if (keyOrPkg.indexOf('.') > 0) return keyOrPkg;
        for (String[] m : APP_MAP) if (m[1].equals(keyOrPkg)) return m[0];
        return null;
    }

    /** 包名 -> 白名单项（认不出返回 null） */
    public static String[] byPkg(String pkg) {
        if (pkg == null) return null;
        for (String[] m : APP_MAP) if (m[0].equalsIgnoreCase(pkg)) return m;
        return null;
    }

    /**
     * 低置信度归类：仅按名称/包名关键词猜音乐或导航。
     * 文案与 JS 版 guessType 保持一致（含各类中文关键词的 unicode 转义，避免源码编码差异）。
     * ★ 注释里不能出现「反斜杠 + u」这种写法 —— javac 的 Unicode 转义预处理发生在词法分析
     *   之前，注释里的它也会被当转义解析，直接报「非法 Unicode 转义」编译失败。
     */
    public static String guessType(String label, String pkg) {
        String z = ((label == null ? "" : label) + " " + (pkg == null ? "" : pkg)).toLowerCase(Locale.ROOT);
        if (z.contains("\u97f3\u4e50") || z.contains("music") || z.contains("radio")
                || z.contains("\u542c\u4e66") || z.contains("song") || z.contains("fm") || z.contains("\u7535\u53f0")
                || z.contains("\u871c\u67d0") || z.contains("xmly") || z.contains("kugou")
                || z.contains("qqmusic") || z.contains("netease") || z.contains("spotify")
                || z.contains("podcast") || z.contains("\u64ad\u5ba2")
                || z.contains("\u871c\u8702") || z.contains("\u8702\u9e1f") || z.contains("ximalaya")) {
            return "music";
        }
        if (z.contains("\u5730\u56fe") || z.contains("\u5bfc\u822a") || z.contains("map")
                || z.contains("navi") || z.contains("gps") || z.contains("amap")
                || z.contains("baidumap") || z.contains("tencentmap") || z.contains("\u9ad8\u5fb7")
                || z.contains("\u767e\u5ea6\u5730\u56fe") || z.contains("\u817e\u8baf\u5730\u56fe")) {
            return "nav";
        }
        return "other";
    }

    /** 类型对应的占位 emoji（拿不到真图标时用） */
    public static String typeEmoji(String type) {
        if ("music".equals(type)) return "\uD83C\uDFB5";
        if ("nav".equals(type)) return "\uD83E\uDDED";
        return "\uD83D\uDCE6";
    }

    /**
     * ★ 高德两个包的显示名必须分开：PackageManager 给车机版/手机版的 label 都叫「高德地图」，
     *   不区分的话「导航源」里会出现两个一模一样的高德，选错就拉错 App。
     */
    public static String navLabelOf(String pkg, String name) {
        String n = name == null ? "" : name;
        if ("com.autonavi.amapauto".equals(pkg)) return n.contains("车机版") ? n : n + "（车机版）";
        if ("com.autonavi.minimap".equals(pkg)) return n.contains("手机版") ? n : n + "（手机版）";
        return n;
    }

    /** 一个可启动应用在原生侧的统一描述 */
    public static final class App {
        public String pkg;
        public String key;      // 白名单短 key；白名单外为包名本身
        public String name;
        public String emoji;
        public String type;     // music / nav / other

        public App() {}

        public App(String pkg, String key, String name, String emoji, String type) {
            this.pkg = pkg;
            this.key = key;
            this.name = name;
            this.emoji = emoji;
            this.type = type;
        }

        /** 「源」取值：白名单内用短 key，否则用包名（与 JS 版 (a.key||a.pkg) 同义） */
        public String srcKey() {
            return (key == null || key.isEmpty()) ? pkg : key;
        }
    }
}

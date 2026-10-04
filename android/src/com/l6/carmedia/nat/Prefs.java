package com.l6.carmedia.nat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * 设置模型 + 持久化（原生版）。
 *
 * ★ 为什么必须新写一套：
 *   WebView 版的设置**全部**存在页面的 localStorage（key = {@code l6_settings_v1}），
 *   Java 侧一行都没有 —— 改原生后那套存储就断了。这里用 SharedPreferences 存**整份 JSON**
 *   一个键，字段名与 JS 版**逐字保持一致**，于是：
 *     ① 一次性迁移可以直接「把老 localStorage 的 JSON 灌进来」，不必逐字段翻译；
 *     ② 出问题时能拿 JS 版存档肉眼对比。
 *
 * ★ 与 JS 版的**唯一实质差异：壁纸改成「文件路径」模型**。
 *   JS 版把上传壁纸压成 dataURL 塞进 localStorage（几十 MB 配额，超了静默丢弃）；
 *   原生版按既有桥的落盘约定存成真文件：
 *       Download/L6/壁纸/静态/<文件名>   Download/L6/壁纸/动态/<文件名>
 *   启动时还会**扫这两个目录** ⇒ 用户往里拷图片/视频就直接出现在壁纸列表里。
 *   内置预设壁纸用 {@code asset:} 前缀指向 assets/wall_preset.jpg（不进 SharedPreferences）。
 */
public final class Prefs {

    private static final String SP = "l6_nat";
    private static final String K_JSON = "settings_json";
    private static final String K_LEGACY_IMPORTED = "legacy_imported";
    private static final String K_GESTURE_FIXED = "gesture_up_fixed";

    public static final String PRESET_ID = "preset";
    public static final String ASSET_PREFIX = "asset:";
    public static final String PRESET_WALL_ASSET = "asset:wall_preset.jpg";

    public static final String KIND_STATIC = "static";
    public static final String KIND_DYNAMIC = "dynamic";

    public static final int DOCK_MAX_DEFAULT = 6;

    private static Prefs inst;

    public static synchronized Prefs get(Context c) {
        if (inst == null) inst = new Prefs(c.getApplicationContext());
        return inst;
    }

    // ------------------------------------------------------------------ 字段

    /** 当前音乐源：usb / bt / 或某个应用 key（或包名） */
    public String musicSrc = "usb";
    /** 当前导航源：应用 key（或包名）；空 = 由已装导航应用自动选第一个 */
    public String navApp = "";
    /** 壁纸类型：static / dynamic */
    public String wall = KIND_STATIC;
    /** 纯色兜底（壁纸不可用时的底色） */
    public String wallColor = "#0b0e14";
    public boolean autoPlay = false;
    public boolean wallDim = true;
    /** dock 钉住的应用（包名，顺序即显示顺序） */
    public ArrayList<String> dockList = new ArrayList<>();
    public int dockMax = DOCK_MAX_DEFAULT;
    /** 四方向手势：up/down/left/right。"home"=回原桌面、"nav"=跟随导航源、""=未绑定、其它=应用key/包名 */
    public String gUp = "home", gDown = "nav", gLeft = "", gRight = "";
    /** 主页三栏顺序与显示（值域 state/nav/music） */
    public ArrayList<String> homeOrder = new ArrayList<>();
    public ArrayList<String> homeOn = new ArrayList<>();
    /** 各类型当前选中的壁纸 id */
    public String pickStatic = PRESET_ID, pickDynamic = PRESET_ID;
    /** 壁纸列表（每类首项恒为 preset） */
    public final ArrayList<WallItem> staticWalls = new ArrayList<>();
    public final ArrayList<WallItem> dynamicWalls = new ArrayList<>();

    /** 一条壁纸。path 为 {@code asset:xxx} 时指内置资源，否则是绝对文件路径 */
    public static final class WallItem {
        public String id;
        public String name;
        public String path;
        public boolean vid;

        public WallItem() {}

        public WallItem(String id, String name, String path, boolean vid) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.vid = vid;
        }

        public boolean isPreset() {
            return PRESET_ID.equals(id);
        }
    }

    // ------------------------------------------------------------------ 构造

    private Context appCtx;

    private Prefs(Context c) {
        appCtx = c;
        defaults();
        load();
        scanWallDirs();
    }

    private void defaults() {
        musicSrc = "usb";
        navApp = "";
        wall = KIND_STATIC;
        wallColor = "#0b0e14";
        autoPlay = false;
        wallDim = true;
        dockList = new ArrayList<>();
        dockMax = DOCK_MAX_DEFAULT;
        gUp = "home";
        gDown = "nav";
        gLeft = "";
        gRight = "";
        homeOrder = arr("state", "nav", "music");
        homeOn = arr("state", "music");
        pickStatic = PRESET_ID;
        pickDynamic = PRESET_ID;
        staticWalls.clear();
        dynamicWalls.clear();
        staticWalls.add(new WallItem(PRESET_ID, "预设壁纸", PRESET_WALL_ASSET, false));
        dynamicWalls.add(new WallItem(PRESET_ID, "预设动态壁纸", "", true));
    }

    private static ArrayList<String> arr(String... v) {
        ArrayList<String> l = new ArrayList<>();
        for (String s : v) l.add(s);
        return l;
    }

    private SharedPreferences sp() {
        return appCtx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public boolean legacyImported() {
        return sp().getBoolean(K_LEGACY_IMPORTED, false);
    }

    public void markLegacyImported() {
        sp().edit().putBoolean(K_LEGACY_IMPORTED, true).apply();
    }

    private void load() {
        String s = sp().getString(K_JSON, null);
        if (s == null || s.isEmpty()) return;
        boolean gestureFixed = sp().getBoolean(K_GESTURE_FIXED, false);
        try {
            fromJson(new JSONObject(s), !gestureFixed);
            if (!gestureFixed) sp().edit().putBoolean(K_GESTURE_FIXED, true).apply();
        } catch (Throwable t) {
            android.util.Log.w("L6Prefs", "设置解析失败，用默认值: " + t);
        }
    }

    // ------------------------------------------------------------------ 序列化

    public void save() {
        try {
            sp().edit().putString(K_JSON, toJson().toString()).apply();
        } catch (Throwable t) {
            android.util.Log.w("L6Prefs", "设置保存失败: " + t);
        }
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("musicSrc", musicSrc);
        o.put("navApp", navApp);
        o.put("wall", wall);
        o.put("wallColor", wallColor);
        o.put("autoPlay", autoPlay);
        o.put("wallDim", wallDim);
        JSONObject dock = new JSONObject();
        dock.put("list", new JSONArray(dockList));
        dock.put("max", dockMax);
        o.put("dockApps", dock);
        JSONObject g = new JSONObject();
        g.put("up", gUp);
        g.put("down", gDown);
        g.put("left", gLeft);
        g.put("right", gRight);
        o.put("gestures", g);
        JSONObject hm = new JSONObject();
        hm.put("order", new JSONArray(homeOrder));
        hm.put("on", new JSONArray(homeOn));
        o.put("homeMode", hm);
        JSONObject pick = new JSONObject();
        pick.put("static", pickStatic);
        pick.put("dynamic", pickDynamic);
        o.put("pick", pick);
        // ★ 只存「上传项」：preset 由常量重建（与 JS 版 uploadedWalls() 同义）
        JSONObject wl = new JSONObject();
        wl.put(KIND_STATIC, itemsJson(staticWalls));
        wl.put(KIND_DYNAMIC, itemsJson(dynamicWalls));
        o.put("wallList", wl);
        return o;
    }

    private static JSONArray itemsJson(ArrayList<WallItem> list) throws Exception {
        JSONArray a = new JSONArray();
        for (WallItem it : list) {
            if (it.isPreset()) continue;
            JSONObject o = new JSONObject();
            o.put("id", it.id);
            o.put("name", it.name);
            o.put("path", it.path);
            o.put("vid", it.vid);
            a.put(o);
        }
        return a;
    }

    /** 把 JSON 读进字段；缺字段保留当前值。{@code fixGestureUp} = 允许补一次老存档的空 up。 */
    public void fromJson(JSONObject s, boolean fixGestureUp) {
        if (s == null) return;
        musicSrc = str(s, "musicSrc", musicSrc);
        navApp = str(s, "navApp", navApp);
        wall = str(s, "wall", wall);
        wallColor = str(s, "wallColor", wallColor);
        autoPlay = s.optBoolean("autoPlay", autoPlay);
        wallDim = s.optBoolean("wallDim", wallDim);

        JSONObject dock = s.optJSONObject("dockApps");
        if (dock != null) {
            ArrayList<String> l = strList(dock.optJSONArray("list"));
            if (l != null) dockList = l;
            int m = dock.optInt("max", dockMax);
            dockMax = m > 0 ? m : DOCK_MAX_DEFAULT;
        }
        JSONObject g = s.optJSONObject("gestures");
        if (g != null) {
            gUp = orEmpty(g.optString("up", gUp));
            gDown = orEmpty(g.optString("down", gDown));
            gLeft = orEmpty(g.optString("left", gLeft));
            gRight = orEmpty(g.optString("right", gRight));
        }
        // ★ 一次性迁移（沿用 JS 版 v1.5.15 语义）：老存档把 up 存成了 ""（空串也是字符串），
        //   只改默认值不生效 ⇒ 这里显式补一次；**只在为空时补**，不覆盖用户自己绑定的应用。
        if (fixGestureUp && gUp.isEmpty()) gUp = "home";

        JSONObject hm = s.optJSONObject("homeMode");
        if (hm != null) {
            ArrayList<String> o = strList(hm.optJSONArray("order"));
            ArrayList<String> on = strList(hm.optJSONArray("on"));
            if (o != null && !o.isEmpty()) homeOrder = normHome(o);
            if (on != null) homeOn = normHomeOn(on);
        }
        JSONObject pick = s.optJSONObject("pick");
        if (pick != null) {
            pickStatic = pick.optString(KIND_STATIC, pickStatic);
            pickDynamic = pick.optString(KIND_DYNAMIC, pickDynamic);
        }
        JSONObject wl = s.optJSONObject("wallList");
        if (wl != null) {
            loadWallList(wl.optJSONArray(KIND_STATIC), staticWalls, false);
            loadWallList(wl.optJSONArray(KIND_DYNAMIC), dynamicWalls, true);
        }
        fixPick();
    }

    private void loadWallList(JSONArray a, ArrayList<WallItem> into, boolean kindVid) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty() || PRESET_ID.equals(id)) continue;
            boolean dup = false;
            for (WallItem it : into) if (it.id.equals(id)) dup = true;
            if (dup) continue;
            String path = o.optString("path", "");
            if (path.isEmpty()) continue;          // 没路径 = 不可用，不列表
            into.add(new WallItem(id, o.optString("name", id), path, o.optBoolean("vid", kindVid)));
        }
    }

    /** 兼容 JS 版存档：那边字段叫 src 且是 dataURL —— 由 LegacyImport 转成文件后再灌进来 */
    private static String str(JSONObject o, String k, String def) {
        String v = o.optString(k, null);
        return v == null ? def : v;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static ArrayList<String> strList(JSONArray a) {
        if (a == null) return null;
        ArrayList<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, null);
            if (v != null) l.add(v);
        }
        return l;
    }

    /** 主页三栏顺序归一化：只认 state/nav/music，缺的补到尾部 */
    public static ArrayList<String> normHome(ArrayList<String> in) {
        ArrayList<String> out = new ArrayList<>();
        for (String k : in) if (Def.paneIndex(k) >= 0 && !out.contains(k)) out.add(k);
        for (String k : Def.PANES) if (!out.contains(k)) out.add(k);
        return out;
    }

    private static ArrayList<String> normHomeOn(ArrayList<String> in) {
        ArrayList<String> out = new ArrayList<>();
        for (String k : in) if (Def.paneIndex(k) >= 0 && !out.contains(k)) out.add(k);
        // ★ 三项全取消 → 自动回默认（JS 版 hmToggle / hmCfg 各兜一层，这里同样兜住）
        if (out.isEmpty()) out = arr("state", "music");
        return out;
    }

    /** 选中项若已不存在（上传项被删）→ 回落第 0 项 */
    private void fixPick() {
        if (!hasWall(staticWalls, pickStatic)) pickStatic = firstId(staticWalls);
        if (!hasWall(dynamicWalls, pickDynamic)) pickDynamic = firstId(dynamicWalls);
    }

    private static boolean hasWall(ArrayList<WallItem> l, String id) {
        for (WallItem it : l) if (it.id.equals(id)) return true;
        return false;
    }

    private static String firstId(ArrayList<WallItem> l) {
        return l.isEmpty() ? PRESET_ID : l.get(0).id;
    }

    // ------------------------------------------------------------------ 壁纸

    public ArrayList<WallItem> walls(String kind) {
        return KIND_DYNAMIC.equals(kind) ? dynamicWalls : staticWalls;
    }

    public String pickId(String kind) {
        return KIND_DYNAMIC.equals(kind) ? pickDynamic : pickStatic;
    }

    public void selectWall(String kind, String id) {
        if (KIND_DYNAMIC.equals(kind)) pickDynamic = id;
        else pickStatic = id;
        save();
    }

    /** 当前生效的壁纸项（依 wall 类型 + 对应 pick）。找不到返回 preset，绝不返回 null */
    public WallItem currentWall() {
        ArrayList<WallItem> l = walls(wall);
        String id = pickId(wall);
        for (WallItem it : l) if (it.id.equals(id)) return it;
        for (WallItem it : l) if (it.isPreset()) return it;
        return new WallItem(PRESET_ID, "预设壁纸", PRESET_WALL_ASSET, false);
    }

    public static boolean isVideo(WallItem it) {
        if (it == null) return false;
        if (it.vid) return true;
        String p = it.path == null ? "" : it.path.toLowerCase(Locale.ROOT);
        return p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".mkv")
                || p.endsWith(".mov") || p.endsWith(".3gp");
    }

    /** 壁纸目录：Download/L6/壁纸/{静态,动态} —— 与既有桥 saveWallpaper 的落盘约定一致 */
    public static File wallDir(String kind) {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File d = new File(new File(new File(base, "L6"), "\u58c1\u7eb8"),
                KIND_DYNAMIC.equals(kind) ? "\u52a8\u6001" : "\u9759\u6001");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 扫壁纸目录，把用户手工拷进去的图片/视频补进列表。
     * id 取「s_/d_ + 文件名」，稳定可复现 ⇒ 迁移来的 pick 不会指空。
     */
    public void scanWallDirs() {
        scanDir(wallDir(KIND_STATIC), staticWalls);
        scanDir(wallDir(KIND_DYNAMIC), dynamicWalls);
        fixPick();
    }

    private static void scanDir(File dir, ArrayList<WallItem> into) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            String low = n.toLowerCase(Locale.ROOT);
            boolean vid = low.endsWith(".mp4") || low.endsWith(".webm") || low.endsWith(".mkv")
                    || low.endsWith(".mov") || low.endsWith(".3gp");
            boolean img = low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                    || low.endsWith(".webp") || low.endsWith(".bmp") || low.endsWith(".gif");
            if (!vid && !img) continue;
            String id = (vid ? "d_" : "s_") + n;
            boolean dup = false;
            for (WallItem it : into) if (it.id.equals(id)) dup = true;
            if (dup) continue;
            into.add(new WallItem(id, n, f.getAbsolutePath(), vid));
        }
    }

    /** 上传壁纸落盘（返回落成的文件；失败返回 null） */
    public static File writeWallFile(String kind, byte[] data, String filename) {
        try {
            File dir = wallDir(kind);
            String n = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (n.isEmpty()) n = "wall_" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir, n);
            int i = 1;
            while (out.exists()) {
                int dot = n.lastIndexOf('.');
                String stem = dot > 0 ? n.substring(0, dot) : n;
                String ext = dot > 0 ? n.substring(dot) : "";
                out = new File(dir, stem + "(" + (i++) + ")" + ext);
            }
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(data);
            fo.close();
            return out;
        } catch (Throwable t) {
            android.util.Log.w("L6Prefs", "壁纸落盘失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ dock / 主页模式

    public boolean isDockPinned(String pkg) {
        return pkg != null && dockList.contains(pkg);
    }

    /** 钉入 / 移除；返回 true = 现在是钉住的。超上限返回 false 且不改动。 */
    public boolean toggleDockPin(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (dockList.contains(pkg)) {
            dockList.remove(pkg);
            save();
            return false;
        }
        if (dockList.size() >= dockMax) return false;
        dockList.add(pkg);
        save();
        return true;
    }

    /** 主页三栏：顺序 + 显示（LinkedHashMap 保序，供视图层直接按序摆栏） */
    public LinkedHashMap<String, Boolean> homeState() {
        LinkedHashMap<String, Boolean> m = new LinkedHashMap<>();
        for (String k : homeOrder) m.put(k, homeOn.contains(k));
        return m;
    }

    public void setHomeOn(LinkedHashSet<String> on) {
        ArrayList<String> l = new ArrayList<>();
        for (String k : homeOrder) if (on.contains(k)) l.add(k);
        if (l.isEmpty()) l = arr("state", "music");       // 全取消 → 回默认
        homeOn = l;
        save();
    }

    public void setHomeOrder(ArrayList<String> o) {
        homeOrder = normHome(o);
        ArrayList<String> keep = new ArrayList<>(homeOn);
        homeOn = new ArrayList<>();
        for (String k : homeOrder) if (keep.contains(k)) homeOn.add(k);
        if (homeOn.isEmpty()) homeOn = arr("state", "music");
        save();
    }
}

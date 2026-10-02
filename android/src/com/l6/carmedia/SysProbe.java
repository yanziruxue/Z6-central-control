package com.l6.carmedia;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 车机系统「只读快照」采集器。
 *
 * <p>做「信号采集」时的取数层：把车机（Android）系统当前可读的各类数据，压成一张
 * **扁平的 {@code Map<String,String>}**（key 形如 {@code "prop:persist.sys.door.fl"}），
 * 交给 {@link SigDiff} 逐秒比差异。差异就是「用户操作车辆按钮时，系统吐出了什么」。
 *
 * <p>只读、不申请任何新权限：
 * <ul>
 *   <li>{@code Build.*} / {@code /proc/*} / {@code getprop} / {@code StatFs} / {@code Settings.*}
 *       —— 普通 App 可读，车辆信号最可能藏在这里；</li>
 *   <li>媒体会话 —— 依赖已有的「通知使用权」；</li>
 *   <li>logcat / dumpsys / Car API —— **能不能读要实测**（普通 App 一般读不到
 *       {@code READ_LOGS}），探不到就如实记为 {@code 不可读}，这正是「探索 API 逻辑」的一部分。</li>
 * </ul>
 *
 * <p><b>分组逐块 {@code try/catch}</b>：任何一组取不到都只是少几项，绝不让一次
 * {@code SecurityException} 把整份快照拖垮 —— 车机上 {@code Settings}、{@code StatFs}、
 * {@code Display} 都可能抛。
 */
public final class SysProbe {

    /**
     * 车辆相关关键字（用来从全量快照里挑出「可能是车辆信号」的键）。
     * 刻意避免过短的词（{@code acc} 会撞 {@code accessibility}、{@code park} 会撞 {@code spark}），
     * 匹配时另由 {@link SigDiff#hit} 做词边界约束，双重保险。
     */
    public static final String[] VEHICLE_KEYWORDS = {
            "door", "window", "vehicle", "gear", "speed", "mileage", "odometer", "odo",
            "ignition", "acc_status", "brake", "seat", "belt", "trunk", "hood",
            "mirror", "voltage", "tire", "wheel", "steer", "hvac", "defrost",
            "wiper", "horn", "airbag", "parking", "carlock", "car_lock", "key_status",
    };

    private SysProbe() {
    }

    /* ==================== 全量快照 ==================== */

    /**
     * 采一份全量快照。**逐组 {@code try/catch}**，单组失败只少几项。
     * 刻意不写入 {@code ts} / {@code ms} 这类自身变化的字段 —— 否则每秒 diff 都会命中它们。
     */
    public static TreeMap<String, String> snapshot(Context c) {
        TreeMap<String, String> m = new TreeMap<>();
        group(m, new Runnable() { public void run() { build(m); } });
        group(m, new Runnable() { public void run() { proc(m); } });
        group(m, new Runnable() { public void run() { mem(m, c); } });
        group(m, new Runnable() { public void run() { cpu(m); } });
        group(m, new Runnable() { public void run() { disk(m); } });
        group(m, new Runnable() { public void run() { display(m, c); } });
        group(m, new Runnable() { public void run() { network(m, c); } });
        group(m, new Runnable() { public void run() { audio(m, c); } });
        group(m, new Runnable() { public void run() { media(m, c); } });
        group(m, new Runnable() { public void run() { apps(m, c); } });
        group(m, new Runnable() { public void run() { power(m, c); } });
        group(m, new Runnable() { public void run() { settings(m, c); } });
        group(m, new Runnable() { public void run() { props(m); } });
        return m;
    }

    /** 跑一组，异常吞掉但留一项标记，便于回看「哪一组在车上抛了」。 */
    private static void group(TreeMap<String, String> m, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            // 组内已各自 try/catch，这里只兜底：不打断其余组
            try {
                m.put("probe:groupErr." + System.identityHashCode(r), errOf(e));
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------- A 系统标识 ---------- */

    private static void build(TreeMap<String, String> m) {
        put(m, "sys:brand", Build.BRAND);
        put(m, "sys:model", Build.MODEL);
        put(m, "sys:device", Build.DEVICE);
        put(m, "sys:manufacturer", Build.MANUFACTURER);
        put(m, "sys:product", Build.PRODUCT);
        put(m, "sys:hardware", Build.HARDWARE);
        put(m, "sys:board", Build.BOARD);
        put(m, "sys:bootloader", Build.BOOTLOADER);
        put(m, "sys:host", Build.HOST);
        put(m, "sys:user", Build.USER);
        put(m, "sys:tags", Build.TAGS);
        put(m, "sys:type", Build.TYPE);
        put(m, "sys:id", Build.ID);
        put(m, "sys:display", Build.DISPLAY);
        put(m, "sys:fingerprint", Build.FINGERPRINT);
        put(m, "sys:android.release", Build.VERSION.RELEASE);
        put(m, "sys:android.sdk", String.valueOf(Build.VERSION.SDK_INT));
        put(m, "sys:android.codename", Build.VERSION.CODENAME);
        put(m, "sys:android.incremental", Build.VERSION.INCREMENTAL);
        put(m, "sys:android.securityPatch", Build.VERSION.SECURITY_PATCH);
        put(m, "sys:android.baseOS", Build.VERSION.BASE_OS);
        try {
            put(m, "sys:abis", Arrays.toString(Build.SUPPORTED_ABIS));
        } catch (Throwable ignored) { }
        put(m, "sys:java.vm", System.getProperty("java.vm.version"));
    }

    /* ---------- B 运行状态（/proc） ---------- */

    private static void proc(TreeMap<String, String> m) {
        put(m, "proc:uptime", firstLine("/proc/uptime"));
        put(m, "proc:loadavg", firstLine("/proc/loadavg"));
        put(m, "proc:version", firstLine("/proc/version"));
    }

    private static void mem(TreeMap<String, String> m, Context c) {
        Map<String, String> mi = kvOf("/proc/meminfo");
        put(m, "mem:totalKB", mi.get("MemTotal"));
        put(m, "mem:availKB", mi.get("MemAvailable"));
        put(m, "mem:freeKB", mi.get("MemFree"));
        put(m, "mem:cacheKB", mi.get("Cached"));
        put(m, "mem:swapTotalKB", mi.get("SwapTotal"));
        put(m, "mem:swapFreeKB", mi.get("SwapFree"));
        Runtime rt = Runtime.getRuntime();
        put(m, "mem:heapMaxKB", String.valueOf(rt.maxMemory() / 1024));
        put(m, "mem:heapUsedKB", String.valueOf((rt.totalMemory() - rt.freeMemory()) / 1024));
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo o = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(o);
            put(m, "mem:availMB", String.valueOf(o.availMem / 1048576L));
            put(m, "mem:low", String.valueOf(o.lowMemory));
            put(m, "mem:thresholdMB", String.valueOf(o.threshold / 1048576L));
        }
    }

    /* ---------- C CPU ---------- */

    private static void cpu(TreeMap<String, String> m) {
        int cores = Runtime.getRuntime().availableProcessors();
        put(m, "cpu:cores", String.valueOf(cores));
        int n = Math.min(cores, 16);
        for (int i = 0; i < n; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            put(m, "cpu:" + i + ".curKHz", one(base + "scaling_cur_freq"));
            put(m, "cpu:" + i + ".maxKHz", one(base + "cpuinfo_max_freq"));
        }
        put(m, "cpu:governor", one("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        Map<String, String> ci = kvOf("/proc/cpuinfo");
        String hw = ci.get("Hardware");
        if (hw == null || hw.isEmpty()) {
            hw = ci.get("model name");
        }
        put(m, "cpu:hardware", hw);
    }

    /* ---------- D 存储 ---------- */

    private static void disk(TreeMap<String, String> m) {
        statFs(m, "data", Environment.getDataDirectory());
        statFs(m, "root", new File("/"));
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignored) { }
        if (ext != null) {
            statFs(m, "sdcard", ext);
        }
        try {
            File storage = new File("/storage");
            File[] vols = storage.listFiles();
            if (vols != null) {
                StringBuilder sb = new StringBuilder();
                for (File f : vols) {
                    if (f == null || !f.isDirectory()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(f.getName());
                    // /storage/self 是符号链接，StatFs 会失败，交给 statFs 自己吞
                    statFs(m, "vol." + f.getName(), f);
                }
                put(m, "disk:volumes", sb.toString());
            }
        } catch (Throwable ignored) { }
    }

    private static void statFs(TreeMap<String, String> m, String name, File f) {
        try {
            StatFs s = new StatFs(f.getAbsolutePath());
            long total = s.getTotalBytes();
            long free = s.getAvailableBytes();
            put(m, "disk:" + name + ".totalMB", String.valueOf(total / 1048576L));
            put(m, "disk:" + name + ".freeMB", String.valueOf(free / 1048576L));
        } catch (Throwable ignored) { }
    }

    /* ---------- E 显示 ---------- */

    private static void display(TreeMap<String, String> m, Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            put(m, "disp:width", String.valueOf(dm.widthPixels));
            put(m, "disp:height", String.valueOf(dm.heightPixels));
            put(m, "disp:densityDpi", String.valueOf(dm.densityDpi));
            put(m, "disp:density", String.valueOf(dm.density));
            put(m, "disp:refreshRate", String.valueOf(d.getRefreshRate()));
            put(m, "disp:rotation", String.valueOf(d.getRotation()));
        }
        put(m, "disp:brightness", String.valueOf(settingInt(c, "system", Settings.System.SCREEN_BRIGHTNESS)));
        put(m, "disp:brightnessMode", String.valueOf(settingInt(c, "system", Settings.System.SCREEN_BRIGHTNESS_MODE)));
        put(m, "disp:screenOffTimeout", String.valueOf(settingInt(c, "system", Settings.System.SCREEN_OFF_TIMEOUT)));
    }

    private static int settingInt(Context c, String table, String key) {
        try {
            if ("system".equals(table)) {
                return Settings.System.getInt(c.getContentResolver(), key, -1);
            }
            if ("global".equals(table)) {
                return Settings.Global.getInt(c.getContentResolver(), key, -1);
            }
            return Settings.Secure.getInt(c.getContentResolver(), key, -1);
        } catch (Throwable e) {
            return -1;
        }
    }

    /* ---------- F 网络 ---------- */

    private static void network(TreeMap<String, String> m, Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null) {
                put(m, "net:type", ni.getTypeName());
                put(m, "net:subtype", ni.getSubtypeName());
                put(m, "net:state", String.valueOf(ni.getState()));
                put(m, "net:connected", String.valueOf(ni.isConnected()));
                put(m, "net:roaming", String.valueOf(ni.isRoaming()));
            } else {
                put(m, "net:type", "none");
                put(m, "net:connected", "false");
            }
        }
        // 本机 IPv4（按网卡名列，只记非回环的 v4）
        try {
            Enumeration<NetworkInterface> es = NetworkInterface.getNetworkInterfaces();
            while (es != null && es.hasMoreElements()) {
                NetworkInterface nif = es.nextElement();
                Enumeration<InetAddress> as = nif.getInetAddresses();
                while (as != null && as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        put(m, "net:ip." + nif.getName(), a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                put(m, "net:wifi.state", String.valueOf(wifi.getWifiState()));
                WifiInfo wi = wifi.getConnectionInfo();
                if (wi != null) {
                    put(m, "net:wifi.ssid", str(wi.getSSID()));
                    put(m, "net:wifi.bssid", str(wi.getBSSID()));
                    put(m, "net:wifi.rssi", String.valueOf(wi.getRssi()));
                    put(m, "net:wifi.linkSpeed", String.valueOf(wi.getLinkSpeed()));
                    put(m, "net:wifi.ip", intToIp(wi.getIpAddress()));
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String intToIp(int v) {
        if (v == 0) {
            return "";
        }
        return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF);
    }

    /* ---------- G 声音 ---------- */

    private static void audio(TreeMap<String, String> m, Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        String[] names = {"music", "call", "ring", "alarm", "system", "notif", "dtmf"};
        int[] streams = {
                AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_RING,
                AudioManager.STREAM_ALARM, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_DTMF,
        };
        for (int i = 0; i < streams.length; i++) {
            try {
                put(m, "audio:vol." + names[i],
                        am.getStreamVolume(streams[i]) + "/" + am.getStreamMaxVolume(streams[i]));
            } catch (Throwable ignored) { }
        }
        put(m, "audio:mode", String.valueOf(am.getMode()));
        put(m, "audio:ringer", String.valueOf(am.getRingerMode()));
        put(m, "audio:musicActive", String.valueOf(am.isMusicActive()));
        try {
            put(m, "audio:speakerOn", String.valueOf(am.isSpeakerphoneOn()));
        } catch (Throwable ignored) { }
    }

    /* ---------- H 媒体会话 ---------- */

    private static void media(TreeMap<String, String> m, Context c) {
        put(m, "media:hasAccess", String.valueOf(MediaHub.hasAccess(c)));
        JSONObject s = null;
        try {
            s = MediaHub.get(c).snapshot();
        } catch (Throwable ignored) { }
        if (s == null) {
            put(m, "media:active", "false");
            return;
        }
        put(m, "media:active", "true");
        put(m, "media:pkg", s.optString("pkg", ""));
        put(m, "media:app", s.optString("app", ""));
        put(m, "media:title", s.optString("title", ""));
        put(m, "media:artist", s.optString("artist", ""));
        put(m, "media:album", s.optString("album", ""));
        put(m, "media:playing", String.valueOf(s.optBoolean("playing", false)));
        put(m, "media:dur", String.valueOf(s.optLong("dur", 0)));
        // ⚠️ media:pos 每秒都在变，由 SignalCapture 的忽略清单排除，这里仍然记录以便实时快照使用
        put(m, "media:pos", String.valueOf(s.optLong("pos", 0)));
    }

    /* ---------- I 应用 ---------- */

    private static void apps(TreeMap<String, String> m, Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            put(m, "app:count", String.valueOf(list == null ? 0 : list.size()));
        } catch (Throwable ignored) { }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) {
                put(m, "app:defaultHome", r.activityInfo.packageName);
            }
        } catch (Throwable ignored) { }
        put(m, "app:notifyAccess", String.valueOf(MediaHub.hasAccess(c)));
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
                if (ps != null) {
                    for (ActivityManager.RunningAppProcessInfo p : ps) {
                        if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            put(m, "app:foreground", p.processName);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    /* ---------- J 电源 ---------- */

    private static void power(TreeMap<String, String> m, Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                put(m, "pwr:interactive", String.valueOf(pm.isInteractive()));
                put(m, "pwr:powerSave", String.valueOf(pm.isPowerSaveMode()));
            }
        } catch (Throwable ignored) { }
        try {
            Intent bat = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int scale = Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                put(m, "pwr:battery.level", String.valueOf(bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale));
                put(m, "pwr:battery.status", String.valueOf(bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
                put(m, "pwr:battery.plugged", String.valueOf(bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)));
                put(m, "pwr:battery.temp", String.valueOf(bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)));
                put(m, "pwr:battery.voltage", String.valueOf(bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)));
                put(m, "pwr:battery.tech", str(bat.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)));
            }
        } catch (Throwable ignored) { }
        // ⚠️ 运行时长每秒都变，由忽略清单排除；保留是为了让实时快照能算出「距上次采集过了多久」
        put(m, "pwr:uptimeMs", String.valueOf(SystemClock.elapsedRealtime()));
        put(m, "pwr:bootMs", String.valueOf(System.currentTimeMillis() - SystemClock.elapsedRealtime()));
    }

    /* ---------- K Settings 三表 ---------- */

    private static void settings(TreeMap<String, String> m, Context c) {
        putAll(m, "set.system:", safeSettings(c, "system"));
        putAll(m, "set.global:", safeSettings(c, "global"));
        putAll(m, "set.secure:", safeSettings(c, "secure"));
    }

    /**
     * 读 Settings 三表。**不能用 {@code Settings.System.getAll()}** —— 那是 {@code @hide} 的隐藏 API，
     * 公开 SDK 里根本没有（编译期就会「找不到符号」）。改为直接查 ContentProvider。
     */
    private static Map<String, String> safeSettings(Context c, String table) {
        String uri;
        if ("system".equals(table)) {
            uri = "content://settings/system";
        } else if ("global".equals(table)) {
            uri = "content://settings/global";
        } else {
            uri = "content://settings/secure";
        }
        Map<String, String> m = new java.util.HashMap<>();
        android.database.Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    android.net.Uri.parse(uri), new String[]{"name", "value"}, null, null, null);
            if (cur != null) {
                while (cur.moveToNext()) {
                    String k = cur.getString(0);
                    if (k != null) {
                        String v = cur.getString(1);
                        m.put(k, v == null ? "" : v);
                    }
                }
            }
        } catch (Throwable e) {
            // 部分厂商 ROM 限制该 Provider：取到多少算多少
        } finally {
            try {
                if (cur != null) {
                    cur.close();
                }
            } catch (Throwable ignored) { }
        }
        return m;
    }

    private static void putAll(TreeMap<String, String> m, String prefix, Map<String, String> src) {
        if (src == null) {
            return;
        }
        try {
            for (Map.Entry<String, String> e : src.entrySet()) {
                if (e.getKey() != null) {
                    put(m, prefix + e.getKey(), e.getValue());
                }
            }
        } catch (Throwable ignored) { }
    }

    /* ---------- L 系统属性（getprop 全量） ---------- */

    /**
     * {@code getprop} 全量。**这是车辆信号命中概率最高的一路** ——
     * 很多车机把车门/挡位/车速直接放在 {@code persist.sys.*} / {@code ro.*} 里。
     */
    private static void props(TreeMap<String, String> m) {
        String out = exec(8, "/system/bin/getprop");
        if (out == null || out.trim().isEmpty()) {
            out = exec(8, "getprop");
        }
        if (out == null) {
            return;
        }
        int n = 0;
        String[] lines = out.split("\n");
        for (String line : lines) {
            // 形如：[persist.sys.foo]: [bar]
            int b = line.indexOf("]: [");
            if (b <= 0 || line.charAt(0) != '[') {
                continue;
            }
            String k = line.substring(1, b);
            String v = line.substring(b + 4);
            int e = v.lastIndexOf(']');
            if (e >= 0) {
                v = v.substring(0, e);
            }
            if (k.isEmpty()) {
                continue;
            }
            m.put("prop:" + k, v);
            n++;
        }
        put(m, "prop:__count", String.valueOf(n));
    }

    /* ==================== 能力探测 ==================== */

    /**
     * 采集开始前先探一遍「这台车机到底开放了哪些接口」。
     * 输出直接写进日志与 JSON 的开头 —— 即使整段采集一个变化都没抓到，
     * 也能从这份清单看出<b>能挖什么、还差什么权限</b>。
     */
    public static JSONObject capability(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("logcat", probeVerdict(probeExec(new String[]{"/system/bin/logcat", "-d", "-t", "3", "-v", "time"})));
        } catch (Throwable e) {
            safePut(o, "logcat", errOf(e));
        }
        try {
            o.put("dumpsys", probeVerdict(probeExec(new String[]{"/system/bin/dumpsys", "-l"})));
        } catch (Throwable e) {
            safePut(o, "dumpsys", errOf(e));
        }
        try {
            JSONObject car = new JSONObject();
            car.put("class", classExists("android.car.Car"));
            car.put("automotiveFeature", hasFeature(c, "android.hardware.type.automotive"));
            car.put("pkg", hasPackage(c, "android.car"));
            car.put("carPowerPermission", perm(c, "android.car.permission.CAR_POWERTRAIN"));
            car.put("vehicleSignal", perm(c, "android.car.permission.CAR_VENDOR_EXTENSION"));
            o.put("car", car);
        } catch (Throwable e) {
            safePut(o, "car", errOf(e));
        }
        // 车辆相关属性键清单（无变化时也能看出「能挖什么」）
        try {
            TreeMap<String, String> snap = new TreeMap<>();
            props(snap);
            List<String> vk = SigDiff.match(snap, VEHICLE_KEYWORDS);
            o.put("propCount", snap.size());
            o.put("vehicleKeyCount", vk.size());
            JSONArray arr = new JSONArray();
            for (String s : vk) {
                arr.put(s);
            }
            o.put("vehicleKeys", arr);
        } catch (Throwable e) {
            safePut(o, "vehicleKeys", errOf(e));
        }
        return o;
    }

    /** 把探针原始输出压成一句结论。 */
    private static String probeVerdict(JSONObject raw) {
        if (raw == null) {
            return "未知";
        }
        if (raw.has("err") && !raw.optBoolean("ok", false) && raw.optInt("lines", 0) == 0) {
            String e = raw.optString("err", "");
            if (e.toLowerCase(Locale.ROOT).indexOf("denied") >= 0) {
                return "权限拒绝";
            }
            return "不可用（" + clip(e, 80) + "）";
        }
        if (raw.optBoolean("ok", false)) {
            return "可读（样例 " + raw.optInt("lines", 0) + " 行）";
        }
        return "不可用";
    }

    /** 跑一条命令，带超时与输出上限。null 表示进程都没起来。 */
    private static JSONObject probeExec(String[] cmd) {
        JSONObject o = new JSONObject();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            String out = readCapped(p.getInputStream(), 8192);
            String er = readCapped(p.getErrorStream(), 4096);
            p.waitFor(2500, TimeUnit.MILLISECONDS);
            String low = (out + " " + er).toLowerCase(Locale.ROOT);
            boolean denied = low.indexOf("permission denied") >= 0
                    || low.indexOf("not allowed") >= 0
                    || low.indexOf("permission denial") >= 0;
            boolean hasOut = out.trim().length() > 0;
            safePut(o, "ok", hasOut && !denied);
            safePut(o, "lines", countLines(out));
            safePut(o, "out", clip(out.trim(), 240));
            if (!er.trim().isEmpty()) {
                safePut(o, "err", clip(er.trim(), 240));
            }
        } catch (Throwable e) {
            safePut(o, "ok", false);
            safePut(o, "err", errOf(e));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) { }
            }
        }
        return o;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean hasFeature(Context c, String f) {
        try {
            return c.getPackageManager().hasSystemFeature(f);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean hasPackage(Context c, String p) {
        try {
            c.getPackageManager().getPackageInfo(p, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String perm(Context c, String p) {
        try {
            return (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) ? "granted" : "denied";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /* ==================== 小工具 ==================== */

    /** 执行命令并取 stdout（带超时）。失败返回 null。 */
    static String exec(int timeoutSec, String... cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            String out = readCapped(p.getInputStream(), 512 * 1024);
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
            return out;
        } catch (Throwable e) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) { }
            }
        }
    }

    /** 读流并截断（防止某个命令吐出海量输出把内存吃光）。 */
    static String readCapped(InputStream in, int maxChars) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() >= maxChars) {
                    sb.append("\n…(已截断)");
                    break;
                }
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) { }
        }
        return sb.toString();
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 读 /proc 风格的「Key: value」文件。 */
    private static Map<String, String> kvOf(String path) {
        Map<String, String> m = new java.util.HashMap<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line;
            while ((line = r.readLine()) != null) {
                int c = line.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                m.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) { }
        }
        return m;
    }

    private static String firstLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable e) {
            return "";
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String one(String path) {
        return firstLine(path);
    }

    private static void put(TreeMap<String, String> m, String k, String v) {
        m.put(k, v == null ? "" : v);
    }

    private static void safePut(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) { }
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String errOf(Throwable e) {
        return e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "?" : e.getMessage());
    }
}

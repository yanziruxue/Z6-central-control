package com.l6.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口探测 —— 「采集所有能检测到的接口信息」的执行体。
 *
 * <p>与 {@link SysProbe#capability} 的分工：SysProbe 探「命令行能力」（logcat/dumpsys/权限位），
 * 本类探「**可编程接口面**」——车机到底开放了哪些数据接口，逐个实测：
 * <ul>
 *   <li><b>service list</b>：系统全量 binder 服务清单（含车机/vendor 私有服务）</li>
 *   <li><b>Car API 深读</b>：反射建 {@code Car} → {@code getCarManager} → {@code getPropertyList}
 *       全量属性枚举 + 已知信号 ID 逐个 try-read + CarInfoManager 读 VIN。
 *       API 28 无 {@code android.car} 公开 SDK（29 才有），全部走反射；
 *       权限 denied 只说明授予状态，可读性必须实读才能坐实。</li>
 *   <li><b>已装包接口发现</b>：遍历所有包的声明式 receiver/service/provider，按车辆关键词
 *       过滤组件名 —— OEM 私有接口的组件名往往自带 vehicle/car 字样</li>
 *   <li><b>历史广播提取</b>：{@code dumpsys activity broadcasts} 里出现过真实 action，
 *       正则提取后武装进嗅探器（本机真发过的广播才拦得到）</li>
 *   <li><b>被动广播嗅探</b>：arm 后采集窗口内收到什么记什么</li>
 *   <li><b>Location</b>：GPS 测速可行性（providers + last known location 的 speed/age）</li>
 *   <li><b>SensorManager</b>：全传感器清单（加速度/陀螺可间接测 G）</li>
 *   <li><b>BatteryManager</b>：全 EXTRA 字段 + {@code dumpsys battery}</li>
 *   <li><b>settings list</b>：global/secure/system 三库键位扫描</li>
 * </ul>
 *
 * <p>红线（与采集主流程同标准）：**每一段独立 try/catch，任何一段炸掉只少一段信息，
 * 绝不让探测拖垮采集**。所有 exec 带超时（复用 {@link SysProbe#exec}）。
 */
public final class IfaceProbe {

    private static final String TAG = "L6Iface";

    /**
     * 已知车辆信号属性 ID（{@code android.hardware.automotive.vehicle.VehiclePropertyIds}，
     * API 28 无公开 SDK 只能写字面量）。
     *
     * ★ 覆盖用户需求全单（2026-10-24 用户确认）：仪表盘全部信息 + 门窗 + 按钮/灯光开关 +
     *   电动座椅 + 空调 + 车内外温度 + 胎压 + 里程 + 转速 + 油位 + 油温 + 瞬时油耗。
     * ★ ID 为 VHAL AIDL 常量的 best-effort 抄录：**记错只会该条 try-read 失败，无副作用**；
     *   权威证据永远是 getPropertyList() 的枚举原文（propList.ids），本表只负责起名 + 逐个试读。
     * ★ 瞬时油耗标准 VHAL 无对应属性，最接近的是自启动油耗（FUEL_CONSUMPTION_SINCE_RESTART）；
     *   电动座椅位置/加热多为 OEM 私有扩展（CAR_VENDOR_EXTENSION 权限，本车已 denied）。
     */
    private static final Object[][] KNOWN_PROPS = {
            {0x11100100, "INFO_VIN"},
            {0x11100101, "INFO_MAKE"},
            {0x11100102, "INFO_MODEL"},
            // 动力总成：油位 / 里程 / 车速 / 转速 / 油温 / 水温 / 油耗
            {0x11600203, "FUEL_LEVEL"},
            {0x11600206, "PERF_ODOMETER"},
            {0x11600207, "PERF_VEHICLE_SPEED"},
            {0x11600304, "ENGINE_RPM"},
            {0x11600305, "ENGINE_OIL_TEMP"},
            {0x11600307, "ENGINE_COOLANT_TEMP"},
            {0x1160030C, "FUEL_CONSUMPTION_SINCE_RESTART"},
            // 车身：挡位 / 点火 / 手刹
            {0x11400409, "GEAR_SELECTION"},
            {0x1140040A, "CURRENT_GEAR"},
            {0x11400411, "PARKING_BRAKE_ON"},
            {0x11400412, "IGNITION_STATE"},
            // 门窗：位置 / 移动 / 锁
            {0x11400400, "DOOR_POS"},
            {0x11400401, "DOOR_MOVE"},
            {0x11400402, "DOOR_LOCK"},
            {0x11400405, "WINDOW_POS"},
            {0x11400406, "WINDOW_MOVE"},
            {0x11400407, "WINDOW_LOCK"},
            // 灯光：状态与开关（「所有灯光开关状态」）
            {0x11400410, "TURN_SIGNAL_STATE"},
            {0x11400414, "HEADLIGHTS_STATE"},
            {0x11400415, "HIGH_BEAM_LIGHTS_STATE"},
            {0x11400416, "HAZARD_LIGHTS_STATE"},
            {0x11400417, "FOG_LIGHTS_STATE"},
            {0x11400418, "PARKING_LIGHTS_STATE"},
            {0x1140040C, "HEADLIGHTS_SWITCH"},
            {0x1140040E, "HAZARD_LIGHTS_SWITCH"},
            // 空调：设定温度 / 风量 / 风向 / AC / AUTO / 循环 / 除雾 / 当前温度
            {0x11400420, "HVAC_TEMPERATURE_SET"},
            {0x11400421, "HVAC_FAN_DIRECTION"},
            {0x11400422, "HVAC_AC_ON"},
            {0x11400423, "HVAC_AUTO_ON"},
            {0x11400425, "HVAC_FAN_SPEED"},
            {0x11400426, "HVAC_RECIRC_ON"},
            {0x11400427, "HVAC_DEFROSTER"},
            {0x11400428, "HVAC_TEMPERATURE_CURRENT"},
            // 座椅加热档位（电动座椅位置标准 VHAL 无，走 OEM 扩展面）
            {0x11400431, "HVAC_SEAT_TEMPERATURE"},
            // 环境：车外温度
            {0x11600308, "ENV_OUTSIDE_TEMPERATURE"},
            // 胎压（标准属性，逐轮 bar）
            {0x1160030D, "TIRE_PRESSURE"},
    };

    /** 车辆相关关键词（小写匹配；刻意避开过短词，与 SigDiff 同标准）。 */
    private static final String[] VEH_WORDS = {
            "vehicle", "automotive", "speed", "rpm", "tire", "tyre",
            "oil", "odo", "mileage", "gear", "battery", "vbat", "12v",
            "door", "window", "hvac", "coolant", "fuel", "car",
    };

    /** 已知可用的导航广播（高德）——武装进嗅探器作「已知活接口」参照。 */
    private static final String NAV_BCAST = "AUTONAVI_STANDARD_BROADCAST_SEND";

    /** 广播历史行里的 action 形态：act=com.foo.BAR 或裸 com.foo.BAR_ACTION（≥3 段点分路径）。 */
    private static final Pattern ACT_PAT = Pattern.compile(
            "\\b(?:[a-z][a-z0-9_]*\\.){2,}[A-Za-z0-9_]+\\b");

    private static final int MAX_ARMED = 200;
    private static final int MAX_HITS = 200;
    private static final int MAX_CANDIDATES = 100;

    /* ==================== 嗅探器状态 ==================== */

    private static final List<JSONObject> HITS = new ArrayList<>();
    private static final Object HIT_LOCK = new Object();
    private static volatile BroadcastReceiver sniffer;
    private static volatile Context armedCtx;
    private static volatile List<String> armedActions = new ArrayList<>();
    private static final List<String> discoveredCache = new ArrayList<>();

    private IfaceProbe() {
    }

    /* ==================== 主入口 ==================== */

    /** 跑一遍全量接口探测，返回可整体塞进 caiji-*.json 的 "iface" 段。本方法不抛异常。 */
    public static JSONObject probe(Context c) {
        JSONObject o = new JSONObject();
        if (c == null) {
            return o;
        }
        try {
            o.put("t", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        section(o, "services", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeServices(s);
            }
        });
        section(o, "car", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeCarApi(c, s);
            }
        });
        section(o, "pkgIfaces", new Section() {
            public void run(JSONObject s) throws Throwable {
                probePackages(c, s);
            }
        });
        section(o, "bcastHistory", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeBcastHistory(s);
            }
        });
        section(o, "location", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeLocation(c, s);
            }
        });
        section(o, "sensors", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeSensors(c, s);
            }
        });
        section(o, "battery", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeBattery(c, s);
            }
        });
        section(o, "settingsScan", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeSettings(s);
            }
        });
        try {
            JSONObject b = new JSONObject();
            b.put("armed", new JSONArray(armedActions));
            b.put("hits", new JSONArray());
            o.put("bcast", b);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 单段隔离执行：炸了只在该段记 err，不影响其它段与调用方。 */
    private interface Section {
        void run(JSONObject s) throws Throwable;
    }

    private static void section(JSONObject o, String name, Section sec) {
        JSONObject s = new JSONObject();
        try {
            sec.run(s);
        } catch (Throwable e) {
            try {
                s.put("err", e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "?" : e.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        try {
            o.put(name, s);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== ① service list：全量 binder 服务 ==================== */

    private static void probeServices(JSONObject s) throws Throwable {
        String out = SysProbe.exec(8, "service", "list");
        if (out == null) {
            s.put("ok", false).put("why", "exec 失败/超时");
            return;
        }
        List<String> all = new ArrayList<>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 形如 "Found 123 services:" 或逐行 "  activity: [android.app.IActivityManager]"
            int colon = t.indexOf(':');
            String name = colon > 0 ? t.substring(0, colon).trim() : t;
            if (!name.isEmpty() && !name.startsWith("Found")) {
                all.add(name);
            }
        }
        List<String> suspects = new ArrayList<>();
        for (String n : all) {
            if (hit(n)) {
                suspects.add(n);
            }
        }
        s.put("ok", true);
        s.put("count", all.size());
        s.put("all", new JSONArray(all));
        s.put("suspects", new JSONArray(suspects));
    }

    /* ==================== ② Car API 深读（反射） ==================== */

    private static void probeCarApi(Context c, JSONObject s) throws Throwable {
        boolean hasClass = classExists("android.car.Car");
        s.put("class", hasClass);
        if (!hasClass) {
            s.put("why", "android.car.Car 不存在");
            return;
        }
        s.put("permPowertrain", perm(c, "android.car.permission.CAR_POWERTRAIN"));
        s.put("permVendorExt", perm(c, "android.car.permission.CAR_VENDOR_EXTENSION"));
        s.put("permCabin", perm(c, "android.car.permission.CAR_CABIN"));
        s.put("permInfo", perm(c, "android.car.permission.CAR_INFO"));

        // 建 Car（Car.createCar(Context) 是 API 29 公开 API，28 上为 hidden —— 反射调用）
        Object car = null;
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            car = carCls.getMethod("createCar", Context.class).invoke(null, c);
        } catch (Throwable e) {
            s.put("createCar", "err: " + brief(e));
        }
        if (car == null) {
            return;
        }
        s.put("createCar", "ok");

        // 各子 manager 可用性（名字对不上只记 err，不影响后面）
        Object pm = manager(car, "property");
        Object im = manager(car, "info");
        Object cm = manager(car, "cabin");
        JSONObject mgrs = new JSONObject();
        mgrs.put("property", pm != null ? "ok" : "err");
        mgrs.put("info", im != null ? "ok" : "err");
        mgrs.put("cabin", cm != null ? "ok" : "err");
        s.put("managers", mgrs);

        // getPropertyList() 全量枚举 —— 多数 VHAL 无权限也可列，这是权威证据
        JSONObject pl = new JSONObject();
        try {
            List<Integer> ids = propertyList(pm);
            pl.put("ok", true).put("count", ids.size());
            JSONArray arr = new JSONArray();
            for (int id : ids) {
                arr.put(id);
            }
            pl.put("ids", arr);
            // 枚举清单里对得上号的已知信号，直接起名（本表记错的 ID 不影响枚举原文）
            JSONArray named = new JSONArray();
            for (int id : ids) {
                String nm = nameOf(id);
                if (nm != null) {
                    named.put(nm + "=0x" + Integer.toHexString(id));
                }
            }
            pl.put("named", named);
        } catch (Throwable e) {
            pl.put("ok", false).put("err", brief(e));
        }
        s.put("propList", pl);

        // 已知信号逐个 try-read —— 权限 denied 会抛 SecurityException，照记
        JSONObject reads = new JSONObject();
        for (Object[] kp : KNOWN_PROPS) {
            try {
                Object v = readProp(pm, (Integer) kp[0]);
                reads.put((String) kp[1], v == null ? "null" : String.valueOf(v));
            } catch (Throwable e) {
                reads.put((String) kp[1], "err: " + brief(e));
            }
        }
        s.put("reads", reads);

        // CarInfoManager 读 VIN（历史上此接口常无权限要求，值得一试）
        if (im != null) {
            try {
                Object vin = call0(im, "getVehicleId");
                if (vin == null) {
                    vin = call0(im, "getVehiceId");   // 早期 AOSP 拼写变体，防御
                }
                s.put("vin", vin == null ? "null" : String.valueOf(vin));
            } catch (Throwable e) {
                s.put("vin", "err: " + brief(e));
            }
        }
        // CarService 状态（车机 ROM 可能改名，服务名以 services.suspects 实测为准；
        // 部分实现的 dumpsys 直接印属性清单，一并留存）
        for (String svc : new String[]{"car_service", "car"}) {
            String d = SysProbe.exec(6, "dumpsys", svc);
            if (d != null) {
                s.put("dumpsysSvc", svc);
                s.put("dumpsysHead", head(d, 60));
                break;
            }
        }
    }

    private static Object manager(Object car, String name) {
        try {
            return car.getClass().getMethod("getCarManager", String.class).invoke(car, name);
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> propertyList(Object pm) throws Throwable {
        Object r = pm.getClass().getMethod("getPropertyList").invoke(pm);
        if (r instanceof int[]) {
            List<Integer> out = new ArrayList<>();
            for (int v : (int[]) r) {
                out.add(v);
            }
            return out;
        }
        return (List<Integer>) r;
    }

    private static Object readProp(Object pm, int id) throws Throwable {
        // 新签名 getProperty(Class, int)（API 29）优先，退回旧签名 getProperty(int)
        try {
            return pm.getClass()
                    .getMethod("getProperty", Class.class, int.class)
                    .invoke(pm, Object.class, id);
        } catch (NoSuchMethodException nsme) {
            return pm.getClass().getMethod("getProperty", int.class).invoke(pm, id);
        }
    }

    private static Object call0(Object target, String method) throws Throwable {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (NoSuchMethodException nsme) {
            return null;
        }
    }

    private static String nameOf(int id) {
        for (Object[] kp : KNOWN_PROPS) {
            if (((Integer) kp[0]).intValue() == id) {
                return (String) kp[1];
            }
        }
        return null;
    }

    /* ==================== ③ 已装包接口发现（组件名维度） ==================== */

    private static void probePackages(Context c, JSONObject s) throws Throwable {
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(
                PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS);
        JSONArray rcv = new JSONArray();
        JSONArray svc = new JSONArray();
        JSONArray prv = new JSONArray();
        for (PackageInfo pi : pkgs) {
            String pkg = pi.packageName;
            if (pi.receivers != null) {
                for (android.content.pm.ActivityInfo ai : pi.receivers) {
                    if (hit(ai.name)) {
                        try {
                            rcv.put(new JSONObject().put("pkg", pkg).put("cls", ai.name));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (pi.services != null) {
                for (android.content.pm.ServiceInfo si : pi.services) {
                    if (hit(si.name)) {
                        try {
                            svc.put(new JSONObject().put("pkg", pkg).put("cls", si.name));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (pi.providers != null) {
                for (android.content.pm.ProviderInfo pvi : pi.providers) {
                    // provider 的 authority 才是接口名，优先按它匹配
                    if (hit(pvi.authority) || hit(pvi.name)) {
                        try {
                            prv.put(new JSONObject().put("pkg", pkg)
                                    .put("authority", pvi.authority));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        s.put("ok", true);
        s.put("pkgCount", pkgs.size());
        s.put("receivers", rcv);
        s.put("services", svc);
        s.put("providers", prv);
    }

    /* ==================== ④ 历史广播提取（action 维度） ==================== */

    /**
     * {@code dumpsys activity broadcasts} 里出现过真实 action —— 本机真发过的广播才拦得到。
     * 正则只起点分路径（≥3 段），原文头部一并留存供人工核对。
     */
    private static void probeBcastHistory(JSONObject s) throws Throwable {
        String out = SysProbe.exec(10, "dumpsys", "activity", "broadcasts");
        if (out == null) {
            s.put("ok", false).put("why", "exec 失败/超时/无权限");
            return;
        }
        Set<String> cands = new HashSet<>();
        Matcher m = ACT_PAT.matcher(out);
        while (m.find()) {
            String a = m.group();
            String low = a.toLowerCase(Locale.ROOT);
            // 只留「像 action」的：点分路径且不是纯类名风格的 API 前缀
            if (low.startsWith("android.") && !low.contains("intent.action")) {
                continue;
            }
            if (low.startsWith("com.android.") || low.startsWith("androidx.")) {
                continue;
            }
            cands.add(a);
            if (cands.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        s.put("ok", true);
        s.put("actionCandidates", new JSONArray(cands));
        s.put("rawHead", head(out, 60));
        // 喂给嗅探器 arm()（probe 完 SignalCapture 紧接着 arm）
        feedDiscovered(new ArrayList<>(cands));
    }

    private static boolean hit(String s) {
        if (s == null) {
            return false;
        }
        String low = s.toLowerCase(Locale.ROOT);
        for (String w : VEH_WORDS) {
            if (low.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /* ==================== ⑤ Location：GPS 测速可行性 ==================== */

    private static void probeLocation(Context c, JSONObject s) throws Throwable {
        String fp = perm(c, "android.permission.ACCESS_FINE_LOCATION");
        s.put("permFine", fp);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            s.put("ok", false).put("why", "无 LocationManager");
            return;
        }
        List<String> providers = lm.getAllProviders();
        s.put("providers", new JSONArray(providers));
        if (!"granted".equals(fp)) {
            s.put("why", "无定位权限，last known 不可读");
            return;
        }
        JSONObject last = new JSONObject();
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) {
                    continue;
                }
                JSONObject j = new JSONObject();
                j.put("speed", l.getSpeed());
                j.put("accuracy", l.getAccuracy());
                j.put("ageMs", System.currentTimeMillis() - l.getTime());
                j.put("lat", l.getLatitude());
                j.put("lon", l.getLongitude());
                last.put(p, j);
            } catch (Throwable e) {
                last.put(p, "err: " + brief(e));
            }
        }
        s.put("last", last);
    }

    /* ==================== ⑥ SensorManager 全清单 ==================== */

    private static void probeSensors(Context c, JSONObject s) throws Throwable {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            s.put("ok", false).put("why", "无 SensorManager");
            return;
        }
        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
        JSONArray arr = new JSONArray();
        for (Sensor sn : list) {
            try {
                arr.put(new JSONObject()
                        .put("name", sn.getName())
                        .put("type", sn.getType())
                        .put("vendor", sn.getVendor())
                        .put("version", sn.getVersion())
                        .put("power", sn.getPower())
                        .put("maxRange", sn.getMaximumRange()));
            } catch (Throwable ignored) {
            }
        }
        s.put("ok", true).put("count", list.size()).put("list", arr);
    }

    /* ==================== ⑦ BatteryManager 全字段 ==================== */

    private static void probeBattery(Context c, JSONObject s) throws Throwable {
        Intent sticky = c.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky == null) {
            s.put("ok", false).put("why", "拿不到 BATTERY_CHANGED");
        } else {
            JSONObject raw = new JSONObject();
            android.os.Bundle ex = sticky.getExtras();
            if (ex != null) {
                for (String k : ex.keySet()) {
                    try {
                        Object v = ex.get(k);
                        raw.put(k, v == null ? JSONObject.NULL : String.valueOf(v));
                    } catch (Throwable ignored) {
                    }
                }
            }
            s.put("raw", raw);
            s.put("voltageMv", sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
        }
        String d = SysProbe.exec(6, "dumpsys", "battery");
        if (d != null) {
            s.put("dumpsysHead", head(d, 40));
        }
    }

    /* ==================== ⑧ settings list 三库扫描 ==================== */

    private static void probeSettings(JSONObject s) throws Throwable {
        String[] libs = {"global", "secure", "system"};
        for (String lib : libs) {
            JSONObject j = new JSONObject();
            String out = SysProbe.exec(8, "settings", "list", lib);
            if (out == null) {
                j.put("ok", false);
                s.put(lib, j);
                continue;
            }
            List<String> suspects = new ArrayList<>();
            int n = 0;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                n++;
                if (hit(t)) {
                    suspects.add(clip(t, 120));
                }
            }
            j.put("ok", true).put("count", n).put("suspects", new JSONArray(suspects));
            s.put(lib, j);
        }
    }

    /* ==================== 嗅探器 arm / drain / disarm ==================== */

    /**
     * 武装被动嗅探器：把「历史广播提取的 action + 已知活接口（高德广播）」注册成
     * 一个 receiver，采集窗口内收到什么记什么。幂等；失败不影响采集。
     */
    public static void arm(Context c) {
        disarm();
        if (c == null) {
            return;
        }
        try {
            Set<String> acts = new HashSet<>();
            acts.add(Intent.ACTION_BATTERY_CHANGED);
            acts.add(NAV_BCAST);
            int added = 0;
            synchronized (discoveredCache) {
                for (String a : discoveredCache) {
                    if (acts.size() >= MAX_ARMED) {
                        break;
                    }
                    acts.add(a);
                    added++;
                }
            }
            Log.i(TAG, "嗅探器武装：" + acts.size() + " 个 action（历史提取 "
                    + added + " 个 + 已知 2 个）");
            final IntentFilter f = new IntentFilter();
            for (String a : acts) {
                f.addAction(a);
            }
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    record(intent);
                }
            };
            c.getApplicationContext().registerReceiver(r, f);
            sniffer = r;
            armedCtx = c.getApplicationContext();
            armedActions = new ArrayList<>(acts);
        } catch (Throwable e) {
            Log.i(TAG, "嗅探器武装失败（不影响采集）：" + brief(e));
        }
    }

    /** probe 内部调用：把本段提取的 action 候选喂给 arm()。 */
    private static void feedDiscovered(List<String> actions) {
        if (actions == null) {
            return;
        }
        synchronized (discoveredCache) {
            discoveredCache.clear();
            discoveredCache.addAll(actions);
        }
    }

    private static void record(Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis());
            j.put("action", intent.getAction());
            JSONObject ex = new JSONObject();
            android.os.Bundle b = intent.getExtras();
            if (b != null) {
                int n = 0;
                for (String k : b.keySet()) {
                    if (n++ > 40) {
                        break;
                    }
                    Object v = b.get(k);
                    ex.put(k, v == null ? JSONObject.NULL : clip(String.valueOf(v), 200));
                }
            }
            j.put("extras", ex);
            synchronized (HIT_LOCK) {
                if (HITS.size() < MAX_HITS) {
                    HITS.add(j);
                }
            }
            Log.i(TAG, "嗅探命中：" + intent.getAction());
        } catch (Throwable ignored) {
        }
    }

    /** 取走并清空命中记录。 */
    public static JSONArray drainHits() {
        synchronized (HIT_LOCK) {
            JSONArray a = new JSONArray(HITS);
            HITS.clear();
            return a;
        }
    }

    public static void disarm() {
        BroadcastReceiver r = sniffer;
        Context ctx = armedCtx;
        sniffer = null;
        armedCtx = null;
        armedActions = new ArrayList<>();
        if (r == null || ctx == null) {
            return;
        }
        try {
            ctx.unregisterReceiver(r);
            Log.i(TAG, "嗅探器已撤装");
        } catch (Throwable e) {
            Log.i(TAG, "嗅探器撤装失败（一次性 receiver，采集已结束）：" + brief(e));
        }
    }

    /* ==================== 小工具 ==================== */

    private static String brief(Throwable e) {
        return e.getClass().getSimpleName();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String head(String s, int lines) {
        if (s == null) {
            return "";
        }
        String[] arr = s.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(arr.length, lines);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String perm(Context c, String p) {
        try {
            return (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
                    ? "granted" : "denied";
        } catch (Throwable e) {
            return "unknown";
        }
    }
}

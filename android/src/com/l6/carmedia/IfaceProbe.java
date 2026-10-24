package com.l6.carmedia;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>★ v2.0.12 探测增强（依据 v2.0.11 首份车机实采结论 2026-10-24）：
 * Car API 四权限全 denied + {@code createCar} NoSuchMethodException ⇒ 该路已死；
 * 但 {@code service list} 里发现 {@code chinatsp.vehicle.network}（长安 TSP 车联网）与
 * {@code coagent.tbox} 两条私有 binder，且已装包里有
 * {@code com.chinatsp.vehiclenetwork}（VehicleNetworkService + VehicleReceiver）、
 * {@code com.banyac.midrive.app}（胎压 manager.DfuService）、
 * {@code com.egogame.yudanbox}（CarPropertyMonitorService）等消费方。
 * 本轮新增四段，目标是把「私有数据通道」凿实：
 * <ul>
 *   <li><b>service list 保留 AIDL 描述符</b>：v2.0.11 解析时把行尾 {@code [xxx.AIDL]}
 *       丢掉了，本轮补回——描述符直接告诉我们对端是什么接口</li>
 *   <li><b>svcDesc</b>：{@code ServiceManager.getService} 反射取关键服务的
 *       {@code IBinder.getInterfaceDescriptor()}（不依赖 service list 行格式）</li>
 *   <li><b>propsFull</b>：{@code getprop} 全量键值 dump（v2.0.11 只存了关键词命中的 60 个键，
 *       实时值可能藏在没命中的键里；两份采集 diff 即可定位活信号）</li>
 *   <li><b>bindProbe</b>：对关键已装服务逐个 {@code bindService}，
 *       连上就拿 {@code IBinder.getInterfaceDescriptor()}——拿不到 AIDL 定义也能坐实接口名</li>
 * </ul>
 * pkgIfaces 段同时补 exported/enabled 旗标与关键包的权限/uid/sourceDir（APK 路径，
 * 供逆向工程定点拉取）。
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

    /**
     * 关键 binder 服务名（v2.0.12 svcDesc 段目标）。
     *
     * <p>来源：v2.0.11 车机实采 {@code service list} 145 个服务里筛出的私有车辆通道。
     * {@code chinatsp.vehicle.network} = 长安 TSP 车联网（消费方 com.chinatsp.vehiclenetwork）；
     * {@code coagent.tbox} = TBox 远控 co-agent。
     */
    private static final String[] KEY_SVCS = {
            "chinatsp.vehicle.network", "coagent.tbox", "chinatsp.autoaction",
            "car_service", "upgrade.service", "fce-radio-ctrl",
    };

    /**
     * 关键已装包（v2.0.12 pkgIfaces.keyPkgs 段目标）——拉权限/uid/sourceDir 供定点逆向。
     *
     * <p>前 7 个是 chinatsp 系（车联网/设置/语音 OIL/一键呼叫/ETC/车外语音/InitCarApi），
     * 后 3 个是已发现的第三方车况消费方（胎压 / CarProperty 监控 / AOSP Car）。
     */
    private static final String[] KEY_PKGS = {
            "com.chinatsp.vehiclenetwork", "com.chinatsp.settings", "com.chinatsp.ifly",
            "com.chinatsp.onecall", "com.chinatsp.etc", "com.chinatsp.outsidecarvoice",
            "com.chinatsp.automate", "com.banyac.midrive.app", "com.egogame.yudanbox",
            "com.android.car",
    };

    /**
     * 关键绑定目标（v2.0.12 bindProbe 段）：{pkg, cls}。
     *
     * <p>★ 只绑「疑似车辆数据通道」的服务，且拿到 descriptor 立即解绑；
     *   bindService 对未导出组件抛 SecurityException、对 onBind 返 null 的服务 2s 超时，
     *   两种结果都如实记录——**能连上就是突破口，连不上也排除了一个方向**。
     * ★ 来自 v2.0.11 实采 pkgIfaces 段（组件名逐字抄录，防止拼写漂移）。
     */
    private static final String[][] KEY_BIND = {
            {"com.chinatsp.vehiclenetwork", "com.chinatsp.vehiclenetwork.service.VehicleNetworkService"},
            {"com.android.car", "com.android.car.CarService"},
            {"com.chinatsp.automate", "com.chinatsp.base.InitCarApiService"},
            {"com.banyac.midrive.app", "com.banyac.tirepressure.manager.DfuService"},
            {"com.egogame.yudanbox", "com.egogame.yudanbox.service.CarPropertyMonitorService"},
            {"com.chinatsp.etc", "com.chinatsp.etc.service.CarService"},
    };

    /**
     * 嗅探候选 action 增量（v2.0.12）。
     *
     * <p>★ v2.0.11 教训：{@code dumpsys activity broadcasts} 要 DUMP 权限（denied）⇒
     *   历史提取恒为空 ⇒ 嗅探器实际只武装了 BATTERY_CHANGED + 高德两个 action，
     *   采集窗口内就算车机真在发车辆广播也拦不到。
     * ★ 本轮按已发现包名推导候选（action 与包名同命名空间是 OEM 常见写法），
     *   best-effort：拦不到不亏，拦到就是活接口。
     */
    private static final String[] EXTRA_ACTIONS = {
            "com.chinatsp.vehiclenetwork", "com.chinatsp.vehiclenetwork.action",
            "com.chinatsp.ifly", "com.chinatsp.ifly.action",
            "com.chinatsp.onecall", "com.chinatsp.settings", "com.chinatsp.etc",
            "com.chinatsp.outsidecarvoice", "com.chinatsp.autoaction",
            "com.banyac.midrive.app", "com.egogame.yudanbox",
            // AOSP car 命名空间的既有广播（API 28 车机常见）
            "android.car.action.VEHICLE_INFO", "android.car.broadcast.action.VEHICLE_STATE",
            // 常见 OEM 车辆状态广播写法（历史车机 ROM 约定，逐字抄录防漂移）
            "vendor.vehicle.STATE", "vendor.vehicle.state",
            "com.android.car.action.VEHICLE_STATE", "com.changan.vehicle.STATE",
    };

    /** 广播历史行里的 action 形态：act=com.foo.BAR 或裸 com.foo.BAR_ACTION（≥3 段点分路径）。 */
    private static final Pattern ACT_PAT = Pattern.compile(
            "\\b(?:[a-z][a-z0-9_]*\\.){2,}[A-Za-z0-9_]+\\b");

    private static final int MAX_ARMED = 200;
    private static final int MAX_HITS = 500;
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
        // v2.0.12 新增三段：svcDesc / propsFull / bindProbe（见类注释）
        section(o, "svcDesc", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeSvcDesc(s);
            }
        });
        section(o, "propsFull", new Section() {
            public void run(JSONObject s) throws Throwable {
                probePropsFull(s);
            }
        });
        section(o, "bindProbe", new Section() {
            public void run(JSONObject s) throws Throwable {
                probeBind(c, s);
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
        // v2.0.12：name → [AIDL 描述符] 全量保留（v2.0.11 把行尾描述符丢了）。
        // service list 行格式："  6  car_service: [android.car.ICarService]"
        JSONObject desc = new JSONObject();
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
                if (colon > 0) {
                    // 行尾 "[com.foo.IBar]" —— 描述符原样留存（无则为空串）
                    int lb = t.indexOf('[', colon);
                    int rb = lb >= 0 ? t.indexOf(']', lb) : -1;
                    if (rb > lb) {
                        try {
                            desc.put(name, t.substring(lb + 1, rb));
                        } catch (Throwable ignored) {
                        }
                    }
                }
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
        // v2.0.12：全量 AIDL 描述符映射 + 疑车辆服务的描述符子集
        s.put("desc", desc);
        JSONObject sd = new JSONObject();
        for (String n : suspects) {
            if (desc.has(n)) {
                try {
                    sd.put(n, desc.getString(n));
                } catch (Throwable ignored) {
                }
            }
        }
        s.put("suspectDesc", sd);
        s.put("rawHead", head(out, 60));
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
        // v2.0.12：补 GET_PERMISSIONS —— keyPkgs 段要 requestedPermissions
        List<PackageInfo> pkgs = pm.getInstalledPackages(
                PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
        JSONArray rcv = new JSONArray();
        JSONArray svc = new JSONArray();
        JSONArray prv = new JSONArray();
        // v2.0.12：全量组件清单（带 exported/enabled）——关键词过滤会漏掉不带车字样
        //   却真是车辆通道的组件（如 com.foo.bar.LightController），落盘供离线分析。
        JSONArray comps = new JSONArray();
        for (PackageInfo pi : pkgs) {
            String pkg = pi.packageName;
            if (pi.receivers != null) {
                for (android.content.pm.ActivityInfo ai : pi.receivers) {
                    putComp(comps, pkg, ai.name, ai.exported, ai.enabled);
                    if (hit(ai.name)) {
                        try {
                            rcv.put(new JSONObject().put("pkg", pkg).put("cls", ai.name)
                                    .put("exp", ai.exported).put("en", ai.enabled));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (pi.services != null) {
                for (android.content.pm.ServiceInfo si : pi.services) {
                    putComp(comps, pkg, si.name, si.exported, si.enabled);
                    if (hit(si.name)) {
                        try {
                            svc.put(new JSONObject().put("pkg", pkg).put("cls", si.name)
                                    .put("exp", si.exported).put("en", si.enabled));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (pi.providers != null) {
                for (android.content.pm.ProviderInfo pvi : pi.providers) {
                    putComp(comps, pkg, pvi.name, pvi.exported, pvi.enabled);
                    // provider 的 authority 才是接口名，优先按它匹配
                    if (hit(pvi.authority) || hit(pvi.name)) {
                        try {
                            prv.put(new JSONObject().put("pkg", pkg)
                                    .put("authority", pvi.authority)
                                    .put("exp", pvi.exported).put("en", pvi.enabled));
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
        s.put("comps", comps);
        s.put("keyPkgs", probeKeyPkgs(pm, pkgs));
    }

    /** 全量组件清单条目（封顶 4000 条防 JSON 膨胀）。 */
    private static void putComp(JSONArray arr, String pkg, String cls,
            boolean exported, boolean enabled) {
        if (arr.length() >= 4000) {
            return;
        }
        try {
            arr.put(new JSONObject().put("pkg", pkg).put("cls", cls)
                    .put("exp", exported).put("en", enabled));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 关键包详情（v2.0.12）：请求的权限 / sharedUserId / APK 路径 / 是否系统应用。
     *
     * <p>★ {@code sourceDir} 是 APK 在车机上的绝对路径——**用户拿 adb pull 它即可定点逆向**，
     *   不用在整个 /data/app 里翻。
     * ★ {@code sharedUserId} 非空说明该包与系统同 uid（数据通道可能只对同 uid 开放），
     *   这是判断「通道是否对我方 App 可用」的关键证据。
     */
    private static JSONObject probeKeyPkgs(PackageManager pm, List<PackageInfo> pkgs) {
        JSONObject out = new JSONObject();
        for (PackageInfo pi : pkgs) {
            boolean key = false;
            for (String k : KEY_PKGS) {
                if (k.equals(pi.packageName)) {
                    key = true;
                    break;
                }
            }
            if (!key) {
                continue;
            }
            try {
                JSONObject j = new JSONObject();
                j.put("versionName", pi.versionName);
                j.put("versionCode", pi.versionCode);
                j.put("sharedUserId", pi.sharedUserId == null ? JSONObject.NULL
                        : pi.sharedUserId);
                ApplicationInfo ai = pi.applicationInfo;
                if (ai != null) {
                    j.put("sourceDir", ai.sourceDir == null ? JSONObject.NULL : ai.sourceDir);
                    j.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    j.put("uid", ai.uid);
                }
                JSONArray perms = new JSONArray();
                if (pi.requestedPermissions != null) {
                    for (String p : pi.requestedPermissions) {
                        if (p != null) {
                            perms.put(p);
                        }
                    }
                }
                j.put("perms", perms);
                out.put(pi.packageName, j);
            } catch (Throwable ignored) {
            }
        }
        return out;
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

    /* ==================== ⑨ svcDesc：关键 binder 服务的 AIDL 描述符（v2.0.12） ==================== */

    /**
     * {@code ServiceManager.getService} 反射取关键服务的 {@code IBinder.getInterfaceDescriptor()}。
     *
     * <p>★ 不依赖 {@code service list} 的行格式（那条路的描述符在 services.desc 里也有一份，互为印证）。
     * ★ 描述符就是 AIDL 接口全名（如 {@code android.car.ICarService}）——私有通道
     *   （chinatsp/tbox）的描述符是本段最大看点：拿到了名字才有下一步逆向的可能。
     * ★ {@code android.os.ServiceManager} 是 hidden API：API 28 上多为 light-greylist
     *   （反射可达、最多一条警告日志）；万一被 blocklist 挡住只记 err，不影响其它段。
     */
    private static void probeSvcDesc(JSONObject s) throws Throwable {
        Method getService;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            getService = smCls.getMethod("getService", String.class);
        } catch (Throwable e) {
            s.put("ok", false).put("why", "ServiceManager 反射不可达: " + brief(e));
            return;
        }
        JSONObject desc = new JSONObject();
        for (String name : KEY_SVCS) {
            try {
                Object b = getService.invoke(null, name);
                if (b == null) {
                    desc.put(name, "null");
                    continue;
                }
                if (!(b instanceof IBinder)) {
                    desc.put(name, "not-IBinder");
                    continue;
                }
                desc.put(name, ((IBinder) b).getInterfaceDescriptor());
            } catch (Throwable e) {
                desc.put(name, "err: " + e.getClass().getSimpleName());
            }
        }
        s.put("ok", true).put("desc", desc);
    }

    /* ==================== ⑩ propsFull：全量系统属性 dump（v2.0.12） ==================== */

    /** {@code getprop} 无参输出行格式：{@code [key]: [value]}。 */
    private static final Pattern PROP_LINE = Pattern.compile("^\\[(.+)\\]: \\[(.*)\\]$");

    /**
     * 全量 {@code getprop} 键值 dump。
     *
     * <p>★ v2.0.11 只存了车辆关键词命中的 60 个键——但实时值未必长在带关键词的键上
     *   （实测活的 {@code persist.sys.door_state} 命中了 door，可 {@code sys.tbox.*} 之类
     *   就可能漏）。本轮全量落盘，**两份采集 diff 即活信号定位器**。
     * ★ 值长 clip 到 400 字符（个别属性带长列表，防 JSON 膨胀）；空值留空串。
     */
    private static void probePropsFull(JSONObject s) throws Throwable {
        String out = SysProbe.exec(12, "getprop");
        if (out == null) {
            s.put("ok", false).put("why", "exec 失败/超时");
            return;
        }
        JSONObject kv = new JSONObject();
        int n = 0;
        int unparsed = 0;
        for (String line : out.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = PROP_LINE.matcher(line);
            if (!m.find()) {
                unparsed++;
                continue;
            }
            try {
                kv.put(m.group(1), clip(m.group(2), 400));
                n++;
            } catch (Throwable ignored) {
            }
        }
        s.put("ok", true).put("count", n).put("unparsed", unparsed).put("kv", kv);
        s.put("rawHead", head(out, 40));
    }

    /* ==================== ⑪ bindProbe：绑定关键服务取 AIDL 描述符（v2.0.12） ==================== */

    /** 单个绑定目标等待 onServiceConnected 的时长（ms）。 */
    private static final long BIND_WAIT_MS = 2000L;

    /**
     * 对 {@link #KEY_BIND} 里的关键已装服务逐个 {@code bindService}，连上即取
     * {@code IBinder.getInterfaceDescriptor()}，随后立即解绑。
     *
     * <p>★ 三种结果都有信息量：
     *   {@code connected=true + desc} = **通道坐实**（接口名到手，可据此逆向 AIDL）；
     *   {@code bind=true 但 2s 未回调} = 服务在但 onBind 返 null（要权限/要签名）；
     *   {@code SecurityException / bind=false} = 未导出，此路不通。
     * ★ BIND_AUTO_CREATE 会把服务拉起来——探测目的正当，且拿到描述符立刻解绑，
     *   不停赖在后台。每个目标独立 try/catch，一个炸不影响其余。
     */
    private static void probeBind(Context c, JSONObject s) throws Throwable {
        JSONArray arr = new JSONArray();
        for (final String[] kb : KEY_BIND) {
            final JSONObject j = new JSONObject();
            try {
                j.put("pkg", kb[0]).put("cls", kb[1]);
                final Object[] conn = {null};
                final CountDownLatch latch = new CountDownLatch(1);
                ServiceConnection sc = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        conn[0] = service;
                        try {
                            j.put("connected", true);
                            j.put("ifaceDesc", service.getInterfaceDescriptor());
                        } catch (Throwable e) {
                            try {
                                j.put("connected", true);
                                j.put("ifaceDesc", "err: " + brief(e));
                            } catch (Throwable ignored) {
                            }
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        latch.countDown();
                    }
                };
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(kb[0], kb[1]));
                boolean bound = c.bindService(intent, sc, Context.BIND_AUTO_CREATE);
                j.put("bind", bound);
                if (bound) {
                    latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS);
                    if (conn[0] == null) {
                        j.put("why", BIND_WAIT_MS + "ms 未回调（未导出被拒/onBind 返 null/权限不足）");
                    }
                    try {
                        c.unbindService(sc);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                try {
                    j.put("err", e.getClass().getSimpleName() + ": "
                            + (e.getMessage() == null ? "?" : e.getMessage()));
                } catch (Throwable ignored) {
                }
            }
            arr.put(j);
        }
        s.put("ok", true).put("results", arr);
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
            // v2.0.12：dumpsys 历史提取恒空（要 DUMP 权限）⇒ 补静态候选清单，
            //   否则嗅探器实际只armed 两个 action，车机发车辆广播也拦不到
            for (String a : EXTRA_ACTIONS) {
                acts.add(a);
            }
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

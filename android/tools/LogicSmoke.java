import com.l6.carmedia.LrcParse;
import com.l6.carmedia.NavBcastData;
import com.l6.carmedia.NavBcastFmt;
import com.l6.carmedia.NavParse;
import com.l6.carmedia.SigDiff;
import com.l6.carmedia.TapJudge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑回归：导航通知解析 + LRC 歌词解析 + 车机信号 diff。
 *
 * 这几块是「真实数据」里最容易出错、又最难在车机上反复试的部分，
 * 所以抽成了零 Android 依赖的纯函数，可以用普通 javac/JVM 直接跑。
 *
 * 运行（见 android/build.sh 或 skill 里的说明）：
 *   javac -encoding UTF-8 -d <tmp> src/com/l6/carmedia/NavParse.java \
 *         src/com/l6/carmedia/LrcParse.java src/com/l6/carmedia/SigDiff.java \
 *         src/com/l6/carmedia/NavBcastFmt.java \
 *         src/com/l6/carmedia/NavBcastData.java \
 *         tools/LogicSmoke.java
 *   java -cp <tmp> LogicSmoke
 */
public class LogicSmoke {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        navCase("高德·转弯",
                "前方 300米 右转 | 剩余 5.6公里 · 12分钟 | 进入 滨江大道",
                "\u21B1", "5.6 km", "300 m", "12 分钟", "滨江大道", "");
        navCase("百度·简写",
                "百度地图 | 前方200米右转，剩余3.2公里",
                "\u21B1", "3.2 km", "200 m", "", "", "");
        navCase("高德·沿路行驶",
                "沿 滨江大道 行驶 2.3公里 | 前方 500米 靠右 | 剩余 8.6公里 · 14分钟",
                "\u2197", "8.6 km", "500 m", "14 分钟", "沿 滨江大道", "");
        navCase("带目的地与到达时间",
                "1.2公里后左转 | 剩余 12.4公里 · 25分钟 · 预计 14:25 | 到 公司",
                "\u21B0", "12.4 km", "1.2 km", "25 分钟", "", "公司");
        navCase("掉头",
                "800米后掉头 | 剩余 3.1公里 · 7分钟",
                "\u21BA", "3.1 km", "800 m", "7 分钟", "", "");

        navNot("普通通知不应被当成导航", "您有一张优惠券即将过期");
        navNot("无距离的到达提示不算导航", "已到达目的地");

        // v1.5.18：车机版导航 App 的常驻通知没有距离，靠宽松判定兜住
        navLoose("车机版·后台导航常驻通知（无距离）", "高德地图已进入后台运行，将持续为您导航", true);
        navLoose("宽松判定也认严格判定的播报", "前方 300米 右转 | 剩余 5.6公里", true);
        navLoose("优惠券这种普通通知仍不算导航", "您有一张优惠券即将过期", false);
        // v1.5.19：车机 ROM 可能不给导航通知打 ongoing flag ⇒ 靠「进行时措辞」旁路
        navStrong("进行时措辞·持续为您导航", "高德地图已进入后台运行，将持续为您导航", true);
        navStrong("进行时措辞·正在导航中", "正在导航中，请沿当前道路继续行驶", true);
        navStrong("进行时措辞·普通推送不算", "您有一张优惠券即将过期，快来领取", false);
        // v1.5.20：高德官方广播协议（AmapAuto）—— 探测版只把 Bundle 摘要成一行可读文本
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("TYPE", "0");
        g.put("CUR_ROAD_NAME", "滨江大道");
        g.put("NEXT_ROAD_NAME", "江南大道");
        g.put("NEW_ICON", "4");
        g.put("ROUTE_REMAIN_DIS_AUTO", "12.4公里");
        g.put("ROUTE_REMAIN_TIME_AUTO", "26分钟");
        g.put("SEG_REMAIN_DIS_AUTO", "300米");
        g.put("CUR_SPEED", "38");
        g.put("LIMITED_SPEED", "60");
        g.put("TRAFFIC_LIGHT_NUM", "2");
        bcOk("广播 10001·道路/剩余/车速/限速都出来", NavBcastFmt.KT_GUIDE, g,
                new String[]{"KEY_TYPE=10001", "导航", "滨江大道 → 江南大道", "12.4公里",
                        "38km/h", "限速 60", "红绿灯 2"});
        bcOk("广播 10001·巡航类型", NavBcastFmt.KT_GUIDE, kvOf("TYPE", "2"),
                new String[]{"TYPE", "巡航"});
        bcOk("广播 10019·开始导航是权威信号", NavBcastFmt.KT_STATE, kvOf("EXTRA_STATE", "8"),
                new String[]{"KEY_TYPE=10019", "8 开始导航"});
        bcOk("广播 10019·到达目的地", NavBcastFmt.KT_STATE, kvOf("EXTRA_STATE", "39"),
                new String[]{"39 到达目的地"});
        bcOk("广播 10019·状态取不到时也不能抛", NavBcastFmt.KT_STATE, kvOf("FOO", "1"),
                new String[]{"keys=FOO"});
        bcOk("未收录 KEY_TYPE 也要可读（便于发现新接口）", 99999, kvOf("SOMETHING", "x"),
                new String[]{"未收录", "keys=SOMETHING"});
        bcOk("空 Bundle 既不抛也不返回空串", NavBcastFmt.KT_GUIDE,
                new LinkedHashMap<String, Object>(), new String[]{"KEY_TYPE=10001"});
        report("KEY_TYPE 收录判定", NavBcastFmt.isKnownKeyType(NavBcastFmt.KT_GUIDE)
                && NavBcastFmt.isKnownKeyType(NavBcastFmt.KT_LANE)
                && !NavBcastFmt.isKnownKeyType(99999));
        report("10019 状态名映射", "开始导航".equals(NavBcastFmt.stateName(8))
                && "结束导航".equals(NavBcastFmt.stateName(9))
                && "到达目的地".equals(NavBcastFmt.stateName(39)));

        // ---- v1.5.21：广播结构化模型（NavBcastData）—— 真正的导航数据源 ----
        // 数据取自 2026-10-03 车机实拍回显（含「无效值占位」的真实样本）
        Map<String, Object> shot = new LinkedHashMap<String, Object>();
        shot.put("TYPE", "0");
        shot.put("CUR_ROAD_NAME", "无名道路");
        shot.put("NEXT_ROAD_NAME", "兴物线");
        shot.put("NEW_ICON", "2");
        shot.put("ROUTE_REMAIN_DIS_AUTO", "5.6公里");
        shot.put("ROUTE_REMAIN_TIME_AUTO", "11分钟");
        shot.put("SEG_REMAIN_DIS_AUTO", "1.1公里");
        shot.put("CUR_SPEED", "0");
        shot.put("LIMITED_SPEED", "1");
        shot.put("CAMERA_DIST", "451");
        shot.put("TRAFFIC_LIGHT_NUM", "0");
        NavBcastData bd = NavBcastData.of(shot);
        report("v1.5.21 实拍回显：导航中 + 道路名成对",
                bd.isNav() && !bd.isCruise() && "无名道路".equals(bd.curRoad)
                        && "兴物线".equals(bd.nextRoad));
        report("v1.5.21 转向图标 2 = 左转 ↰",
                "\u21B0".equals(bd.arrow()) && "左转".equals(bd.turnName()));
        report("v1.5.21 ★ 限速 1 = 无数据（不显示假的限速）", !bd.hasLimit());
        report("v1.5.21 ★ 红绿灯 0 = 无数据", !bd.hasLight());
        report("v1.5.21 电子眼 451m 有效", bd.hasCamera());
        report("v1.5.21 ★ 车速 0 是真值（车停着），照显示",
                bd.hasSpeed() && bd.speed == 0);
        report("v1.5.21 chips 只列有效项：0km/h · 电子眼 451m",
                "0km/h · 电子眼 451m".equals(bd.chips()));
        report("v1.5.21 instruction 用下一道路名",
                "左转 · 兴物线".equals(bd.instruction()));
        report("v1.5.21 remainLine",
                "剩余 5.6公里 · 11分钟".equals(bd.remainLine()));

        // 图标映射全表（v1.5.21 实测 2 ⇒ 与高德悬浮窗一致；其余为社区通行值）
        String[] arrows = {"\u2191", "\u2196", "\u21B0", "\u2199", "\u2197",
                "\u21B1", "\u2198", "\u21BA", "\u21BB", "\u26F3"};
        String[] names = {"直行", "左前方", "左转", "左后方", "右前方",
                "右转", "右后方", "掉头", "环岛", "到达目的地"};
        boolean iconOk = true;
        for (int i = 0; i < arrows.length; i++) {
            if (!arrows[i].equals(NavBcastFmt.iconArrow(i))
                    || !names[i].equals(NavBcastFmt.iconName(i))) {
                iconOk = false;
                System.out.println("    图标 " + i + " 期望 " + names[i]
                        + " / 实得 " + NavBcastFmt.iconName(i));
            }
        }
        report("v1.5.21 转向图标全表 0..9", iconOk);
        report("v1.5.21 未知图标不空白（↑ / 继续行驶）",
                "\u2191".equals(NavBcastFmt.iconArrow(88))
                        && "继续行驶".equals(NavBcastFmt.iconName(88)));

        // 边界：空 Map / null / 全无效值 都不能抛，也不能编数据
        NavBcastData empty = NavBcastData.of(new LinkedHashMap<String, Object>());
        NavBcastData nul = NavBcastData.of(null);
        report("v1.5.21 空/ null 输入不抛且不编数据",
                !empty.hasLimit() && !empty.hasCamera() && !empty.hasLight()
                        && !empty.hasSpeed() && "".equals(empty.chips())
                        && !nul.hasLimit() && !nul.hasSpeed());
        report("v1.5.21 空输入也能给出中性指令",
                "继续行驶".equals(empty.instruction()) && "".equals(empty.remainLine()));

        Map<String, Object> noRoad = new LinkedHashMap<String, Object>();
        noRoad.put("NEW_ICON", "5");
        noRoad.put("CUR_ROAD_NAME", "滨江大道");
        NavBcastData nr = NavBcastData.of(noRoad);
        report("v1.5.21 没有下一道路时退回当前道路名",
                "右转 · 滨江大道".equals(nr.instruction()));

        Map<String, Object> cruise = new LinkedHashMap<String, Object>();
        cruise.put("TYPE", "2");
        NavBcastData cr = NavBcastData.of(cruise);
        report("v1.5.21 TYPE=2 判巡航（不是导航）", cr.isCruise() && !cr.isNav());

        lrcOk("标准 LRC（含制作信息行，应剔除）",
                "[00:00.00]作词：张三\n[00:12.50]夜空中最亮的星\n[00:17.20]能否听清\n[01:02.00]那仰望的人",
                3, new int[]{12500, 17200, 62000});
        lrcOk("一行多时间轴（副歌复用）",
                "[00:10.00][01:20.00]副歌一句",
                2, new int[]{10000, 80000});
        lrcOk("乱序应重排",
                "[01:02.00]第三句\n[00:12.50]第一句\n[00:30.00]第二句",
                3, new int[]{12500, 30000, 62000});
        lrcOk("毫秒两位精度 [mm:ss:xx]",
                "[00:05:25]开头\n[00:06:75]第二句",
                2, new int[]{5250, 6750});

        // ---- v2.0.2：悬浮「返回」按钮的「点按」判定（TapJudge）----
        tap();

        sig();

        System.out.println();
        System.out.println("结果: 通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* ---------------- 导航 ---------------- */

    private static void navCase(String name, String raw, String arrow, String remain,
                                String next, String eta, String road, String dest) {
        String[] v = NavParse.parse(raw);
        boolean nav = NavParse.looksLikeNav(raw);
        boolean ok = nav
                && arrow.equals(v[NavParse.ARROW])
                && remain.equals(v[NavParse.REMAIN])
                && next.equals(v[NavParse.NEXT])
                && eta.equals(v[NavParse.ETA])
                && road.equals(v[NavParse.ROAD])
                && dest.equals(v[NavParse.DEST]);
        report(name, ok);
        if (!ok) {
            System.out.println("    raw    = " + raw);
            System.out.println("    期望   arrow=" + arrow + " remain=" + remain + " next=" + next
                    + " eta=" + eta + " road=" + road + " dest=" + dest + " nav=" + true);
            System.out.println("    实际   arrow=" + v[NavParse.ARROW] + " remain=" + v[NavParse.REMAIN]
                    + " next=" + v[NavParse.NEXT] + " eta=" + v[NavParse.ETA]
                    + " road=" + v[NavParse.ROAD] + " dest=" + v[NavParse.DEST] + " nav=" + nav);
        }
    }

    private static void navStrong(String name, String raw, boolean expect) {
        boolean got = NavParse.mentionsNavStrong(raw);
        boolean ok = got == expect;
        report(name, ok);
        if (!ok) System.out.println("    raw = " + raw + " 进行时措辞期望 " + expect + " 实得 " + got);
    }

    private static void navLoose(String name, String raw, boolean expect) {
        boolean got = NavParse.looksLikeNavLoose(raw);
        boolean ok = got == expect;
        report(name, ok);
        if (!ok) {
            System.out.println("    raw = " + raw + " 宽松判定期望 " + expect + " 实得 " + got);
        }
    }

    private static void navNot(String name, String raw) {
        boolean ok = !NavParse.looksLikeNav(raw);
        report(name, ok);
        if (!ok) {
            System.out.println("    raw = " + raw + " 不应被识别为导航");
        }
    }

    /* ---------------- 高德广播协议（v1.5.20） ---------------- */

    /** 摘要必须包含全部关键子串，且不抛、不为空。 */
    private static void bcOk(String name, int keyType, Map<String, Object> kv, String[] must) {
        String got;
        try {
            got = NavBcastFmt.summarize(keyType, kv);
        } catch (Throwable t) {
            got = "抛异常: " + t;
        }
        StringBuilder miss = new StringBuilder();
        for (String m : must) {
            if (got == null || !got.contains(m)) {
                miss.append("[").append(m).append("]");
            }
        }
        boolean ok = miss.length() == 0 && got != null && !got.isEmpty();
        report(name, ok);
        if (!ok) {
            System.out.println("    实际 = " + got);
            System.out.println("    缺少 = " + miss);
        }
    }

    private static Map<String, Object> kvOf(String k, String v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    /* ---------------- 歌词 ---------------- */

    private static void lrcOk(String name, String lrc, int count, int[] times) {
        List<LrcParse.Line> lines = LrcParse.parse(lrc);
        boolean ok = lines.size() == count;
        if (ok) {
            for (int i = 0; i < count; i++) {
                if (lines.get(i).t != times[i]) {
                    ok = false;
                    break;
                }
            }
        }
        report(name, ok);
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            for (LrcParse.Line l : lines) {
                sb.append(l.t).append(':').append(l.s).append("  ");
            }
            System.out.println("    期望 " + count + " 行 " + java.util.Arrays.toString(times));
            System.out.println("    实际 " + lines.size() + " 行 " + sb);
        }
    }

    /* ---------------- 悬浮返回按钮点按判定（TapJudge，v2.0.2）---------------- */

    /**
     * 回归背景：v2.0.2 之前判定写死「位移 &lt; 8 物理像素」。车机 density=1 时 8px=8dp 正常，
     * 但手机 density 2.6 时 8px 只等于 3dp、4.0 时只有 2dp，手指抖动必然越界
     * ⇒ 点悬浮「返回」全被误判成拖动，用户在手机代车机上点了没反应。
     * 三种密度 + 拖动边界全部钉死。
     */
    private static void tap() {
        final float car = 1.0f;        // 车机 1920×720，density 160
        final float phone = 2.625f;    // 420dpi 常见手机
        final float flagship = 4.0f;   // 640dpi 旗舰

        report("车机 1x：3px 抖动仍算点按", TapJudge.isTap(3, 2, 120, car));
        report("★ 手机 2.6x：3px 抖动仍算点按（v2.0.1 的真 bug 点）",
                TapJudge.isTap(3, 2, 120, phone));
        report("旗舰 4x：5px 抖动仍算点按", TapJudge.isTap(5, 4, 200, flagship));
        report("手机 2.6x：12px/9px 抖动（≈4.6dp）仍算点按", TapJudge.isTap(12, 9, 300, phone));
        report("真拖动 40px/25px 在 2.6x 上判为拖动（别把拖动误当点击）",
                !TapJudge.isTap(40, 25, 300, phone));
        report("真拖动 40px/25px 在 1x 上也判为拖动", !TapJudge.isTap(40, 25, 300, car));
        report("斜向只按各轴独立判：14px 算点按、40px 不算",
                TapJudge.isTap(14, 3, 120, phone) && !TapJudge.isTap(40, 3, 120, phone));
        report("反向位移按绝对值处理（-4/-4 仍算点按）", TapJudge.isTap(-4, -4, 120, phone));
        report("按住 900ms 不算点按", !TapJudge.isTap(2, 2, 900, phone));
        report("600ms 边界内算点按（闭区间）", TapJudge.isTap(2, 2, 600, phone));
        report("density 传 0 兜底成 1x，不会把所有点击都判成拖动",
                TapJudge.isTap(3, 2, 120, 0f));
        report("density 传负数同样兜底", TapJudge.isTap(3, 2, 120, -2f));
        report("dp→px：12dp @2.6x ≈ 30.3px、@1x = 12px",
                Math.abs(TapJudge.dpToPx(12, car) - 12f) < 0.01f
                        && Math.abs(TapJudge.dpToPx(12, phone) - 31.5f) < 0.01f);
        report("describe 能直接看出判定结论（落盘日志靠它）",
                TapJudge.describe(3, 2, 120, phone).contains("点按")
                        && TapJudge.describe(40, 25, 300, phone).contains("拖动")
                        && TapJudge.describe(3, 2, 120, phone).contains("density"));
        report("★ 关键区分用例：2.6x 上 10px/4px 抖动（≈3.8dp）判为点按"
                        + "（旧实现写死 8px，这一例会被误判成拖动 → 点了没反应）",
                TapJudge.isTap(10, 4, 150, phone));
    }

    /* ---------------- 车机信号采集 diff（SigDiff，v1.5.14 起）---------------- */

    /**
     * 采集的全部判断都压在 diff 上：把「上一秒快照」和「这一秒快照」比出差异。
     * 这块在车机上没法反复按键复现，必须在这里把边界钉死。
     */
    private static void sig() {
        java.util.TreeMap<String, String> a = new java.util.TreeMap<>();
        a.put("prop:persist.sys.door.fl", "0");
        a.put("audio:vol.music", "10/15");
        a.put("set.global:x", "1");

        java.util.TreeMap<String, String> b = new java.util.TreeMap<>();
        b.put("prop:persist.sys.door.fl", "1");   // 值变了
        b.put("audio:vol.music", "10/15");        // 没变 → 不该出现
        b.put("set.global:y", "2");               // 新增
        // set.global:x 消失

        List<SigDiff.Change> d = SigDiff.diff(a, b);
        report("diff 命中「变化/新增/消失」三类，未变化的项不报", d.size() == 3, "size=" + d.size());
        report("diff 结果按 key 升序（两次采集才能逐行比对）",
                d.size() == 3
                        && "prop:persist.sys.door.fl".equals(d.get(0).key)
                        && "set.global:x".equals(d.get(1).key)
                        && "set.global:y".equals(d.get(2).key));

        SigDiff.Change c0 = d.get(0);
        report("change 能拆出 src / name",
                "prop".equals(c0.src()) && "persist.sys.door.fl".equals(c0.name()));
        report("changed 的 from / to 正确",
                SigDiff.CHANGED == c0.kind && "0".equals(c0.from) && "1".equals(c0.to));
        report("describe() 人类可读", "persist.sys.door.fl: 0 → 1".equals(c0.describe()), c0.describe());
        report("removed 的 kind / to 正确",
                SigDiff.REMOVED == d.get(1).kind && d.get(1).to == null);
        report("added 的 kind / from 正确",
                SigDiff.ADDED == d.get(2).kind && d.get(2).from == null);

        report("null 快照当空处理（不抛）", SigDiff.diff(null, b).size() == 3);
        report("两边都 null → 无变化", SigDiff.diff(null, null).isEmpty());
        report("完全相同的快照 → 无变化", SigDiff.diff(a, new java.util.TreeMap<>(a)).isEmpty());

        java.util.Set<String> ig = new java.util.HashSet<>();
        ig.add("prop:persist.sys.door.fl");
        report("忽略清单能挡掉脏键", SigDiff.diff(a, b, ig).size() == 2);
        report("不传 ignore 等价于空清单", SigDiff.diff(a, b).size() == 3);

        // 词边界：不然 acc 会撞 accessibility、lock 会撞 clock，车辆键清单被垃圾淹没
        report("命中真车辆键 door", SigDiff.hit("prop:persist.sys.door.fl", "door"));
        report("不命中 accessibility 里的 acc", !SigDiff.hit("set.secure:accessibility_enabled", "acc"));
        report("不命中 clock_format 里的 lock", !SigDiff.hit("set.system:clock_format", "lock"));
        report("命中 car_lock（下划线是合法边界）", SigDiff.hit("prop:ro.car_lock", "car_lock"));
        report("命中 vehicle.speed_hit 里的 speed", SigDiff.hit("prop:ro.vehicle.speed_hit", "speed"));
        report("中间夹字母不算命中（doorx）", !SigDiff.hit("prop:persist.sys.doorx", "door"));
        report("空关键字 / 空键 / null 都安全",
                !SigDiff.hit("prop:x", "") && !SigDiff.hit("", "door") && !SigDiff.hit(null, "door"));

        java.util.TreeMap<String, String> s2 = new java.util.TreeMap<>();
        s2.put("prop:persist.sys.door.fl", "1");
        s2.put("prop:persist.sys.gear", "P");
        s2.put("set.secure:accessibility_enabled", "1");
        s2.put("sys:model", "Z6");
        List<String> hitKeys = SigDiff.match(s2, new String[]{"door", "gear", "acc"});
        report("match 挑出 2 个车辆键（acc 没误伤 accessibility）",
                hitKeys.size() == 2
                        && "prop:persist.sys.door.fl".equals(hitKeys.get(0))
                        && "prop:persist.sys.gear".equals(hitKeys.get(1)), hitKeys.toString());
        report("match 对 null 快照安全", SigDiff.match(null, new String[]{"door"}).isEmpty());
        report("match 对 null 关键字安全", SigDiff.match(s2, null).isEmpty());

        report("eq(null,null) 真、eq(\"\",null) 假",
                SigDiff.eq(null, null) && !SigDiff.eq("", null) && SigDiff.eq("", ""));
    }

    /** 拼一个 DNS 应答（1 问题 + CNAME + A），用来单测解包。 */
    private static byte[] mkDnsResp(int id, int rcode) { return mkDnsResp(id, rcode, true); }
    private static byte[] mkDnsResp(int id, int rcode, boolean withA) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write((id >> 8) & 0xFF); o.write(id & 0xFF);
        o.write(0x81); o.write(0x80 | (rcode & 0x0F));   // QR=1, RD/RA=1, rcode
        o.write(0); o.write(1);                          // QDCOUNT = 1
        o.write(0); o.write(withA ? 2 : 0);              // ANCOUNT
        o.write(0); o.write(0); o.write(0); o.write(0);  // NS / AR = 0
        byte[] name = {3, 'a', 'p', 'i', 4, 't', 'e', 's', 't', 0};
        o.write(name, 0, name.length);
        o.write(0); o.write(1);                          // QTYPE = A
        o.write(0); o.write(1);                          // QCLASS = IN
        if (!withA) return o.toByteArray();
        // 先放一条 CNAME（解析应跳过），再放 A
        o.write(0xC0); o.write(12);
        o.write(0); o.write(5); o.write(0); o.write(1);
        o.write(0); o.write(0); o.write(0); o.write(60);
        byte[] tgt = {3, 'a', 'l', 't', 4, 't', 'e', 's', 't', 0};
        o.write(0); o.write(tgt.length);
        o.write(tgt, 0, tgt.length);
        o.write(0xC0); o.write(12);
        o.write(0); o.write(1); o.write(0); o.write(1);
        o.write(0); o.write(0); o.write(0); o.write(60);
        o.write(0); o.write(4);
        o.write(60); o.write(205); o.write(251); o.write(18);
        return o.toByteArray();
    }

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("line").append(i).append('\n');
        return sb.toString();
    }

    private static void report(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    /** 带补充信息的断言：失败时把实际值打出来，省得再改代码复现。 */
    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name
                    + (detail == null || detail.isEmpty() ? "" : "  → 实际: " + detail));
        }
    }
}

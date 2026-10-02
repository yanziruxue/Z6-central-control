import com.l6.carmedia.LrcParse;
import com.l6.carmedia.NavParse;
import com.l6.carmedia.SigDiff;

import java.util.List;

/**
 * 纯逻辑回归：导航通知解析 + LRC 歌词解析 + 车机信号 diff。
 *
 * 这几块是「真实数据」里最容易出错、又最难在车机上反复试的部分，
 * 所以抽成了零 Android 依赖的纯函数，可以用普通 javac/JVM 直接跑。
 *
 * 运行（见 android/build.sh 或 skill 里的说明）：
 *   javac -encoding UTF-8 -d <tmp> src/com/l6/carmedia/NavParse.java \
 *         src/com/l6/carmedia/LrcParse.java src/com/l6/carmedia/SigDiff.java \
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

    private static void navNot(String name, String raw) {
        boolean ok = !NavParse.looksLikeNav(raw);
        report(name, ok);
        if (!ok) {
            System.out.println("    raw = " + raw + " 不应被识别为导航");
        }
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

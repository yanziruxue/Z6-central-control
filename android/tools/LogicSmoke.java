import com.l6.carmedia.DnsQuery;
import com.l6.carmedia.LogBatch;
import com.l6.carmedia.LogDiag;
import com.l6.carmedia.LrcParse;
import com.l6.carmedia.NavParse;
import com.l6.carmedia.SigDiff;

import java.util.List;

/**
 * 纯逻辑回归：导航通知解析 + LRC 歌词解析。
 *
 * 这两块是「真实数据」里最容易出错、又最难在车机上反复试的部分，
 * 所以抽成了零 Android 依赖的纯函数，可以用普通 javac/JVM 直接跑。
 *
 * 运行（见 android/build.sh 或 skill 里的说明）：
 *   javac -encoding UTF-8 -d <tmp> src/com/l6/carmedia/NavParse.java \
 *         src/com/l6/carmedia/LrcParse.java src/com/l6/carmedia/LogBatch.java \
 *         src/com/l6/carmedia/LogDiag.java src/com/l6/carmedia/DnsQuery.java \
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

        batch();
        diag();
        dns();
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

    /* ---------------- 日志上报分批（服务端单批上限 2000 行） ---------------- */

    private static void batch() {
        final int max = LogBatch.MAX_LINES_PER_BATCH;
        report("单批上限必须 ≤ 服务端 2000 行", max <= 2000);
        // 弱网护栏：上限一旦被调回大值，一次抖动要重发的行数就回到几千，这里钉住
        report("单批行数维持在弱网友好区间（≤300 行）", max <= 300);
        report("单批字节维持在弱网友好区间（≤128KB）", LogBatch.MAX_BYTES_PER_BATCH <= 128 * 1024);

        batchCase("两行合成一批", "a\nb\n", new int[]{2}, 2);
        batchCase("单行", "x\n", new int[]{1}, 1);
        batchCase("无换行的一段（无完整行）→ 不切", "abc", new int[0], 0);
        batchCase("空文本", "", new int[0], 0);
        batchCase("纯空行不产生批次", "\n\n\n", new int[0], 0);
        batchCase("空行被跳过但仍计入偏移", "a\n\nb\n", new int[]{2}, 2);

        // 超过单批上限才切。期望值一律由常量推导，以后调上限不必改用例
        batchCase("正好一个上限 → 只切一批", lines(max), new int[]{max}, max);
        batchCase("上限 + 1 → 切两批", lines(max + 1), new int[]{max, 1}, max + 1);
        batchCase("2.5 倍上限 → 切三批（末批为余数）",
                lines(max * 2 + max / 2), counts(max * 2 + max / 2, max), max * 2 + max / 2);
        batchCase("2500 行 → 全部切完且不丢行", lines(2500), counts(2500, max), 2500);

        // 内容保真
        LogBatch.Chunk[] cs = LogBatch.split("a\r\nb\r\n", 1000);
        report("CRLF 行尾的 \\r 已剔除",
                cs.length == 1 && cs[0].lines.length == 2
                        && cs[0].lines[0].equals("a") && cs[0].lines[1].equals("b"));

        // 中文：偏移必须按 UTF-8 字节算，否则偶数字节错位会切坏后续内容
        String cn = "中文一行\n";
        LogBatch.Chunk[] c2 = LogBatch.split(cn, 1000);
        report("中文 offset 按 UTF-8 字节算（4 字 + 换行 = 13）",
                c2.length == 1 && c2[0].endOffset == 13);

        // 光限行数不够：服务端 ECONNRESET 是按体积触发的，长行必须再按字节切
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("x".repeat(50)).append('\n');
        LogBatch.Chunk[] c3 = LogBatch.split(sb.toString(), 1000, 120);   // 每行 50B，限额 120B
        report("长行按字节切批（每行 50B / 限额 120B → 3,3,3,1）",
                c3.length == 4 && c3[0].lines.length == 3 && c3[1].lines.length == 3
                        && c3[2].lines.length == 3 && c3[3].lines.length == 1);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append("y".repeat(2048)).append('\n');   // 每行 2KB，共 ~800KB
        LogBatch.Chunk[] c4 = LogBatch.split(big.toString());
        boolean sizeOk = c4.length > 1;
        for (LogBatch.Chunk c : c4) {
            int bytes = 0;
            for (String ln : c.lines) bytes += ln.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > LogBatch.MAX_BYTES_PER_BATCH) { sizeOk = false; break; }
        }
        report("2KB 超长行：切成多批且每批都在字节上限内", sizeOk);
        report("默认字节上限远低于服务端 ECONNRESET 阈值(~1.6MB)",
                LogBatch.MAX_BYTES_PER_BATCH < 1600 * 1024);
    }

    /* ---------------- 上传失败诊断（v1.5.7） ---------------- */

    private static void diag() {
        // 重试阶梯：0.4s → 2s，共 3 次尝试。放宽是为了不再让两次尝试落在同一个故障窗口里
        // （手机实测：400ms 后重发两次一起失败）。
        report("重试阶梯为 0.4s / 2s", LogDiag.RETRY_DELAYS_MS.length == 2
                && LogDiag.RETRY_DELAYS_MS[0] == 400 && LogDiag.RETRY_DELAYS_MS[1] == 2000);
        report("单批最多尝试 3 次（1 原始 + 2 重试）", LogDiag.maxAttempts() == 3);
        report("第 1 次失败后等 400ms", LogDiag.delayAfter(1) == 400);
        report("第 2 次失败后等 2000ms", LogDiag.delayAfter(2) == 2000);
        report("用完阶梯后不再有延迟（-1）", LogDiag.delayAfter(3) == -1);

        // 只在「当场重发没准就好」的瞬时故障上重试：4xx 重发毫无意义，还会白白放大请求量
        report("code<0（没连上）算瞬时", LogDiag.isTransient(-1));
        report("5xx 算瞬时", LogDiag.isTransient(500) && LogDiag.isTransient(502));
        report("4xx 不算瞬时（不重发）",
                !LogDiag.isTransient(400) && !LogDiag.isTransient(403) && !LogDiag.isTransient(413));
        report("2xx 不算瞬时", !LogDiag.isTransient(200));

        // 阶段名必须是中文，且三段各自可辨（回显给用户看的不是 connect/write/read）
        report("connect 阶段显示为「建立连接」", "建立连接".equals(LogDiag.phaseLabel("connect")));
        report("write 阶段显示为「写入请求体」", "写入请求体".equals(LogDiag.phaseLabel("write")));
        report("read 阶段显示为「读取响应」", "读取响应".equals(LogDiag.phaseLabel("read")));

        // 失败原因：网络异常要带「阶段 + 异常类 + 耗时」；HTTP 失败要带状态码 + 服务端原文
        String net = LogDiag.reason(-1, "SocketException: Connection reset", "read", 3021, "");
        report("网络异常回显含阶段/异常/耗时",
                net.contains("读取响应") && net.contains("Connection reset") && net.contains("3021ms"), net);
        report("网络异常不含 HTTP 状态码", net.indexOf("HTTP") < 0, net);
        report("失败原因不再自带「上传失败」前缀（页面已有徽标，重复会说两遍）",
                net.indexOf("上传失败") < 0, net);
        String http = LogDiag.reason(400, "", "read", 210, "：TOO_MANY_LINES");
        report("HTTP 失败回显状态码 + 服务端 error",
                http.contains("400") && http.contains("TOO_MANY_LINES"), http);
        report("err 为空时给兜底文案", LogDiag.reason(-1, "", "connect", 15, "").contains("未知网络异常"));
        report("阶段缺失时兜底为「建立连接」", LogDiag.reason(-1, "boom", "", 5, "").contains("建立连接"));

        // 探针结论：TCP 未测到（-1）时不应出现「TCP」字样，避免把「没测」说成「0ms」
        String okSum = LogDiag.probeSummary(true, 12, 45, 218);
        report("探针结论含三段耗时", okSum.contains("DNS 12ms") && okSum.contains("TCP 45ms")
                && okSum.contains("请求 218ms"), okSum);
        report("TCP 未测到时不显示 TCP 段", LogDiag.probeSummary(false, 3, -1, 0).indexOf("TCP") < 0);
        report("失败结论用「接口不通」", LogDiag.probeSummary(false, 3, -1, 0).contains("接口不通"));
    }

    /* ---------------- 自定义 DNS（DnsQuery，v1.5.8）---------------- */

    private static void dns() {
        // 组包：单问题 A 查询，QD=1、RD=1，其余计数为 0
        byte[] q = DnsQuery.buildQuery("api.test", 0x1234);
        report("查询包长度 = 头12 + 名10 + 4", q.length == 26, String.valueOf(q.length));
        report("查询包 ID 正确", (q[0] & 0xFF) == 0x12 && (q[1] & 0xFF) == 0x34);
        report("查询包 RD=1 且 rcode=0", (q[2] & 0xFF) == 0x01 && (q[3] & 0xFF) == 0x00);
        report("QDCOUNT=1，AN/NS/AR=0",
                q[4] == 0 && q[5] == 1 && q[6] == 0 && q[7] == 0 && q[8] == 0 && q[9] == 0
                        && q[10] == 0 && q[11] == 0);
        report("QNAME 编码为 3api4test0",
                q[12] == 3 && q[13] == 'a' && q[14] == 'p' && q[15] == 'i'
                        && q[16] == 4 && q[17] == 't' && q[21] == 0);
        report("QTYPE=A(1)、QCLASS=IN(1)",
                q[22] == 0 && q[23] == 1 && q[24] == 0 && q[25] == 1);

        // 解包：应答里 CNAME 在前、A 在后 → 应跳过 CNAME 取到 A
        byte[] resp = mkDnsResp(0x1234, 0);
        java.util.List<String> all = DnsQuery.parseAllA(resp, 0x1234);
        report("应答解析出 1 条 A 记录（去重）", all.size() == 1, all.toString());
        report("A 记录内容正确（60.205.251.18）",
                all.size() == 1 && "60.205.251.18".equals(all.get(0)), all.toString());
        report("parseFirstA 取首条", "60.205.251.18".equals(DnsQuery.parseFirstA(resp, 0x1234)));
        report("ID 不匹配的应答被丢弃（防串包）", DnsQuery.parseFirstA(resp, 0x9999) == null);
        report("rcode!=0（NXDOMAIN）返回空", DnsQuery.parseFirstA(mkDnsResp(0x1234, 3), 0x1234) == null);
        report("ANCOUNT=0 返回空", DnsQuery.parseFirstA(mkDnsResp(0x1234, 0, false), 0x1234) == null);
        report("空/短包不抛异常", DnsQuery.parseAllA(new byte[3], 1).isEmpty());

        // 设置页输入校验
        report("合法 IPv4 通过", DnsQuery.isIpv4("60.205.251.18") && DnsQuery.isIpv4("223.5.5.5"));
        report("非法 IPv4 被拒（越界/缺段/非数字）",
                !DnsQuery.isIpv4("256.1.1.1") && !DnsQuery.isIpv4("1.2.3") && !DnsQuery.isIpv4("a.b.c.d"));
        report("isIp 接受 IPv6", DnsQuery.isIp("2408:4009:501::39") && DnsQuery.isIp("::1"));
        report("isIp 拒绝非 IP",
                !DnsQuery.isIp("") && !DnsQuery.isIp("dns.alidns.com") && !DnsQuery.isIp("223.5.5.5/24"));
    }

    /* ---------------- 车机信号采集 diff（SigDiff，v1.5.14）---------------- */

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

    /** 把 total 行按每批 per 行切分时的各批行数（末批为余数）。期望值由常量推导，不写死。 */
    private static int[] counts(int total, int per) {
        int[] tmp = new int[(total + per - 1) / per];
        for (int i = 0; i < tmp.length; i++) tmp[i] = Math.min(per, total - i * per);
        return tmp;
    }

    private static void batchCase(String name, String text, int[] expectCounts, int expectTotal) {
        LogBatch.Chunk[] cs = LogBatch.split(text, LogBatch.MAX_LINES_PER_BATCH);
        boolean ok = cs.length == expectCounts.length;
        int total = 0;
        if (ok) {
            for (int i = 0; i < cs.length; i++) {
                if (cs[i].lines.length != expectCounts[i]) { ok = false; break; }
                total += cs[i].lines.length;
            }
        }
        if (ok && total != expectTotal) ok = false;
        // 偏移必须单调递增，且最后一批正好落在文本末尾（保证续传不重不漏）
        if (ok && cs.length > 0) {
            int expectEnd = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (cs[cs.length - 1].endOffset != expectEnd) ok = false;
            for (int i = 1; ok && i < cs.length; i++) {
                if (cs[i].endOffset <= cs[i - 1].endOffset) ok = false;
            }
        }
        report(name, ok);
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            for (LogBatch.Chunk c : cs) sb.append('[').append(c.lines.length).append('@').append(c.endOffset).append("] ");
            System.out.println("    期望 " + java.util.Arrays.toString(expectCounts)
                    + " 实际 " + sb + " 总行数=" + total);
        }
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

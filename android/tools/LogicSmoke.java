import com.l6.carmedia.LrcParse;
import com.l6.carmedia.LogBatch;
import com.l6.carmedia.NavParse;

import java.util.List;

/**
 * 纯逻辑回归：导航通知解析 + LRC 歌词解析。
 *
 * 这两块是「真实数据」里最容易出错、又最难在车机上反复试的部分，
 * 所以抽成了零 Android 依赖的纯函数，可以用普通 javac/JVM 直接跑。
 *
 * 运行（见 android/build.sh 或 skill 里的说明）：
 *   javac -encoding UTF-8 -d <tmp> src/com/l6/carmedia/NavParse.java \
 *         src/com/l6/carmedia/LrcParse.java tools/LogicSmoke.java
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
        report("单批上限必须 ≤ 服务端 2000 行", LogBatch.MAX_LINES_PER_BATCH <= 2000);

        batchCase("两行合成一批", "a\nb\n", new int[]{2}, 2);
        batchCase("单行", "x\n", new int[]{1}, 1);
        batchCase("无换行的一段（无完整行）→ 不切", "abc", new int[0], 0);
        batchCase("空文本", "", new int[0], 0);
        batchCase("纯空行不产生批次", "\n\n\n", new int[0], 0);
        batchCase("空行被跳过但仍计入偏移", "a\n\nb\n", new int[]{2}, 2);

        // 超过单批上限才切
        batchCase("2500 行切成 1000/1000/500", lines(2500), new int[]{1000, 1000, 500}, 2500);
        batchCase("正好 1000 行只切一批", lines(1000), new int[]{1000}, 1000);
        batchCase("2000 行切两批", lines(2000), new int[]{1000, 1000}, 2000);

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
    }

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("line").append(i).append('\n');
        return sb.toString();
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
}

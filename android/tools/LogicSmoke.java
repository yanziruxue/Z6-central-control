import com.l6.carmedia.LrcParse;
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

package com.l6.carmedia;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃守护（v2.0.16）。
 *
 * <p><b>为什么需要</b>：v2.0.15 修复后车机仍闪退，且无日志可查——Android 默认的
 * 未捕获异常处理只把堆栈打进 logcat，App 自己看不到。挂上本守护后，任何线程
 * （主线程 / 采集线程 / 探测回调）崩溃时先把完整堆栈落盘到
 * {@code Download/L6/app-log/l6-crash-<时间戳>.txt}（与运行日志同目录），再交回
 * 系统默认处理（该杀进程照杀，不改变系统行为）。下次复现只需拷这一个文件即可一击定位。
 *
 * <p><b>职责边界</b>：只记录、不拦截、不重启——卸掉 handler 交回 prev 链，
 * 保持系统默认崩溃行为不变；落盘全程 try-catch，守护自身绝不再抛异常。
 *
 * <p><b>挂载点</b>：{@code MainActivity.onCreate}（L6Log.init 之后，保证日志目录可用）。
 * 幂等：重复调用不会叠层。
 */
public final class CrashGuard implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler prev;

    private CrashGuard(Thread.UncaughtExceptionHandler prevHandler) {
        this.prev = prevHandler;
    }

    /** 进程内挂一次即可；重复调用幂等（已挂则不叠层）。 */
    public static void install() {
        try {
            Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
            if (cur instanceof CrashGuard) {
                return;
            }
            Thread.setDefaultUncaughtExceptionHandler(new CrashGuard(cur));
            L6Log.i("L6Crash", "崩溃守护已挂载（崩溃堆栈将落盘 app-log/l6-crash-*.txt）");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        // ★ 先同步落盘——交回系统后进程随时可能被回收，必须赶在那之前写完
        writeCrash(t, e);
        try {
            L6Log.e("L6Crash", "未捕获异常 线程=" + t.getName() + "：" + e);
        } catch (Throwable ignored) {
        }
        Thread.UncaughtExceptionHandler p = prev;
        if (p != null) {
            p.uncaughtException(t, e);
        }
    }

    /** 崩溃堆栈落盘：写到运行日志同目录（app-log/l6-crash-<ts>.txt），失败静默。 */
    private void writeCrash(Thread t, Throwable e) {
        FileWriter w = null;
        try {
            File dir = null;
            File daily = L6Log.logFile();
            if (daily != null && daily.getParentFile() != null) {
                dir = daily.getParentFile();
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
            }
            if (dir == null || !dir.isDirectory()) {
                return;
            }
            File f = new File(dir, "l6-crash-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                    + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                    .format(new Date())).append('\n');
            sb.append("thread=").append(t == null ? "?" : t.getName()).append('\n');
            sb.append("ver=").append(L6Log.appVerPublic()).append('\n');
            sb.append("os=Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("model=").append(android.os.Build.MODEL).append('\n');
            stackTrace(e, sb);
            // 原因链（Caused by）
            Throwable cause = e.getCause();
            while (cause != null && cause != e) {
                sb.append('\n');
                stackTrace(cause, sb);
                cause = cause.getCause();
            }
            w = new FileWriter(f, false);
            w.write(sb.toString());
            w.close();
            w = null;
        } catch (Throwable ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 一个 Throwable 的 toString + 堆栈行（Caused by 复用）。 */
    private static void stackTrace(Throwable e, StringBuilder sb) {
        sb.append(e.toString()).append('\n');
        StackTraceElement[] st = e.getStackTrace();
        for (StackTraceElement el : st) {
            sb.append("    at ").append(el).append('\n');
        }
    }
}

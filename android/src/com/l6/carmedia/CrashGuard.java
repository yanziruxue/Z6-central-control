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
        // ★ v2.0.35：网络上报能力已整体下线，崩溃证据只落本地。
        //   需要外发时由用户在设置页点「上传到 NAS」生成 .txt 后手动上传。
        String text = writeCrash(t, e);
        try {
            L6Log.e("L6Crash", "未捕获异常 线程=" + t.getName() + "：" + e);
        } catch (Throwable ignored) {
        }
        try {
            if (text != null && !text.isEmpty()) {
                L6Log.e("L6Crash", "崩溃堆栈（本地留证，未外发）：\n" + text);
            }
        } catch (Throwable ignored) {
        }
        Thread.UncaughtExceptionHandler p = prev;
        if (p != null) {
            p.uncaughtException(t, e);
        }
    }

    /**
     * v2.0.31：崩溃堆栈落盘。
     * ★ 先无条件生成完整崩溃文本，再逐级尝试目录；旧实现「目录 mkdirs 失败就 return 空串」
     *   正是车机上「闪退了却拿不到任何证据」的原因。
     * ★ v2.0.35：不再自动外发。目录全不可写时仍返回完整文本，由调用方写进运行日志留证。
     */
    private String writeCrash(Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        try {
            Throwable ex = e == null ? new Throwable("(null throwable)") : e;
            sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                    .format(new Date())).append('\n');
            sb.append("thread=").append(t == null ? "?" : t.getName()).append('\n');
            sb.append("ver=").append(L6Log.appVerPublic()).append('\n');
            sb.append("os=Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("model=").append(android.os.Build.MODEL).append('\n');
            stackTrace(ex, sb);
            Throwable cause = ex.getCause();
            while (cause != null && cause != ex) {
                sb.append('\n');
                stackTrace(cause, sb);
                cause = cause.getCause();
            }
        } catch (Throwable ignored) {
            sb.append("崩溃文本生成异常（已尽力保留上述内容）\n");
        }
        final String text = sb.toString();
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        for (File dir : crashDirs()) {
            if (dir == null) continue;
            FileWriter w = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) continue;
                File f = new File(dir, "l6-crash-" + stamp + ".txt");
                w = new FileWriter(f, false);
                w.write(text);
                w.flush();
                w.close();
                w = null;
                if (f.length() > 0) {
                    try { L6Log.i("L6Crash", "崩溃堆栈已落盘 " + f.getAbsolutePath()); }
                    catch (Throwable ignored) { }
                    return text;
                }
            } catch (Throwable ignored) {
            } finally {
                if (w != null) {
                    try { w.close(); } catch (Throwable ignored) { }
                }
            }
        }
        try { L6Log.e("L6Crash", "崩溃堆栈落盘失败（多级目录均不可写），文本保留在运行日志"); }
        catch (Throwable ignored) { }
        return text;
    }

    /** 崩溃日志目录候选：统一 {@link L6Log#logDir()} 优先，其余按无权限成本逐级兜底。 */
    private File[] crashDirs() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        try {
            File d = L6Log.logDir();
            if (d != null) out.add(d);
        } catch (Throwable ignored) { }
        android.content.Context c = L6Log.appContext();
        if (c == null) return out.toArray(new File[0]);
        try {
            File e = c.getExternalFilesDir(null);
            if (e != null) out.add(new File(e, "L6/app-log"));
        } catch (Throwable ignored) { }
        try {
            File f = c.getFilesDir();
            if (f != null) out.add(new File(f, "L6/app-log"));
        } catch (Throwable ignored) { }
        try {
            File k = c.getCacheDir();
            if (k != null) out.add(new File(k, "L6/app-log"));
        } catch (Throwable ignored) { }
        return out.toArray(new File[0]);
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

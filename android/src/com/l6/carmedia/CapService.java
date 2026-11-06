package com.l6.carmedia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 采集保活前台服务（v2.0.13）。
 *
 * <p><b>为什么需要</b>：采集线程（{@link SignalCapture} 的 l6-signal）不绑 Activity，
 * 按 HOME / 切导航 / 熄屏都能活；但进程无前台优先级时，划多任务卡片或 ROM 内存紧张
 * 会连进程带线程一起杀，结构化 JSON 是结束时才打包的，中途被杀=白采。
 *
 * <p><b>职责边界</b>：只做「保活 + 通知」两件事，不碰采集逻辑——
 * <ul>
 *   <li>{@link #start}：采集开始时挂上前台服务，进程提到前台优先级
 *       （划卡片不杀、ROM 杀进程排到最后）</li>
 *   <li>{@link #progress}：采集线程每 10s 心跳调一次，刷新通知文案（用时/变化数）</li>
 *   <li>{@link #stop}：采集落盘后由 {@code finish()} 调用撤掉</li>
 * </ul>
 *
 * <p><b>权限</b>：API 28 起 {@code startForeground} 必需 manifest 声明
 * {@code FOREGROUND_SERVICE}（normal 权限，声明即授予，无运行时弹窗）。
 * v2.0.14 及之前漏声明 ⇒ startForeground 抛异常带崩整个进程（点开始采集闪退），
 * v2.0.15 补上并在此处 try-catch 兜底。通知渠道 IMPORTANCE_LOW 无声无角标。
 * START_NOT_STICKY：进程真被杀了不重启——采集数据已散佚，空跑无意义。
 *
 * <p>★ 唯一启动入口是设置页「开始采集」按钮（App 必在前台），
 *   因此不需要 {@code startForegroundService} 的后台启动兜底；
 *   万一被 ROM 拦（{@code startService} 抛异常）只损失保活，采集线程照跑。
 */
public final class CapService extends Service {

    /** 通知栏「结束采集」动作的 action。 */
    public static final String ACTION_STOP = "com.l6.carmedia.CAP_STOP";

    private static final String CHANNEL_ID = "l6_capture";
    private static final String CHANNEL_NAME = "信号采集保活";
    private static final int NOTIF_ID = 11812;

    /** 服务实例与通知三件套（静态可达：采集线程要刷新通知）。 */
    private static volatile CapService self;
    private static volatile NotificationManager nm;
    private static volatile Notification.Builder builder;
    /** v2.0.16：startForeground 专用的最小通知（仅 icon+标题+渠道，无 action/PendingIntent）。 */
    private static volatile Notification.Builder minBuilder;

    private CapService() {
    }

    /* ==================== 采集侧调用入口 ==================== */

    /** 挂前台服务。null / 被 ROM 拦都只损失通知，不影响采集。 */
    public static void start(Context c) {
        if (c == null) {
            return;
        }
        try {
            c.startService(new Intent(c, CapService.class));
        } catch (Throwable t) {
            L6Log.i("L6Signal", "前台服务启动被拒（不影响采集）：" + t);
        }
    }

    /** 撤前台服务（{@code finish()} 落盘后调用）。 */
    public static void stop(Context c) {
        if (c == null) {
            return;
        }
        try {
            c.stopService(new Intent(c, CapService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 刷新通知文案（采集线程心跳调用，可跨线程）。
     *
     * @param elapsedMs 本次采集已进行毫秒数
     * @param changes   累计保留的变化条数
     */
    public static void progress(long elapsedMs, int changes) {
        Notification.Builder b = builder;
        NotificationManager m = nm;
        if (b == null || m == null) {
            return;
        }
        try {
            b.setContentText(fmt(elapsedMs, changes));
            m.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(long elapsedMs, int changes) {
        long s = Math.max(0L, elapsedMs) / 1000L;
        return "已 " + (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60)
                + " · 变化 " + changes + " 条";
    }

    /* ==================== Service 生命周期 ==================== */

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        // v2.0.31 P0：Service.onCreate 抛异常 ⇒ 系统直接杀进程（点开始采集闪退）。
        // 定制 ROM 上 createNotificationChannel / Notification.Builder / PendingIntent 都可能抛，
        // 全量兜住：通知挂不上只损失通知栏，采集线程照跑。
        try {
            nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    ch.setDescription("采集期间常驻，显示进度，可一键结束");
                    nm.createNotificationChannel(ch);
                } catch (Throwable t) {
                    L6Log.e("L6Signal", "前台服务创建异常（通知渠道）：" + t);
                }
            }
            // 通知小图标：运行时按名找 ic_launcher（构建链不生成 R.java，不能用 R.mipmap）；
            // 找不到就退平台图标，保证通知一定能出。
            int icon = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (icon == 0) {
                icon = android.R.drawable.ic_dialog_info;
            }
            // v2.0.16 P0：最小通知——startForeground 时只挂它。system_server 会对
            // startForeground 提交的通知做严格校验，复杂通知（addAction/contentIntent）在
            // 定制 ROM 上可能被判 Bad notification ⇒ AMS 直接杀进程（app 侧 try-catch 拦不住）。
            try {
                minBuilder = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(icon)
                        .setContentTitle("老六 · 信号采集中")
                        .setOngoing(true);
            } catch (Throwable t) {
                L6Log.e("L6Signal", "前台服务创建异常（最小通知）：" + t);
            }
            try {
                // ★ v2.0.31：action 图标改用真实 smallIcon，不再传 0（部分 ROM 判 Bad notification）
                builder = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(icon)
                        .setContentTitle("老六 · 信号采集中")
                        .setContentText(fmt(0, 0))
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(PendingIntent.getActivity(this, 0,
                                new Intent(this, MainActivity.class),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                        .addAction(icon, "结束采集",
                                PendingIntent.getService(this, 1,
                                        new Intent(this, CapService.class).setAction(ACTION_STOP),
                                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } catch (Throwable t) {
                L6Log.e("L6Signal", "前台服务创建异常（完整通知）：" + t);
            }
        } catch (Throwable t) {
            L6Log.e("L6Signal", "前台服务创建异常（已兜住，不影响采集）：" + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // v2.0.31 P0：Service 生命周期回调绝不让异常冒泡（冒泡 = 进程被杀 = 点采集闪退）
        try {
        // ★ startForeground 必须在 onCreate 后尽早调；★ 必须 try-catch——
        //   API 28 起缺 FOREGROUND_SERVICE 权限（v2.0.14 及之前漏声明）或 ROM 拦截时
        //   这里会抛异常，服务崩溃=整个进程闪退（点开始采集直接退回桌面）。
        //   兜底策略：保活失败只损失通知，采集线程照跑，绝不带崩进程。
        // v2.0.16 P0：先挂「最小通知」（minBuilder，无 action/PendingIntent），
        //   挂稳后再 notify 完整版——把 Bad notification 杀进程的炸点排除在
        //   startForeground 之外（完整版失败只损失按钮/进度，不影响保活）。
        Notification.Builder mb = minBuilder;
        if (mb != null) {
            try {
                startForeground(NOTIF_ID, mb.build());
            } catch (Throwable t) {
                L6Log.e("L6Signal", "startForeground 失败（仅损失保活，采集继续）：" + t);
                stopSelf();
                return START_NOT_STICKY;
            }
            Notification.Builder fb = builder;
            NotificationManager m = nm;
            if (fb != null && m != null) {
                try {
                    m.notify(NOTIF_ID, fb.build());
                } catch (Throwable ignored) {
                    // 完整版挂不上只损失「结束采集」按钮与进度文案，最小常驻通知仍在
                }
            }
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 通知栏结束：只置停止请求，真正的落盘收尾在采集线程；
            // 若采集已自行结束（如到 5min 上限）则立即撤服务。
            SignalCapture.stop();
            if (!SignalCapture.isRunning()) {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
        } catch (Throwable t) {
            L6Log.e("L6Signal", "前台服务启动异常（已兜住，不影响采集）：" + t);
            try { stopSelf(); } catch (Throwable ignored) { }
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        self = null;
        builder = null;
        minBuilder = null;
        nm = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

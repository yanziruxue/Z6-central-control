package com.l6.carmedia;

/**
 * 悬浮「返回」按钮的「算不算点按」判定（v2.0.2 新增）。
 *
 * <h3>为什么抽成纯逻辑类</h3>
 * 这段判定原先直接写在 {@code FloatNav} 的 {@code onTouch} 里，硬编码「位移 &lt; 8 物理像素」。
 * 车机（1920×720 / density 160）上 8px = 8dp，手感正常；<b>但手机 density 2.6 时 8px 只有 3dp、
 * density 4 时只有 2dp</b> —— 手指按下时的正常抖动就有 3~8dp，于是<b>每一次点击都被判成「拖动」</b>，
 * 走 {@code MOVE} 分支只改窗口位置，永不调 {@code bringToFront} ⇒ 悬浮窗明明在，点它就是没反应。
 * （用户是「手机代车机」实测复现的，车机屏上不会犯病，所以之前一直没人发现。）
 *
 * <h3>为什么必须按 dp 判</h3>
 * {@code MotionEvent.getRawX()} 返回的是<b>物理像素</b>，而「手指算不算抖」这件事本质上是
 * 跟物理尺寸有关的。Android 自己的 {@code ViewConfiguration.getScaledTouchSlop()} 也是 dp（8dp）。
 * 所以阈值必须走 dp→px 换算，不能写死。
 *
 * <h3>为什么要抽出来测</h3>
 * 这块在真车上没法反复复现（要先装导航 App、要弹到前台、手机/车机两种密度），
 * 所以按本项目既有惯例（{@code NavParse} / {@code LrcParse} / {@code SigDiff}）抽成零 Android 依赖的
 * 纯函数，在 {@code tools/LogicSmoke.java} 里把三种密度与拖动边界全部钉死。
 *
 * <p>零依赖：只用 {@code java.lang.Math}，不引用任何 Android 类。
 */
public final class TapJudge {

    /** 位移容差（dp）。取 12dp：比系统 8dp 宽裕一档，手指按下与抬起之间的自然抖动不会越界。 */
    public static final int TAP_SLOP_DP = 12;

    /** 按住时长上限（ms）。超过即视为「长按 / 拖动」，不当作点按。 */
    public static final long TAP_TIMEOUT_MS = 600L;

    private TapJudge() {
    }

    /**
     * dp → px。
     *
     * @param density {@code DisplayMetrics.density}（车机 1.0 / 常见手机 2.625 / 旗舰 4.0）；
     *                传 0 或负数时按 1.0 兜底，避免把所有点击都判成拖动。
     */
    public static float dpToPx(float dp, float density) {
        float d = (density > 0f) ? density : 1f;
        return dp * d;
    }

    /**
     * 这次 ACTION_UP 算不算「点按」。
     *
     * @param dxPx    抬手相对按下点的横向位移（物理像素，可正可负，按绝对值判）
     * @param dyPx    纵向位移（同上）
     * @param durMs   从按下到抬起的时长（ms）
     * @param density 屏幕密度
     */
    public static boolean isTap(float dxPx, float dyPx, long durMs, float density) {
        float slop = dpToPx(TAP_SLOP_DP, density);
        if (Math.abs(dxPx) > slop || Math.abs(dyPx) > slop) {
            return false;
        }
        return durMs >= 0L && durMs <= TAP_TIMEOUT_MS;
    }

    /** 判定依据的可读描述，直接进落盘日志 —— 真机复现时不用再猜是哪一环坏了。 */
    public static String describe(float dxPx, float dyPx, long durMs, float density) {
        float slop = dpToPx(TAP_SLOP_DP, density);
        return "dx=" + dxPx + "px dy=" + dyPx + "px dur=" + durMs + "ms"
                + " density=" + density + " slop=" + slop + "px"
                + " ⇒ " + (isTap(dxPx, dyPx, durMs, density) ? "点按" : "拖动");
    }
}

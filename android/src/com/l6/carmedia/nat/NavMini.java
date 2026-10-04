package com.l6.carmedia.nat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 顶部迷你导航卡片（对应原型 {@code #miniNav}）。
 *
 * ★ 只在**真实导航中**才显示：原型靠 {@code #miniNav.show} 这个 class 切换 display，
 *   这里的默认态就是 GONE —— 车机上一条常驻的空卡片比没有更糟（会盖住主页内容）。
 */
public class NavMini extends LinearLayout {

    private final TextView arr, turn, meta;

    public NavMini(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(U.bg(0xD1080C14, 12, U.LINE, 1));
        U.pad(this, 14, 8, 14, 8);
        U.gapH(this, 12);
        setVisibility(GONE);

        arr = U.text(c, "\u21B1", 26, U.ACC);
        addView(arr);

        turn = U.text(c, "", 14, U.TXT);
        turn.setTypeface(turn.getTypeface(), android.graphics.Typeface.BOLD);
        turn.setSingleLine(true);
        turn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(turn, new LinearLayout.LayoutParams(0, U.WC, 1f));

        meta = U.text(c, "", 12, U.SUB);
        meta.setSingleLine(true);
        meta.setGravity(Gravity.END);
        addView(meta);
    }

    /** 每秒一次的系统数据；导航未激活就整块隐藏 */
    public void onSys(JSONObject sys) {
        JSONObject n = sys == null ? null : sys.optJSONObject("nav");
        boolean active = n != null && n.optBoolean("active", false);
        if (!active) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        arr.setText(n.optString("arr", "\u21B1"));
        String t = n.optString("turn", "");
        turn.setText(t.isEmpty() ? n.optString("road", "导航中") : t);
        String dist = n.optString("dist", "--");
        String eta = n.optString("eta", "");
        String dest = n.optString("dest", "");
        StringBuilder sb = new StringBuilder("剩余 ").append(dist);
        if (!eta.isEmpty()) sb.append(" · 约 ").append(eta);
        if (!dest.isEmpty()) sb.append(" · 到 ").append(dest);
        meta.setText(sb);
    }
}

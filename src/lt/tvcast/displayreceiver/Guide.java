package lt.tvcast.displayreceiver;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The two full-screen explanations: how to use the app (three pictured steps) and what 5 GHz mode is,
 * why it exists and what it does. They are shown over the main screen and closed with a button or Back.
 */
final class Guide {
    private Guide() { }

    private static LinearLayout page(MainActivity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(a.dp(56), a.dp(24), a.dp(56), a.dp(22));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF0B0D1A, 0xFF1B1840 }));
        return root;
    }

    private static LinearLayout.LayoutParams weight(float w, int leftDp, MainActivity a) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        p.leftMargin = a.dp(leftDp);
        return p;
    }

    /** How to use the app. The returned view carries its main button as the tag, to take the focus. */
    static View help(final MainActivity a, final Runnable close) {
        LinearLayout root = page(a);
        TextView title = a.text(30, MainActivity.INK, "sans-serif-light");
        title.setText(R.string.help_title);
        root.addView(title);

        LinearLayout row = new LinearLayout(a);
        int[] pictures = { PictureView.PHONE_QUICK_SETTINGS, PictureView.PICK_THE_TV, PictureView.BIG_SCREEN };
        int[] titles = { R.string.help1_title, R.string.help2_title, R.string.help3_title };
        int[] texts = { R.string.help1_text, R.string.help2_text, R.string.help3_text };
        for (int i = 0; i < 3; i++) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
            card.setBackground(MainActivity.rounded(0x1AFFFFFF, a.dp(22), 0));
            card.addView(new PictureView(a, pictures[i]),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(150)));
            TextView t = a.text(18, MainActivity.INK, "sans-serif-medium");
            t.setGravity(Gravity.START);
            t.setText(titles[i]);
            card.addView(t, a.margin(12, true));
            TextView body = a.text(15, MainActivity.INK_SOFT, "sans-serif");
            body.setGravity(Gravity.START);
            body.setText(texts[i]);
            card.addView(body, a.margin(6, true));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            cardLp.leftMargin = a.dp(i == 0 ? 0 : 16);
            row.addView(card, cardLp);
        }
        root.addView(row, a.margin(16, true));

        TextView tip = a.text(15, MainActivity.BUSY_AMBER, "sans-serif-medium");
        tip.setText(R.string.help_tip);
        tip.setMaxWidth(a.dp(780));
        root.addView(tip, a.margin(14, false));

        Button ok = a.pill();
        ok.setText(R.string.close);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close.run(); }
        });
        root.addView(ok, a.margin(14, false));
        root.setTag(ok);
        return root;
    }

    /**
     * What 5 GHz mode is. {@code on} is the current state: when it is off the buttons turn it on, when
     * it is on they turn it off.
     */
    static View five(final MainActivity a, boolean on, final Runnable change, final Runnable close) {
        LinearLayout root = page(a);
        TextView title = a.text(30, MainActivity.INK, "sans-serif-light");
        title.setText(R.string.five_title);
        root.addView(title);

        LinearLayout body = new LinearLayout(a);
        body.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        left.setBackground(MainActivity.rounded(0x1AFFFFFF, a.dp(22), 0));
        left.addView(new PictureView(a, PictureView.RESOLUTION),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(120)));
        TextView c1 = a.text(13, MainActivity.INK_FAINT, "sans-serif");
        c1.setText(R.string.five_pic1);
        left.addView(c1, a.margin(4, true));
        left.addView(new PictureView(a, PictureView.FIVE_STEPS),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(96)));
        TextView c2 = a.text(13, MainActivity.INK_FAINT, "sans-serif");
        c2.setText(R.string.five_pic2);
        left.addView(c2, a.margin(2, true));
        body.addView(left, weight(4, 0, a));

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        int[] heads = { R.string.five_why_title, R.string.five_how_title, R.string.five_safe_title };
        int[] texts = { R.string.five_why, R.string.five_how, R.string.five_safe };
        for (int i = 0; i < 3; i++) {
            TextView h = a.text(17, i == 2 ? MainActivity.OK_GREEN : MainActivity.INK, "sans-serif-medium");
            h.setGravity(Gravity.START);
            h.setText(heads[i]);
            right.addView(h, a.margin(i == 0 ? 0 : 10, true));
            TextView t = a.text(15, MainActivity.INK_SOFT, "sans-serif");
            t.setGravity(Gravity.START);
            t.setText(texts[i]);
            right.addView(t, a.margin(3, true));
        }
        body.addView(right, weight(6, 28, a));
        root.addView(body, a.margin(14, true));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER);
        Button go = a.pill();
        go.setText(on ? R.string.five_turn_off : R.string.five_turn_on);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { change.run(); }
        });
        Button no = a.pill();
        no.setText(on ? R.string.five_keep : R.string.cancel);
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close.run(); }
        });
        buttons.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = a.dp(16);
        buttons.addView(no, lp);
        root.addView(buttons, a.margin(16, false));
        // when it is on, the safe answer is to leave it as it is; when off, "turn on" is the thing asked for
        root.setTag(on ? no : go);
        return root;
    }
}

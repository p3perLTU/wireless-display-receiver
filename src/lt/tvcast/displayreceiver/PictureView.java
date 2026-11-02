package lt.tvcast.displayreceiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The little illustrations of the instruction screens, drawn in code (no picture files): each one is
 * a simple flat scene in the colours of the app. The scene is chosen by {@code kind} and drawn to fit
 * whatever size the view gets.
 */
final class PictureView extends View {
    static final int PHONE_QUICK_SETTINGS = 1, PICK_THE_TV = 2, BIG_SCREEN = 3, RESOLUTION = 4, FIVE_STEPS = 5;

    private static final int BLUE = 0xFF3884FF, PURPLE = 0xFF7C4DFF, GREEN = 0xFF6BDB8B, AMBER = 0xFFFFC069;
    private static final int LINE = 0x66FFFFFF, FILL = 0x1FFFFFFF, BRIGHT = 0xE6FFFFFF;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final float d;

    PictureView(Context c, int kind) {
        super(c);
        this.kind = kind;
        this.d = c.getResources().getDisplayMetrics().density;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        switch (kind) {
            case PHONE_QUICK_SETTINGS: quickSettings(c, w, h); break;
            case PICK_THE_TV: pick(c, w, h); break;
            case BIG_SCREEN: bigScreen(c, w, h); break;
            case RESOLUTION: resolution(c, w, h); break;
            case FIVE_STEPS: fiveSteps(c, w, h); break;
            default: break;
        }
    }

    // ---- scenes ----

    /** A phone with the quick settings pulled down: the DeX tile lit. */
    private void quickSettings(Canvas c, float w, float h) {
        float pw = Math.min(w * 0.46f, h * 0.62f), ph = h * 0.94f;
        float x = (w - pw) / 2, y = (h - ph) / 2;
        phone(c, x, y, pw, ph);
        float gap = pw * 0.07f, tile = (pw - gap * 5) / 4f;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                float tx = x + gap + col * (tile + gap), ty = y + ph * 0.16f + row * (tile + gap);
                boolean dex = row == 0 && col == 1;
                if (dex) {
                    p.setShader(null);
                    p.setColor(0xFF5A6BFF);
                } else {
                    p.setShader(null);
                    p.setColor(FILL);
                }
                r.set(tx, ty, tx + tile, ty + tile);
                c.drawRoundRect(r, tile * 0.3f, tile * 0.3f, p);
                p.setShader(null);
                if (dex) monitor(c, tx + tile * 0.2f, ty + tile * 0.25f, tile * 0.6f, tile * 0.45f, 0xFFFFFFFF);
            }
        }
        // a slider under the tiles
        p.setColor(FILL);
        r.set(x + gap, y + ph * 0.52f, x + pw - gap, y + ph * 0.52f + tile * 0.28f);
        c.drawRoundRect(r, tile, tile, p);
        p.setColor(LINE);
        r.set(x + gap, y + ph * 0.52f, x + gap + (pw - 2 * gap) * 0.6f, y + ph * 0.52f + tile * 0.28f);
        c.drawRoundRect(r, tile, tile, p);
    }

    /** A list of displays on the phone; ours is highlighted and ticked. */
    private void pick(Canvas c, float w, float h) {
        float lw = w * 0.86f, lh = h * 0.24f, x = (w - lw) / 2, gap = h * 0.07f;
        float top = (h - (3 * lh + 2 * gap)) / 2;
        for (int i = 0; i < 3; i++) {
            float y = top + i * (lh + gap);
            boolean ours = i == 1;
            if (ours) fillGradient(x, y, x + lw, y + lh, 0x663884FF, 0x667C4DFF);
            else { p.setShader(null); p.setColor(FILL); }
            r.set(x, y, x + lw, y + lh);
            c.drawRoundRect(r, lh * 0.3f, lh * 0.3f, p);
            p.setShader(null);
            tv(c, x + lh * 0.3f, y + lh * 0.2f, lh * 0.9f, lh * 0.6f, ours ? BRIGHT : LINE);
            // the name, as a bar
            p.setColor(ours ? BRIGHT : LINE);
            r.set(x + lh * 1.5f, y + lh * 0.38f, x + lh * 1.5f + lw * (ours ? 0.42f : 0.3f), y + lh * 0.62f);
            c.drawRoundRect(r, lh * 0.12f, lh * 0.12f, p);
            if (ours) {
                p.setColor(GREEN);
                c.drawCircle(x + lw - lh * 0.55f, y + lh * 0.5f, lh * 0.28f, p);
                tick(c, x + lw - lh * 0.55f, y + lh * 0.5f, lh * 0.15f, 0xFF10231A);
            }
        }
    }

    /** A TV with two windows and a task bar: DeX on the big screen. */
    private void bigScreen(Canvas c, float w, float h) {
        float tw = w * 0.86f, th = h * 0.74f, x = (w - tw) / 2, y = h * 0.05f;
        p.setShader(null);
        p.setColor(LINE);
        r.set(x, y, x + tw, y + th);
        c.drawRoundRect(r, th * 0.05f, th * 0.05f, p);
        fillGradient(x + 4 * d, y + 4 * d, x + tw - 4 * d, y + th - 4 * d, 0xFF2A2F6B, 0xFF171A3F);
        r.set(x + 4 * d, y + 4 * d, x + tw - 4 * d, y + th - 4 * d);
        c.drawRoundRect(r, th * 0.04f, th * 0.04f, p);
        p.setShader(null);
        window(c, x + tw * 0.07f, y + th * 0.14f, tw * 0.46f, th * 0.62f, 0xFFF3F4FA);
        window(c, x + tw * 0.40f, y + th * 0.26f, tw * 0.5f, th * 0.56f, 0xFF20233F);
        // task bar and the stand
        p.setColor(0x55000000);
        r.set(x + 4 * d, y + th - 4 * d - th * 0.1f, x + tw - 4 * d, y + th - 4 * d);
        c.drawRect(r, p);
        p.setColor(LINE);
        r.set(w * 0.42f, y + th, w * 0.58f, y + th + h * 0.07f);
        c.drawRect(r, p);
        r.set(w * 0.32f, y + th + h * 0.07f, w * 0.68f, y + th + h * 0.1f);
        c.drawRoundRect(r, 4 * d, 4 * d, p);
    }

    /** The same TV twice: coarse blocks for 1080p, a fine grid for 2560x1440. */
    private void resolution(Canvas c, float w, float h) {
        float tw = w * 0.44f, th = h * 0.52f, y = h * 0.1f;
        screenGrid(c, w * 0.04f, y, tw, th, 5, 3, LINE);
        screenGrid(c, w * 0.52f, y, tw, th, 13, 7, BRIGHT);
        p.setColor(LINE);
        p.setTextSize(h * 0.15f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("1080p", w * 0.04f + tw / 2, h * 0.88f, p);
        p.setColor(BRIGHT);
        c.drawText("2560×1440", w * 0.52f + tw / 2, h * 0.88f, p);
        // an arrow between them
        p.setColor(AMBER);
        p.setStrokeWidth(3 * d);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        float ay = y + th / 2;
        c.drawLine(w * 0.487f, ay, w * 0.513f, ay, p);
        path.reset();
        path.moveTo(w * 0.506f, ay - 0.05f * h);
        path.lineTo(w * 0.514f, ay);
        path.lineTo(w * 0.506f, ay + 0.05f * h);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** Three things that happen: USB debugging on, "Allow" pressed, Wi-Fi restarted. */
    private void fiveSteps(Canvas c, float w, float h) {
        float cell = w / 3f, cy = h * 0.42f, rad = Math.min(cell * 0.36f, h * 0.3f);
        for (int i = 0; i < 3; i++) {
            float cx = cell * (i + 0.5f);
            p.setColor(FILL);
            c.drawCircle(cx, cy, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(LINE);
            c.drawCircle(cx, cy, rad, p);
            p.setStyle(Paint.Style.FILL);
            if (i == 0) toggle(c, cx, cy, rad * 0.85f);
            else if (i == 1) { p.setColor(GREEN); c.drawCircle(cx, cy, rad * 0.5f, p); tick(c, cx, cy, rad * 0.26f, 0xFF10231A); }
            else wifi(c, cx, cy + rad * 0.3f, rad * 0.95f, BRIGHT);
            p.setColor(BRIGHT);
            p.setTextSize(h * 0.17f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(i + 1), cx, h * 0.92f, p);
            if (i < 2) {
                p.setColor(LINE);
                path.reset();
                float ax = cell * (i + 1);
                path.moveTo(ax - rad * 0.2f, cy - rad * 0.2f);
                path.lineTo(ax + rad * 0.2f, cy);
                path.lineTo(ax - rad * 0.2f, cy + rad * 0.2f);
                path.close();
                c.drawPath(path, p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ---- pieces ----

    private void phone(Canvas c, float x, float y, float w, float h) {
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, w * 0.14f, w * 0.14f, p);
        p.setColor(0xFF12142E);
        r.set(x + 2.5f * d, y + 2.5f * d, x + w - 2.5f * d, y + h - 2.5f * d);
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, p);
        p.setColor(LINE);
        c.drawCircle(x + w / 2, y + h * 0.04f, w * 0.025f, p);
    }

    private void tv(Canvas c, float x, float y, float w, float h, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(color);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, h * 0.12f, h * 0.12f, p);
        p.setStyle(Paint.Style.FILL);
        r.set(x + w * 0.3f, y + h + 2 * d, x + w * 0.7f, y + h + 4 * d);
        c.drawRoundRect(r, 2 * d, 2 * d, p);
    }

    private void monitor(Canvas c, float x, float y, float w, float h, int color) {
        tv(c, x, y, w, h, color);
    }

    private void window(Canvas c, float x, float y, float w, float h, int body) {
        p.setShader(null);
        p.setColor(0xFF3A3F72);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 5 * d, 5 * d, p);
        p.setColor(body);
        r.set(x, y + h * 0.17f, x + w, y + h);
        c.drawRoundRect(r, 5 * d, 5 * d, p);
        p.setColor(0xFF3A3F72);
        r.set(x, y + h * 0.17f, x + w, y + h * 0.3f);
        c.drawRect(r, p);
        p.setColor(body == 0xFFF3F4FA ? 0x22000000 : 0x33FFFFFF);
        for (int i = 0; i < 3; i++) {
            r.set(x + w * 0.1f, y + h * (0.42f + i * 0.17f), x + w * (i == 2 ? 0.55f : 0.9f), y + h * (0.50f + i * 0.17f));
            c.drawRoundRect(r, 3 * d, 3 * d, p);
        }
    }

    private void screenGrid(Canvas c, float x, float y, float w, float h, int cols, int rows, int color) {
        p.setShader(null);
        p.setColor(0xFF12142E);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 4 * d, 4 * d, p);
        p.setColor(color);
        float gap = Math.max(1f, d * (cols > 8 ? 0.8f : 2f));
        float cw = (w - gap * (cols + 1)) / cols, chh = (h - gap * (rows + 1)) / rows;
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                float cx = x + gap + i * (cw + gap), cy = y + gap + j * (chh + gap);
                p.setColor((i + j) % 3 == 0 ? color : (color & 0x00FFFFFF) | 0x33000000);
                c.drawRect(cx, cy, cx + cw, cy + chh, p);
            }
        }
        p.setColor(LINE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 4 * d, 4 * d, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void toggle(Canvas c, float cx, float cy, float s) {
        p.setColor(GREEN);
        r.set(cx - s * 0.5f, cy - s * 0.25f, cx + s * 0.5f, cy + s * 0.25f);
        c.drawRoundRect(r, s * 0.25f, s * 0.25f, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx + s * 0.25f, cy, s * 0.19f, p);
    }

    private void tick(Canvas c, float cx, float cy, float s, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.45f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        path.reset();
        path.moveTo(cx - s * 0.9f, cy);
        path.lineTo(cx - s * 0.25f, cy + s * 0.65f);
        path.lineTo(cx + s * 0.95f, cy - s * 0.65f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void wifi(Canvas c, float cx, float cy, float s, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.1f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        for (int i = 1; i <= 3; i++) {
            float rr = s * 0.3f * i;
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(r, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, s * 0.07f, p);
    }

    private void fillGradient(float x0, float y0, float x1, float y1, int a, int b) {
        p.setShader(new LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP));
    }
}

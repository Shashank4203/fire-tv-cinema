package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.View;

import java.util.function.IntConsumer;

/**
 * The light strip's brightness beside the light icon: a slim track with the level filled in.
 * OK starts adjusting (the track turns the accent colour and grows a knob); then Left and Right
 * change it in 10 % steps, and OK, Back or four seconds without a key finish. While it isn't being
 * adjusted, Left and Right move focus as everywhere else on the top bar.
 */
final class BrightnessBar extends View {
    private static final long IDLE_MS = 4000;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final IntConsumer changed;
    private final Runnable stop = () -> editing(false);
    private int percent;
    private boolean editing;

    BrightnessBar(Context c, int percent, int accent, IntConsumer changed) {
        super(c);
        this.percent = percent;
        this.accent = accent;
        this.changed = changed;
        setFocusable(true);
        setClickable(true);
        setContentDescription("Light brightness");
        setAlpha(0.7f);
        setOnClickListener(v -> editing(!editing));
        setOnFocusChangeListener((v, has) -> {
            if (!has) editing(false);
            animate().alpha(has ? 1f : 0.7f).setDuration(200).start();
        });
    }

    void set(int value) {
        percent = value;
        invalidate();
    }

    private void editing(boolean on) {
        editing = on;
        removeCallbacks(stop);
        if (on) postDelayed(stop, IDLE_MS);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (editing && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int next = Math.max(10, Math.min(100, percent + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10)));
            if (next != percent) {
                percent = next;
                changed.accept(percent);
                invalidate();
            }
            editing(true); // four more seconds
            return true;
        }
        if (editing && code == KeyEvent.KEYCODE_BACK) {
            editing(false);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float h = (editing ? 8 : 5) * d, r = h / 2;
        float left = 8 * d, right = getWidth() - 8 * d, cy = getHeight() / 2f;
        paint.setColor(0x40FFFFFF);
        c.drawRoundRect(left, cy - r, right, cy + r, r, r, paint);
        float end = left + (right - left) * percent / 100f;
        paint.setColor(editing ? accent : 0xFFFFFFFF);
        c.drawRoundRect(left, cy - r, Math.max(left + h, end), cy + r, r, r, paint);
        if (editing) {
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(Math.max(left + r, end), cy, 8 * d, paint);
        }
    }
}

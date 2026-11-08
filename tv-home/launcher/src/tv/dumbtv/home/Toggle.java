package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** An on/off switch drawn as a pill with a knob: the accent colour and the knob right when on. */
final class Toggle extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private boolean on;

    Toggle(Context c, boolean on, int accent) {
        super(c);
        this.on = on;
        this.accent = accent;
    }

    void set(boolean value) {
        on = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        paint.setColor(on ? accent : 0x40FFFFFF);
        c.drawRoundRect(0, 0, w, h, r, r, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(on ? w - r : r, r, r * 0.76f, paint);
    }
}

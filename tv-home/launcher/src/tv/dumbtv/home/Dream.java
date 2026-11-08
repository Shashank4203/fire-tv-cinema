package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's screensaver, in place of Amazon's: the home screen's wallpapers drifting and
 * crossfading, with the time, the weather and the film each one comes from. Any key wakes it.
 * Set as the screensaver with: settings put secure screensaver_components tv.dumbtv.home/.Dream
 */
public final class Dream extends DreamService {
    private static final long NEXT_AFTER = 40_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ImageView[] walls = new ImageView[2];
    private final Runnable next = this::next;
    private File dir;
    private List<File> all;
    private int index, front;
    private boolean shownOnce;
    private TextView clock, temp, caption, line;
    private ImageView wxIcon;
    private ValueAnimator drift;
    private long weatherAt;

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        dir = new File(getExternalFilesDir(null), "cinema");
        setContentView(build());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        all = Walls.list(dir);
        index = getSharedPreferences("cinema", MODE_PRIVATE).getInt("wall", 0);
        registerReceiver(time, new IntentFilter(Intent.ACTION_TIME_TICK));
        tick();
        next();
        startDrift();
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacks(next);
        if (drift != null) drift.cancel();
        try {
            unregisterReceiver(time);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout stage = new FrameLayout(this);
        for (int i = 0; i < 2; i++) {
            ImageView w = new ImageView(this);
            w.setScaleType(ImageView.ScaleType.CENTER_CROP);
            w.setAlpha(0f);
            walls[i] = w;
            stage.addView(w, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xB3000000, 0x33000000, 0x40000000, 0xB3000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        Typeface light = font("Outfit-Light.ttf", Ui.LIGHT), regular = font("Outfit-Regular.ttf", Ui.MEDIUM),
                medium = font("Outfit-Medium.ttf", Ui.MEDIUM);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(light, 54, 0xFFF4F4F6);
        top.addView(clock);
        wxIcon = new ImageView(this);
        wxIcon.setAlpha(0.85f);
        wxIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.leftMargin = dp(26);
        top.addView(wxIcon, ilp);
        temp = text(regular, 19, 0xD9FFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(8);
        top.addView(temp, tlp);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        topLp.setMargins(dp(56), dp(34), 0, 0);
        root.addView(top, topLp);

        caption = text(medium, 11, 0x99FFFFFF);
        caption.setLetterSpacing(0.24f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        clp.setMargins(dp(58), 0, 0, dp(40));
        root.addView(caption, clp);

        // A line from the film, bottom right.
        line = text(light, 21, 0xE6FFFFFF);
        line.setGravity(Gravity.END);
        line.setLineSpacing(0, 1.12f);
        line.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 2 / 5);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        llp.setMargins(0, 0, dp(58), dp(36));
        root.addView(line, llp);
        return root;
    }

    /** The next wallpaper, crossfaded, and its film's name. */
    private void next() {
        ui.removeCallbacks(next);
        if (all == null || all.isEmpty()) return;
        File f = all.get(Math.floorMod(index++, all.size()));
        String c = Walls.caption(f), q = Walls.quote(f);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        io.execute(() -> {
            Bitmap b = Walls.decode(f, w, h);
            ui.post(() -> {
                if (b == null) return;
                ImageView in = walls[1 - front], out = walls[front];
                in.setImageBitmap(b);
                int ms = shownOnce ? 2000 : 800;
                in.animate().alpha(1f).setDuration(ms).start();
                out.animate().alpha(0f).setDuration(ms).start();
                front = 1 - front;
                shownOnce = true;
                caption.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    caption.setText(c == null ? "" : c.toUpperCase(Locale.ROOT));
                    caption.animate().alpha(1f).setDuration(900).start();
                }).start();
                line.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    line.setText(q == null ? "" : "\u201C" + q + "\u201D");
                    line.animate().alpha(1f).setStartDelay(400).setDuration(1200).start();
                }).start();
            });
        });
        ui.postDelayed(next, NEXT_AFTER);
    }

    private void startDrift() {
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(38_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            for (ImageView w : walls) {
                w.setScaleX(1.04f + 0.06f * v);
                w.setScaleY(1.04f + 0.06f * v);
                w.setTranslationX(-dp(14) * v);
            }
        });
        drift.start();
    }

    private void tick() {
        Date now = new Date();
        if (DateFormat.is24HourFormat(this)) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now).toString().toUpperCase(Locale.ROOT));
            half.setSpan(new RelativeSizeSpan(0.3f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        if (System.currentTimeMillis() - weatherAt > Weather.EVERY) {
            weatherAt = System.currentTimeMillis();
            io.execute(() -> {
                Weather w = Weather.fetch(dir);
                ui.post(() -> {
                    if (w == null) {
                        weatherAt = System.currentTimeMillis() - Weather.EVERY + 5 * 60_000L;
                        return;
                    }
                    temp.setText(w.temperature + "°");
                    wxIcon.setImageDrawable(getDrawable(w.icon));
                    wxIcon.setVisibility(View.VISIBLE);
                });
            });
        }
    }

    private Typeface font(String name, Typeface fallback) {
        File f = new File(dir, "fonts/" + name);
        try {
            return f.isFile() ? Typeface.createFromFile(f) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView v = new TextView(this);
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(dp(8), 0, 0, 0x99000000);
        return v;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}

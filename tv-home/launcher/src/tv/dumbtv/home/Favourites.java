package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "My Favourites": characters standing over the wallpaper in black and white. The selected one
 * turns to colour, grows and glows in its own colour; front-row characters step in front of
 * everyone, back-row ones stay behind the front row. Laid out from cinema/favourites.txt, the
 * same file tools/preview_lineup.py renders on the Mac.
 */
final class Favourites extends FrameLayout {
    static final class Character {
        String file, title, app, link, sub;
        File cutout, scene;
        boolean back;
        float cx, bottom, h, aspect, face;
        int color;
        ImageView view;
        ValueAnimator tone;
        float saturation, brightness = 0.84f;
    }

    private static final float BASE = 0.84f, DIMMED = 0.6f;

    final List<Character> all = new ArrayList<>();
    private final File dir;
    private final View glow;
    private final GradientDrawable glowShape;
    private final TextView title, sub;
    private final ImageView appLogo;
    private int selected = -1, glowColor;
    private ValueAnimator glowAnimation;
    private boolean loaded;

    Favourites(Context c, File dir, Typeface light, Typeface medium) {
        super(c);
        this.dir = dir;
        setClipChildren(false);

        glowShape = new GradientDrawable();
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setColors(new int[] {Color.TRANSPARENT, Color.TRANSPARENT});
        glow = new View(c);
        glow.setBackground(glowShape);
        addView(glow, new LayoutParams(0, 0));

        // Legs fade into black so the section ends softly.
        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.TRANSPARENT, 0xB3000000, 0xFF000000}));
        fade.setTranslationZ(40);
        addView(fade, new LayoutParams(LayoutParams.MATCH_PARENT, dp(150), Gravity.BOTTOM));

        TextView header = new TextView(c);
        header.setText(new File(dir, "pool/pool.txt").isFile() ? "SUGGESTIONS   ·   NEW & 7.5+ ON IMDb" : "SUGGESTIONS");
        header.setTypeface(medium);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.setLetterSpacing(0.3f);
        header.setTextColor(0x80FFFFFF);
        header.setTranslationZ(50);
        LayoutParams hlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hlp.setMargins(dp(56), dp(40), 0, 0);
        addView(header, hlp);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setTranslationZ(50);
        title = new TextView(c);
        title.setTypeface(light);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(0xF2FFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(12), 0, 0, 0xCC000000);
        top.addView(title);
        sub = new TextView(c);
        sub.setTypeface(medium);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setLetterSpacing(0.08f);
        sub.setTextColor(0xB3FFFFFF);
        sub.setVisibility(View.GONE);
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sublp.topMargin = dp(10);
        top.addView(sub, sublp);
        appLogo = new ImageView(c);
        appLogo.setAlpha(0.75f);
        appLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(110), dp(22));
        alp.topMargin = dp(12);
        top.addView(appLogo, alp);
        LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.topMargin = dp(70);
        addView(top, tlp);
    }

    /**
     * The characters to show this time: a fresh mix from the refreshed pool when there is one,
     * otherwise the hand-placed favourites.txt. Off the UI thread.
     */
    List<Character> read(int screenW, int screenH) {
        List<Character> pool = pick();
        return pool.size() >= 6 ? pool : fixed();
    }

    /** Back-row and front-row slots for a picked set; the wider figures get the roomier back row. */
    private static final float[] BACK_X = {0.17f, 0.50f, 0.83f}, FRONT_X = {0.07f, 0.29f, 0.51f, 0.72f, 0.93f};

    private List<String> lastPick = new ArrayList<>();

    /**
     * Eight titles from pool/pool.txt, half movies and half series where possible, avoiding the
     * previous set, laid out automatically from each cut-out's shape.
     */
    private List<Character> pick() {
        List<Character> movies = new ArrayList<>(), series = new ArrayList<>();
        File f = new File(dir, "pool/pool.txt");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] p = t.split(" \\| ");
                if (p.length < 9) continue;
                Character ch = new Character();
                ch.file = p[0].trim();
                ch.title = p[1].trim();
                boolean movie = "movie".equals(p[2].trim());
                ch.app = p[6].trim();
                ch.link = p.length > 10 ? p[10].trim() : "-";
                ch.cutout = new File(dir, "pool/cutouts/" + ch.file + ".webp");
                ch.scene = new File(dir, "pool/scenes/" + ch.file + ".jpg");
                try {
                    ch.aspect = Float.parseFloat(p[7].trim());
                    ch.color = Color.parseColor(p[8].trim());
                    if (p.length > 9) ch.face = Float.parseFloat(p[9].trim());
                    ch.sub = "★ " + p[4].trim() + " IMDb   ·   " + p[3].trim() + "   ·   " + (movie ? "Film" : "Series")
                            + (p.length > 12 && !p[12].trim().isEmpty() ? "   \u00B7   " + p[12].trim() : "")
                            + (p.length > 11 && ("," + p[11].trim() + ",").contains(",seen,") ? "   \u00B7   Seen" : "");
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!ch.cutout.isFile()) continue;
                (movie ? movies : series).add(ch);
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }
        java.util.Random rnd = new java.util.Random();
        List<Character> chosen = new ArrayList<>();
        for (List<Character> kind : java.util.Arrays.asList(movies, series)) {
            java.util.Collections.shuffle(kind, rnd);
            // Titles from last time go to the end, so each visit shows new faces.
            List<Character> fresh = new ArrayList<>(), seen = new ArrayList<>();
            for (Character ch : kind) (lastPick.contains(ch.file) ? seen : fresh).add(ch);
            fresh.addAll(seen);
            kind.clear();
            kind.addAll(fresh);
        }
        for (int i = 0; chosen.size() < 8 && (i < movies.size() || i < series.size()); i++) {
            if (i < movies.size()) chosen.add(movies.get(i));
            if (chosen.size() < 8 && i < series.size()) chosen.add(series.get(i));
        }
        lastPick = new ArrayList<>();
        for (Character ch : chosen) lastPick.add(ch.file);

        // Widest three to the back row, the rest to the front; shuffled within each row.
        List<Character> byWidth = new ArrayList<>(chosen);
        java.util.Collections.sort(byWidth, (a, b) -> Float.compare(b.aspect, a.aspect));
        List<Character> back = new ArrayList<>(byWidth.subList(0, Math.min(3, byWidth.size())));
        List<Character> front = new ArrayList<>(byWidth.subList(back.size(), byWidth.size()));
        java.util.Collections.shuffle(back, rnd);
        java.util.Collections.shuffle(front, rnd);
        List<Character> out = new ArrayList<>();
        place(back, BACK_X, true, out);
        place(front, FRONT_X, false, out);
        return out;
    }

    /** Face height on screen, as a fraction of the screen: every figure is scaled to it. */
    private static final float FACE_BACK = 0.060f, FACE_FRONT = 0.068f;

    /**
     * Height so every face comes out about the same size (a close-up shrinks, a full-length
     * figure grows), or from the figure's shape when no face size is known; then capped so
     * neighbours only overlap a little.
     */
    private static void place(List<Character> row, float[] xs, boolean back, List<Character> out) {
        for (int i = 0; i < row.size() && i < xs.length; i++) {
            Character ch = row.get(i);
            float h = ch.face > 0.02f
                    ? Math.max(0.34f, Math.min(0.82f, (back ? FACE_BACK : FACE_FRONT) / ch.face))
                    : ch.aspect < 0.45f ? 0.70f : ch.aspect < 0.75f ? 0.60f : 0.52f;
            float maxW = back ? 0.33f : 0.25f;
            float w = ch.aspect * h * 1080f / 1920f;
            if (w > maxW) h = maxW / (ch.aspect * 1080f / 1920f);
            ch.back = back;
            ch.cx = xs[i];
            ch.h = h;
            // Heads stay below the title: a tall figure stands lower, its legs off the screen.
            ch.bottom = Math.max(back ? 1.0f : 1.1f, (back ? 0.36f : 0.44f) + h);
            out.add(ch);
        }
    }

    private List<Character> fixed() {
        List<Character> out = new ArrayList<>();
        File f = new File(dir, "favourites.txt");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] p = t.split("\\|");
                if (p.length < 9) continue;
                Character ch = new Character();
                ch.file = p[0].trim();
                ch.title = p[1].trim();
                ch.app = p[2].trim();
                ch.link = p[3].trim();
                ch.back = "back".equals(p[4].trim());
                try {
                    ch.cx = Float.parseFloat(p[5].trim());
                    ch.bottom = Float.parseFloat(p[6].trim());
                    ch.h = Float.parseFloat(p[7].trim());
                    ch.color = Color.parseColor(p[8].trim());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ch.cutout = new File(dir, "characters/" + ch.file + ".png");
                ch.scene = new File(dir, "scenes/" + ch.file + ".png");
                out.add(ch);
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    static Bitmap decode(File f, int targetH) {
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null || b.getHeight() <= targetH) return b;
        Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * (float) targetH / b.getHeight()), targetH, true);
        if (s != b) b.recycle();
        return s;
    }

    boolean loaded() {
        return loaded;
    }

    /** Places the characters (on the UI thread) once their bitmaps are decoded. */
    void show(List<Character> chars, List<Bitmap> bitmaps, int screenW, int screenH) {
        for (Character ch : all) removeView(ch.view);
        all.clear();
        for (int i = 0; i < chars.size(); i++) {
            Character ch = chars.get(i);
            Bitmap b = bitmaps.get(i);
            if (b == null) continue;
            int h = Math.round(ch.h * screenH), w = Math.round(h * (float) b.getWidth() / b.getHeight());
            ImageView v = new ImageView(getContext());
            v.setImageBitmap(b);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            LayoutParams lp = new LayoutParams(w, h, Gravity.TOP | Gravity.START);
            lp.leftMargin = Math.round(ch.cx * screenW - w / 2f);
            lp.topMargin = Math.round(ch.bottom * screenH - h);
            v.setPivotX(w / 2f);
            v.setPivotY(h);
            v.setTranslationZ(ch.back ? 0 : 10);
            ch.view = v;
            ch.saturation = 0f;
            ch.brightness = BASE;
            applyTone(ch);
            addView(v, lp);
            all.add(ch);
        }
        LayoutParams g = (LayoutParams) glow.getLayoutParams();
        g.width = dp(560);
        g.height = dp(560);
        g.gravity = Gravity.TOP | Gravity.START;
        g.topMargin = Math.round(screenH * 0.42f);
        glow.setLayoutParams(g);
        glowShape.setGradientRadius(dp(280));
        selected = -1;
        loaded = true;
    }

    int selected() {
        return selected;
    }

    Character current() {
        return selected >= 0 && selected < all.size() ? all.get(selected) : null;
    }

    /** Colour, size, depth, glow and title follow the selection. */
    void select(int i, Bitmap logo) {
        if (i < 0 || i >= all.size()) return;
        int was = selected;
        selected = i;
        for (int k = 0; k < all.size(); k++) {
            Character ch = all.get(k);
            boolean on = k == i;
            ch.view.setTranslationZ(on ? (ch.back ? 5 : 20) : (ch.back ? 0 : 10));
            ch.view.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator()).start();
            toneTo(ch, on ? 1f : 0f, on ? 1f : DIMMED);
        }
        Character ch = all.get(i);
        int screenW = getWidth();
        float x = ch.cx * screenW - glow.getLayoutParams().width / 2f;
        if (was < 0) glow.setTranslationX(x);
        else glow.animate().translationX(x).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        glowTo(ch.color);
        title.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            title.setText(ch.title);
            sub.setText(ch.sub == null ? "" : ch.sub);
            sub.setVisibility(ch.sub == null ? View.GONE : View.VISIBLE);
            appLogo.setImageBitmap(logo);
            title.animate().alpha(1f).setDuration(280).start();
        }).start();
    }

    /** Drops the characters and their bitmaps: the next visit deals and decodes a new set. */
    void release() {
        for (Character ch : all) {
            removeView(ch.view);
            android.graphics.drawable.Drawable d = ch.view.getDrawable();
            ch.view.setImageDrawable(null);
            if (d instanceof android.graphics.drawable.BitmapDrawable) ((android.graphics.drawable.BitmapDrawable) d).getBitmap().recycle();
        }
        all.clear();
        selected = -1;
        loaded = false;
    }

    /** Everyone back to plain black and white (leaving the section). */
    void clear() {
        selected = -1;
        for (Character ch : all) {
            ch.view.setTranslationZ(ch.back ? 0 : 10);
            ch.view.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            toneTo(ch, 0f, BASE);
        }
    }

    /** Left/Right along a row; Up/Down to the nearest character of the other row (-1: none). */
    int neighbour(int from, int dx, int dy) {
        Character a = all.get(from);
        int best = -1;
        float bestGap = Float.MAX_VALUE;
        for (int k = 0; k < all.size(); k++) {
            if (k == from) continue;
            Character b = all.get(k);
            if (dx != 0) {
                if (b.back != a.back || Math.signum(b.cx - a.cx) != dx) continue;
                float gap = Math.abs(b.cx - a.cx);
                if (gap < bestGap) { bestGap = gap; best = k; }
            } else {
                boolean wantBack = dy < 0;
                if (b.back != wantBack || a.back == wantBack) continue;
                float gap = Math.abs(b.cx - a.cx);
                if (gap < bestGap) { bestGap = gap; best = k; }
            }
        }
        return best;
    }

    /** The front-row character nearest the middle: where Down from the dock lands. */
    int entry() {
        int best = -1;
        float bestGap = Float.MAX_VALUE;
        for (int k = 0; k < all.size(); k++) {
            Character ch = all.get(k);
            float gap = Math.abs(ch.cx - 0.5f);
            if (!ch.back && gap < bestGap) { bestGap = gap; best = k; }
        }
        return best >= 0 ? best : (all.isEmpty() ? -1 : 0);
    }

    private void toneTo(Character ch, float sat, float bright) {
        if (ch.tone != null) ch.tone.cancel();
        float s0 = ch.saturation, b0 = ch.brightness;
        ch.tone = ValueAnimator.ofFloat(0f, 1f);
        ch.tone.setDuration(340);
        ch.tone.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            ch.saturation = s0 + (sat - s0) * v;
            ch.brightness = b0 + (bright - b0) * v;
            applyTone(ch);
        });
        ch.tone.start();
    }

    private static void applyTone(Character ch) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(ch.saturation);
        ColorMatrix scale = new ColorMatrix();
        scale.setScale(ch.brightness, ch.brightness, ch.brightness, 1f);
        m.postConcat(scale);
        ch.view.setColorFilter(new ColorMatrixColorFilter(m));
    }

    private void glowTo(int color) {
        if (glowAnimation != null) glowAnimation.cancel();
        glowAnimation = ValueAnimator.ofArgb(glowColor == 0 ? (color & 0x00FFFFFF) : glowColor, color);
        glowAnimation.setDuration(420);
        glowAnimation.addUpdateListener(a -> {
            glowColor = (Integer) a.getAnimatedValue();
            int c = glowColor & 0x00FFFFFF;
            glowShape.setColors(new int[] {c | 0x66000000, c | 0x1F000000, Color.TRANSPARENT});
        });
        glowAnimation.start();
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}

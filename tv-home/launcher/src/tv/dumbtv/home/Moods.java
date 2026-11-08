package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "Don't have time to browse?": a deck of mood cards built daily from the Suggestions titles
 * (pool/moods.txt). Left and Right swipe the top card away (off to that side) and bring up the
 * next mood; OK spins its posters like a slot machine, lands on one title, counts down from three
 * and opens it. During the countdown OK opens it now; Left, Right or Back cancels. Up or Back on
 * the deck leaves.
 */
final class Moods extends FrameLayout {
    interface Host {
        void scene(File scene);

        void open(Title t);
    }

    static final class Title {
        String key, title, app, link, sub;
        File poster, scene;
    }

    static final class Mood {
        String name;
        int color;
        List<Title> titles = new ArrayList<>();
    }

    /** What the deck is doing: showing cards, spinning the reel, or counting down to open. */
    private enum State { DECK, SPINNING, COUNTDOWN }

    private static final int CARD_W = 300, CARD_H = 384; // dp
    private static final int REEL_W = 176, REEL_H = 264; // dp: one poster in the reel window
    private static final long SPIN_MS = 2800, COUNT_MS = 3000;

    private final File dir;
    private final Host host;
    private final Typeface light, medium;
    private final Map<String, Bitmap> posters = new HashMap<>();
    private final FrameLayout deckFrame;
    private final View[] cards = new View[3];
    private final View glow;
    private final GradientDrawable glowShape;
    private final FrameLayout reelWindow;
    private final LinearLayout reelStrip;
    private final LinearLayout result;
    private final TextView resultTitle, resultSub, countdown;
    private final View progressFill;
    private final TextView hint;
    private List<Mood> deck = new ArrayList<>();
    private int top;
    private State state = State.DECK;
    private Title picked;
    private ValueAnimator spin, count;
    private final Random rnd = new Random();

    Moods(Context c, File dir, Typeface light, Typeface medium, Host host) {
        super(c);
        this.dir = dir;
        this.host = host;
        this.light = light;
        this.medium = medium;
        setClipChildren(false);

        TextView header = text(medium, 11, 0x80FFFFFF);
        header.setText("DON'T HAVE TIME TO BROWSE?");
        header.setLetterSpacing(0.3f);
        LayoutParams hlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hlp.setMargins(dp(56), dp(40), 0, 0);
        addView(header, hlp);

        glowShape = new GradientDrawable();
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientRadius(dp(300));
        glowShape.setColors(new int[] {Color.TRANSPARENT, Color.TRANSPARENT});
        glow = new View(c);
        glow.setBackground(glowShape);
        addView(glow, new LayoutParams(dp(600), dp(600), Gravity.CENTER));

        deckFrame = new FrameLayout(c);
        deckFrame.setClipChildren(false);
        LayoutParams dlp = new LayoutParams(dp(CARD_W), dp(CARD_H), Gravity.CENTER);
        dlp.topMargin = dp(10);
        addView(deckFrame, dlp);

        // The reel: one poster-sized window over the top card, a strip of posters sliding through it.
        reelWindow = new FrameLayout(c);
        reelWindow.setClipChildren(true);
        reelWindow.setBackground(Ui.round(0xFF0E0E12, dp(14)));
        reelWindow.setClipToOutline(true);
        reelWindow.setElevation(dp(12));
        reelWindow.setVisibility(GONE);
        reelStrip = new LinearLayout(c);
        reelStrip.setOrientation(LinearLayout.VERTICAL);
        reelWindow.addView(reelStrip, new FrameLayout.LayoutParams(dp(REEL_W), -2));
        LayoutParams rlp = new LayoutParams(dp(REEL_W), dp(REEL_H), Gravity.CENTER);
        rlp.topMargin = -dp(26);
        addView(reelWindow, rlp);

        // The pick: its title, rating line, and the countdown with a bar in the mood's colour.
        result = new LinearLayout(c);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setGravity(Gravity.CENTER_HORIZONTAL);
        result.setVisibility(GONE);
        resultTitle = text(light, 24, 0xF2FFFFFF);
        resultTitle.setGravity(Gravity.CENTER);
        result.addView(resultTitle);
        resultSub = text(medium, 11, 0xB3FFFFFF);
        resultSub.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(-2, -2);
        sublp.topMargin = dp(8);
        result.addView(resultSub, sublp);
        countdown = text(medium, 12, 0xCCFFFFFF);
        countdown.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(14);
        result.addView(countdown, clp);
        FrameLayout bar = new FrameLayout(c);
        bar.setBackground(Ui.round(0x33FFFFFF, dp(2)));
        progressFill = new View(c);
        progressFill.setPivotX(0);
        bar.addView(progressFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(220), dp(3));
        blp.topMargin = dp(10);
        result.addView(bar, blp);
        LayoutParams reslp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        reslp.bottomMargin = dp(26);
        addView(result, reslp);

        hint = text(medium, 10.5f, 0x73FFFFFF);
        hint.setLetterSpacing(0.24f);
        hint.setText("\u25C0 \u25B6  SWIPE          OK  SPIN");
        LayoutParams hintlp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintlp.bottomMargin = dp(30);
        addView(hint, hintlp);
    }

    // ---- data -----------------------------------------------------------------------------

    /** Reads moods.txt and pool.txt, and decodes every poster the deck can show. Off the UI thread. */
    List<Mood> read() {
        Map<String, Title> titles = new HashMap<>();
        for (String line : Walls.read(new File(dir, "pool/pool.txt"))) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split(" \\| ");
            if (p.length < 9) continue;
            Title t = new Title();
            t.key = p[0].trim();
            t.title = p[1].trim();
            t.app = p[6].trim();
            t.link = p.length > 10 ? p[10].trim() : "-";
            t.sub = "★ " + p[4].trim() + " IMDb   ·   " + p[3].trim() + "   ·   "
                    + ("movie".equals(p[2].trim()) ? "Film" : "Series")
                    + (p.length > 12 && !p[12].trim().isEmpty() ? "   \u00B7   " + p[12].trim() : "");
            t.poster = new File(dir, "pool/posters/" + t.key + ".jpg");
            t.scene = new File(dir, "pool/scenes/" + t.key + ".jpg");
            if (t.poster.isFile()) titles.put(t.key, t);
        }
        List<Mood> out = new ArrayList<>();
        for (String line : Walls.read(new File(dir, "pool/moods.txt"))) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            Mood m = new Mood();
            m.name = p[0].trim();
            try {
                m.color = Color.parseColor(p[1].trim());
            } catch (IllegalArgumentException e) {
                m.color = 0xFFB8C0CC;
            }
            for (String k : p[2].split(",")) {
                Title t = titles.get(k.trim());
                if (t != null) m.titles.add(t);
            }
            if (m.titles.size() >= 3) out.add(m);
        }
        // Only the first cards' posters now; the rest as cards come up and before each spin.
        for (int m = 0; m < Math.min(3, out.size()); m++) {
            List<Title> ts = out.get(m).titles;
            ensure(ts.subList(0, Math.min(3, ts.size())));
        }
        return out;
    }

    void show(List<Mood> moods) {
        deck = moods;
        top = 0;
        state = State.DECK;
        reelWindow.setVisibility(GONE);
        result.setVisibility(GONE);
        hint.setAlpha(1f);
        deckFrame.removeAllViews();
        for (int i = 0; i < 3; i++) cards[i] = null;
        for (int i = 2; i >= 0; i--) {
            if (deck.isEmpty()) break;
            View card = card(deck.get(Math.floorMod(top + i, deck.size())));
            cards[i] = card;
            deckFrame.addView(card, new FrameLayout.LayoutParams(-1, -1));
            place(card, i, false);
        }
        topChanged();
    }

    /** Back from a title that was opened: the deck as it was, with its scene. */
    void resume() {
        if (!deck.isEmpty()) cancel();
    }

    boolean hasDeck() {
        return !deck.isEmpty();
    }

    /** Frees the posters when the section is left: they are decoded again on the next visit. */
    void release() {
        if (spin != null) spin.cancel();
        if (count != null) count.cancel();
        deckFrame.removeAllViews();
        reelStrip.removeAllViews();
        for (int i = 0; i < 3; i++) cards[i] = null;
        nextRun = null;
        decoder.execute(() -> {
            synchronized (posters) {
                for (Bitmap b : posters.values()) b.recycle();
                posters.clear();
            }
        });
        deck = new ArrayList<>();
    }

    /** Decodes the posters in {@code ts} that aren't in memory yet. */
    private void ensure(List<Title> ts) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565; // posters have no transparency: half the memory
        for (Title t : ts) {
            synchronized (posters) {
                if (posters.containsKey(t.key)) continue;
            }
            Bitmap b = BitmapFactory.decodeFile(t.poster.getPath(), o);
            if (b != null) synchronized (posters) {
                posters.put(t.key, b);
            }
        }
    }

    private Bitmap poster(Title t) {
        synchronized (posters) {
            Bitmap b = posters.get(t.key);
            if (b != null) return b;
        }
        ensure(Collections.singletonList(t));
        synchronized (posters) {
            return posters.get(t.key);
        }
    }

    /**
     * The next spin's reel for the top mood, decoded in the background so pressing Right starts
     * at once; posters nothing on screen needs any more are dropped.
     */
    private void prepareRun() {
        Mood m = deck.get(top);
        Title pick = m.titles.get(rnd.nextInt(m.titles.size()));
        List<Title> run = new ArrayList<>();
        while (run.size() < 22) {
            List<Title> round = new ArrayList<>(m.titles);
            Collections.shuffle(round, rnd);
            run.addAll(round);
        }
        run = new ArrayList<>(run.subList(0, 22));
        run.add(pick);
        nextRun = run;
        List<Title> wanted = new ArrayList<>(run);
        for (int i = 0; i < 3 && !deck.isEmpty(); i++) {
            List<Title> ts = deck.get(Math.floorMod(top + i, deck.size())).titles;
            wanted.addAll(ts.subList(0, Math.min(3, ts.size())));
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Title t : wanted) keep.add(t.key);
        List<Title> todo = new ArrayList<>(wanted);
        decoder.execute(() -> {
            ensure(todo);
            synchronized (posters) {
                if (posters.size() > 40) posters.keySet().retainAll(keep);
            }
        });
    }

    private final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newSingleThreadExecutor();
    private List<Title> nextRun;

    // ---- keys -----------------------------------------------------------------------------

    /** Handles a key; false for Up or Back on the deck, meaning "leave this section". */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        switch (state) {
            case DECK:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) skip(-1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) skip(1);
                else if (ok) spin();
                else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_BACK) return false;
                return true;
            case SPINNING:
                if (code == KeyEvent.KEYCODE_BACK || sideways) cancel();
                return true;
            default:
                if (ok) {
                    if (count != null) count.cancel();
                    openPicked();
                } else if (sideways || code == KeyEvent.KEYCODE_BACK) {
                    cancel();
                }
                return true;
        }
    }

    // ---- the deck -------------------------------------------------------------------------

    /** One card: the mood's colour fading to near-black, three posters fanned out, its name. */
    private View card(Mood m) {
        FrameLayout card = new FrameLayout(getContext());
        int c = m.color;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {(c & 0x00FFFFFF) | 0xE6000000, mix(c, 0xFF101014, 0.72f), 0xFF0C0C10});
        bg.setCornerRadius(dp(26));
        card.setBackground(bg);
        card.setElevation(dp(10));
        card.setClipChildren(false);
        int[] dx = {-74, 74, 0};
        float[] rot = {-9f, 9f, 0f};
        for (int i = 0; i < 3 && i < m.titles.size(); i++) {
            ImageView p = new ImageView(getContext());
            p.setImageBitmap(poster(m.titles.get(i)));
            p.setScaleType(ImageView.ScaleType.CENTER_CROP);
            p.setBackground(Ui.round(0xFF000000, dp(10)));
            p.setClipToOutline(true);
            p.setElevation(dp(i == 2 ? 8 : 4));
            p.setRotation(rot[i]);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(112), dp(168), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(i == 2 ? 34 : 50);
            p.setTranslationX(dp(dx[i]));
            card.addView(p, lp);
        }
        TextView name = text(medium, 25, 0xFFFFFFFF);
        name.setText(m.name);
        name.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.bottomMargin = dp(62);
        card.addView(name, nlp);
        TextView n = text(medium, 11, 0x99FFFFFF);
        n.setText(m.titles.size() + " TITLES");
        n.setLetterSpacing(0.22f);
        n.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        clp.bottomMargin = dp(38);
        card.addView(n, clp);
        return card;
    }

    /** Position i: 0 = top card, 1 and 2 peek out behind it to the right. */
    private void place(View card, int i, boolean animate) {
        float scale = 1f - 0.07f * i, tx = dp(36) * i, alpha = i == 0 ? 1f : i == 1 ? 0.7f : 0.42f;
        card.setTranslationZ(-i * dp(4));
        card.setPivotX(0);
        card.setPivotY(dp(CARD_H) / 2f);
        if (animate) {
            card.animate().translationX(tx).scaleX(scale).scaleY(scale).alpha(alpha).rotation(0)
                    .setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            card.setTranslationX(tx);
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setAlpha(alpha);
            card.setRotation(0);
        }
    }

    /** Swiped: the top card flies off to that side (-1 left, 1 right) with a tilt; the next mood comes up. */
    private void skip(int side) {
        if (deck.size() < 2 || cards[0] == null) return;
        View gone = cards[0];
        gone.setPivotX(dp(CARD_W) / 2f);
        gone.animate().translationX(side * dp(760)).rotation(side * 14f).alpha(0f).setDuration(330)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> deckFrame.removeView(gone)).start();
        top = Math.floorMod(top + 1, deck.size());
        cards[0] = cards[1];
        cards[1] = cards[2];
        View back = card(deck.get(Math.floorMod(top + 2, deck.size())));
        deckFrame.addView(back, 0, new FrameLayout.LayoutParams(-1, -1));
        place(back, 2, false);
        back.setAlpha(0f);
        cards[2] = back;
        for (int i = 0; i < 3; i++) if (cards[i] != null) place(cards[i], i, true);
        topChanged();
    }

    private void topChanged() {
        if (deck.isEmpty()) return;
        Mood m = deck.get(top);
        glowTo(m.color);
        host.scene(m.titles.get(0).scene);
        prepareRun(); // the next spin is ready before Right is pressed
    }

    // ---- the reel ---------------------------------------------------------------------------

    /** Yes: the mood's posters spin past, fast then slowing, and land on one random title. */
    private void spin() {
        if (deck.isEmpty()) return;
        if (count != null) count.cancel();
        if (nextRun == null) prepareRun();
        List<Title> run = nextRun;
        nextRun = null;
        picked = run.get(run.size() - 1);
        reelStrip.removeAllViews();
        for (Title t : run) {
            ImageView p = new ImageView(getContext());
            p.setImageBitmap(poster(t));
            p.setScaleType(ImageView.ScaleType.CENTER_CROP);
            reelStrip.addView(p, new LinearLayout.LayoutParams(dp(REEL_W), dp(REEL_H)));
        }
        // The strip must be as tall as all its posters: sized to the window, Android clipped every
        // poster below the first and the reel slid past nothing but black.
        reelStrip.setLayoutParams(new FrameLayout.LayoutParams(dp(REEL_W), dp(REEL_H) * run.size()));
        reelStrip.setTranslationY(0);
        state = State.SPINNING;
        result.setVisibility(GONE);
        hint.animate().alpha(0f).setDuration(200).start();
        // The whole deck steps aside: a dimmed top card let the cards behind it show through,
        // and their names and posters overlapped behind the reel.
        for (View c : cards) if (c != null) c.animate().alpha(0f).setDuration(220).start();
        reelWindow.setVisibility(VISIBLE);
        reelWindow.setAlpha(0f);
        reelWindow.animate().alpha(1f).setDuration(200).start();
        float end = -(run.size() - 1) * (float) dp(REEL_H);
        spin = ValueAnimator.ofFloat(0f, end);
        spin.setDuration(SPIN_MS);
        spin.setInterpolator(new DecelerateInterpolator(2.4f));
        spin.addUpdateListener(a -> reelStrip.setTranslationY((Float) a.getAnimatedValue()));
        spin.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) landed();
            }
        });
        spin.start();
    }

    /** Landed: the pick's scene behind, its name, and "Opening in 3…" with the bar filling. */
    private void landed() {
        state = State.COUNTDOWN;
        host.scene(picked.scene);
        resultTitle.setText(picked.title);
        resultSub.setText(picked.sub);
        result.setVisibility(VISIBLE);
        result.setAlpha(0f);
        result.animate().alpha(1f).setDuration(250).start();
        int c = deck.get(top).color;
        progressFill.setBackground(Ui.round(c, dp(2)));
        progressFill.setScaleX(0f);
        count = ValueAnimator.ofFloat(0f, 1f);
        count.setDuration(COUNT_MS);
        count.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            progressFill.setScaleX(v);
            int left = (int) Math.ceil(3 * (1 - v));
            countdown.setText(left > 0 ? "OPENING IN " + left + "…" : "OPENING…");
        });
        count.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) openPicked();
            }
        });
        count.start();
    }

    private void openPicked() {
        Title t = picked;
        cancel();
        if (t != null) host.open(t);
    }

    /** Back to the deck. */
    private void cancel() {
        if (spin != null) spin.cancel();
        if (count != null) count.cancel();
        state = State.DECK;
        reelWindow.animate().alpha(0f).setDuration(200).withEndAction(() -> reelWindow.setVisibility(GONE)).start();
        result.animate().alpha(0f).setDuration(200).withEndAction(() -> result.setVisibility(GONE)).start();
        hint.animate().alpha(1f).setDuration(300).start();
        for (int i = 0; i < 3; i++) if (cards[i] != null) place(cards[i], i, true);
        topChanged();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ValueAnimator glowAnimation;
    private int glowColor;

    private void glowTo(int color) {
        if (glowAnimation != null) glowAnimation.cancel();
        glowAnimation = ValueAnimator.ofArgb(glowColor == 0 ? (color & 0x00FFFFFF) : glowColor, color);
        glowAnimation.setDuration(420);
        glowAnimation.addUpdateListener(a -> {
            glowColor = (Integer) a.getAnimatedValue();
            int c = glowColor & 0x00FFFFFF;
            glowShape.setColors(new int[] {c | 0x52000000, c | 0x14000000, Color.TRANSPARENT});
        });
        glowAnimation.start();
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView v = new TextView(getContext());
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(dp(8), 0, 0, 0x99000000);
        return v;
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}

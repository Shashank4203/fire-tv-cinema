package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Continue watching (Down from the apps): what played lately on any app, newest first, from
 * {@link Watching}. The focused title's backdrop fills the screen, with its logo, the episode,
 * and how much is left; each card shows how far in it is. Left and Right move; OK resumes it
 * (the title's page where the app takes links); Menu asks to take it off the row (OK yes, Back
 * no); Up or Back go back to the apps, Down to Suggestions.
 */
final class Continue extends FrameLayout {
    interface Host {
        /** The focused title's backdrop behind everything; null for one without (the wallpaper then). */
        void scene(File backdrop);
    }

    /** A title with its images, decoded for one visit. */
    static final class Card {
        Watching.Entry entry;
        File backdropFile;
        /** No backdrop (YouTube shares no thumbnail): the app's icon on its colour instead. */
        Bitmap backdrop, logo, icon;
    }

    private static final int CARD_W = 224, CARD_H = 126, GAP = 20; // dp, 16:9
    private static final String HINT = "\u25C0 \u25B6  BROWSE        OK  RESUME        MENU  REMOVE        \u25BC  SUGGESTIONS";
    private static final String ASKING = "OK  REMOVE        BACK  KEEP";

    private final File dir;
    private final Host host;
    private final Typeface light, medium;
    private final LinearLayout row, details;
    private final ImageView titleLogo, appLogo;
    private final LinearLayout status;
    private final Map<String, Bitmap> appLogos = new HashMap<>();
    private final TextView title, detail, sub, left;
    private final List<Card> cards = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final TextView empty, hint;
    /** The question on the focused card while removing it is being asked; null otherwise. */
    private View prompt;
    private int selected = -1;

    Continue(Context c, File dir, Typeface light, Typeface medium, Host host) {
        super(c);
        this.dir = dir;
        this.host = host;
        this.light = light;
        this.medium = medium;
        setClipChildren(false);

        // Darker on the left for the words, and at the bottom for the cards.
        View side = new View(c);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x8C000000, 0x00000000}));
        addView(side, new LayoutParams(-1, -1));
        View foot = new View(c);
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0xD9000000}));
        addView(foot, new LayoutParams(-1, -1));

        TextView header = text(medium, 11, 0x80FFFFFF);
        header.setText("CONTINUE WATCHING");
        header.setLetterSpacing(0.3f);
        addView(header, place(-2, -2, Gravity.TOP | Gravity.START, 56, 40, 0, 0));

        details = new LinearLayout(c);
        details.setOrientation(LinearLayout.VERTICAL);
        titleLogo = new ImageView(c);
        titleLogo.setScaleType(ImageView.ScaleType.FIT_START);
        titleLogo.setAdjustViewBounds(true);
        titleLogo.setMaxWidth(dp(380));
        titleLogo.setMaxHeight(dp(104));
        details.addView(titleLogo, new LinearLayout.LayoutParams(-2, -2));
        title = text(light, 34, 0xF2FFFFFF);
        title.setMaxWidth(dp(520));
        details.addView(title);
        detail = text(light, 18, 0xE6FFFFFF);
        detail.setMaxWidth(dp(520));
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(detail, margins(16));
        sub = text(medium, 12, 0xB3FFFFFF);
        sub.setLetterSpacing(0.06f);
        details.addView(sub, margins(10));
        status = new LinearLayout(c);
        status.setGravity(Gravity.CENTER_VERTICAL);
        appLogo = new ImageView(c);
        appLogo.setScaleType(ImageView.ScaleType.FIT_START);
        appLogo.setAlpha(0.8f);
        status.addView(appLogo, new LinearLayout.LayoutParams(dp(96), dp(20)));
        left = text(medium, 11, 0xCCFFFFFF);
        left.setLetterSpacing(0.16f);
        status.addView(left);
        details.addView(status, margins(16));
        addView(details, place(-2, -2, Gravity.TOP | Gravity.START, 56, 92, 0, 0));

        row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(56), 0, dp(56), 0);
        FrameLayout rail = new FrameLayout(c);
        rail.setClipChildren(false);
        rail.addView(row, new FrameLayout.LayoutParams(-2, dp(CARD_H)));
        addView(rail, place(-1, dp(CARD_H), Gravity.BOTTOM, 0, 0, 0, 70));

        empty = text(light, 20, 0xCCFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.3f);
        empty.setText("Nothing to continue yet.\nPlay a film, an episode or a video for half a minute and it shows up here.\n"
                + "Netflix and Apple TV only say what's playing when it's opened from Suggestions, the moods or Alexa.");
        empty.setVisibility(GONE);
        addView(empty, place(dp(720), -2, Gravity.CENTER, 0, 0, 0, 0));

        hint = text(medium, 10.5f, 0x73FFFFFF);
        hint.setLetterSpacing(0.24f);
        hint.setText(HINT);
        addView(hint, place(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 30));
    }

    // ---- data -----------------------------------------------------------------------------

    /** The row and its images, decoded for this visit. Off the UI thread. */
    List<Card> read() {
        List<Card> out = new ArrayList<>();
        for (Watching.Entry e : Watching.read(dir)) {
            if (Watching.expired(e)) continue; // Watching takes it off the row within a minute
            Card c = new Card();
            c.entry = e;
            c.backdropFile = Watching.backdrop(dir, e.key);
            if (c.backdropFile.isFile()) {
                c.backdrop = decode(c.backdropFile);
            }
            File logo = Watching.logo(dir, e.key);
            if (logo.isFile()) c.logo = BitmapFactory.decodeFile(logo.getPath());
            if (c.backdrop == null) c.icon = BitmapFactory.decodeFile(new File(dir, "icons/" + e.app + ".png").getPath());
            out.add(c);
        }
        return out;
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // no transparency: half the memory
        o.inSampleSize = o.outWidth >= 1000 ? 2 : 1; // a card is under 500 pixels wide
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    void show(List<Card> list) {
        release();
        cards.addAll(list);
        for (int i = 0; i < cards.size(); i++) {
            View v = card(cards.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
            lp.rightMargin = dp(GAP);
            row.addView(v, lp);
            views.add(v);
            // The cards rise into place one after another.
            v.setAlpha(0f);
            v.setTranslationY(dp(40));
            v.animate().alpha(0.5f).translationY(0).setStartDelay(70L * Math.min(i, 6)).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        row.setTranslationX(0);
        selected = -1;
        empty.setVisibility(cards.isEmpty() ? VISIBLE : GONE);
        details.setVisibility(cards.isEmpty() ? GONE : VISIBLE);
        if (!cards.isEmpty()) select(0);
    }

    /** Back from an app: the same card, with its backdrop. */
    void resume() {
        if (selected < 0) return;
        select(selected);
        Card c = cards.get(selected);
        host.scene(c.backdropFile.isFile() ? c.backdropFile : null);
    }

    Watching.Entry current() {
        return selected >= 0 && selected < cards.size() ? cards.get(selected).entry : null;
    }

    void move(int by) {
        int to = selected + by;
        if (to >= 0 && to < cards.size()) select(to);
    }

    /** Frees the images when the section is left: they are decoded again on the next visit. */
    void release() {
        prompt = null;
        hint.setText(HINT);
        for (View v : views) v.animate().cancel();
        row.removeAllViews();
        views.clear();
        titleLogo.setImageDrawable(null);
        appLogo.setImageDrawable(null);
        for (Card c : cards) {
            if (c.backdrop != null) c.backdrop.recycle();
            if (c.logo != null) c.logo.recycle();
            if (c.icon != null) c.icon.recycle();
        }
        cards.clear();
        for (Bitmap b : appLogos.values()) if (b != null) b.recycle();
        appLogos.clear();
        selected = -1;
    }

    // ---- removing -------------------------------------------------------------------------

    boolean asking() {
        return prompt != null;
    }

    /** Menu on a card: it darkens and asks. */
    void askRemove() {
        if (selected < 0 || prompt != null) return;
        LinearLayout ask = new LinearLayout(getContext());
        ask.setOrientation(LinearLayout.VERTICAL);
        ask.setGravity(Gravity.CENTER);
        ask.setBackground(Ui.round(0xD9000000, dp(10)));
        TextView q = text(medium, 12, 0xF2FFFFFF);
        q.setText("REMOVE FROM THE ROW?");
        q.setLetterSpacing(0.16f);
        ask.addView(q);
        TextView keys = text(medium, 9.5f, 0xB3FFFFFF);
        keys.setText("OK  YES        BACK  NO");
        keys.setLetterSpacing(0.2f);
        ask.addView(keys, margins(10));
        ((FrameLayout) views.get(selected)).addView(ask, new LayoutParams(-1, -1));
        ask.setAlpha(0f);
        ask.animate().alpha(1f).setDuration(150).start();
        prompt = ask;
        hint.setText(ASKING);
    }

    void cancelRemove() {
        View ask = prompt;
        prompt = null;
        hint.setText(HINT);
        if (ask != null) ask.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (ask.getParent() != null) ((FrameLayout) ask.getParent()).removeView(ask);
        }).start();
    }

    /**
     * Takes the focused card off the row: it shrinks away and the cards after it slide over.
     * Returns its title, for {@link Watching} to forget.
     */
    Watching.Entry removeSelected() {
        if (selected < 0) return null;
        int i = selected;
        Card c = cards.remove(i);
        View v = views.remove(i);
        prompt = null;
        hint.setText(HINT);
        v.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(220).withEndAction(() -> {
            row.removeView(v);
            if (c.backdrop != null) c.backdrop.recycle();
            if (c.logo != null) c.logo.recycle();
            if (c.icon != null) c.icon.recycle();
            int step = dp(CARD_W + GAP);
            for (int k = i; k < views.size(); k++) {
                View next = views.get(k);
                next.setTranslationX(step);
                next.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            }
        }).start();
        selected = -1;
        if (cards.isEmpty()) {
            empty.setVisibility(VISIBLE);
            details.setVisibility(GONE);
            host.scene(null);
        } else {
            select(Math.min(i, cards.size() - 1));
        }
        return c.entry;
    }

    // ---- selection ------------------------------------------------------------------------

    private void select(int i) {
        int was = selected;
        selected = i;
        for (int k = 0; k < views.size(); k++) {
            boolean on = k == i;
            View v = views.get(k);
            v.animate().scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f).alpha(on ? 1f : 0.5f).setStartDelay(0)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            v.setElevation(on ? dp(10) : 0);
        }
        // Keep the focused card in view: the row slides once it reaches the second card.
        int step = dp(CARD_W + GAP);
        int shift = Math.max(0, Math.min(i - 1, cards.size() - 3));
        row.animate().translationX(-shift * step).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        if (i == was) return;
        Card c = cards.get(i);
        Watching.Entry e = c.entry;
        boolean hasLogo = c.logo != null;
        titleLogo.setImageBitmap(c.logo);
        titleLogo.setVisibility(hasLogo ? VISIBLE : GONE);
        title.setText(e.title);
        title.setVisibility(hasLogo ? GONE : VISIBLE);
        detail.setText(e.detail);
        detail.setVisibility(e.detail.isEmpty() ? GONE : VISIBLE);
        sub.setText(subLine(e));
        sub.setVisibility(sub.getText().length() == 0 ? GONE : VISIBLE);
        if (!appLogos.containsKey(e.app)) appLogos.put(e.app, BitmapFactory.decodeFile(new File(dir, "logos/" + e.app + ".png").getPath()));
        appLogo.setImageBitmap(appLogos.get(e.app));
        String l = timeLeft(e);
        left.setText((l.isEmpty() ? "" : l + "   ·   ") + when(e.updated));
        View[] parts = {hasLogo ? titleLogo : title, detail, sub, status};
        for (int k = 0; k < parts.length; k++) {
            parts[k].animate().cancel();
            parts[k].setAlpha(0f);
            parts[k].setTranslationX(-dp(16));
            parts[k].animate().alpha(1f).translationX(0).setStartDelay(45L * k).setDuration(280)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        host.scene(c.backdropFile.isFile() ? c.backdropFile : null);
    }

    // ---- cards ----------------------------------------------------------------------------

    private View card(Card c) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);
        card.setBackground(Ui.round(0xFF16161C, dp(10)));
        card.setClipToOutline(true);
        if (c.backdrop != null) {
            ImageView img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(c.backdrop);
            card.addView(img, new LayoutParams(-1, -1));
        } else {
            // No picture: the app's colour, with its icon above the name.
            GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    Watching.YOUTUBE.equals(c.entry.app) ? new int[] {0xFF8A0F1C, 0xFF2A060A} : new int[] {0xFF2E3140, 0xFF111218});
            tint.setCornerRadius(dp(10));
            card.setBackground(tint);
            if (c.icon != null) {
                ImageView icon = new ImageView(ctx);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageBitmap(c.icon);
                icon.setAlpha(0.9f);
                card.addView(icon, place(dp(34), dp(34), Gravity.TOP | Gravity.START, 12, 12, 0, 0));
            }
        }
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x14000000, 0xB3000000}));
        card.addView(shade, new LayoutParams(-1, -1));
        // The title on the card: its logo, or its name.
        if (c.logo != null) {
            ImageView logo = new ImageView(ctx);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            logo.setAdjustViewBounds(true);
            logo.setMaxWidth(dp(120));
            logo.setMaxHeight(dp(34));
            logo.setImageBitmap(c.logo);
            card.addView(logo, place(-2, -2, Gravity.BOTTOM | Gravity.START, 12, 0, 0, 20));
        } else {
            TextView name = text(medium, 12, 0xF2FFFFFF);
            name.setText(c.entry.title);
            name.setMaxLines(2);
            name.setMaxWidth(dp(CARD_W - 24));
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name, place(-2, -2, Gravity.BOTTOM | Gravity.START, 12, 0, 12, 20));
        }
        // How far in: a bar along the bottom when the length is known.
        float done = progress(c.entry);
        if (done > 0) {
            FrameLayout track = new FrameLayout(ctx);
            track.setBackground(Ui.round(0x4DFFFFFF, dp(2)));
            View fill = new View(ctx);
            fill.setBackground(Ui.round(0xF2FFFFFF, dp(2)));
            fill.setPivotX(0);
            fill.setScaleX(done);
            track.addView(fill, new LayoutParams(-1, -1));
            card.addView(track, place(-1, dp(3), Gravity.BOTTOM, 12, 0, 12, 10));
        }
        return card;
    }

    private static float progress(Watching.Entry e) {
        if (e.duration <= 0 || e.position <= 0) return 0;
        return Math.max(0.03f, Math.min(1f, e.position / (float) e.duration));
    }

    private static String subLine(Watching.Entry e) {
        if ("video".equals(e.kind)) return "";
        List<String> parts = new ArrayList<>();
        if (e.rating > 0) parts.add(String.format(Locale.ROOT, "★ %.1f TMDB", e.rating));
        if (!e.year.isEmpty()) parts.add(e.year);
        parts.add("movie".equals(e.kind) ? "Film" : "Series");
        if (!e.genres.isEmpty()) parts.add(e.genres);
        return android.text.TextUtils.join("   ·   ", parts);
    }

    private static String timeLeft(Watching.Entry e) {
        if (e.duration <= 0 || e.position <= 0 || e.position >= e.duration) return "";
        long min = Math.max(1, (e.duration - e.position) / 60_000);
        return (min >= 60 ? min / 60 + " H " + min % 60 + " MIN" : min + " MIN") + " LEFT";
    }

    /** When it last played, by the day. */
    private static String when(long t) {
        if (t <= 0) return "";
        long days = (dayStart(System.currentTimeMillis()) - dayStart(t)) / 86_400_000L;
        return days <= 0 ? "TODAY" : days == 1 ? "YESTERDAY" : days + " DAYS AGO";
    }

    private static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 12); // noon: a daylight-saving change can't shift the count
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    private LayoutParams place(int w, int h, int gravity, int l, int t, int r, int b) {
        LayoutParams lp = new LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
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

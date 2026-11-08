package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cinematic home screen: a slowly drifting wallpaper, the time and weather, and one row of
 * white logos with a quote and a glow per app. Every other app and the inputs live in
 * {@link Library}. The look comes from files in {@code <external files>/cinema}: dock.txt,
 * logos/, wallpapers/ and fonts/, so it can be restyled with adb push and no rebuild.
 */
public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final long IDLE_AFTER = 2 * 60_000L;
    private static final long IDLE_ROTATE = 45_000L;
    private static final float LOGO_AREA = 2500f; // dp², so thin and solid logos look equally big
    private static final float ICON_AREA = 2900f; // the same for icon-only marks, which are compact
    private static final float ICON_MAX = 54f; // dp: tallest a dock icon gets
    private static final int ROW = 116, BASE = 70; // dp: dock row height; the icons' shared baseline in it
    private static final int GAP = 86; // dp: space between dock apps
    private static final float REFLECTION = 0.5f; // the mirrored shadow, as a share of the icon's height
    private static final int LIFT = 9; // dp: icons float this far above the floor their reflection is on
    private static final int GLOW = 560; // dp: diameter of the soft light behind the focused app

    private static final class Dock {
        String pkg, name;
        int color;
        String[][] quotes; // {quote, source}
        Intent launch;
        View cell;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Dock> dock = new ArrayList<>();
    private final List<View> icons = new ArrayList<>();
    /** The light strip's modes in its menu, in {@link Lights#MODES} order: symbols, no words. */
    private static final int[] LIGHT_ICONS = {R.drawable.ic_light_static, R.drawable.ic_light_sound,
            R.drawable.ic_light_video, R.drawable.ic_light_both};
    private static final String[] LIGHT_NAMES = {"Lights: static", "Lights: sound sync", "Lights: video sync",
            "Lights: sound and video sync"};
    /** How sound shows, in {@link Lights#STYLES} order: strobe, gradual. */
    private static final int[] STYLE_ICONS = {R.drawable.ic_style_strobe, R.drawable.ic_style_gradual};
    private static final String[] STYLE_NAMES = {"Sound effect: strobe", "Sound effect: gradual"};
    private ImageView lightIcon;
    private PopupWindow lightMenu;
    /** The lighting menu closes after this long without a key press in it. */
    private static final long LIGHT_MENU_IDLE_MS = 5000;
    private final Runnable closeLightMenu = () -> {
        if (lightMenu != null && lightMenu.isShowing()) lightMenu.dismiss();
    };
    private final ImageView[] walls = new ImageView[2];
    private final Runnable sleep = this::sleep;
    private final Runnable idleRotate = this::idleRotate;

    private File dir;
    private long dockStamp = -1;
    private Typeface light, regular, medium;
    private SharedPreferences prefs;
    private FrameLayout stage, chrome, dockFrame;
    private LinearLayout row;
    private View glow;
    private GradientDrawable glowShape;
    private TextView clock, temp, quote, source, more, caption, filmLine;
    private ImageView wxIcon;
    private int front, focusIndex, glowColor, visit, page, favIndex = -1;
    private FrameLayout layer;
    private ImageView amazonButton;
    /** What the screensaver fades: everything but the wallpaper, the clock, the weather and the film. */
    private final List<View> sleepers = new ArrayList<>();
    private View favShade;
    private Favourites fav;
    private Moods moods;
    private boolean moodsLoading;
    // Going down: the dock (page 0), Continue watching (3), Suggestions (1), the mood cards (2).
    private Continue cont;
    private boolean contLoading;
    private boolean favLoading;
    private String wallShown;
    private long weatherAt;
    private boolean resumed, idle;
    private ValueAnimator drift, glowAnimation;

    private final android.content.BroadcastReceiver time = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dir = new File(getExternalFilesDir(null), "cinema");
        prefs = getSharedPreferences("cinema", MODE_PRIVATE);
        light = font("Outfit-Light.ttf", Ui.LIGHT);
        regular = font("Outfit-Regular.ttf", Ui.MEDIUM);
        medium = font("Outfit-Medium.ttf", Ui.MEDIUM);
        setContentView(build());
        Keeper.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        Lights.home();
        showLights();
        if (dockFile().lastModified() != dockStamp) loadDock();
        visit = prefs.getInt("visit", 0) + 1;
        prefs.edit().putInt("visit", visit).apply();
        if (page == 0) showWallpaper(false);
        else if (page == 1) selectFavourite(favIndex);
        else if (page == 3) cont.resume();
        else moods.resume();
        registerReceiver(time, new android.content.IntentFilter(Intent.ACTION_TIME_TICK));
        tick();
        startDrift();
        if (System.currentTimeMillis() - weatherAt > Weather.EVERY) weather();
        wake();
        if (page == 0) focusDock(focusIndex);
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        ui.removeCallbacks(sleep);
        ui.removeCallbacks(idleRotate);
        if (drift != null) drift.cancel();
    }

    /**
     * Another app is in front: hand its memory back. The sections go back to the dock and every
     * full-screen image is dropped; coming home decodes a fresh wallpaper anyway.
     */
    @Override
    protected void onStop() {
        super.onStop();
        resetToDock();
        ui.removeCallbacksAndMessages(null);
        imageWanted = null;
        for (ImageView w : walls) {
            w.animate().cancel();
            w.setImageDrawable(null);
            w.setAlpha(0f);
        }
        synchronized (images) {
            for (Bitmap b : images.values()) b.recycle();
            images.clear();
        }
        wallShown = null;
        wallShownBefore = false;
    }

    /** Instantly back to the dock (no animation: the window isn't drawing while stopped). */
    private void resetToDock() {
        int h = getResources().getDisplayMetrics().heightPixels;
        if (page == 2) moods.release();
        fav.clear();
        fav.release();
        cont.release();
        page = 0;
        for (View v : new View[] {fav, cont, moods}) {
            v.animate().cancel();
            v.setVisibility(View.INVISIBLE);
            v.setAlpha(0f);
            v.setTranslationY(h * 0.25f);
        }
        chrome.animate().cancel();
        chrome.setTranslationY(0);
        chrome.setAlpha(1f);
        favShade.setAlpha(0f);
        idle = false;
        for (View v : sleepers) v.setAlpha(1f);
        filmLine.setAlpha(0f);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** Same as the original home: drop the hidden window's GPU context while an app plays. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME always lands on the dock, on the app used last.
        backToDock();
        if (resumed) {
            wake();
            focusDock(focusIndex);
        }
    }

    @Override
    public void onBackPressed() {
        focusDock(focusIndex);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (idle) {
            // The key that wakes the screen does nothing else.
            if (e.getAction() == KeyEvent.ACTION_UP) wake();
            return true;
        }
        scheduleSleep();
        if (page == 2 && moodsKey(e)) return true;
        if (page == 3 && continueKey(e)) return true;
        if (page == 1 && favouritesKey(e)) return true;
        if (page == 0 && e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && more.hasFocus()) {
            enterContinue();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---- layout ---------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        // Wallpapers sit on a slightly enlarged stage so the parallax never shows an edge.
        stage = new FrameLayout(this);
        stage.setScaleX(1.08f);
        stage.setScaleY(1.08f);
        for (int i = 0; i < 2; i++) {
            ImageView w = new ImageView(this);
            w.setScaleType(ImageView.ScaleType.CENTER_CROP);
            w.setAlpha(i == 0 ? 1f : 0f);
            walls[i] = w;
            stage.addView(w, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        root.addView(stage, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Dark mode: a dim over everything, deeper at the top for the clock and black at the bottom.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xB8000000, 0x52000000, 0x66000000, 0xE6000000, 0xFA000000}));
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        // My Favourites sits on a darker page.
        favShade = new View(this);
        favShade.setBackgroundColor(Color.BLACK);
        favShade.setAlpha(0f);
        root.addView(favShade, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Everything but the wallpaper: the screensaver fades this one layer.
        layer = new FrameLayout(this);
        layer.setClipChildren(false);
        root.addView(layer, new FrameLayout.LayoutParams(MATCH, MATCH));

        chrome = new FrameLayout(this);
        chrome.setClipChildren(false);
        layer.addView(chrome, new FrameLayout.LayoutParams(MATCH, MATCH));
        chrome.addView(buildTopLeft(), place(WRAP, WRAP, Gravity.TOP | Gravity.START, 40, 34, 0, 0));
        View topRight = buildTopRight();
        chrome.addView(topRight, place(WRAP, WRAP, Gravity.TOP | Gravity.END, 0, 36, 44, 0));
        // Which film the wallpaper is from: small and faint, under the icons.
        caption = text(medium, 10, 0x73FFFFFF);
        caption.setLetterSpacing(0.22f);
        chrome.addView(caption, place(WRAP, WRAP, Gravity.TOP | Gravity.END, 0, 92, 56, 0));
        // Screensaver only: a line from the wallpaper's film, bottom right.
        filmLine = text(light, 21, 0xE6FFFFFF);
        filmLine.setGravity(Gravity.END);
        filmLine.setLineSpacing(0, 1.12f);
        filmLine.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 2 / 5);
        filmLine.setShadowLayer(dp(8), 0, 0, 0x99000000);
        filmLine.setAlpha(0f);
        chrome.addView(filmLine, place(WRAP, WRAP, Gravity.BOTTOM | Gravity.END, 0, 0, 58, 36));
        View bottom = buildBottom();
        chrome.addView(bottom, place(MATCH, WRAP, Gravity.BOTTOM, 0, 0, 0, 22));
        sleepers.add(topRight);
        sleepers.add(bottom);
        sleepers.add(amazonButton);

        fav = new Favourites(this, dir, light, medium);
        fav.setAlpha(0f);
        fav.setTranslationY(getResources().getDisplayMetrics().heightPixels * 0.25f);
        fav.setVisibility(View.INVISIBLE);
        layer.addView(fav, new FrameLayout.LayoutParams(MATCH, MATCH));

        cont = new Continue(this, dir, light, medium, backdrop -> {
            File f = backdrop != null ? backdrop : wallShown != null ? new File(wallShown) : null;
            if (f != null) showImage(f, 700);
        });
        cont.setAlpha(0f);
        cont.setTranslationY(getResources().getDisplayMetrics().heightPixels * 0.25f);
        cont.setVisibility(View.INVISIBLE);
        layer.addView(cont, new FrameLayout.LayoutParams(MATCH, MATCH));

        moods = new Moods(this, dir, light, medium, new Moods.Host() {
            @Override
            public void scene(File scene) {
                if (scene != null && scene.isFile()) showImage(scene, 700);
            }

            @Override
            public void open(Moods.Title t) {
                openTitle(t.app, t.link, t.title);
            }
        });
        moods.setAlpha(0f);
        moods.setTranslationY(getResources().getDisplayMetrics().heightPixels * 0.25f);
        moods.setVisibility(View.INVISIBLE);
        layer.addView(moods, new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    // ---- My Favourites --------------------------------------------------------------------

    /**
     * Down from Continue watching: the page slides up into the lineup, centre of the front row
     * selected. Every visit deals a new set of characters from the pool.
     */
    private void enterFavourites() {
        if (favLoading) return;
        favLoading = true;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        io.execute(() -> {
            List<Favourites.Character> chars = fav.read(sw, sh);
            List<Bitmap> bitmaps = new ArrayList<>();
            for (Favourites.Character ch : chars) bitmaps.add(Favourites.decode(ch.cutout, Math.round(ch.h * sh * 1.06f)));
            ui.post(() -> {
                favLoading = false;
                if (isDestroyed() || page != 3 || !resumed) return;
                fav.show(chars, bitmaps, sw, sh);
                if (fav.all.isEmpty()) return;
                favIndex = -1;
                slideInFavourites();
            });
        });
    }

    private void slideInFavourites() {
        page = 1;
        int h = getResources().getDisplayMetrics().heightPixels;
        cont.animate().translationY(-h * 0.25f).alpha(0f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        fav.setVisibility(View.VISIBLE);
        fav.animate().translationY(0).alpha(1f).setDuration(480).setInterpolator(new DecelerateInterpolator()).start();
        favShade.animate().alpha(0.3f).setDuration(480).start();
        selectFavourite(favIndex >= 0 && favIndex < fav.all.size() ? favIndex : fav.entry());
    }

    /** Up from the front row, or Back: back to Continue watching. */
    private void leaveFavourites() {
        page = 3;
        int h = getResources().getDisplayMetrics().heightPixels;
        fav.clear();
        fav.animate().translationY(h * 0.25f).alpha(0f).setDuration(380).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (page != 1 && page != 2) {
                        fav.setVisibility(View.INVISIBLE);
                        fav.release(); // frees the cut-outs; the next visit deals new ones
                    }
                }).start();
        cont.animate().translationY(0).alpha(1f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        favShade.animate().alpha(0.35f).setDuration(420).start();
        stage.animate().translationX(0).setDuration(900).setInterpolator(new DecelerateInterpolator()).start();
        cont.resume();
    }

    private void selectFavourite(int i) {
        if (i < 0) return;
        favIndex = i;
        Favourites.Character ch = fav.all.get(i);
        Bitmap logo = BitmapFactory.decodeFile(new File(dir, "logos/" + ch.app + ".png").getPath());
        fav.select(i, logo);
        if (ch.scene != null && ch.scene.isFile()) showImage(ch.scene, 700);
        stage.animate().translationX(-(ch.cx - 0.5f) * dp(40)).setDuration(900)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void openFavourite() {
        Favourites.Character ch = fav.current();
        if (ch != null) openTitle(ch.app, ch.link, ch.title);
    }

    /** Opens a title's own page in its app when there is a link, otherwise the app. */
    private void openTitle(String app, String url, String title) {
        Lights.opened(this, app, title);
        Watching.opened(this, app, title, url);
        PackageManager pm = getPackageManager();
        if (url != null && !"-".equals(url) && !url.isEmpty()) {
            Intent link = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(app);
            // Netflix's TV app only follows a link that names where it came from.
            if ("com.netflix.ninja".equals(app)) link.putExtra("source", "30");
            if (link.resolveActivity(pm) != null) {
                // A fresh task: an app already running in the background would otherwise just
                // come to the front where it was, ignoring the link.
                try {
                    startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    // fall through to opening the app
                }
            }
        }
        // No link: open the app. Prime Video's launcher entry needs its routing activity; see dock.txt.
        Intent i = null;
        for (Dock d : dock) if (d.pkg.equals(app)) i = new Intent(d.launch);
        if (i == null) i = pm.getLeanbackLaunchIntentForPackage(app);
        if (i == null) i = pm.getLaunchIntentForPackage(app);
        if (i == null) {
            Toast.makeText(this, "That app isn't installed", Toast.LENGTH_SHORT).show();
            return;
        }
        launch(i);
    }

    /**
     * A YouTube card. Its app follows a youtube.com/watch link even while it's running, but only
     * when the link comes without a fresh task (a fresh task, or a youtu.be link, gets ignored or
     * its home page); and it starts the video from the top, so Watching moves it to where it was
     * left, under the guard's cover.
     */
    private void openYouTube(Watching.Entry t) {
        if (!t.link.startsWith("https://www.youtube.com/watch")) {
            openTitle(t.app, t.link, t.title); // never looked up: just the app
            return;
        }
        Lights.opened(this, t.app, t.title);
        Watching.opened(this, t.app, t.title, t.link);
        Watching.resumeAt(t);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(t.link)).setPackage(t.app)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Watching.cancelResume();
            openTitle(t.app, null, t.title);
        }
    }

    // ---- mood cards -------------------------------------------------------------------------

    /** Down from Suggestions: the mood deck slides up; posters are decoded just for this visit. */
    private void enterMoods() {
        if (moodsLoading) return;
        moodsLoading = true;
        io.execute(() -> {
            List<Moods.Mood> deck = moods.read();
            ui.post(() -> {
                moodsLoading = false;
                if (isDestroyed() || page != 1 || deck.isEmpty()) return;
                page = 2;
                int h = getResources().getDisplayMetrics().heightPixels;
                fav.clear();
                fav.animate().translationY(-h * 0.25f).alpha(0f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
                moods.show(deck);
                moods.setVisibility(View.VISIBLE);
                moods.animate().translationY(0).alpha(1f).setDuration(480).setInterpolator(new DecelerateInterpolator()).start();
                favShade.animate().alpha(0.35f).setDuration(480).start();
            });
        });
    }

    /** Up or Back on the deck: back to Suggestions. */
    private void leaveMoods() {
        page = 1;
        int h = getResources().getDisplayMetrics().heightPixels;
        moods.animate().translationY(h * 0.25f).alpha(0f).setDuration(380).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (page != 2) {
                        moods.setVisibility(View.INVISIBLE);
                        moods.release();
                    }
                }).start();
        fav.animate().translationY(0).alpha(1f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        selectFavourite(favIndex >= 0 && favIndex < fav.all.size() ? favIndex : fav.entry());
    }

    /** HOME or the screensaver from any section: straight back to the dock. */
    private void backToDock() {
        int h = getResources().getDisplayMetrics().heightPixels;
        if (page == 2) {
            moods.release();
            moods.setVisibility(View.INVISIBLE);
            moods.setAlpha(0f);
            moods.setTranslationY(h * 0.25f);
            page = 1;
        }
        if (page == 1) {
            fav.clear();
            fav.animate().cancel();
            fav.release();
            fav.setVisibility(View.INVISIBLE);
            fav.setAlpha(0f);
            fav.setTranslationY(h * 0.25f);
            page = 3;
        }
        if (page == 3) leaveContinue();
    }

    // ---- continue watching ----------------------------------------------------------------

    /** Down from the apps: what played lately slides up, its images decoded just for this visit. */
    private void enterContinue() {
        if (contLoading) return;
        contLoading = true;
        io.execute(() -> {
            List<Continue.Card> cards = cont.read();
            ui.post(() -> {
                contLoading = false;
                if (isDestroyed() || page != 0 || !resumed) return;
                page = 3;
                int h = getResources().getDisplayMetrics().heightPixels;
                chrome.animate().translationY(-h * 0.25f).alpha(0f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
                cont.show(cards);
                cont.setVisibility(View.VISIBLE);
                cont.animate().translationY(0).alpha(1f).setDuration(480).setInterpolator(new DecelerateInterpolator()).start();
                favShade.animate().alpha(0.35f).setDuration(480).start();
            });
        });
    }

    /** Up or Back: back to the apps. */
    private void leaveContinue() {
        page = 0;
        int h = getResources().getDisplayMetrics().heightPixels;
        cont.animate().translationY(h * 0.25f).alpha(0f).setDuration(380).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (page == 0) {
                        cont.setVisibility(View.INVISIBLE);
                        cont.release();
                    }
                }).start();
        chrome.animate().translationY(0).alpha(1f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        favShade.animate().alpha(0f).setDuration(420).start();
        if (wallShown != null) showImage(new File(wallShown), 900);
        focusDock(focusIndex);
    }

    private boolean continueKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
        if (!ours) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (cont.asking()) { // OK takes the card off the row; anything else keeps it
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                Watching.Entry gone = cont.removeSelected();
                if (gone != null) Watching.remove(gone.key);
            } else {
                cont.cancelRemove();
            }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                cont.askRemove();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cont.move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cont.move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
                leaveContinue();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                enterFavourites();
                break;
            default:
                Watching.Entry t = cont.current();
                if (t == null || e.getRepeatCount() > 0) break;
                if (Watching.YOUTUBE.equals(t.app)) openYouTube(t);
                else openTitle(t.app, Watching.playLink(t), t.title);
        }
        return true;
    }

    /** Keys while the lineup shows: the lineup moves its own selection, nothing else gets focus. */
    private boolean moodsKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK;
        if (!ours) return false;
        if (!moods.key(e)) leaveMoods();
        return true;
    }

    private boolean favouritesKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK;
        if (!ours) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int at = fav.selected();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                selectFavourite(fav.neighbour(at, -1, 0));
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                selectFavourite(fav.neighbour(at, 1, 0));
                break;
            case KeyEvent.KEYCODE_DPAD_UP: {
                int up = fav.neighbour(at, 0, -1);
                if (up >= 0) selectFavourite(up);
                else leaveFavourites();
                break;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int down = fav.neighbour(at, 0, 1);
                if (down >= 0) selectFavourite(down);
                else enterMoods();
                break;
            }
            case KeyEvent.KEYCODE_BACK:
                leaveFavourites();
                break;
            default:
                if (e.getRepeatCount() == 0) openFavourite();
        }
        return true;
    }

    private View buildTopLeft() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // The only way to Amazon's home screen. The guard lets it stay until another app opens.
        amazonButton = new ImageView(this);
        amazonButton.setImageDrawable(getDrawable(R.drawable.ic_amazon));
        amazonButton.setContentDescription("Amazon home screen");
        amazonButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        amazonButton.setAlpha(0.55f);
        amazonButton.setFocusable(true);
        amazonButton.setClickable(true);
        amazonButton.setId(View.generateViewId());
        amazonButton.setOnClickListener(v -> Guard.amazon(this));
        amazonButton.setOnFocusChangeListener((v, has) -> grow(v, has, 1.2f, has ? 1f : 0.55f));
        amazonButton.setNextFocusLeftId(amazonButton.getId());
        amazonButton.setNextFocusUpId(amazonButton.getId());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
        alp.rightMargin = dp(10);
        top.addView(amazonButton, alp);
        clock = text(light, 54, 0xFFF4F4F6);
        top.addView(clock);
        wxIcon = new ImageView(this);
        wxIcon.setAlpha(0.85f);
        wxIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.leftMargin = dp(26);
        top.addView(wxIcon, ilp);
        temp = text(regular, 19, 0xD9FFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tlp.leftMargin = dp(8);
        top.addView(temp, tlp);
        return top;
    }

    private View buildTopRight() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        lightIcon = (ImageView) icon(R.drawable.ic_lights, "Lights", v -> openLightMenu());
        bar.addView(lightIcon);
        Config cfg = Config.get(this);
        BrightnessBar level = new BrightnessBar(this, Lights.brightnessOf(cfg), cfg.accent(), percent -> {
            Config.get(this).set(Config.LIGHTS_BRIGHTNESS.key, Integer.toString(percent));
            Lights.brightness(percent);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(88), dp(48));
        blp.rightMargin = dp(10);
        bar.addView(level, blp);
        bar.addView(icon(R.drawable.ic_wifi, "Wi-Fi", v -> openWifi()));
        bar.addView(icon(R.drawable.ic_input, "Inputs", v -> pickInput()));
        bar.addView(icon(R.drawable.ic_settings, "Settings", v -> openSettings()));
        icons.get(0).setNextFocusLeftId(amazonButton.getId());
        amazonButton.setNextFocusRightId(icons.get(0).getId());
        icons.get(icons.size() - 1).setNextFocusRightId(icons.get(icons.size() - 1).getId());
        return bar;
    }

    /** The top bar's light icon: a bulb giving light while the strip is switched on. */
    private void showLights() {
        Config cfg = Config.get(this);
        cfg.refresh();
        if (lightIcon != null) {
            lightIcon.setImageDrawable(getDrawable(Lights.powerOf(cfg) ? R.drawable.ic_lights : R.drawable.ic_lights_off));
        }
    }

    /**
     * The lighting menu, a card under the light icon: the on/off switch and the camera's and the
     * sound's lining up on top, the four modes below as symbols, and under them strobe or gradual, the one in use on an accent-coloured pill. BACK closes it, and so do five
     * seconds without a key press.
     */
    private void openLightMenu() {
        if (lightMenu != null && lightMenu.isShowing()) return;
        Config cfg = Config.get(this);
        cfg.refresh();
        int accent = cfg.accent();
        int pill = (accent & 0x00FFFFFF) | 0x40000000;
        LinearLayout card = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                stillUsing(this); // any key in the menu restarts its five seconds
                return super.dispatchKeyEvent(e);
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(Ui.round(0xF2141518, dp(20)));

        LinearLayout modes = new LinearLayout(this);
        ImageView[] buttons = new ImageView[LIGHT_ICONS.length];
        Runnable[] showModes = new Runnable[1];

        boolean on = Lights.powerOf(cfg);
        LinearLayout sw = new LinearLayout(this);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(dp(12), dp(10), dp(12), dp(10));
        sw.setFocusable(true);
        sw.setClickable(true);
        sw.setContentDescription("Light strip on or off");
        sw.setBackground(focusPill(0x26FFFFFF));
        ImageView powerIcon = new ImageView(this);
        powerIcon.setImageDrawable(getDrawable(R.drawable.ic_power));
        sw.addView(powerIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        Toggle toggle = new Toggle(this, on, accent);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(46), dp(26));
        tlp.leftMargin = dp(16);
        sw.addView(toggle, tlp);
        sw.setOnClickListener(v -> {
            boolean now = !Lights.powerOf(Config.get(this));
            Config.get(this).set(Config.LIGHTS_POWER.key, now ? "on" : "off");
            Lights.power(now);
            toggle.set(now);
            showModes[0].run();
            showLights();
        });
        // Top row: the switch, and beside it the camera's lining up (white, then black, on screen).
        ImageView cal = new ImageView(this);
        cal.setImageDrawable(getDrawable(R.drawable.ic_calibrate));
        cal.setContentDescription("Line up the light camera");
        cal.setPadding(dp(12), dp(12), dp(12), dp(12));
        cal.setFocusable(true);
        cal.setClickable(true);
        cal.setAlpha(0.7f);
        cal.setBackground(focusPill(0x26FFFFFF));
        cal.setOnFocusChangeListener((v, has) -> grow(v, has, 1.15f, has ? 1f : 0.7f));
        cal.setOnClickListener(v -> {
            lightMenu.dismiss();
            startActivity(new Intent(this, Calibrate.class));
        });
        // And beside it, lining the light up with sound: a test rhythm at the usual volume.
        ImageView snd = new ImageView(this);
        snd.setImageDrawable(getDrawable(R.drawable.ic_sound_calibrate));
        snd.setContentDescription("Tune the light to the sound");
        snd.setPadding(dp(12), dp(12), dp(12), dp(12));
        snd.setFocusable(true);
        snd.setClickable(true);
        snd.setAlpha(0.7f);
        snd.setBackground(focusPill(0x26FFFFFF));
        snd.setOnFocusChangeListener((v, has) -> grow(v, has, 1.15f, has ? 1f : 0.7f));
        snd.setOnClickListener(v -> {
            lightMenu.dismiss();
            startActivity(new Intent(this, SoundCalibrate.class));
        });
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(sw, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(50), dp(50));
        clp.leftMargin = dp(18);
        top.addView(cal, clp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(50), dp(50));
        slp.leftMargin = dp(6);
        top.addView(snd, slp);
        LinearLayout.LayoutParams toplp = new LinearLayout.LayoutParams(WRAP, WRAP);
        toplp.gravity = Gravity.CENTER_HORIZONTAL;
        toplp.bottomMargin = dp(8);
        card.addView(top, toplp);

        for (int i = 0; i < LIGHT_ICONS.length; i++) {
            String m = Lights.MODES[i];
            ImageView b = new ImageView(this);
            b.setImageDrawable(getDrawable(LIGHT_ICONS[i]));
            b.setContentDescription(LIGHT_NAMES[i]);
            b.setPadding(dp(12), dp(12), dp(12), dp(12));
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                Config.get(this).set(Config.LIGHTS.key, m);
                Lights.choose(m);
                showModes[0].run();
            });
            b.setOnFocusChangeListener((v, has) -> showModes[0].run());
            buttons[i] = b;
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(50), dp(50));
            blp.leftMargin = blp.rightMargin = dp(3);
            modes.addView(b, blp);
        }
        card.addView(modes, new LinearLayout.LayoutParams(WRAP, WRAP)); // its own width, not the switch's

        // Below: how sound shows, strobe or gradual. Only the modes with sound use it.
        LinearLayout styles = new LinearLayout(this);
        ImageView[] styleButtons = new ImageView[STYLE_ICONS.length];
        for (int i = 0; i < STYLE_ICONS.length; i++) {
            String st = Lights.STYLES[i];
            ImageView b = new ImageView(this);
            b.setImageDrawable(getDrawable(STYLE_ICONS[i]));
            b.setContentDescription(STYLE_NAMES[i]);
            b.setPadding(dp(12), dp(12), dp(12), dp(12));
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                Config.get(this).set(Config.LIGHTS_STYLE.key, st);
                Lights.style(st);
                showModes[0].run();
            });
            b.setOnFocusChangeListener((v, has) -> showModes[0].run());
            styleButtons[i] = b;
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(50), dp(50));
            blp.leftMargin = blp.rightMargin = dp(3);
            styles.addView(b, blp);
        }
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        stlp.gravity = Gravity.CENTER_HORIZONTAL;
        stlp.topMargin = dp(6);
        card.addView(styles, stlp);

        // The mode in use on an accent pill; focus is a soft white one, a bigger and bright symbol;
        // switched off, all dim.
        showModes[0] = () -> {
            Config c = Config.get(this);
            String mode = Lights.modeOf(c);
            boolean powered = Lights.powerOf(c);
            for (int i = 0; i < buttons.length; i++) {
                ImageView b = buttons[i];
                boolean chosen = Lights.MODES[i].equals(mode);
                b.setBackground(chosen ? Ui.round(powered ? pill : 0x33FFFFFF, dp(14))
                        : b.isFocused() ? Ui.round(0x26FFFFFF, dp(14)) : null);
                float alpha = b.isFocused() ? 1f : chosen ? (powered ? 1f : 0.5f) : (powered ? 0.45f : 0.25f);
                b.animate().alpha(alpha).scaleX(b.isFocused() ? 1.15f : 1f).scaleY(b.isFocused() ? 1.15f : 1f)
                        .setDuration(150).start();
            }
            // Strobe or gradual: only for the modes with sound. Otherwise (static, video sync, switched
            // off) greyed out: grey and faint, and the remote passes over it.
            String styleNow = Lights.styleOf(c);
            boolean sound = powered && (Lights.SOUND.equals(mode) || Lights.BOTH.equals(mode));
            for (int i = 0; i < styleButtons.length; i++) {
                ImageView b = styleButtons[i];
                boolean chosen = Lights.STYLES[i].equals(styleNow);
                b.setFocusable(sound);
                b.setClickable(sound);
                if (sound) b.clearColorFilter();
                else b.setColorFilter(0xFF808080, PorterDuff.Mode.SRC_IN);
                boolean focused = sound && b.isFocused();
                b.setBackground(chosen ? Ui.round(sound ? pill : 0x1AFFFFFF, dp(14))
                        : focused ? Ui.round(0x26FFFFFF, dp(14)) : null);
                float alpha = !sound ? (chosen ? 0.4f : 0.25f) : focused ? 1f : chosen ? 1f : 0.45f;
                b.animate().alpha(alpha).scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
                        .setDuration(150).start();
            }
        };
        showModes[0].run();

        lightMenu = new PopupWindow(card, WRAP, WRAP, true);
        lightMenu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        lightMenu.setOutsideTouchable(true);
        lightMenu.setElevation(dp(12));
        lightMenu.setOnDismissListener(() -> {
            card.removeCallbacks(closeLightMenu);
            lightIcon.requestFocus();
        });
        // Right-aligned under the top bar's last icon, clear of the screen's edge.
        lightMenu.showAsDropDown(icons.get(icons.size() - 1), 0, dp(8), Gravity.END);
        int current = Arrays.asList(Lights.MODES).indexOf(Lights.modeOf(cfg));
        View first = on && current >= 0 ? buttons[current] : sw;
        first.post(first::requestFocus);
        stillUsing(card);
    }

    private void stillUsing(View card) {
        card.removeCallbacks(closeLightMenu);
        card.postDelayed(closeLightMenu, LIGHT_MENU_IDLE_MS);
    }

    /** A soft pill behind a view while it has focus. */
    private Drawable focusPill(int colour) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(colour, dp(14)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(14)));
        return bg;
    }

    private View buildBottom() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        quote = text(light, 23, 0xEBFFFFFF);
        quote.setGravity(Gravity.CENTER);
        quote.setMaxWidth(dp(700));
        quote.setLineSpacing(0, 1.1f);
        quote.setShadowLayer(dp(10), 0, 0, 0x99000000);
        col.addView(quote, new LinearLayout.LayoutParams(WRAP, WRAP));
        source = text(regular, 10.5f, 0x8CFFFFFF);
        source.setLetterSpacing(0.24f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP);
        slp.topMargin = dp(9);
        col.addView(source, slp);

        dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        glowShape = new GradientDrawable();
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientRadius(dp(GLOW / 2f));
        glowShape.setColors(new int[] {Color.TRANSPARENT, Color.TRANSPARENT});
        glow = new View(this);
        glow.setBackground(glowShape);
        row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        dockFrame.addView(row, new FrameLayout.LayoutParams(WRAP, dp(ROW), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(MATCH, dp(ROW + 8));
        dlp.topMargin = dp(26);
        col.addView(dockFrame, dlp);

        more = text(medium, 11.5f, 0xFFFFFFFF);
        more.setText("MORE APPS");
        more.setLetterSpacing(0.22f);
        more.setAlpha(0.5f);
        more.setPadding(dp(16), dp(8), dp(16), dp(8));
        more.setFocusable(true);
        more.setClickable(true);
        more.setId(View.generateViewId());
        more.setOnClickListener(v -> startActivity(new Intent(this, Library.class)));
        more.setOnFocusChangeListener((v, has) -> {
            grow(v, has, 1.12f, has ? 1f : 0.5f);
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mlp.topMargin = dp(6);
        col.addView(more, mlp);
        // The glow sits behind the column, centred on the dock row (about 100 dp above the bottom).
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(glow, new FrameLayout.LayoutParams(dp(GLOW), dp(GLOW), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        // Centred on the icons: half an icon above the baseline, measured from the bottom.
        glow.setTranslationY(dp(GLOW / 2f - (ROW + 8 - BASE + ICON_MAX / 2f + 36)));
        wrap.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        return wrap;
    }

    private View icon(int res, String name, View.OnClickListener click) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(getDrawable(res));
        v.setContentDescription(name);
        v.setPadding(dp(11), dp(11), dp(11), dp(11));
        v.setAlpha(0.7f);
        v.setFocusable(true);
        v.setClickable(true);
        v.setId(View.generateViewId());
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            grow(view, has, 1.2f, has ? 1f : 0.7f);
        });
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        icons.add(v);
        return v;
    }

    // ---- dock -----------------------------------------------------------------------------

    private File dockFile() {
        return new File(dir, "dock.txt");
    }

    private void loadDock() {
        File f = dockFile();
        dockStamp = f.lastModified();
        dock.clear();
        row.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String line : read(f)) {
            String[] p = line.split("\\|", 4);
            if (line.trim().isEmpty() || line.trim().startsWith("#") || p.length < 4) continue;
            Dock d = new Dock();
            // "package" or "package/activity" for apps whose launcher entry goes somewhere odd
            ComponentName exact = p[0].contains("/") ? ComponentName.unflattenFromString(p[0].trim()) : null;
            d.pkg = exact != null ? exact.getPackageName() : p[0].trim();
            d.name = p[1].trim();
            try {
                d.color = Color.parseColor(p[2].trim());
            } catch (IllegalArgumentException e) {
                d.color = 0xFFFFFFFF;
            }
            List<String[]> quotes = new ArrayList<>();
            for (String q : p[3].split("\\|\\|")) {
                String[] qa = q.split("~", 2);
                quotes.add(new String[] {qa[0].trim(), qa.length > 1 ? qa[1].trim() : ""});
            }
            d.quotes = quotes.toArray(new String[0][]);
            if (exact != null) {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(exact);
                d.launch = i.resolveActivity(pm) != null ? i : null;
            } else {
                d.launch = pm.getLeanbackLaunchIntentForPackage(d.pkg);
                if (d.launch == null) d.launch = pm.getLaunchIntentForPackage(d.pkg);
            }
            if (d.launch == null) continue; // not installed
            File icon = new File(dir, "icons/" + d.pkg + ".png");
            boolean isIcon = icon.isFile();
            Bitmap logo = BitmapFactory.decodeFile((isIcon ? icon : new File(dir, "logos/" + d.pkg + ".png")).getPath());
            d.cell = cell(d, logo, isIcon, dock.size());
            dock.add(d);
            row.addView(d.cell);
        }
        // Left and Right stop at the ends; Up goes to the icons, Down to More apps.
        for (int i = 0; i < dock.size(); i++) {
            View c = dock.get(i).cell;
            if (i == 0) c.setNextFocusLeftId(c.getId());
            if (i == dock.size() - 1) c.setNextFocusRightId(c.getId());
            c.setNextFocusUpId(i < 2 ? amazonButton.getId() : icons.get(icons.size() - 1).getId());
            if (i == 0) amazonButton.setNextFocusDownId(c.getId());
            c.setNextFocusDownId(more.getId());
        }
        if (focusIndex >= dock.size()) focusIndex = 0;
    }

    private View cell(Dock d, Bitmap logo, boolean isIcon, int index) {
        FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setId(View.generateViewId());
        cell.setAlpha(0.72f);
        cell.setContentDescription(d.name);
        int w, h;
        ImageView shadow = null;
        if (logo != null) {
            float r = (float) logo.getWidth() / logo.getHeight();
            float hd = Math.min(isIcon ? ICON_MAX : 40f, (float) Math.sqrt((isIcon ? ICON_AREA : LOGO_AREA) / r));
            h = dp(hd);
            w = dp(hd * r);
            // Every icon stands on the same baseline, its mirrored shadow fading out beneath it.
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            ilp.topMargin = dp(BASE) - h;
            cell.addView(iv, ilp);
            shadow = new ImageView(this);
            shadow.setImageBitmap(reflection(logo));
            shadow.setAlpha(0f); // only the selected app shows its reflection
            shadow.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, Math.round(h * REFLECTION), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            // The floor is LIFT below the icon; a reflection sits as far below the floor as the icon
            // floats above it, so the two mirror each other across it.
            slp.topMargin = dp(BASE + 2 * LIFT);
            cell.addView(shadow, slp);
        } else {
            TextView t = text(medium, 20, 0xFFFFFFFF);
            t.setText(d.name);
            cell.addView(t, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
            w = dp(110);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w + dp(GAP), MATCH);
        cell.setLayoutParams(lp);
        cell.setPivotY(dp(BASE)); // grows up from its base, the shadow down
        cell.setClipChildren(false);
        cell.setOnClickListener(v -> launch(d.launch));
        View reflection = shadow;
        cell.setOnFocusChangeListener((v, has) -> {
            grow(v, has, 1.17f, has ? 1f : 0.72f);
            if (reflection != null) reflection.animate().alpha(has ? 1f : 0f).setDuration(has ? 320 : 180).start();
            if (has) focused(index);
        });
        return cell;
    }

    /** The icon flipped upside down, fading from faint to nothing: a soft shadow on a glossy floor. */
    private static Bitmap reflection(Bitmap src) {
        int w = src.getWidth(), h = Math.max(1, Math.round(src.getHeight() * REFLECTION));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix flip = new Matrix();
        flip.preScale(1f, -1f);
        flip.postTranslate(0f, src.getHeight());
        c.drawBitmap(src, flip, null);
        Paint fade = new Paint();
        fade.setShader(new LinearGradient(0, 0, 0, h, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, fade);
        return out;
    }

    private void focusDock(int i) {
        if (dock.isEmpty()) {
            more.requestFocus();
            return;
        }
        View c = dock.get(Math.max(0, Math.min(i, dock.size() - 1))).cell;
        if (c.hasFocus()) focused(focusIndex);
        else c.requestFocus();
    }

    /** Quote, glow and parallax follow the focused app. */
    private void focused(int i) {
        focusIndex = i;
        Dock d = dock.get(i);
        View c = d.cell;
        c.post(() -> {
            float center = row.getLeft() + c.getLeft() + c.getWidth() / 2f - dockFrame.getWidth() / 2f;
            glow.animate().translationX(center).setDuration(380).setInterpolator(new DecelerateInterpolator()).start();
        });
        glowTo(d.color);
        String[] q = quoteFor(i, d);
        setQuote(q[0], q[1]);
        float mid = (dock.size() - 1) / 2f;
        stage.animate().translationX(-(i - mid) * dp(12)).setDuration(900)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * A quote for the focused app: from the daily pool (pool/quotes.txt, refreshed every evening),
     * different for each app and each visit; dock.txt's own quotes when there is no pool.
     */
    private String[] quoteFor(int app, Dock d) {
        File f = new File(dir, "pool/quotes.txt");
        if (f.lastModified() != quotesStamp) {
            quotesStamp = f.lastModified();
            quotePool.clear();
            for (String line : read(f)) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] qa = line.split("~", 2);
                quotePool.add(new String[] {qa[0].trim(), qa.length > 1 ? qa[1].trim() : ""});
            }
        }
        if (quotePool.isEmpty()) return d.quotes[visit % d.quotes.length];
        return quotePool.get(new java.util.Random(visit * 131L + app).nextInt(quotePool.size()));
    }

    private final List<String[]> quotePool = new ArrayList<>();
    private long quotesStamp = -1;

    private void setQuote(String q, String src) {
        String shown = "“" + q + "”";
        if (shown.contentEquals(quote.getText())) return;
        quote.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            quote.setText(shown);
            source.setText(src.toUpperCase(Locale.ROOT));
            quote.animate().alpha(1f).setDuration(260).start();
        }).start();
        source.animate().alpha(0f).setDuration(110).withEndAction(() -> source.animate().alpha(1f).setDuration(260).start()).start();
    }

    private void glowTo(int color) {
        if (glowAnimation != null) glowAnimation.cancel();
        glowAnimation = ValueAnimator.ofArgb(glowColor == 0 ? withAlpha(color, 0) : glowColor, color);
        glowAnimation.setDuration(380);
        glowAnimation.addUpdateListener(a -> {
            glowColor = (Integer) a.getAnimatedValue();
            glowShape.setColors(new int[] {withAlpha(glowColor, 0x5C), withAlpha(glowColor, 0x1A), Color.TRANSPARENT});
        });
        glowAnimation.start();
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    // ---- wallpaper ------------------------------------------------------------------------

    /** A new wallpaper on every visit, crossfaded; {@code next} moves on without a visit. */
    private void showWallpaper(boolean next) {
        List<File> all = Walls.list(dir);
        if (all.isEmpty()) return;
        int n = prefs.getInt("wall", -1);
        if (next || wallShown == null || resumed) n++;
        n = Math.floorMod(n, all.size());
        prefs.edit().putInt("wall", n).apply();
        File f = all.get(n);
        if (f.getPath().equals(wallShown)) return;
        wallShown = f.getPath();
        showImage(f, 1400);
        String c = Walls.caption(f);
        caption.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            caption.setText(c == null ? "" : c.toUpperCase(Locale.ROOT));
            caption.animate().alpha(1f).setDuration(600).start();
        }).start();
    }

    /**
     * Crossfades the stage to {@code f}. Scenes are kept in a small cache so moving back and
     * forth along the lineup switches instantly.
     */
    private void showImage(File f, int fadeMs) {
        String key = f.getPath();
        imageWanted = key;
        Bitmap cached = images.get(key);
        if (cached != null) {
            crossfade(cached, fadeMs);
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        io.execute(() -> {
            Bitmap b = Walls.decode(f, w, h);
            ui.post(() -> {
                if (isDestroyed() || b == null) return;
                images.put(key, b);
                if (key.equals(imageWanted)) crossfade(b, fadeMs);
            });
        });
    }

    private void crossfade(Bitmap b, int fadeMs) {
        ImageView in = walls[1 - front], out = walls[front];
        if (out.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) out.getDrawable()).getBitmap() == b) return;
        int ms = wallShownBefore ? fadeMs : 0;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageBitmap(b);
        in.animate().alpha(1f).setDuration(ms).start();
        out.animate().alpha(0f).setDuration(ms).start();
        front = 1 - front;
        wallShownBefore = true;
    }

    private boolean wallShownBefore;
    private String imageWanted;
    /** The last couple of full-screen images: 8 MB each. */
    private final java.util.LinkedHashMap<String, Bitmap> images = new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
            return size() > 2;
        }
    };

    /** A slow push-in on the wallpaper, back and forth: the scene never quite stands still. */
    private void startDrift() {
        if (Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return;
        if (drift != null) drift.cancel();
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(38_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            for (ImageView w : walls) {
                w.setScaleX(1f + 0.06f * v);
                w.setScaleY(1f + 0.06f * v);
                w.setTranslationY(-dp(10) * v);
            }
        });
        drift.start();
    }

    // ---- screensaver ----------------------------------------------------------------------

    private void scheduleSleep() {
        ui.removeCallbacks(sleep);
        if (resumed) ui.postDelayed(sleep, IDLE_AFTER);
    }

    private void sleep() {
        if (!resumed) return;
        idle = true;
        backToDock();
        // Screensaver: the wallpaper, the time, the weather and the film; everything else fades.
        for (View v : sleepers) v.animate().alpha(0f).setDuration(1600).start();
        caption.animate().alpha(1f).setDuration(1600).start();
        showFilmLine(1800);
        ui.postDelayed(idleRotate, IDLE_ROTATE);
    }

    private void idleRotate() {
        if (!idle || !resumed) return;
        if (page == 0) showWallpaper(true);
        showFilmLine(600);
        ui.postDelayed(idleRotate, IDLE_ROTATE);
    }

    /** The screensaver's line from the film now on screen. */
    private void showFilmLine(long delay) {
        String q = wallShown == null ? null : Walls.quote(new File(wallShown));
        filmLine.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction(() -> {
            filmLine.setText(q == null ? "" : "\u201C" + q + "\u201D");
            if (idle) filmLine.animate().alpha(1f).setStartDelay(delay).setDuration(1200).start();
        }).start();
    }

    private void wake() {
        idle = false;
        ui.removeCallbacks(idleRotate);
        for (View v : sleepers) v.animate().alpha(1f).setDuration(350).start();
        filmLine.animate().alpha(0f).setStartDelay(0).setDuration(250).start();
        scheduleSleep();
    }

    // ---- clock and weather ----------------------------------------------------------------

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
        if (resumed && System.currentTimeMillis() - weatherAt > Weather.EVERY) weather();
        if (resumed) showLights(); // picks up the switch changed elsewhere (Options, home.conf)
    }

    /** Only while the home screen shows, at most every 30 minutes. */
    private void weather() {
        weatherAt = System.currentTimeMillis();
        io.execute(() -> {
            Weather w = Weather.fetch(dir);
            ui.post(() -> {
                if (w == null) {
                    weatherAt = System.currentTimeMillis() - Weather.EVERY + 5 * 60_000L; // retry in 5 min
                    return;
                }
                temp.setText(w.temperature + "\u00B0");
                wxIcon.setImageDrawable(getDrawable(w.icon));
                wxIcon.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---- actions --------------------------------------------------------------------------

    private void openWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            openSettings();
        }
    }

    /** Fire OS answers the generic settings action with a stub; the TV settings entry opens the real one. */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        launch(tvSettings.resolveActivity(getPackageManager()) != null ? tvSettings : new Intent(Settings.ACTION_SETTINGS));
    }

    private void pickInput() {
        Config cfg = Config.get(this);
        TvInputManager tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<Item> inputs = Catalog.visible(cfg, Catalog.INPUTS, Catalog.inputs(this, tv));
        if (inputs.isEmpty()) {
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = new CharSequence[inputs.size()];
        for (int i = 0; i < names.length; i++) names[i] = Catalog.label(cfg, inputs.get(i));
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Inputs")
                .setItems(names, (dlg, which) -> {
                    Item it = inputs.get(which);
                    Intent own = Input.intent(this, cfg, it, names[which]);
                    if (own != null) startActivity(own);
                    else launch(new Intent(it.intent));
                })
                .show();
    }

    private void launch(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    /** The only focus state anywhere: a small, smooth grow. */
    private static void grow(View v, boolean has, float scale, float alpha) {
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).alpha(alpha)
                .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
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
        return v;
    }

    private static List<String> read(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        } catch (java.io.IOException ignored) {
            // no dock file yet: an empty dock, More apps still works
        }
        return out;
    }

    private FrameLayout.LayoutParams place(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}

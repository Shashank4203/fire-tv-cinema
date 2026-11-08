package tv.dumbtv.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Fire OS keeps HOME bound to Amazon's protected launcher and handles the HOME key before any
 * accessibility filter sees it, so this service waits for Amazon's home window and replaces it.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second
 * app-switch delay. An alarm-fired PendingIntent is sent by system_server, which is exempt,
 * so {@link #home} launches through an alarm. That alarm only fires at once when the global
 * {@code alarm_manager_constants} has {@code min_futurity=0}; install.sh sets it.
 *
 * Amazon's home is covered with a plain overlay until ours has drawn, so it never shows.
 *
 * Android 11 refuses an activity start from an app with no visible window, even one sent
 * through system_server by an alarm ("Background activity start ... callingUidHasAnyVisibleWindow:
 * false"). The cover is such a window, so the launch waits until the cover has reached the
 * window manager: {@link #launchWhenCovered} fires it from the cover's first draw pass.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    /** Where Amazon shows an Alexa voice search's results; the query is in its search_text view. */
    private static final String AMAZON_SEARCH = "com.amazon.tv.launcher.deeplink.activity.DirectiveSearchResultsActivityDI";
    private static final String SEARCH_TEXT = "com.amazon.tv.launcher:id/search_text";
    /** Back from our results shows Amazon's for the same query: it isn't replaced again for this long. */
    private static final long SAME_QUERY_MS = 3 * 60_000L;
    /** Hotstar's player names the series here; the episode is in its media session. */
    private static final String HOTSTAR_TITLE = "in.startv.hotstar:id/tv_title";
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;
    private static final long LAUNCH_FALLBACK_MS = 150;
    /** How often the safety net looks at what's on screen. */
    private static final long POLL_MS = 1500;

    private static volatile boolean paused;
    private static Guard running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = this::go;
    /** Set when the cover is up for a search rather than for HOME: what the next launch opens. */
    private String nextQuery, lastQuery;
    private long lastQueryAt;
    private final Runnable poll = this::poll;
    /**
     * The safety net runs on its own thread: asking what's on screen waits on the app in front,
     * and a busy app (YouTube starting up) once held the guard's main thread for two seconds.
     */
    private HandlerThread pollThread;
    private Handler pollHandler;
    /** The last Amazon launcher screen seen: only its home is replaced, not search or settings. */
    private volatile String lastAmazonClass = AMAZON_HOME;
    private View cover;
    private long lastLaunch;
    /** Over a YouTube video while it is moved to where it was left; the sound is off meanwhile. */
    private View resumeCard;
    private boolean muted;
    private final Runnable endResuming = this::endResuming;
    /** The card and the mute never last longer than this, whatever happens. */
    private static final long RESUMING_MAX_MS = 8000;
    /** After the jump: YouTube fills its buffer at the new place before the card lifts. */
    private static final long RESUMED_SETTLE_MS = 700;

    /** True while the service really runs in this process (Fire OS can list it as bound when not). */
    static boolean connected() {
        return running != null;
    }

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
        Keeper.pause();
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        Log.i("guard", "connected"); // deploy.sh waits for this line
        View view = new View(this);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
        pollThread = new HandlerThread("guard-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.postDelayed(poll, POLL_MS);
    }

    /**
     * Safety net: Fire OS occasionally doesn't report Amazon's home coming back (seen after HOME
     * from Netflix), which left it on screen. So every 1.5 s the guard also checks what is
     * actually in front, and replaces Amazon's home if that's what it finds.
     */
    private void poll() {
        pollHandler.postDelayed(poll, POLL_MS);
        if (paused || !AMAZON_HOME.equals(lastAmazonClass)) return;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return;
        }
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (pkg == null || !AMAZON.contentEquals(pkg)) return;
        handler.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (paused || now - lastLaunch < REPEAT_MS * 3) return;
            lastLaunch = now;
            Log.i("guard", "Amazon home found by the safety net; bringing ours back");
            launchWhenCovered();
        });
    }

    @Override
    public void onDestroy() {
        endResuming();
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launch);
        if (pollThread != null) pollThread.quitSafely();
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        if (cls != null && cls.toString().startsWith("com.amazon.")) lastAmazonClass = cls.toString();
        if (!paused && cls != null && AMAZON_SEARCH.contentEquals(cls) && pollHandler != null) {
            pollHandler.post(this::readQuery);
            return;
        }
        if (paused || cls == null || !AMAZON_HOME.contentEquals(cls)) return;
        Log.i("guard", "Amazon home reported; bringing ours back");
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        launchWhenCovered();
    }

    /**
     * Shows the cover, then starts the launch once that window is visible to the window manager.
     * A cover that is already on screen (a repeat within {@link #COVER_MAX_MS}) gets no new draw
     * pass, so the launch goes out at once; otherwise it waits for the first pre-draw, which runs
     * after the window's relayout. A short fallback covers a window that never draws.
     */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            go();
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            go();
            return;
        }
        final ViewTreeObserver observer = cover.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                cover.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                go();
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    /** Opens what the cover is up for: our results for a voice search, otherwise our home. */
    private void go() {
        String q = nextQuery;
        nextQuery = null;
        if (q == null) {
            home(this);
            return;
        }
        Intent i = new Intent(this, Search.class).putExtra(Search.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent p = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), p);
    }

    /**
     * On the poll thread: Amazon's results screen fills in a moment after it appears, so its
     * search_text is read for up to about two seconds; then our results open with that query.
     */
    private void readQuery() {
        for (int tries = 0; tries < 16; tries++) {
            AccessibilityNodeInfo root;
            try {
                root = getRootInActiveWindow();
            } catch (RuntimeException e) {
                root = null;
            }
            if (root != null) {
                String q = null;
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(SEARCH_TEXT)) {
                    if (q == null && n.getText() != null && n.getText().toString().trim().length() > 0) {
                        q = n.getText().toString().trim();
                    }
                    n.recycle();
                }
                root.recycle();
                if (q != null) {
                    String query = q;
                    handler.post(() -> searchFor(query));
                    return;
                }
            }
            SystemClock.sleep(125);
        }
        Log.i("guard", "Alexa results without a readable query: left as they are");
    }

    private void searchFor(String q) {
        long now = SystemClock.uptimeMillis();
        if (paused || q.equalsIgnoreCase(lastQuery) && now - lastQueryAt < SAME_QUERY_MS) return; // came Back to Amazon's
        lastQuery = q;
        lastQueryAt = now;
        nextQuery = q;
        Log.i("guard", "Alexa searched \"" + q + "\": showing our results");
        launchWhenCovered();
    }

    /**
     * The title on the player screen of {@code app}, for Continue watching: Prime Video describes
     * its player as "playing <title>" and Hotstar shows the series name; Netflix and Apple TV
     * draw their own screens, with nothing to read. Null when the guard isn't running, another
     * app is in front or nothing is readable. Waits on the app: never on the main thread.
     */
    static String screenTitle(String app) {
        Guard g = running;
        if (g == null) return null;
        AccessibilityNodeInfo root;
        try {
            root = g.getRootInActiveWindow();
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null) return null;
        try {
            if (root.getPackageName() == null || !app.contentEquals(root.getPackageName())) return null;
            if (HOTSTAR_TITLE.startsWith(app + ":")) {
                String t = null;
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(HOTSTAR_TITLE)) {
                    if (t == null && n.getText() != null && n.getText().toString().trim().length() > 0) {
                        t = n.getText().toString().trim();
                    }
                    n.recycle();
                }
                return t;
            }
            return playing(root, 0);
        } catch (RuntimeException e) {
            return null; // the window went away while it was read
        } finally {
            root.recycle();
        }
    }

    /** The first "playing <title>" description under {@code n}. */
    private static String playing(AccessibilityNodeInfo n, int depth) {
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 8 && d.toString().regionMatches(true, 0, "playing ", 0, 8)) {
            return d.toString().substring(8).trim();
        }
        if (depth > 25) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            String t = playing(c, depth + 1);
            c.recycle();
            if (t != null) return t;
        }
        return null;
    }

    /**
     * A video is being opened and moved to where it was left (see {@link Watching#resumeAt}): a
     * card goes over everything (its picture, name, and where it picks up), and the sound goes
     * off, so the jump from its start isn't seen or heard. Sound that was off already stays off.
     */
    static void resuming(File picture, String app, String title, String detail, long atMs, long durationMs) {
        Guard g = running;
        if (g != null) g.handler.post(() -> g.startResuming(picture, app, title, detail, atMs, durationMs));
    }

    /** It's at the right place: the card lifts and the sound comes back, once YouTube has caught up. */
    static void resumed() {
        Guard g = running;
        if (g == null) return;
        g.handler.post(() -> {
            g.handler.removeCallbacks(g.endResuming);
            g.handler.postDelayed(g.endResuming, RESUMED_SETTLE_MS);
        });
    }

    /**
     * Looks like the Continue watching page it was opened from: the video's picture behind,
     * slowly pushing in, darkened at the left and bottom for the words, and a progress bar with a
     * pulsing dot where it picks up.
     */
    private void startResuming(File picture, String app, String title, String detail, long atMs, long durationMs) {
        endResuming();
        File cinema = new File(getExternalFilesDir(null), "cinema");
        File fonts = new File(cinema, "fonts");
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundColor(Color.BLACK);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (picture != null && picture.isFile()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            back.setImageBitmap(BitmapFactory.decodeFile(picture.getPath(), o));
        }
        card.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View side = new View(this);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x8C000000, 0x14000000}));
        card.addView(side, new FrameLayout.LayoutParams(-1, -1));
        View foot = new View(this);
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x26000000, 0xE6000000}));
        card.addView(foot, new FrameLayout.LayoutParams(-1, -1));

        Typeface light = font(fonts, "Outfit-Light.ttf"), medium = font(fonts, "Outfit-Medium.ttf");
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAlpha(0.85f);
        logo.setImageBitmap(BitmapFactory.decodeFile(new File(cinema, "logos/" + app + ".png").getPath()));
        words.addView(logo, new LinearLayout.LayoutParams(dp(110), dp(22)));
        words.addView(label(medium, 11, 0x99FFFFFF, "RESUMING", 0.3f), below(22));
        TextView name = label(light, 34, 0xF2FFFFFF, title, 0f);
        name.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 2 / 3);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(8), 0, 0, 0x99000000);
        words.addView(name, below(10));
        if (detail != null && !detail.isEmpty()) words.addView(label(light, 16, 0xB3FFFFFF, detail, 0f), below(10));
        String at = clock(atMs);
        if (durationMs > 0) {
            float done = Math.max(0.01f, Math.min(1f, atMs / (float) durationMs));
            int barW = dp(420);
            FrameLayout bar = new FrameLayout(this);
            bar.setClipChildren(false);
            View track = new View(this);
            track.setBackground(Ui.round(0x40FFFFFF, dp(2)));
            bar.addView(track, new FrameLayout.LayoutParams(barW, dp(3), Gravity.CENTER_VERTICAL));
            View fill = new View(this);
            fill.setBackground(Ui.round(0xF2FFFFFF, dp(2)));
            bar.addView(fill, new FrameLayout.LayoutParams(Math.round(barW * done), dp(3), Gravity.CENTER_VERTICAL));
            View dot = new View(this);
            dot.setBackground(Ui.round(Color.WHITE, dp(6)));
            dot.setTranslationX(barW * done - dp(6));
            bar.addView(dot, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER_VERTICAL));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(barW, dp(12));
            blp.topMargin = dp(26);
            words.addView(bar, blp);
            pulse = ValueAnimator.ofFloat(1f, 0.35f);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(an -> {
                float v = (Float) an.getAnimatedValue();
                dot.setAlpha(v);
                dot.setScaleX(0.8f + 0.4f * v);
                dot.setScaleY(0.8f + 0.4f * v);
            });
            pulse.start();
            at += "  /  " + clock(durationMs);
        } else {
            at = "FROM " + at;
        }
        words.addView(label(medium, 12, 0xB3FFFFFF, at, 0.12f), below(12));
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        wlp.setMargins(dp(56), 0, 0, dp(64));
        card.addView(words, wlp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(card, lp);
        resumeCard = card;
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(220).start();
        back.setScaleX(1.02f);
        back.setScaleY(1.02f);
        back.animate().scaleX(1.1f).scaleY(1.1f).setDuration(RESUMING_MAX_MS).setInterpolator(new DecelerateInterpolator()).start();
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            muted = true;
        }
        handler.postDelayed(endResuming, RESUMING_MAX_MS);
    }

    private ValueAnimator pulse;

    private LinearLayout.LayoutParams below(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void endResuming() {
        handler.removeCallbacks(endResuming);
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (muted) {
            muted = false;
            ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }
        View v = resumeCard;
        resumeCard = null;
        if (v != null) v.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            if (v.isAttachedToWindow()) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v);
        }).start();
    }

    private TextView label(Typeface face, float sp, int color, String text, float spacing) {
        TextView v = new TextView(this);
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setLetterSpacing(spacing);
        v.setIncludeFontPadding(false);
        v.setText(text);
        return v;
    }

    private static Typeface font(File dir, String name) {
        File f = new File(dir, name);
        try {
            return f.isFile() ? Typeface.createFromFile(f) : Ui.LIGHT;
        } catch (RuntimeException e) {
            return Ui.LIGHT;
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** The overlay window exists from service start; a GONE root keeps its surface hidden. */
    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(Config.get(this).background());
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override
    public void onInterrupt() {}
}

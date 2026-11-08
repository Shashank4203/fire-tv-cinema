package tv.dumbtv.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * Keeps the HOME guard switched on. Fire OS sometimes rewrites the accessibility settings and
 * drops it (seen once right after its screen reader's speech engine started: turning VoiceView
 * off clears every accessibility service), after which HOME showed Amazon's home for good.
 *
 * A content provider is created whenever this app's process starts, for any reason (the light
 * strip's listener, the home screen, a boot), so it is the place to start watching. When the
 * settings change and the guard is missing, it is added back next to whatever else is on.
 * Writing these settings needs WRITE_SECURE_SETTINGS, granted once over adb
 * (pm grant tv.dumbtv.home android.permission.WRITE_SECURE_SETTINGS; deploy.sh does it).
 */
public final class Watchdog extends ContentProvider {
    private static final String TAG = "guard";
    private static final long SETTLE_MS = 3000;
    /** How often the guard is checked to be really running, not just switched on. */
    private static final long ALIVE_EVERY_MS = 30_000;
    /** Restarting it takes a moment: not again sooner than this. */
    private static final long RESTART_GAP_MS = 2 * 60_000L;
    private static boolean watching;
    private static long restartedAt;

    @Override
    public boolean onCreate() {
        watch(getContext());
        return true;
    }

    /** Starts watching (once per process) and checks straight away. */
    static void watch(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Handler main = new Handler(Looper.getMainLooper());
        if (!watching) {
            watching = true;
            ContentObserver observer = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    // Let whatever is changing the settings finish first.
                    main.removeCallbacksAndMessages(null);
                    main.postDelayed(() -> check(app), SETTLE_MS);
                }
            };
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
            Handler beat = new Handler(Looper.getMainLooper()); // its own: the observer clears main's
            beat.postDelayed(new Runnable() {
                @Override
                public void run() {
                    alive(app);
                    beat.postDelayed(this, ALIVE_EVERY_MS);
                }
            }, ALIVE_EVERY_MS);
        }
        main.postDelayed(() -> check(app), SETTLE_MS);
    }

    /** Adds the guard back if it was dropped, keeping any other service that is on. */
    static void check(Context c) {
        String guard = c.getPackageName() + "/" + Guard.class.getName();
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        boolean listed = false;
        if (enabled != null) {
            for (String s : enabled.split(":")) {
                if (s.equals(guard) || s.equals(c.getPackageName() + "/.Guard")) listed = true;
            }
        }
        if (listed && on) return;
        try {
            if (!listed) {
                String value = TextUtils.isEmpty(enabled) || "null".equals(enabled) ? guard : enabled + ":" + guard;
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "the HOME guard had been switched off; switched it back on");
        } catch (SecurityException e) {
            Log.w(TAG, "the HOME guard is off and can't be switched back on: grant WRITE_SECURE_SETTINGS over adb");
        }
    }

    /**
     * Switched on but not running: after an update, Fire OS once listed the guard as bound while
     * nothing of it ran, and HOME stayed on Amazon's home. Switching it off and on again makes
     * Fire OS start it afresh.
     */
    static void alive(Context c) {
        if (Guard.connected()) return;
        String guard = c.getPackageName() + "/" + Guard.class.getName();
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        if (enabled == null || !on) return; // switched off: check() puts it back
        StringBuilder others = new StringBuilder();
        boolean listed = false;
        for (String s : enabled.split(":")) {
            if (s.equals(guard) || s.equals(c.getPackageName() + "/.Guard")) {
                listed = true;
            } else if (!s.isEmpty()) {
                others.append(others.length() == 0 ? "" : ":").append(s);
            }
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!listed || now - restartedAt < RESTART_GAP_MS) return;
        restartedAt = now;
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }, 1000);
            Log.i(TAG, "the HOME guard was switched on but not running; restarting it");
        } catch (SecurityException e) {
            Log.w(TAG, "the HOME guard isn't running and can't be restarted: grant WRITE_SECURE_SETTINGS over adb");
        }
    }

    // Not a real provider: it only exists to start with the process.

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}

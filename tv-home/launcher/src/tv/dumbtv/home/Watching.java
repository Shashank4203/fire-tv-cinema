package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continue watching: what played on the TV lately, newest first, one card per film or series, in
 * cinema/continue/continue.txt with each title's backdrop and logo beside it. All of it on the TV
 * (TMDB with cinema/tmdb.key), nothing from the Mac.
 *
 * Apps say different amounts about what they play (tested November 2026):
 * <ul>
 *   <li>YouTube: the video's title and channel, in its media session; its ID and thumbnail are
 *       looked up with the YouTube Data API (cinema/youtube.key), so OK opens that very video.
 *   <li>Hotstar: the episode in its session ("S1 E1 No Strings"); the series name on its player
 *       screen while its controls are up (as it starts, and when paused), read through the HOME
 *       guard (an accessibility service), else by the camera; when the next episode plays on by
 *       itself, the series last watched there if it has an episode of that name.
 *   <li>Prime Video: only "PrimeVideo" in its session, but its player says "playing <title>".
 *       For a series, the episode comes from the phone's camera (below), once and in each pause.
 *   <li>Netflix, Apple TV: nothing (no metadata, self-drawn screens). The phone filming the TV
 *       for the light strip reads it off the screen instead: asked with "snap" a few seconds into
 *       playing and when it's paused (their players show the title then), it sends back what
 *       Gemini read ("lightcam title ..."). Until then, a title opened from the home screen or
 *       the Alexa results stands for it.
 * </ul>
 * Every app reports how far in it is: with the length (the app's own, else TMDB's runtime) that
 * is the card's progress bar. A title counts after half a minute of playing, so trailers and
 * previews don't; a film watched to the credits leaves the row.
 *
 * Runs in the light strip's process: its notification listener is what may see media sessions.
 */
final class Watching {
    private static final String TAG = "watching";
    static final String HOTSTAR = "in.startv.hotstar", YOUTUBE = "com.amazon.firetv.youtube";
    static final int MAX = 12;
    private static final long POLL_MS = 3000;
    private static final long COUNTS_AFTER_MS = 30_000;
    private static final long SAVE_EVERY_MS = 15_000;
    /** The player screen is read this long into a title: it names the title as playing starts. */
    private static final long SCREEN_MS = 2 * 60_000L;
    /** How long a title opened from the home screen stands for what its app plays. */
    private static final long OPENED_MS = 4 * 3600_000L;
    /** A film this far through has been watched. */
    private static final float DONE = 0.93f;
    private static final Set<String> VIDEO_APPS = new HashSet<>(Arrays.asList(Tmdb.NETFLIX, Tmdb.PRIME, Tmdb.APPLE,
            HOTSTAR, YOUTUBE, "com.zee5.amazon", "com.onemainstream.sonyliv.android", "com.discoveryplus.tv.fire"));
    /** What apps put in their sessions instead of a title. */
    private static final Set<String> APP_NAMES = new HashSet<>(Arrays.asList("primevideo", "amazonprimevideo",
            "netflix", "hotstar", "jiohotstar", "disneyhotstar", "youtube", "appletv", "zee5", "sonyliv", "discoveryplus"));
    /** A season or episode after a series name ("The Boys: Season 4", "Dark S1 E3"). */
    private static final String WATCH = "https://www.youtube.com/watch?v=";
    private static final Pattern SEASON = Pattern.compile(
            "(?i)\\s*[-:,|·]?\\s*(season\\s*\\d+|s\\d+\\s*[:.]?\\s*e\\d+|episode\\s*\\d+|ep\\.?\\s*\\d+).*$");

    static final class Entry {
        String key, title, kind, year = "", app, link = "-", detail = "", genres = "";
        double rating;
        long position, duration, updated;

        /** Films and videos are done once watched; a series stays for its next episode. */
        boolean film() {
            return !"tv".equals(kind);
        }
    }

    /** One stretch of playing, until it pauses, stops or changes title. */
    private static final class Play {
        final String app, id;
        final List<String> texts;
        final long since;
        String screen, triedWith;
        /** What the phone's camera read on screen, for apps that name nothing; and the episode. */
        String camera, cameraDetail = "";
        boolean tried;
        Entry entry;
        long savedAt, stoppedAt;
        /** Asking the camera: how often while playing and during this pause, and when next. */
        int playingAsks, pauseAsks;
        long nextAsk, pausedAt;

        Play(String app, String id, List<String> texts, long since) {
            this.app = app;
            this.id = id;
            this.texts = texts;
            this.since = since;
        }
    }

    private static HandlerThread thread;
    private static Handler handler;
    private static Watching instance;

    /** Where the phone takes commands (see lightcam's Main). */
    private static final int PHONE_COMMANDS = 5579;
    /**
     * The camera is asked a few seconds into playing, twice, and twice in each pause; once per
     * pause when the title is known already, as the episode may have moved on.
     */
    private static final long ASK_FIRST_MS = 3000, ASK_EVERY_MS = 6000, PAUSE_ASK_MS = 1500;
    private static final int PLAYING_ASKS = 2, PAUSE_ASKS = 2;
    /** Played again within this of a pause, it's taken to be what the camera read before it. */
    private static final long SAME_AFTER_PAUSE_MS = 10 * 60_000L;

    /** A YouTube video opened from its card, to be moved to where it was left once it plays. */
    private static final class Seek {
        final String app, title;
        final long to, until;
        /** Jumped already, before the app named the video: checked once it does. */
        boolean sent;

        Seek(String app, String title, long to, long until) {
            this.app = app;
            this.title = title;
            this.to = to;
            this.until = until;
        }
    }

    private static volatile Seek seek;
    /** How long to wait for the video to play; meanwhile sessions are looked at four times a second. */
    private static final long SEEK_WAIT_MS = 8000, SEEK_POLL_MS = 250;

    // Everything below belongs to the watching thread.
    private final Context c;
    private final File dir;
    private final Palette palette;
    /** Episodes already checked on TMDB: series key + episode as read -> as it really is. */
    private final java.util.Map<String, String> checked = new java.util.HashMap<>();
    private final Runnable pollTask = this::poll;
    private final List<Entry> entries;
    private Play cur, last;
    /** The play the camera was last asked about: its answer belongs to it. */
    private Play asked;
    private long prunedAt;

    private Watching(Context c) {
        this.c = c;
        dir = new File(c.getExternalFilesDir(null), "cinema");
        palette = new Palette(c);
        entries = read(dir);
    }

    /** From the light strip's listener: starts watching (once per process). */
    static synchronized void start(Context c) {
        if (thread == null) {
            thread = new HandlerThread(TAG);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        if (instance == null) instance = new Watching(c.getApplicationContext());
        Watching w = instance;
        handler.removeCallbacks(w.pollTask);
        handler.post(w.pollTask);
        handler.post(w::backfill);
    }

    /**
     * Cards from before something was learnt: videos from before the YouTube key get their ID and
     * thumbnail, and episodes the camera read get their numbers checked on TMDB.
     */
    private void backfill() {
        boolean changed = false;
        for (Entry e : entries) {
            if ("video".equals(e.kind) && !e.link.startsWith(WATCH)) {
                youtube(e);
                changed |= e.link.startsWith(WATCH);
            } else if ("tv".equals(e.kind) && !e.detail.isEmpty() && (Tmdb.NETFLIX.equals(e.app) || Tmdb.APPLE.equals(e.app))) {
                String fixed = checkEpisode(e.key, episode(e.detail));
                changed |= !fixed.equals(e.detail);
                e.detail = fixed;
            }
        }
        if (changed) write();
    }

    /** The listener was unbound: media sessions can't be seen until it is back. */
    static synchronized void stop() {
        if (instance == null) return;
        Watching w = instance;
        handler.removeCallbacks(w.pollTask);
        handler.post(w::finish);
    }

    /** The home screen or the Alexa results opened this title, which is all Netflix and Apple TV tell. */
    static void opened(Context c, String app, String title, String link) {
        c.getSharedPreferences(TAG, Context.MODE_PRIVATE).edit()
                .putString("app", app)
                .putLong("at", System.currentTimeMillis())
                .putString("title", title == null ? "" : title)
                .putString("link", link == null ? "" : link)
                .apply();
    }

    /** {title, link} opened in this app lately, or null. */
    private String[] openedIn(String app) {
        SharedPreferences p = c.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        boolean recent = System.currentTimeMillis() - p.getLong("at", 0) < OPENED_MS;
        String title = p.getString("title", "");
        return recent && app.equals(p.getString("app", "")) && !title.isEmpty()
                ? new String[] {title, p.getString("link", "")} : null;
    }

    // ---- watching ---------------------------------------------------------------------------

    private void poll() {
        handler.postDelayed(pollTask, seek != null ? SEEK_POLL_MS : POLL_MS);
        long now = SystemClock.elapsedRealtime();
        if (prunedAt == 0 || now - prunedAt > 60_000) {
            prunedAt = now;
            prune();
        }
        Seek s = seek;
        if (s != null && now > s.until) {
            seek = null;
            Guard.resumed();
            Log.i(TAG, "gave up moving " + s.title + " to where it was left");
        }
        MediaController mc = playing();
        if (mc == null) {
            finish();
            pausedAsk(now);
            return;
        }
        MediaMetadata md = mc.getMetadata();
        List<String> texts = Lights.texts(md);
        seek(mc, texts);
        String id = mc.getPackageName() + " " + texts;
        if (cur != null && !cur.id.equals(id)) finish();
        // Playing again after a pause: the same title, when the session names one. Prime Video
        // ("PrimeVideo") and Netflix (nothing) say the same for every title, so they're worked out again.
        if (cur == null) {
            boolean again = last != null && last.id.equals(id)
                    && (named(texts) || silent(last) && last.camera != null && now - last.stoppedAt < SAME_AFTER_PAUSE_MS);
            cur = again ? last : new Play(mc.getPackageName(), id, texts, now);
            cur.pausedAt = 0;
            Log.i(TAG, id + " playing" + (again && last.camera != null && !named(texts) ? " (" + last.camera + ", read before the pause)" : ""));
        }
        Play p = cur;
        if (silent(p) && p.camera == null && p.playingAsks < PLAYING_ASKS && now - p.since >= ASK_FIRST_MS && now >= p.nextAsk) {
            p.playingAsks++;
            p.nextAsk = now + ASK_EVERY_MS;
            ask(p);
        }
        if (p.screen == null && now - p.since < SCREEN_MS && (Tmdb.PRIME.equals(p.app) || HOTSTAR.equals(p.app))) {
            p.screen = Guard.screenTitle(p.app);
            if (p.screen != null) Log.i(TAG, p.app + " shows \"" + p.screen + "\"");
        }
        if (p.entry == null) {
            // Once, after half a minute; again if the screen names it later.
            if (now - p.since < COUNTS_AFTER_MS || p.tried && Objects.equals(hint(p), p.triedWith)) return;
            p.tried = true;
            p.triedWith = hint(p);
            p.entry = resolve(p, md);
            if (p.entry == null) return;
            Log.i(TAG, "continue watching: " + p.entry.title + (p.entry.detail.isEmpty() ? "" : " (" + p.entry.detail + ")"));
            p.savedAt = 0;
        }
        Entry e = p.entry;
        e.position = position(mc.getPlaybackState());
        long d = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (d > 0) e.duration = d;
        e.updated = System.currentTimeMillis();
        if (now - p.savedAt >= SAVE_EVERY_MS) {
            p.savedAt = now;
            remember(e);
        }
        // A Prime Video series: its player names the show, the camera reads the episode.
        if (primeSeries(p) && p.camera == null && p.playingAsks < 1 && now >= p.nextAsk) {
            p.playingAsks++;
            p.nextAsk = now + ASK_EVERY_MS;
            ask(p);
        }
    }

    /** Playing stopped: its place is kept, and a film watched to the credits leaves the row. */
    private void finish() {
        Play p = cur;
        cur = null;
        if (p == null) return;
        last = p;
        p.stoppedAt = SystemClock.elapsedRealtime();
        Entry e = p.entry;
        if (e == null) {
            Log.i(TAG, p.id + " stopped after " + (SystemClock.elapsedRealtime() - p.since) / 1000 + " s, not on the row");
            return;
        }
        if (e.film() && e.duration > 0 && e.position >= e.duration * DONE) {
            Log.i(TAG, e.title + " watched: off the row");
            forget(e.key);
        } else {
            remember(e);
        }
    }

    /** The video app that is playing, or null (music and Alexa don't count). */
    private MediaController playing() {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, Lights.class))) {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING && VIDEO_APPS.contains(mc.getPackageName())) {
                    return mc;
                }
            }
        } catch (SecurityException e) {
            // the listener is off: nothing can be seen
        }
        return null;
    }

    private static long position(PlaybackState s) {
        if (s == null) return 0;
        long p = s.getPosition();
        long at = s.getLastPositionUpdateTime();
        if (s.getState() == PlaybackState.STATE_PLAYING && at > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - at) * s.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    // ---- what it is -------------------------------------------------------------------------

    /** Works out the title from the screen, the session or what was opened; null if it can't. */
    private Entry resolve(Play p, MediaMetadata md) {
        String detail = sessionEpisode(p.texts);
        if (detail.isEmpty() && p.camera != null) detail = p.cameraDetail;
        List<String> names = new ArrayList<>();
        if (p.camera != null) names.add(p.camera);
        if (p.screen != null) names.add(p.screen);
        for (String t : p.texts) if (!APP_NAMES.contains(squash(t))) names.add(t);
        String[] opened = openedIn(p.app);
        if (names.isEmpty() && opened != null) names.add(opened[0]);
        if (names.isEmpty()) {
            Log.i(TAG, p.app + " is playing something it doesn't name");
            return null;
        }
        if (YOUTUBE.equals(p.app)) return video(p, md, names.get(0));
        Entry e = lookup(p.app, names, detail, opened);
        if (e == null && HOTSTAR.equals(p.app)) e = sameSeries(p.app, detail, opened);
        return e;
    }

    /** The episode an app names in its session ("S1 E1 No Strings" -> "S1 E1 · No Strings"), or "". */
    private static String sessionEpisode(List<String> texts) {
        for (String t : texts) {
            Matcher m = Palette.EPISODE.matcher(t);
            if (!m.matches()) continue;
            String name = m.group(3).trim();
            return "S" + Integer.parseInt(m.group(1)) + " E" + Integer.parseInt(m.group(2))
                    + (name.isEmpty() ? "" : " \u00B7 " + name);
        }
        return "";
    }

    /**
     * Hotstar names only the episode, and shows the series only while its controls are up: when
     * the next episode plays on by itself, nothing names the series. It's taken to be the series
     * last watched on Hotstar if, on TMDB, that series has an episode of this name.
     */
    private Entry sameSeries(String app, String detail, String[] opened) {
        Matcher m = CARD_EPISODE.matcher(detail);
        if (!m.matches()) return null;
        for (Entry old : entries) { // newest first: only the latest series of this app is tried
            if (!app.equals(old.app) || !"tv".equals(old.kind)) continue;
            try {
                String found = findEpisode(old.key.substring(3), m.group(3), Integer.parseInt(m.group(1)));
                if (found == null) return null;
                Entry e = details(old.key, app);
                if (e == null) return null;
                e.detail = found;
                e.link = link(e, opened);
                Log.i(TAG, "\"" + m.group(3) + "\" is an episode of " + e.title + ", the series last watched there");
                return e;
            } catch (IOException | JSONException | NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** The first of {@code names} that is a TMDB title, with its details and link; or null. */
    private Entry lookup(String app, List<String> names, String detail, String[] opened) {
        for (String n : names) {
            for (String q : variants(n)) {
                String[] hit = palette.find(q);
                if (hit == null) continue;
                Entry e = details(hit[0], app);
                if (e == null) return null;
                e.detail = detail;
                e.link = link(e, opened);
                return e;
            }
        }
        Log.i(TAG, "no title found for " + names);
        return null;
    }

    private static String hint(Play p) {
        return p.screen + "\n" + p.camera;
    }

    // ---- the phone's camera -----------------------------------------------------------------

    /** Apps that name nothing of what they play (Netflix, Apple TV): the camera reads it instead. */
    private static boolean silent(Play p) {
        return !named(p.texts) && !Tmdb.PRIME.equals(p.app) && !HOTSTAR.equals(p.app) && !YOUTUBE.equals(p.app);
    }

    /**
     * Prime Video's series: the show is known from its player, but only the camera can read which
     * episode is on.
     */
    private static boolean primeSeries(Play p) {
        return Tmdb.PRIME.equals(p.app) && p.entry != null && "tv".equals(p.entry.kind);
    }

    /**
     * Paused in an app that names nothing, or in a Prime Video series: the player shows the title
     * (and episode) now, so the camera looks.
     */
    private void pausedAsk(long now) {
        Play p = last;
        if (p == null) return;
        boolean hotstar = HOTSTAR.equals(p.app) && p.entry == null; // the series isn't known yet
        if (!silent(p) && !primeSeries(p) && !hotstar) return;
        MediaController m = session(p.app);
        PlaybackState st = m == null ? null : m.getPlaybackState();
        if (st == null || st.getState() != PlaybackState.STATE_PAUSED) {
            p.pausedAt = 0;
            return;
        }
        if (p.pausedAt == 0) {
            p.pausedAt = now;
            p.pauseAsks = 0;
            p.nextAsk = now + PAUSE_ASK_MS;
        }
        if (hotstar && p.screen == null) {
            // Paused, Hotstar's controls are up: the series is on screen, readable without a photo.
            p.screen = Guard.screenTitle(p.app);
            if (p.screen != null) {
                Log.i(TAG, p.app + " shows \"" + p.screen + "\" (paused)");
                Entry e = resolve(p, null);
                if (e != null) {
                    e.position = position(st);
                    e.updated = System.currentTimeMillis();
                    p.entry = e;
                    p.savedAt = now;
                    remember(e);
                    Log.i(TAG, "continue watching: " + e.title + (e.detail.isEmpty() ? "" : " (" + e.detail + ")"));
                    return;
                }
            }
        }
        if (p.pauseAsks < (p.camera == null && (silent(p) || hotstar) ? PAUSE_ASKS : 1) && now >= p.nextAsk) {
            p.pauseAsks++;
            p.nextAsk = now + ASK_EVERY_MS;
            ask(p);
        }
    }

    /** Asks the phone for a photo of the screen; it answers through the light strip's listener. */
    private void ask(Play p) {
        InetAddress phone = Lights.phone;
        if (phone == null) return; // the camera isn't running
        asked = p;
        byte[] b = "snap".getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.send(new DatagramPacket(b, b.length, phone, PHONE_COMMANDS));
            Log.i(TAG, "asked the camera what " + p.app + " shows");
        } catch (IOException e) {
            Log.w(TAG, "can't reach the camera: " + e);
        }
    }

    /** From the light strip's listener: what the camera read on screen, or NONE. */
    static void cameraSaw(String text) {
        Watching w = instance;
        if (w != null) handler.post(() -> w.seen(text.trim()));
    }

    /**
     * The camera read a title ("name | S1 E3 | episode"). It becomes the title of the play it was
     * asked about, replacing a guess from what was opened on the home screen if that was wrong.
     */
    private void seen(String text) {
        Play p = asked;
        if (p == null) return;
        if (text.isEmpty() || "NONE".equalsIgnoreCase(text)) {
            Log.i(TAG, "the camera saw no title on " + p.app);
            return;
        }
        Log.i(TAG, "the camera read \"" + text + "\" on " + p.app);
        String[] parts = text.split("\\s*\\|\\s*");
        String name = unquote(parts[0]), detail = "";
        if (parts.length > 1) detail = episode(unquote(parts[1]) + (parts.length > 2 && !unquote(parts[2]).isEmpty() ? " \u00B7 " + unquote(parts[2]) : ""));
        if (name.isEmpty()) return;
        // The same show as the card: only the episode is news. Prime Video's own name for the show
        // is the one kept; the camera only adds the episode.
        boolean same = name.equals(p.camera) || p.entry != null
                && (squash(name).equals(squash(p.entry.title)) || Tmdb.PRIME.equals(p.app));
        p.camera = name;
        p.cameraDetail = detail;
        if (same && p.entry != null) { // the same series: perhaps another episode
            if ("tv".equals(p.entry.kind)) detail = checkEpisode(p.entry.key, detail);
            if (!detail.isEmpty() && !detail.equals(p.entry.detail)) {
                Log.i(TAG, p.entry.title + " is now at " + detail);
                p.entry.detail = detail;
                remember(p.entry);
            }
            return;
        }
        if (HOTSTAR.equals(p.app) && !sessionEpisode(p.texts).isEmpty()) detail = sessionEpisode(p.texts); // exact, from the app
        Entry e = lookup(p.app, java.util.Collections.singletonList(name), detail, openedIn(p.app));
        if (e == null) return;
        if ("tv".equals(e.kind)) e.detail = checkEpisode(e.key, e.detail);
        if (p.entry != null && !p.entry.key.equals(e.key)) Log.i(TAG, "it's " + e.title + ", not " + p.entry.title);
        MediaController m = session(p.app);
        e.position = m == null ? p.entry != null ? p.entry.position : 0 : position(m.getPlaybackState());
        e.updated = System.currentTimeMillis();
        p.entry = e;
        p.savedAt = SystemClock.elapsedRealtime();
        remember(e);
        Log.i(TAG, "continue watching: " + e.title + (e.detail.isEmpty() ? "" : " (" + e.detail + ")") + ", read by the camera");
    }

    private static final Pattern READ_EPISODE =
            Pattern.compile("(?i)^S\\s*(\\d+)\\s*[,:.]?\\s*E\\s*(\\d+)\\s*[-\u2013\u2014\u00B7:|.]?\\s*(.*)$");

    private static final Pattern CARD_EPISODE = Pattern.compile("^S(\\d+) E(\\d+) \u00B7 (.+)$");

    /**
     * The season and episode numbers of an episode the camera read, from TMDB. The numbers are
     * small on screen and get misread (Slow Horses' "S6, E5 · Sayonara" came back as S3), while
     * the episode's name is larger and comes out right; so the series' seasons are searched for
     * that name, the season read first. Unchanged when the name isn't found or TMDB can't be asked.
     */
    private String checkEpisode(String key, String detail) {
        Matcher m = CARD_EPISODE.matcher(detail);
        if (!m.matches() || !key.startsWith("tv-")) return detail;
        String cacheKey = key + " " + detail;
        String known = checked.get(cacheKey);
        if (known != null) return known;
        String found;
        try {
            String f = findEpisode(key.substring(3), m.group(3), Integer.parseInt(m.group(1)));
            found = f != null ? f : detail;
        } catch (IOException | JSONException | NumberFormatException e) {
            Log.w(TAG, "can't check the episode " + detail + ": " + e.getClass().getSimpleName());
            return detail; // not remembered: tried again next time
        }
        if (!found.equals(detail)) Log.i(TAG, "read " + detail + "; TMDB has it as " + found);
        checked.put(cacheKey, found);
        return found;
    }

    /** "S<n> E<m> · <name>" for the TMDB series' episode called {@code name}, season {@code read} first; or null. */
    private String findEpisode(String id, String name, int read) throws IOException, JSONException {
        String want = squash(name);
        int seasons = new JSONObject(Tmdb.api(dir, "/tv/" + id)).optInt("number_of_seasons");
        List<Integer> order = new ArrayList<>();
        if (read >= 1 && read <= seasons) order.add(read);
        for (int n = seasons; n >= 1; n--) if (n != read) order.add(n); // newest first: what's being watched now
        for (int n : order) {
            JSONArray eps = new JSONObject(Tmdb.api(dir, "/tv/" + id + "/season/" + n)).optJSONArray("episodes");
            for (int i = 0; eps != null && i < eps.length(); i++) {
                JSONObject ep = eps.getJSONObject(i);
                if (squash(ep.optString("name")).equals(want)) {
                    return "S" + n + " E" + ep.optInt("episode_number") + " \u00B7 " + ep.optString("name");
                }
            }
        }
        return null;
    }

    /** "S3, E5 - Sayonara" (as the screen had it) -> "S3 E5 · Sayonara", as the cards show episodes. */
    private static String episode(String s) {
        Matcher m = READ_EPISODE.matcher(s.trim());
        if (!m.matches()) return s.trim();
        String name = unquote(m.group(3));
        return "S" + Integer.parseInt(m.group(1)) + " E" + Integer.parseInt(m.group(2)) + (name.isEmpty() ? "" : " \u00B7 " + name);
    }

    /** Without the quotation marks Gemini sometimes puts around an episode's name. */
    private static String unquote(String s) {
        return s.trim().replaceAll("^[\"'\u201C\u2018]+|[\"'\u201D\u2019]+$", "").trim();
    }

        /** The app's media session, playing or not. */
    private MediaController session(String app) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, Lights.class))) {
                if (app.equals(mc.getPackageName())) return mc;
            }
        } catch (SecurityException e) {
            // the listener is off
        }
        return null;
    }

    /** The name, and the name without a season or episode after it. */
    private static List<String> variants(String n) {
        List<String> out = new ArrayList<>();
        out.add(n);
        String bare = SEASON.matcher(n).replaceFirst("").trim();
        if (!bare.isEmpty() && !bare.equals(n)) out.add(bare);
        return out;
    }

    /** A TMDB title's name, year, rating, genres and length, with its backdrop and logo saved. */
    private Entry details(String key, String app) {
        String[] k = key.split("-", 2);
        if (k.length != 2) return null;
        try {
            boolean movie = "movie".equals(k[0]);
            JSONObject d = new JSONObject(Tmdb.api(dir, "/" + k[0] + "/" + Long.parseLong(k[1])
                    + "?append_to_response=images&include_image_language=en,null"));
            Entry e = new Entry();
            e.key = key;
            e.kind = k[0];
            e.app = app;
            e.title = d.optString(movie ? "title" : "name", "");
            String date = d.optString(movie ? "release_date" : "first_air_date", "");
            e.year = date.length() >= 4 ? date.substring(0, 4) : "";
            e.rating = d.optDouble("vote_average", 0);
            JSONArray g = d.optJSONArray("genres");
            List<String> names = new ArrayList<>();
            for (int i = 0; g != null && i < g.length() && names.size() < 2; i++) {
                String n = Tmdb.GENRES.get(g.getJSONObject(i).optInt("id"));
                if (n != null && !names.contains(n)) names.add(n);
            }
            e.genres = TextUtils.join(", ", names);
            e.duration = (movie ? d.optInt("runtime") : episodeMinutes(d)) * 60_000L;
            save(d.optString("backdrop_path", ""), "w1280", backdrop(dir, key));
            save(logoPath(d), "w500", logo(dir, key));
            return e;
        } catch (IOException | JSONException | NumberFormatException e) {
            Log.w(TAG, "no details for " + key + ": " + e);
            return null;
        }
    }

    private static int episodeMinutes(JSONObject d) {
        JSONArray r = d.optJSONArray("episode_run_time");
        for (int i = 0; r != null && i < r.length(); i++) if (r.optInt(i) > 0) return r.optInt(i);
        JSONObject last = d.optJSONObject("last_episode_to_air");
        return last == null ? 0 : last.optInt("runtime");
    }

    /** The title's logo (its name as artwork, transparent): English first. */
    private static String logoPath(JSONObject d) {
        JSONObject images = d.optJSONObject("images");
        JSONArray logos = images == null ? null : images.optJSONArray("logos");
        String any = "";
        for (int i = 0; logos != null && i < logos.length(); i++) {
            JSONObject l = logos.optJSONObject(i);
            String path = l == null ? "" : l.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            if ("en".equals(l.optString("iso_639_1"))) return path;
            if (any.isEmpty()) any = path;
        }
        return any;
    }

    /** Where OK takes it: what was opened, what the row had, the Suggestions link, or TMDB's. */
    private String link(Entry e, String[] opened) {
        // An Apple TV series needs the link that names its show (see playLink): older ones, and
        // Suggestions', point at its first episode only.
        boolean appleSeries = Tmdb.APPLE.equals(e.app) && "tv".equals(e.kind);
        if (opened != null && !opened[1].isEmpty() && !"-".equals(opened[1]) && squash(opened[0]).equals(squash(e.title))
                && !(appleSeries && !opened[1].contains("showId="))) {
            return opened[1];
        }
        Entry old = find(e.key);
        if (old != null && !"-".equals(old.link) && !(appleSeries && !old.link.contains("showId="))) return old.link;
        for (String line : Walls.read(new File(dir, "pool/pool.txt"))) {
            String[] p = line.split(" \\| ");
            if (p.length > 10 && p[0].trim().equals(e.key) && p[10].startsWith("http") && !appleSeries) return p[10].trim();
        }
        String l = Tmdb.link(e.kind, Long.parseLong(e.key.split("-", 2)[1]), e.app);
        return l.isEmpty() ? "-" : l;
    }

    /** A YouTube video: its title, channel, length and thumbnail all come from the session. */
    private Entry video(Play p, MediaMetadata md, String title) {
        Entry e = new Entry();
        e.key = "yt-" + Integer.toHexString(squash(title).hashCode());
        e.kind = "video";
        e.app = p.app;
        e.title = title;
        CharSequence by = md == null ? null : md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        e.detail = by == null ? "" : by.toString().trim();
        e.duration = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        youtube(e);
        Bitmap art = Lights.art(md);
        if (art != null && !backdrop(dir, e.key).isFile()) {
            File f = backdrop(dir, e.key);
            f.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(f)) {
                art.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } catch (IOException | RuntimeException ex) {
                Log.w(TAG, "can't keep the thumbnail: " + ex);
            }
        }
        return e;
    }

    /**
     * A video's ID and thumbnail, which YouTube's app doesn't share: found by title and channel
     * with the YouTube Data API. A search costs 100 of the key's free 10,000 daily units, so each
     * video is looked up once and remembered.
     */
    private void youtube(Entry e) {
        Entry old = find(e.key);
        if (old != null && old.link.startsWith(WATCH)) e.link = old.link;
        if (e.link.startsWith(WATCH) && backdrop(dir, e.key).isFile()) return;
        List<String> keyFile = Walls.read(new File(dir, "youtube.key"));
        String key = keyFile.isEmpty() ? "" : keyFile.get(0).trim();
        if (key.isEmpty()) return;
        String id = e.link.startsWith(WATCH) ? e.link.substring(WATCH.length()) : null;
        if (id == null) {
            try {
                String q = URLEncoder.encode(e.title + (e.detail.isEmpty() ? "" : " " + e.detail), "UTF-8");
                JSONObject res = new JSONObject(new String(Tmdb.get("https://www.googleapis.com/youtube/v3/search"
                        + "?part=snippet&type=video&maxResults=5&q=" + q + "&key=" + key, null, null), StandardCharsets.UTF_8));
                JSONArray items = res.optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    JSONObject ids = it.optJSONObject("id"), snippet = it.optJSONObject("snippet");
                    String vid = ids == null ? "" : ids.optString("videoId", "");
                    if (vid.isEmpty()) continue;
                    if (id == null) id = vid; // the best match, unless one has exactly this title
                    String t = snippet == null ? "" : unescape(snippet.optString("title", ""));
                    if (squash(t).equals(squash(e.title))) {
                        id = vid;
                        break;
                    }
                }
            } catch (IOException | JSONException ex) {
                // Not the exception's text: a failed request's message carries the URL, key and all.
                Log.w(TAG, "YouTube lookup failed for " + e.title + " (" + ex.getClass().getSimpleName() + ")");
                return;
            }
            if (id == null) {
                Log.i(TAG, "YouTube has no video called " + e.title);
                return;
            }
            e.link = WATCH + id;
            Log.i(TAG, e.title + " is YouTube video " + id);
        }
        // The largest thumbnail there is; smaller ones are 4:3 with bars, which the card crops off.
        for (String size : new String[] {"maxresdefault", "sddefault", "hqdefault", "mqdefault"}) {
            if (fetch("https://i.ytimg.com/vi/" + id + "/" + size + ".jpg", backdrop(dir, e.key))) break;
        }
    }

    /**
     * A YouTube video is about to be opened from its card. Its app follows a youtube.com/watch
     * link whether it's running or not, but starts the video from the top, ignoring any start
     * time; its media session takes a seek, though. So the guard covers the screen with the
     * sound off, and the video is moved to a few seconds before where it was left: as soon as
     * YouTube allows seeking (never during an ad), checked again once it names the video, which
     * takes it two to four seconds. False when there's nothing to resume.
     */
    static boolean resumeAt(Entry e) {
        if (!e.link.startsWith(WATCH) || e.position < 15_000 || e.duration > 0 && e.position > e.duration - 15_000) {
            return false;
        }
        long to = e.position - 3000;
        seek = new Seek(e.app, squash(e.title), to, SystemClock.elapsedRealtime() + SEEK_WAIT_MS);
        Watching inst = instance;
        Guard.resuming(inst == null ? null : backdrop(inst.dir, e.key), e.app, e.title, e.detail, to, e.duration);
        Watching w = instance;
        if (w != null) {
            handler.removeCallbacks(w.pollTask);
            handler.post(w.pollTask);
        }
        return true;
    }

    /**
     * Where a card's OK goes: straight into playing where possible. Netflix's /watch/ link starts
     * the title where it was left (Netflix keeps the place), and so does an Apple TV film's link
     * with ?action=play; the title pages Suggestions opens would need another OK. An Apple TV
     * series plays its first episode from any play link, so it opens on the show's page instead,
     * where Apple's own button resumes the episode left off. Hotstar takes no links at all; its
     * app opens on its own Continue Watching row, as Prime Video's does for a series past its
     * first season.
     */
    static String playLink(Entry e) {
        // Prime Video keeps a page per season, and the only link to be had (TMDB's) is the first
        // season's, whose button offers that season's next episode (Fringe in season 2 opened on
        // S1 E20). Beyond season 1 the app itself opens, on its own Continue watching row.
        if (Tmdb.PRIME.equals(e.app) && "tv".equals(e.kind) && !e.detail.startsWith("S1 ")) return "-";
        if (Tmdb.NETFLIX.equals(e.app) && e.link.contains("netflix.com/title/")) return e.link.replace("/title/", "/watch/");
        if (Tmdb.APPLE.equals(e.app) && e.link.contains("tv.apple.com/")) {
            String show = android.net.Uri.parse(e.link).getQueryParameter("showId");
            if (show != null) return "https://tv.apple.com/in/show/" + slug(e.title) + "/" + show;
            return e.link.split("\\?")[0] + ("tv".equals(e.kind) ? "" : "?action=play");
        }
        return e.link;
    }

    /** "Slow Horses" -> "slow-horses", as Apple's links spell a show's name. */
    private static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "show" : s;
    }

    /** Taken off the row on the Continue watching page. Playing it again puts it back. */
    static void remove(String key) {
        Watching w = instance;
        if (w == null) return;
        handler.post(() -> {
            w.forget(key);
            Log.i(TAG, key + " taken off the row");
        });
    }

    /** The video couldn't be opened after all. */
    static void cancelResume() {
        seek = null;
        Guard.resumed();
    }

    private void seek(MediaController mc, List<String> texts) {
        Seek s = seek;
        if (s == null || !s.app.equals(mc.getPackageName())) return;
        boolean ours = false;
        for (String t : texts) ours |= squash(t).equals(s.title);
        if (!ours && named(texts)) return; // the video before it, on its way out
        PlaybackState st = mc.getPlaybackState();
        if (st == null || (st.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
        if (!ours) {
            if (s.sent) return;
            s.sent = true;
        } else {
            seek = null;
            Guard.resumed();
            if (s.sent && position(st) >= s.to - 5000) return; // the early jump took
        }
        mc.getTransportControls().seekTo(s.to);
        Log.i(TAG, "moved " + s.title + " to " + s.to / 1000 + " s" + (ours ? "" : " (before it was named)"));
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Downloads a TMDB image once. */
    private static void save(String path, String size, File f) {
        if (path == null || path.isEmpty() || "null".equals(path) || f.isFile()) return;
        if (!fetch("https://image.tmdb.org/t/p/" + size + path, f)) Log.w(TAG, "no image " + path);
    }

    /** Downloads {@code url} to {@code f}; false when there's nothing there. */
    private static boolean fetch(String url, File f) {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(Tmdb.get(url, null, null));
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(f)) return true;
        tmp.delete();
        return false;
    }

    // ---- the row ----------------------------------------------------------------------------

    private Entry find(String key) {
        for (Entry e : entries) if (e.key.equals(key)) return e;
        return null;
    }

    /** To the front of the row, replacing the same title; the oldest beyond {@link #MAX} go. */
    private void remember(Entry e) {
        Entry old = find(e.key);
        if (old != null) {
            entries.remove(old);
            if ("-".equals(e.link)) e.link = old.link;
            // Reopened from its card, a Netflix or Apple TV series is known by name only: the
            // episode last read stays until the camera reads the next one.
            if (e.detail.isEmpty() && "tv".equals(e.kind)) e.detail = old.detail;
        }
        entries.add(0, e);
        while (entries.size() > MAX) drop(entries.remove(entries.size() - 1));
        write();
    }

    /** YouTube videos are passing things: their cards go 6 hours after they last played. */
    static final long VIDEO_KEEP_MS = 6 * 3600_000L;

    static boolean expired(Entry e) {
        return YOUTUBE.equals(e.app) && System.currentTimeMillis() - e.updated > VIDEO_KEEP_MS;
    }

    private void prune() {
        boolean changed = false;
        for (java.util.Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!expired(e) || cur != null && cur.entry == e) continue;
            it.remove();
            drop(e);
            changed = true;
            Log.i(TAG, e.title + " off the row: a video last played over 6 hours ago");
        }
        if (changed) write();
    }

    private void forget(String key) {
        Entry old = find(key);
        if (old == null) return;
        entries.remove(old);
        drop(old);
        write();
    }

    private void drop(Entry e) {
        backdrop(dir, e.key).delete();
        logo(dir, e.key).delete();
    }

    static File folder(File dir) {
        return new File(dir, "continue");
    }

    static File backdrop(File dir, String key) {
        return new File(folder(dir), key + ".jpg");
    }

    static File logo(File dir, String key) {
        return new File(folder(dir), key + "-logo.png");
    }

    /** Written whole and swapped in, so the home screen never reads half a file. */
    private void write() {
        File f = new File(folder(dir), "continue.txt"), tmp = new File(folder(dir), "continue.txt.part");
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# key | title | kind | year | app | link | detail | position ms | duration ms | updated | rating | genres\n");
            for (Entry e : entries) {
                w.write(TextUtils.join(" | ", new String[] {e.key, field(e.title), e.kind, e.year, e.app, field(e.link),
                        field(e.detail), Long.toString(e.position), Long.toString(e.duration), Long.toString(e.updated),
                        String.format(Locale.ROOT, "%.1f", e.rating), field(e.genres)}) + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "can't save the row: " + e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "can't save the row");
    }

    /** The row, newest first. */
    static List<Entry> read(File dir) {
        List<Entry> out = new ArrayList<>();
        for (String line : Walls.read(new File(folder(dir), "continue.txt"))) {
            if (line.startsWith("#")) continue;
            String[] p = line.split(" \\| ", -1);
            if (p.length < 12) continue;
            try {
                Entry e = new Entry();
                e.key = p[0];
                e.title = p[1];
                e.kind = p[2];
                e.year = p[3];
                e.app = p[4];
                e.link = p[5];
                e.detail = p[6];
                e.position = Long.parseLong(p[7]);
                e.duration = Long.parseLong(p[8]);
                e.updated = Long.parseLong(p[9]);
                e.rating = Double.parseDouble(p[10]);
                e.genres = p[11];
                out.add(e);
            } catch (NumberFormatException ignored) {
                // a damaged line: skipped
            }
        }
        return out;
    }

    private static boolean named(List<String> texts) {
        for (String t : texts) if (!APP_NAMES.contains(squash(t))) return true;
        return false;
    }

    private static String field(String s) {
        return s == null || s.isEmpty() ? "" : s.replace('|', '/').replace('\n', ' ');
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

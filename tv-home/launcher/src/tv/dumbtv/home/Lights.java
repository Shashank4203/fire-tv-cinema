package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the Magic Home strip behind the TV in the mode picked in the home screen's lighting menu (or
 * in Options), wherever the TV is: an app, the home screen or the screensaver. The menu's switch
 * turns the strip off and on.
 * <ul>
 *   <li>static: the owner's own colour, as set in the Magic Home app; the strip is left alone.
 *   <li>sound: the colours of what's playing (or the owner's colour), pulsing with the bass the
 *       phone's microphone hears.
 *   <li>video: the colour of the picture, from the phone filming the screen.
 *   <li>both: the picture's colour, pulsing with the sound.
 * </ul>
 * Without the phone, the colours of what's playing flow slowly from one to the next (dimmed while
 * paused), or the owner's colour shows when nothing plays. With the TV off the strip goes back to
 * the owner's colour.
 *
 * It is a notification listener only because that is what lets an app see other apps' media
 * sessions on Fire OS 7 (deploy.sh enables it with cmd notification allow_listener). It reads
 * no notifications. The TV cannot capture the picture (DRM) or the sound (offloaded or direct
 * outputs, no loopback for apps), so the colours come from the title's artwork, or, while the
 * phone running lightcam/ films the screen, from the picture itself ("lightcam r g b beat level"
 * on UDP {@link #LIVE_PORT}: the picture's colour at full brightness, the strength of the flash
 * that is on (bass, mids or highs), how bright the picture is, and the gradual mode's groove).
 */
public final class Lights extends NotificationListenerService {
    private static final String TAG = "lights";
    private static final long POLL_MS = 1000;
    private static final long FRAME_MS = 50;
    /** Each colour holds, then fades into the next. */
    private static final long HOLD_MS = 6000;
    private static final long FADE_MS = 4000;
    /** A new title's colours fade in over this. */
    private static final long SWITCH_MS = 1500;
    private static final long PAUSED_MS = 2 * 60_000L;
    /** Strip not found: look again this soon. Lost mid-show (it dropped off the Wi-Fi): sooner. */
    private static final long RETRY_MS = 10_000;
    private static final long LOST_RETRY_MS = 2000;
    private static final float PAUSED_LEVEL = 0.5f;
    static final int LIVE_PORT = 5578;
    /** The phone's port for the TV's word: commands, and "here". */
    private static final int PHONE_PORT = 5579;
    /** How often the TV says "here" to the phone while its colours keep coming. */
    private static final long HERE_MS = 5000;
    /** The phone's colours count while they keep coming; after this the artwork takes over. */
    private static final long LIVE_MS = 1000;
    private static volatile int liveColour;
    /** The phone's beat strength, 0..1, or -1 for no sound: applied unsmoothed, so beats stay sharp. */
    private static volatile float liveBeat = -1f;
    /**
     * The gradual mode's groove from the phone: how much is going on in the sound across eight
     * bands, 0..1, or -1 with no sound; -2 when the phone's app is too old to send it.
     */
    private static volatile float liveGroove = -2f;
    /** How bright the picture is, 0..1; the colour itself comes at full brightness. */
    private static volatile float liveLevel = 1f;

    private static volatile long liveAt;
    /** Where the phone's colours come from, for {@link Calibrate} to talk back to. */
    static volatile InetAddress phone;
    /** The phone's answer to lining up: "calibrated <percent>" or "failed <why>". */
    static volatile String calibration;
    /** Its answer to lining up with sound: "scal <caught/played/stray per band>" or "scalfail <why>". */
    static volatile String soundCalibration;
    /** Its answer to matching colours: "colour <hue error before> <after>" or "colourfail <why>". */
    static volatile String colourCalibration;
    /** While the camera is being lined up, the strip stays dark: its glow would light the screen and wall. */
    private static volatile boolean quiet;
    /** While the camera matches the strip's own colours: what it shows instead of dark (LED power), or -1. */
    private static volatile int test = -1;
    /** When the strip was last driven; its colours can only be matched while it is. */
    private static volatile long frameAt;
    /** The phone's answer to matching the strip: "strip" and what it measured, or "stripfail <why>". */
    static volatile String stripCalibration;
    /** How the strip turns power into light, as the light camera measured it; null until it has. */
    private static volatile StripModel model;

    static void quiet(boolean q) {
        quiet = q;
    }

    static void test(int rgb) {
        test = rgb;
    }

    static boolean driving() {
        return SystemClock.elapsedRealtime() - frameAt < 1000;
    }

    /** Keeps the strip's measured colours from the phone's answer; null if it isn't one. */
    static StripModel matchStrip(Context c, String answer) {
        String measured = answer.replaceFirst("^strip ", "");
        StripModel m = StripModel.parse(measured);
        if (m == null) return null;
        model = m;
        c.getSharedPreferences("lights", MODE_PRIVATE).edit().putString("strip", measured).apply();
        Log.i(TAG, "the strip's own colours are matched");
        return m;
    }

    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::poll;
    private final Runnable frameTask = this::frame;
    static final String STATIC = "static", SOUND = "sound", VIDEO = "video", BOTH = "both";
    static final String[] MODES = {STATIC, SOUND, VIDEO, BOTH};
    private static volatile String mode = BOTH;
    /** How sound shows: sharp on/off flashes, or a flash that eases back down. */
    static final String STROBE = "strobe", GRADUAL = "gradual";
    static final String[] STYLES = {STROBE, GRADUAL};
    private static volatile String style = STROBE;
    private static volatile boolean power = true;
    /** The brightness bar's level, 0.1..1. */
    private static volatile float brightness = 1f;
    /** The switch's position at the last poll; null before the first, so a restart sends nothing. */
    private Boolean lastPower;
    /**
     * False once Android unbinds this instance. Checked on the lights thread, because a poll
     * running while the main thread removes it would otherwise post itself again and keep a stale
     * instance driving the strip beside the new one.
     */
    private volatile boolean alive;

    /**
     * One thread for the whole process: when Android rebinds the listener, the old instance's
     * restore runs before the new one reads the strip.
     */
    private static HandlerThread thread;
    private static Handler handler;
    private static Strip strip;
    private static Palette palette;

    // Everything below belongs to the lights thread.
    /** The title the colours are from; "" for the owner's colour; null to work it out again. */
    private String showing;
    /** The home screen was shown since the last thing played: paused sessions no longer count. */
    private boolean left;
    private int[] colours = Palette.forApp("");
    private long coloursSince;
    private int from = -1;
    private float level, targetLevel;
    private int sent = -1;
    private boolean wasLive;
    /** The colour before brightness, eased towards the phone's so its noise doesn't flicker. */
    private final float[] cur = new float[3];
    private float curLevel = 1f;
    /** The colours are the owner's own, LED power already, not a screen's. */
    private boolean raw;
    /** Gradual: the light a hit gave, easing back down frame by frame. */
    private float glow;
    private long lastBeatAt;
    private static final long STROBE_HOLD_MS = 1500;
    private long sentAt, pausedSince, retryAt;

    private static Lights running;

    /**
     * The home screen is showing: whatever was playing has been left (its app keeps a paused
     * session in the background), so its colours give way to the owner's.
     */
    static void home() {
        Lights l = running;
        if (l != null) handler.post(() -> {
            l.left = true;
            l.showing = null;
        });
    }

    /** The mode picked on the home screen: applied now rather than at the next poll. */
    static void choose(String m) {
        mode = m;
        Lights l = running;
        if (l != null) {
            handler.removeCallbacks(l.pollTask);
            handler.post(l.pollTask);
        }
    }

    /** The menu's switch: off turns the strip off; on turns it back on, in the chosen mode. */
    static void power(boolean on) {
        power = on;
        Lights l = running;
        if (l != null) {
            handler.removeCallbacks(l.pollTask);
            handler.post(l.pollTask);
        }
    }

    /** The brightness bar: applied now; below 100 % even static mode shows the owner's colour, dimmed. */
    static void brightness(int percent) {
        brightness = Math.max(10, Math.min(100, percent)) / 100f;
        Lights l = running;
        if (l != null) {
            handler.removeCallbacks(l.pollTask);
            handler.post(l.pollTask);
        }
    }

    static int brightnessOf(Config cfg) {
        try {
            return Integer.parseInt(cfg.get(Config.LIGHTS_BRIGHTNESS));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    /** The light menu's choice of strobe or gradual, applied from the next frame. */
    static void style(String s) {
        style = s;
    }

    static String styleOf(Config cfg) {
        return cfg.get(Config.LIGHTS_STYLE);
    }

    static boolean powerOf(Config cfg) {
        return !"off".equals(cfg.get(Config.LIGHTS_POWER));
    }

    /** The mode in the settings file; the old on/off values of the setting are moved over. */
    static String modeOf(Config cfg) {
        String m = cfg.get(Config.LIGHTS.key, BOTH);
        if ("off".equals(m) || "on".equals(m)) {
            m = "off".equals(m) ? STATIC : BOTH;
            cfg.set(Config.LIGHTS.key, m);
        }
        return Arrays.asList(MODES).contains(m) ? m : BOTH;
    }

    /**
     * The home screen opened this title. Netflix names nothing in its media session, so when the
     * app it went to plays without a title, these are its colours.
     */
    static void opened(Context c, String app, String title) {
        c.getSharedPreferences("lights", MODE_PRIVATE).edit()
                .putString("opened", app + "|" + System.currentTimeMillis() + "|" + title).apply();
    }

    /** The title opened from the home screen in this app in the last four hours, or null. */
    private String openedIn(String app) {
        String[] o = getSharedPreferences("lights", MODE_PRIVATE).getString("opened", "||").split("\\|", 3);
        boolean recent = o.length == 3 && !o[1].isEmpty()
                && System.currentTimeMillis() - Long.parseLong(o[1]) < 4 * 3600_000L;
        return recent && app.equals(o[0]) && !o[2].isEmpty() ? o[2] : null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (thread == null) {
            thread = new HandlerThread("lights");
            thread.start();
            handler = new Handler(thread.getLooper());
            strip = new Strip(getApplicationContext());
            palette = new Palette(getApplicationContext());
            model = StripModel.parse(getSharedPreferences("lights", MODE_PRIVATE).getString("strip", ""));
            Log.i(TAG, model == null ? "the strip's own colours aren't matched yet" : "the strip's own colours are matched");
            listen();
        }
    }

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "listening for what's playing");
        running = this;
        alive = true;
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
        Watching.start(this);
    }

    @Override
    public void onListenerDisconnected() {
        alive = false;
        Watching.stop();
        if (running == this) running = null;
        handler.removeCallbacks(pollTask);
        handler.post(this::release);
    }

    @Override
    public void onDestroy() {
        alive = false;
        Watching.stop();
        if (running == this) running = null;
        handler.removeCallbacks(pollTask);
        handler.post(this::release);
        lookups.shutdownNow();
        super.onDestroy();
    }

    private void poll() {
        if (!alive) return;
        handler.postDelayed(pollTask, POLL_MS);
        main.post(() -> {
            Config cfg = Config.get(this);
            cfg.refresh();
            mode = modeOf(cfg);
            power = powerOf(cfg);
            style = styleOf(cfg);
            brightness = brightnessOf(cfg) / 100f;
        });
        boolean on = power;
        if (lastPower == null) {
            lastPower = on;
        } else if (on != lastPower) {
            lastPower = on;
            Log.i(TAG, on ? "switched on" : "switched off");
            if (!on) release(); // the owner's colour back first, so it's there when switched on again
            strip.power(on);
        }
        if (!on) {
            release();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean still = STATIC.equals(mode);
        if (still && brightness >= 1f && !quiet || !pm.isInteractive()) { // static at full: the strip is the owner's
            release();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!strip.connected()) {
            if (now < retryAt) return;
            if (!strip.take()) {
                Log.w(TAG, "no light strip found; trying again in " + RETRY_MS / 1000 + " s");
                retryAt = now + RETRY_MS;
                return;
            }
            level = 0;
            sent = -1;
            from = -1;
            showing = null;
            handler.removeCallbacks(frameTask);
            handler.post(frameTask);
        }
        if (still) { // static, dimmed: the owner's colour, nothing else
            targetLevel = 1f;
            if (!"".equals(showing)) {
                showing = "";
                setColours(base(), strip.owner() > 0);
            }
            return;
        }
        MediaController playing = null;
        MediaController paused = null;
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            for (MediaController c : m.getActiveSessions(new ComponentName(this, Lights.class))) {
                PlaybackState s = c.getPlaybackState();
                if (s == null || "com.amazon.vizzini".equals(c.getPackageName())) continue; // Alexa's voice
                int st = s.getState();
                if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                    if (playing == null) playing = c;
                } else if (st == PlaybackState.STATE_PAUSED && paused == null) {
                    paused = c;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "not allowed to see media sessions: " + e.getMessage());
            return;
        }
        // A pause keeps the title's colours, dimmed, until the home screen is shown or it has been
        // paused a while: apps leave paused sessions behind in the background.
        if (playing != null) {
            left = false;
            pausedSince = 0;
        } else if (paused == null) {
            pausedSince = 0;
        } else if (pausedSince == 0) {
            pausedSince = now;
        }
        boolean pausedTooLong = pausedSince != 0 && now - pausedSince > PAUSED_MS;
        MediaController c = playing != null ? playing : !left && !pausedTooLong ? paused : null;
        targetLevel = c != null && playing == null ? PAUSED_LEVEL : 1f;
        if (c != null) {
            title(c);
        } else if (!"".equals(showing)) {
            showing = "";
            Log.i(TAG, "nothing playing: the owner's colour");
            setColours(base(), strip.owner() > 0);
        }
    }

    /** The owner's colour, which shows (and pulses) when nothing plays and the phone isn't filming. */
    private static int[] base() {
        int o = strip.owner();
        return o > 0 ? new int[] {o} : Palette.forApp("");
    }

    /** Takes the colours of what this session is playing. */
    private void title(MediaController c) {
        MediaMetadata md = c.getMetadata();
        List<String> texts = texts(md);
        String opened = texts.isEmpty() ? openedIn(c.getPackageName()) : null;
        if (opened != null) texts.add(opened);
        String id = c.getPackageName() + " " + texts;
        if (id.equals(showing)) return;
        showing = id;
        Log.i(TAG, "now showing " + id);
        setColours(Palette.forApp(c.getPackageName()));
        Bitmap art = art(md);
        lookups.execute(() -> {
            int[] found = art != null ? Palette.extract(art) : palette.lookup(texts);
            if (art != null) Log.i(TAG, "artwork colours " + Palette.describe(found));
            if (found != null) handler.post(() -> {
                if (id.equals(showing)) setColours(found);
            });
        });
    }

    private void setColours(int[] c) {
        setColours(c, false);
    }

    /** own: the owner's colour, as set in the Magic Home app: already LED power, shown as it is. */
    private void setColours(int[] c, boolean own) {
        from = sent;
        colours = c;
        raw = own;
        coloursSince = SystemClock.elapsedRealtime();
    }

    /**
     * A screen colour (sRGB) as LED power, at brightness f. On a screen 128 is about 22 % of the
     * light, on the strip 50 %: sent as it is, any mixed colour got far too much of its weaker
     * channels, so teal and sky blue went green and pastel. (LED projects like WLED correct the same
     * way.) Once the light camera has matched the strip, its own colours and how its LEDs dim each
     * other are made up for as well ({@link StripModel}).
     */
    private static int toLed(int c, float f) {
        StripModel m = model;
        if (m != null) return m.drive(c, f);
        return rgb((int) (StripModel.linear(r(c)) * 255 * f), (int) (StripModel.linear(g(c)) * 255 * f),
                (int) (StripModel.linear(bl(c)) * 255 * f));
    }

    /** One step of the slow flow through the colours, about 20 times a second. */
    private void frame() {
        if (!alive || !strip.connected()) return;
        handler.postDelayed(frameTask, FRAME_MS);
        long now = SystemClock.elapsedRealtime();
        if (!strip.healthy()) {
            Log.w(TAG, "the strip stopped answering: reconnecting");
            strip.close();
            showing = null;
            retryAt = now + LOST_RETRY_MS;
            return;
        }
        boolean fresh = liveAt != 0 && now - liveAt < LIVE_MS;
        String m = mode;
        boolean live = fresh && (VIDEO.equals(m) || BOTH.equals(m));
        boolean pulse = fresh && (SOUND.equals(m) || BOTH.equals(m));
        if (live != wasLive) {
            wasLive = live;
            Log.i(TAG, live ? "colours from the phone's camera" : "colours from the artwork or the owner's");
            if (!live) {
                from = sent;
                coloursSince = now;
            }
        }
        int colour;
        if (live) {
            int c = liveColour;
            cur[0] += (r(c) - cur[0]) * 0.35f;
            cur[1] += (g(c) - cur[1]) * 0.35f;
            cur[2] += (bl(c) - cur[2]) * 0.35f;
            colour = rgb((int) cur[0], (int) cur[1], (int) cur[2]);
        } else {
            long t = now - coloursSince;
            int n = colours.length;
            long step = t / (HOLD_MS + FADE_MS);
            long phase = t % (HOLD_MS + FADE_MS);
            int a = colours[(int) (step % n)];
            int b = colours[(int) ((step + 1) % n)];
            colour = phase < HOLD_MS || n == 1 ? a : mix(a, b, ease((phase - HOLD_MS) / (float) FADE_MS));
            if (from >= 0 && t < SWITCH_MS) colour = mix(from, colour, ease(t / (float) SWITCH_MS));
            cur[0] = r(colour);
            cur[1] = g(colour);
            cur[2] = bl(colour);
        }
        level += ((live ? 1f : targetLevel) - level) * 0.06f;
        // Brightness. Video: the picture's. With sound, a strobe: each beat is a flash of the colour
        // (the picture's, or the title's or owner's in sound sync) at full brightness, and between
        // beats the strip is dark; no fading either way. No sound to strobe to: no strobing.
        curLevel += (liveLevel - curLevel) * 0.35f;
        // The strobe runs while beats keep coming; 1.5 s without one (talk, a quiet passage) and the
        // strip follows the picture again rather than sitting dark.
        float beat = pulse ? liveBeat : -1f;
        if (beat >= 0.3f) lastBeatAt = now;
        boolean strobing = beat >= 0 && now - lastBeatAt < STROBE_HOLD_MS;
        // A flash's strength says what was hit: full for the bass, less for snares and hi-hats.
        // Strobe: that strength while the flash lasts, dark between. Gradual: the same jump up, then
        // easing back over about a fifth of a second to a soft floor instead of dark.
        float hitNow = beat >= 0.3f ? beat : 0f;
        float groove = pulse ? liveGroove : -2f;
        float bright;
        if (GRADUAL.equals(style) && groove > -2f) {
            // Gradual, following the phone's groove: every rise in any of eight bands lifts the
            // light, which eases back, over a base set by the loudness; all the time, not only on
            // strict beats. No sound: the picture as it is.
            // Squared, because an LED at half power looks about 70 % as bright: the groove's swing
            // only shows if the quiet between beats is really dim. A low floor, never dark.
            glow = 0;
            float floor = live ? curLevel * 0.15f : 0.05f;
            bright = groove < 0 ? (live ? curLevel : 1f) : floor + (1 - floor) * groove * groove;
        } else if (!strobing) {
            glow = 0;
            bright = live ? curLevel : 1f;
        } else if (GRADUAL.equals(style)) {
            glow = Math.max(glow * 0.8f, hitNow);
            bright = Math.max(live ? curLevel * 0.4f : 0.2f, glow);
        } else {
            bright = hitNow;
        }
        float b2 = brightness * brightness; // the bar, squared: even steps to the eye
        float f = level * level * bright * b2; // squared: closer to how bright it looks
        int out;
        if (quiet) out = Math.max(0, test);
        else if (live || !raw) out = toLed(colour, f);
        else out = rgb((int) (r(colour) * f), (int) (g(colour) * f), (int) (bl(colour) * f)); // the owner's: LED power already
        frameAt = now;
        // Unchanged colours are re-sent every 2 s, so a change made in the Magic Home app is undone.
        if (sent >= 0 && near(out, sent) && now - sentAt < 2000) return;
        if (!strip.colour(r(out), g(out), bl(out))) {
            Log.w(TAG, "lost the strip");
            showing = null;
            retryAt = now + LOST_RETRY_MS;
            return;
        }
        sent = out;
        sentAt = now;
    }

    /** Receives the phone's colours, for as long as the process lives. */
    private static void listen() {
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(LIVE_PORT)) {
                byte[] buf = new byte[2048]; // the strip's matching runs to some 600
                byte[] here = "here".getBytes(StandardCharsets.US_ASCII);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                long hereAt = 0;
                while (true) {
                    p.setLength(buf.length);
                    s.receive(p);
                    String msg = new String(buf, 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    String[] m = msg.split(" ");
                    if (m.length < 2 || !"lightcam".equals(m[0])) continue;
                    phone = p.getAddress();
                    // The router hands out addresses afresh now and then. The phone sends to the
                    // address it last heard "here" from, and when it hears nothing for a while it
                    // says "hello" to every address on the Wi-Fi; answered at once.
                    long now = SystemClock.elapsedRealtime();
                    if (now - hereAt > HERE_MS || "hello".equals(m[1])) {
                        hereAt = now;
                        s.send(new DatagramPacket(here, here.length, p.getAddress(), PHONE_PORT));
                    }
                    if ("hello".equals(m[1])) continue;
                    if ("title".equals(m[1])) { // what's playing, read off the screen (Continue watching)
                        Watching.cameraSaw(msg.substring("lightcam title".length()));
                        continue;
                    }
                    if ("calibrated".equals(m[1]) || "failed".equals(m[1])) {
                        calibration = msg.substring("lightcam ".length());
                        continue;
                    }
                    if ("colour".equals(m[1]) || "colourfail".equals(m[1])) {
                        colourCalibration = msg.substring("lightcam ".length());
                        continue;
                    }
                    if ("strip".equals(m[1]) || "stripfail".equals(m[1])) {
                        stripCalibration = msg.substring("lightcam ".length());
                        continue;
                    }
                    if ("scal".equals(m[1]) || "scalfail".equals(m[1])) {
                        soundCalibration = msg.substring("lightcam ".length());
                        continue;
                    }
                    try {
                        if (m.length == 6 || m.length == 7) {
                            liveColour = rgb(byteOf(m[1]), byteOf(m[2]), byteOf(m[3]));
                            int b = Integer.parseInt(m[4]);
                            liveBeat = b < 0 ? -1f : Math.min(100, b) / 100f;
                            liveLevel = Math.max(0, Math.min(100, Integer.parseInt(m[5]))) / 100f;
                            int gr = m.length == 7 ? Integer.parseInt(m[6]) : -2;
                            liveGroove = gr < -1 ? -2f : gr < 0 ? -1f : Math.min(100, gr) / 100f;
                            liveAt = SystemClock.elapsedRealtime();
                        } else if (m.length == 4 || m.length == 5) { // an older phone app: colour pre-dimmed
                            liveColour = rgb(byteOf(m[1]), byteOf(m[2]), byteOf(m[3]));
                            liveBeat = -1f;
                            liveLevel = 1f;
                            liveGroove = -2f;
                            liveAt = SystemClock.elapsedRealtime();
                        }
                    } catch (NumberFormatException ignored) {
                        // not ours
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "can't listen for the phone on UDP " + LIVE_PORT + ": " + e);
            }
        }, "lightcam");
        t.setDaemon(true);
        t.start();
    }

    private static int byteOf(String s) {
        return Math.max(0, Math.min(255, Integer.parseInt(s)));
    }

    private void release() {
        handler.removeCallbacks(frameTask);
        strip.restore();
        showing = null;
        pausedSince = 0;
    }

    /** What the app says is playing: its title fields, most specific first, without repeats. */
    static List<String> texts(MediaMetadata md) {
        List<String> out = new ArrayList<>();
        if (md == null) return out;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE}) {
            CharSequence v = md.getText(k);
            String s = v == null ? "" : v.toString().trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Artwork the app supplies itself (music apps do), which beats a lookup. */
    static Bitmap art(MediaMetadata md) {
        if (md == null) return null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = md.getBitmap(k);
            if (b != null && !b.isRecycled()) return b;
        }
        return null;
    }

    private static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3 - 2 * x);
    }

    private static int mix(int a, int b, float t) {
        return rgb((int) (r(a) + (r(b) - r(a)) * t), (int) (g(a) + (g(b) - g(a)) * t), (int) (bl(a) + (bl(b) - bl(a)) * t));
    }

    private static boolean near(int a, int b) {
        return Math.abs(r(a) - r(b)) < 2 && Math.abs(g(a) - g(b)) < 2 && Math.abs(bl(a) - bl(b)) < 2;
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int r(int c) {
        return (c >> 16) & 0xFF;
    }

    private static int g(int c) {
        return (c >> 8) & 0xFF;
    }

    private static int bl(int c) {
        return c & 0xFF;
    }
}

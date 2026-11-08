package tv.dumbtv.lightcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/**
 * Listens to the TV's speakers through the phone's microphone and finds the hits, for the TV to
 * strobe the strip with. Three bands are heard: bass (kicks, explosions), mids (snares, claps,
 * voices) and highs (hi-hats, cymbals). A hit is a band jumping well above its last 60 ms, and
 * each one is a flash of {@link #FLASH_MS}, at most one every {@link #GAP_MS}: full for the bass,
 * 75 % for the mids, 55 % for the highs. Mids and highs only count while a bass beat is going
 * (music), so dialogue doesn't set the light flashing. Only levels are kept; no sound is stored
 * or sent.
 *
 * Loudness is judged against each band's last few seconds, so it works at any volume, and with
 * no real sound around the strip simply follows the picture.
 *
 * Alongside the strobe's hits it works out the gradual mode's {@link Groove}: how much is going
 * on across eight bands, every 20 ms.
 */
final class Pulse {
    private static final String TAG = "lightcam";
    private static final int RATE = 32000; // high enough to hear hi-hats
    private static final int BLOCK = RATE / 50; // 20 ms
    private static final double MID_TOP_HZ = 2000;
    /** Mids and highs count only within this long of a bass beat: music, not talk. */
    private static final long MUSIC_MS = 1500;
    /**
     * The strip's controller fades every change over about 0.25 s, so a flash stays on 200 ms to
     * reach full brightness, and flashes are at least 250 ms apart so it gets dark in between.
     */
    static final long FLASH_MS = 200;
    static final long GAP_MS = 250;

    /** Each band's thresholds {ratio, over the room, of the loudest}: bass, mids, highs. */
    private volatile double[][] params = {{1.52, 2.34, 0.39}, {1.52, 2.34, 0.39}, {1.77, 2.5, 0.45}};
    private volatile Band[] live;
    /** Lining up with sound: each block's time and band levels, while recording. */
    private final Object recLock = new Object();
    private boolean recording;
    private final long[] recT = new long[1500];
    private final double[][] recL = new double[3][1500];
    private int recN;
    /** A flash's strength (1, 0.75, 0.55) while it is on, 0 between flashes, -1 with nothing to pulse to. */
    private volatile float beat = -1f;
    /** The strongest beat since the TV was last told: beats are shorter than the 50 ms between sends. */
    private float held = -1f;
    private final Object lock = new Object();
    /** The top of the bass, in Hz (bass_hz in lightcam.conf). */
    private volatile double bassHz = 250;
    /** The mids run from just above the bass to {@link #MID_TOP_HZ}; the highs start well clear of a snare. */
    private static final double MID_BOTTOM_HZ = 300, HIGH_BOTTOM_HZ = 5000;
    /** The gradual mode's groove, 0..1 or -1, and the strongest since the TV was last told. */
    private volatile float groove = -1f;
    private float grooveHeld = -1f;
    /** The groove's tunables {flux min, flux full, release, loudness share}, from lightcam.conf. */
    private volatile double[] grooveParams = {3, 18, 0.85, 0.25};
    /** Recording real sound for tuning ("rec start"/"rec done"): time, 8 band levels, flux, loudness, glow, groove, beat. */
    private boolean recordingMusic;
    private final long[] musicT = new long[3000];
    private final double[][] musicV = new double[3000][13];
    private int musicN;
    private volatile float loudness;
    private volatile String numbers = "";
    private volatile boolean running;
    private Thread thread;

    /** The strongest beat since the last call (0..1), or -1 when there's no sound to pulse to. */
    float takeBeat() {
        synchronized (lock) {
            float b = held;
            held = beat;
            return b;
        }
    }

    /** The groove since the last call (its strongest, 0..1), or -1 when there's no sound to follow. */
    float takeGroove() {
        synchronized (lock) {
            float g = grooveHeld;
            grooveHeld = groove;
            return g;
        }
    }

    /** The gradual mode's tunables: flux that starts to show, flux that shows fully, release, loudness share. */
    void groove(double fluxMin, double fluxFull, double release, double loudShare) {
        grooveParams = new double[] {fluxMin, fluxFull, release, loudShare};
    }

    void recordMusic() {
        synchronized (recLock) {
            musicN = 0;
            recordingMusic = true;
        }
    }

    /** {times, rows, count} recorded since recordMusic. */
    Object[] stopMusic() {
        synchronized (recLock) {
            recordingMusic = false;
            return new Object[] {Arrays.copyOf(musicT, musicN), Arrays.copyOf(musicV, musicN), musicN};
        }
    }

    /** New thresholds for the bands, from lightcam.conf or from lining up with sound. */
    void tune(double[][] p) {
        params = p;
        Band[] b = live;
        if (b == null) return;
        for (int i = 0; i < 3; i++) {
            b[i].ratio = p[i][0];
            b[i].overFloor = p[i][1];
            b[i].ofPeak = p[i][2];
        }
    }

    void recordStart() {
        synchronized (recLock) {
            recN = 0;
            recording = true;
        }
    }

    /** The recording since recordStart, as {times, levels...} arrays and its length. */
    Object[] recordStop() {
        synchronized (recLock) {
            recording = false;
            return new Object[] {Arrays.copyOf(recT, recN),
                new double[][] {Arrays.copyOf(recL[0], recN), Arrays.copyOf(recL[1], recN), Arrays.copyOf(recL[2], recN)},
                recN};
        }
    }

    /** The top of the bass, in Hz. */
    void bass(double hz) {
        bassHz = Math.max(40, Math.min(1000, hz));
    }

    /** The bass's current strength against recent peaks, 0..1, for the status line. */
    float loudness() {
        return loudness;
    }

    /** The bass levels (now, recent peak, room noise) and the last ten seconds' hits per band. */
    String numbers() {
        return numbers;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "lightcam-pulse");
        thread.start();
    }

    void stop() {
        running = false;
        beat = -1f;
        groove = -1f;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void run() {
        AudioRecord rec = open();
        if (rec == null) {
            Log.w(TAG, "no microphone: the strip won't pulse");
            running = false;
            return;
        }
        rec.startRecording();
        short[] buf = new short[BLOCK];
        double[][] p = params;
        Band bass = new Band(p[0][0], p[0][1], p[0][2]), mids = new Band(p[1][0], p[1][1], p[1][2]),
                highs = new Band(p[2][0], p[2][1], p[2][2]);
        live = new Band[] {bass, mids, highs};
        // Steep filters, so the bands don't bleed: a kick drum used to show up three times louder in
        // the mids than the snares did, and a snare twice as loud in the highs as the hi-hats.
        Biquad lowA = Biquad.lowPass(bassHz, RATE), lowB = Biquad.lowPass(bassHz, RATE);
        Biquad midHigh = Biquad.highPass(MID_BOTTOM_HZ, RATE), midHigh2 = Biquad.highPass(MID_BOTTOM_HZ, RATE);
        Biquad midLow = Biquad.lowPass(MID_TOP_HZ, RATE);
        Biquad highA = Biquad.highPass(HIGH_BOTTOM_HZ, RATE), highB = Biquad.highPass(HIGH_BOTTOM_HZ, RATE);
        Groove gr = new Groove(RATE);
        long flashUntil = 0, lastFlash = 0, lastBass = -MUSIC_MS, windowStart = SystemClock.elapsedRealtime();
        float flashLevel = 0;
        String counts = "";
        try {
            while (running) {
                int n = 0;
                while (n < BLOCK && running) {
                    int k = rec.read(buf, n, BLOCK - n);
                    if (k < 0) throw new IllegalStateException("read " + k);
                    n += k;
                }
                double sb = 0, sm = 0, sh = 0;
                for (int i = 0; i < n; i++) {
                    double x = buf[i];
                    double lo = lowB.process(lowA.process(x));                                // 24 dB/oct
                    double mid = midLow.process(midHigh2.process(midHigh.process(x)));        // 24 up, 12 down
                    double hi = highB.process(highA.process(x));                              // 24 dB/oct
                    gr.sample(x);
                    sb += lo * lo;
                    sm += mid * mid;
                    sh += hi * hi;
                }
                int count = Math.max(1, n);
                long now = SystemClock.elapsedRealtime();
                double vb = Math.sqrt(sb / count), vm = Math.sqrt(sm / count), vh = Math.sqrt(sh / count);
                boolean kick = bass.hit(vb), snare = mids.hit(vm), hat = highs.hit(vh);
                double[] gp = grooveParams;
                gr.fluxMin = gp[0];
                gr.fluxFull = gp[1];
                gr.release = gp[2];
                gr.loudShare = gp[3];
                double g = gr.block(n);
                synchronized (lock) {
                    groove = (float) g;
                    grooveHeld = Math.max(grooveHeld, groove);
                }
                synchronized (recLock) {
                    if (recordingMusic && musicN < musicT.length) {
                        double[] row = musicV[musicN];
                        System.arraycopy(gr.levels, 0, row, 0, 8);
                        row[8] = gr.flux;
                        row[9] = gr.loudness;
                        row[10] = gr.glowing;
                        row[11] = g;
                        row[12] = beat;
                        musicT[musicN++] = now;
                    }
                }
                synchronized (recLock) {
                    if (recording && recN < recT.length) {
                        recT[recN] = now;
                        recL[0][recN] = vb;
                        recL[1][recN] = vm;
                        recL[2][recN] = vh;
                        recN++;
                    }
                }
                if (now - windowStart >= 10_000) {
                    counts = "  hits b" + bass.hits + " m" + mids.hits + " h" + highs.hits;
                    bass.hits = mids.hits = highs.hits = 0;
                    windowStart = now;
                }
                numbers = Math.round(bass.env) + "/" + Math.round(bass.peak) + "/" + Math.round(bass.floor) + counts;
                // Nothing worth pulsing to: the loudest of late is barely above the quietest. (With a
                // film playing the "floor" is its own quiet moments, so this must stay close to 1x; and
                // the phone is across the room from the speakers, so the margin is small.)
                if (bass.quiet()) {
                    publish(-1f);
                    loudness = 0f;
                    continue;
                }
                loudness = (float) Math.max(0, Math.min(1, (bass.env - bass.floor) / (bass.peak - bass.floor)));
                if (kick) lastBass = now;
                boolean music = now - lastBass < MUSIC_MS;
                float strength = kick ? 1f : music && snare ? 0.75f : music && hat ? 0.55f : 0f;
                if (strength > 0 && now - lastFlash >= GAP_MS) {
                    lastFlash = now;
                    flashUntil = now + FLASH_MS;
                    flashLevel = strength;
                    (kick ? bass : strength > 0.6f ? mids : highs).hits++;
                } else if (kick && now < flashUntil && flashLevel < 1f) {
                    flashLevel = 1f; // a kick during a softer flash: full
                }
                publish(now < flashUntil ? flashLevel : 0f);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "microphone: " + e);
        } finally {
            rec.stop();
            rec.release();
            publish(-1f);
        }
    }

    private void publish(float b) {
        synchronized (lock) {
            beat = b;
            held = Math.max(held, b);
        }
    }

    /** The least processed source the phone offers: voice processing would flatten the beats. */
    private static AudioRecord open() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (int source : new int[] {MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}) {
            try {
                AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOCK * 8));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "microphone source " + source);
                    return r;
                }
                r.release();
            } catch (IllegalArgumentException | SecurityException e) {
                Log.w(TAG, "microphone source " + source + ": " + e);
            }
        }
        return null;
    }
}

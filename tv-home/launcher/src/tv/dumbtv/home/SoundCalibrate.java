package tv.dumbtv.home;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lines the light up with sound: plays a test rhythm through the TV at the volume it is set to
 * (two seconds of quiet, then four bars of kicks, snares and hi-hats), while the phone that films
 * the screen (lightcam/) listens from where it stands and keeps the hit thresholds that catch the
 * rhythm best ("scal start", "scal done" on UDP 5579; the answer comes back as "lightcam scal").
 * The rhythm must match lightcam's SoundTune. Light menu › the speaker symbol, or:
 * adb shell am start -n tv.dumbtv.home/.SoundCalibrate
 */
public final class SoundCalibrate extends Activity {
    private static final int COMMANDS = 5579;
    private static final int RATE = 44100;
    private static final int LEAD_MS = 2000, BAR_MS = 2000, BARS = 4, TAIL_MS = 1000;
    private static final int[][] PATTERN = {{0, 1000}, {500, 1500}, {250, 750, 1250, 1750}};
    private static final long LENGTH_MS = LEAD_MS + BARS * BAR_MS + TAIL_MS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AudioManager.OnAudioFocusChangeListener focus = change -> { };
    private FrameLayout root;
    private TextView text;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        text = Ui.text(this, 22, Ui.TEXT, Ui.MEDIUM);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.3f);
        root.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(root);
        waitForPhone(SystemClock.elapsedRealtime() + 4000);
    }

    private void waitForPhone(long until) {
        InetAddress phone = Lights.phone;
        if (phone == null) {
            if (SystemClock.elapsedRealtime() < until) {
                text.setText("Looking for the light camera…");
                main.postDelayed(() -> waitForPhone(until), 200);
            } else {
                text.setText("The light camera isn't running on the phone.\nOpen \"TV light camera\" on it and try again.");
                main.postDelayed(this::finish, 6000);
            }
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        short[] pcm = rhythm();
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        Lights.soundCalibration = null;
        text.setText("Tuning the light to your sound…\nKeep the volume where you usually watch.");
        send(phone, "scal start");
        main.postDelayed(track::play, 150);
        main.postDelayed(() -> {
            send(phone, "scal done");
            text.setText("Working it out…");
            waitForAnswer(SystemClock.elapsedRealtime() + 6000);
        }, 150 + LENGTH_MS + 500);
    }

    private void waitForAnswer(long until) {
        String answer = Lights.soundCalibration;
        if (answer != null || SystemClock.elapsedRealtime() > until) {
            text.setText(answer == null ? "The phone didn't answer. Is it on the same Wi-Fi?" : describe(answer));
            main.postDelayed(this::finish, 6000);
            return;
        }
        main.postDelayed(() -> waitForAnswer(until), 200);
    }

    /** "scal 8/8/0 7/8/1 15/16/2" (caught/played/stray per band) or "scalfail why". */
    private static String describe(String answer) {
        if (answer.startsWith("scalfail ")) return "Couldn't tune to the sound: " + answer.substring(9);
        String[] bands = answer.substring(5).trim().split(" ");
        String[] names = {"kicks", "snares", "hi-hats"};
        StringBuilder s = new StringBuilder("Light tuned to your sound.\nIt caught ");
        for (int i = 0; i < bands.length && i < 3; i++) {
            String[] n = bands[i].split("/");
            if (n.length < 2) continue;
            s.append(i == 0 ? "" : i == 2 ? " and " : ", ").append(n[0]).append(" of ").append(n[1]).append(' ').append(names[i]);
        }
        return s.append('.').toString();
    }

    /** The test rhythm: synthesised kicks (a falling low tone), snares (mid noise) and hi-hats (high noise). */
    private static short[] rhythm() {
        float[] mix = new float[(int) (LENGTH_MS * RATE / 1000)];
        Random noise = new Random(7);
        for (int bar = 0; bar < BARS; bar++) {
            int base = LEAD_MS + bar * BAR_MS;
            for (int at : PATTERN[0]) kick(mix, sample(base + at));
            for (int at : PATTERN[1]) snare(mix, sample(base + at), noise);
            for (int at : PATTERN[2]) hat(mix, sample(base + at), noise);
        }
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) out[i] = (short) (Math.max(-1f, Math.min(1f, mix[i])) * 30000);
        return out;
    }

    private static int sample(int ms) {
        return (int) ((long) ms * RATE / 1000);
    }

    private static void kick(float[] m, int at) {
        double phase = 0;
        for (int i = 0; i < RATE * 3 / 10 && at + i < m.length; i++) {
            double tau = (double) i / RATE;
            phase += 2 * Math.PI * (50 + 70 * Math.exp(-tau / 0.03)) / RATE;
            m[at + i] += (float) (0.6 * Math.sin(phase) * Math.exp(-tau / 0.12));
        }
    }

    private static void snare(float[] m, int at, Random noise) {
        double low = 0, lower = 0;
        double aLow = 1 - Math.exp(-2 * Math.PI * 4000 / RATE), aHigh = 1 - Math.exp(-2 * Math.PI * 300 / RATE);
        for (int i = 0; i < RATE / 4 && at + i < m.length; i++) {
            double tau = (double) i / RATE;
            low += aLow * (noise.nextGaussian() - low);     // below 4 kHz
            lower += aHigh * (low - lower);                   // and above 300 Hz: low - lower
            double body = 0.15 * Math.sin(2 * Math.PI * 190 * tau) * Math.exp(-tau / 0.05);
            m[at + i] += (float) (0.45 * (low - lower) * Math.exp(-tau / 0.09) + body);
        }
    }

    private static void hat(float[] m, int at, Random noise) {
        double l1 = 0, l2 = 0, a = 1 - Math.exp(-2 * Math.PI * 7000 / RATE);
        for (int i = 0; i < RATE / 10 && at + i < m.length; i++) {
            double tau = (double) i / RATE, x = noise.nextGaussian();
            l1 += a * (x - l1);
            double h1 = x - l1;                               // above 7 kHz
            l2 += a * (h1 - l2);
            m[at + i] += (float) (0.35 * (h1 - l2) * Math.exp(-tau / 0.03));
        }
    }

    /** Twice, since UDP can drop one; the phone ignores a repeat. */
    private void send(InetAddress phone, String cmd) {
        net.execute(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] b = cmd.getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < 2; i++) {
                    s.send(new DatagramPacket(b, b.length, phone, COMMANDS));
                    Thread.sleep(40);
                }
            } catch (IOException | InterruptedException e) {
                Log.w("lights", "sound calibration: " + e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // never played
            }
            track.release();
        }
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(focus);
        net.shutdown();
        super.onDestroy();
    }
}

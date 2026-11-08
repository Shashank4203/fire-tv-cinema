package tv.dumbtv.home;

import android.app.Activity;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lines up the phone that films the TV (lightcam/): the screen goes full white, then full black,
 * while the phone is told which is showing. Whatever changed between the two in its camera is the
 * screen. Then the colours: patches of pure colour ({@link #PATCHES}), each named to the phone as
 * it shows ("cpatch i rrggbb"), from which it works out how its camera, at its angle and with its
 * fixed white balance, sees the TV's colours, and corrects for it ("colour done"). The strip is
 * kept dark meanwhile, so its own glow can't light the screen or the wall. Last, with the screen
 * black, the strip's own colours ({@link #STRIP}, "spatch i rrggbb", "strip done"): the phone
 * films their glow on the wall and answers with how to drive the strip so the wall glows in the
 * screen's colours.
 * Light menu › the viewfinder, Options › "Line up the light camera", or:
 * adb shell am start -n tv.dumbtv.home/.Calibrate
 */
public final class Calibrate extends Activity {
    private static final int COMMANDS = 5579;
    private static final long WHITE_MS = 4000, BLACK_MS = 1800;
    /**
     * Black first (the TV's own glow and the room), white, the primaries and secondaries. Each is all
     * or nothing in red, green and blue: this TV brightens a dim full-screen colour by itself, so a
     * half-strength patch wouldn't show what its number says.
     */
    static final int[] PATCHES = {0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x00FFFF, 0xFF00FF, 0xFFFF00};
    private static final long PATCH_MS = 1200;
    /**
     * The strip, as LED power: off (the room as it is), each LED alone at half (where its light
     * still keeps in step with its power) and full, then pairs and all three, which dim each other.
     */
    static final int[] STRIP = {0x000000, 0x800000, 0x008000, 0x000080, 0xFF0000, 0x00FF00, 0x0000FF, 0x808000, 0x008080,
        0x800080, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF};
    /** The strip's controller fades for up to a second on every change. */
    private static final long STRIP_MS = 2200;
    private String lined = "";
    private boolean stripShown;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private TextView text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // no screensaver mid-way
        root = new FrameLayout(this);
        text = Ui.text(this, 22, Ui.TEXT, Ui.MEDIUM);
        text.setGravity(Gravity.CENTER);
        int margin = getResources().getDisplayMetrics().widthPixels / 10;
        text.setPadding(margin, 0, margin, 0);
        root.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(root);
        Lights.quiet(true);
        waitForPhone(SystemClock.elapsedRealtime() + 4000);
    }

    /**
     * The phone's address comes from its colours, which arrive 20 times a second; just after the TV
     * app restarts there may not have been one yet, so it waits a moment before giving up.
     */
    private void waitForPhone(long until) {
        InetAddress phone = Lights.phone;
        if (phone == null) {
            if (SystemClock.elapsedRealtime() < until) {
                show(0xFF000000, "Looking for the light camera…");
                main.postDelayed(() -> waitForPhone(until), 200);
            } else {
                show(0xFF000000, "The light camera isn't running on the phone.\nOpen \"TV light camera\" on it and try again.");
                main.postDelayed(this::finish, 6000);
            }
            return;
        }
        Lights.calibration = null;
        Lights.colourCalibration = null;
        Lights.stripCalibration = null;
        show(0xFFFFFFFF, "");
        send(phone, "calib white");
        main.postDelayed(() -> {
            show(0xFF000000, "");
            send(phone, "calib black");
        }, WHITE_MS);
        main.postDelayed(() -> {
            send(phone, "calib done");
            show(0xFF000000, "Lining up the light camera…");
            waitForAnswer(phone, SystemClock.elapsedRealtime() + 4000);
        }, WHITE_MS + BLACK_MS);
    }

    private void waitForAnswer(InetAddress phone, long until) {
        String answer = Lights.calibration;
        if (answer != null && answer.startsWith("calibrated ")) {
            lined = "Light camera lined up: the screen fills " + answer.substring(11) + "% of its view.";
            patch(phone, 0);
            return;
        }
        if (answer != null || SystemClock.elapsedRealtime() > until) {
            show(0xFF000000, answer == null ? "The phone didn't answer. Is it on the same Wi-Fi?"
                    : "Couldn't line up: " + answer.replaceFirst("^failed ", ""));
            main.postDelayed(this::finish, 4000);
            return;
        }
        main.postDelayed(() -> waitForAnswer(phone, until), 200);
    }

    /** Shows colour patch i and tells the phone; then the next, and after the last, the strip's. */
    private void patch(InetAddress phone, int i) {
        if (i == PATCHES.length) {
            send(phone, "colour done");
            show(0xFF000000, "");
            if (Lights.driving()) {
                stripShown = true;
                stripPatch(phone, 0);
            } else {
                waitForAnswers(SystemClock.elapsedRealtime() + 4000);
            }
            return;
        }
        show(0xFF000000 | PATCHES[i], "");
        send(phone, String.format(Locale.ROOT, "cpatch %d %06x", i, PATCHES[i]));
        main.postDelayed(() -> patch(phone, i + 1), PATCH_MS);
    }

    /** Lights the strip in its colour i and tells the phone; then the next, and after the last, the answers. */
    private void stripPatch(InetAddress phone, int i) {
        if (i == STRIP.length) {
            Lights.test(-1);
            send(phone, "strip done");
            waitForAnswers(SystemClock.elapsedRealtime() + 4000);
            return;
        }
        Lights.test(STRIP[i]);
        send(phone, String.format(Locale.ROOT, "spatch %d %06x", i, STRIP[i]));
        main.postDelayed(() -> stripPatch(phone, i + 1), STRIP_MS);
    }

    /**
     * "colour <before> <after>": the camera's average hue error over the mixed colours, in degrees;
     * "strip m0 … m8": how the strip is driven from now on.
     */
    private void waitForAnswers(long until) {
        String colour = Lights.colourCalibration, strip = Lights.stripCalibration;
        if ((colour == null || stripShown && strip == null) && SystemClock.elapsedRealtime() < until) {
            main.postDelayed(() -> waitForAnswers(until), 200);
            return;
        }
        String colours;
        if (colour == null) {
            colours = "The phone didn't answer about the colours.";
        } else if (colour.startsWith("colour ")) {
            String[] p = colour.split(" ");
            colours = "Colours matched: the camera saw them " + p[1] + "° off on average; now " + p[2] + "°.";
        } else {
            colours = "Couldn't match the colours: " + colour.replaceFirst("^colourfail ", "");
        }
        String led;
        StripModel model;
        if (!stripShown) {
            led = "The light is off, so the strip's own colours weren't matched.";
        } else if (strip == null) {
            led = "The phone didn't answer about the strip.";
        } else if (strip.startsWith("strip ") && (model = Lights.matchStrip(this, strip)) != null) {
            double[] w = model.fullWhite();
            led = String.format(Locale.ROOT, "Strip matched. Lit together its LEDs dim each other (full white gave"
                    + " red %.0f%%, green %.0f%%, blue %.0f%% of each alone): colours are now mixed to make up for it."
                    + " A stronger power supply would make mixed colours brighter.", 100 * w[0], 100 * w[1], 100 * w[2]);
        } else {
            led = "Couldn't match the strip: " + strip.replaceFirst("^stripfail ", "");
        }
        show(0xFF000000, lined + "\n" + colours + "\n" + led);
        main.postDelayed(this::finish, 8000);
    }


    private void show(int background, String message) {
        root.setBackgroundColor(background);
        text.setText(message);
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
                Log.w("lights", "calibrate: " + e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        Lights.test(-1);
        Lights.quiet(false);
        main.removeCallbacksAndMessages(null);
        net.shutdown();
        super.onDestroy();
    }
}

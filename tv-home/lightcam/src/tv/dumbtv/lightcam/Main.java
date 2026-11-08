package tv.dumbtv.lightcam;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Points the phone's back camera at the TV and sends the picture's colour to the TV's home app,
 * which drives the light strip with it: "lightcam r g b beat level groove" over UDP, where r g b is the
 * colour at full brightness, level (0..100) how bright the picture is, beat the strength of the
 * strobe's flash that is on (100 bass, 75 mids, 55 highs), 0 between, or -1 with no sound to
 * strobe to, and groove (0..100, or -1) how much is going on in the sound for the gradual mode
 * ({@link Pulse}, {@link Groove}). The TV decides what to
 * make of them in each lighting mode.
 * The TV itself can't see its own picture: the streaming apps' video is DRM-protected.
 *
 * Lining up: the TV's Options › "Line up the light camera" shows full white, then full black,
 * and tells the phone which is on screen ("calib white", "calib black", "calib done" on UDP
 * {@link #COMMANDS}). Whatever changed between the two is the screen, at any angle; it is saved in
 * files/mask.bin. While the screen is white the exposure is set so white reads well below clipping,
 * where the camera's readings keep in step with the light. Then patches of pure colour on the
 * screen ("cpatch i rrggbb", "colour done") match the camera's colours to the TV's, and the strip's
 * own colours on the wall around it ("spatch i rrggbb", "strip done", answered "lightcam strip …")
 * let the TV make up for how its LEDs dim each other.
 *
 * Settings in files/lightcam.conf (key=value; lining up rewrites all but tv):
 *   tv=192.168.1.20      the TV's address (deploy.sh writes it)
 *   exposure_us, iso     fixed exposure, so a dark scene stays dark
 *   black, white         the screen's camera brightness at black and at white
 *   camera=0             which back camera (0 is the main one; the phone logs the others)
 *   crop=l,t,r,b         used only before the first lining up (0..1)
 *   bass_hz=250          the top of the bass band; the mids run from there to 2 kHz, the highs above
 *   groove_min=3, groove_full=18, groove_release=0.85, groove_loud=0.25: the gradual mode's
 *                        response (dB of rise that starts to show and that shows fully, how fast the
 *                        glow eases back per 20 ms, and how much loudness alone can light)
 *   colour_m, colour_black: how this camera, at its angle, sees the TV's colours (a 3x3 matrix in
 *                        linear light, and the black it adds), set by the colour patches at the end
 *                        of lining up; picture colours are corrected with it
 *   b_ratio, b_floor, b_peak (and m_, h_ for mids and highs): each band's hit thresholds, set by
 *                        lining up with sound (the TV's light menu); how sharply it must jump over
 *                        the last 60 ms, how far over the room's noise, and how close to the loudest
 *   vivid=2.0            saturation boost (1 = as the camera sees it)
 * files/frame.jpg is the latest camera frame, rewritten every five seconds, with everything
 * outside the screen darkened.
 *
 * "snap" from the TV: a full-resolution photo of the screen, read by Gemini for the name of what
 * is playing ({@link Snap}), answered as "lightcam title <name or NONE>". gemini_model= in
 * lightcam.conf picks the model; the key is files/gemini.key.
 *
 * The screen stays on but black (an OLED pixel that is black is off) at the lowest brightness;
 * only a small, dim status line moves around on it. The phone runs off the TV's USB port: when
 * the power goes (TV off) the camera stops and the screen may sleep; {@link Power} wakes it all
 * again when the power comes back.
 */
public final class Main extends Activity {
    private static final String TAG = "lightcam";
    private static final int PORT = 5578;
    private static final int COMMANDS = 5579;
    private static final long SEND_MS = 50;
    private static final long SNAPSHOT_MS = 5000;
    private static final int NONE = 0, WHITE = 1, BLACK = 2;
    /** No frame for this long while the camera should be running: it is reopened. */
    private static final long STALL_MS = 5000;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    /** Full-resolution photos for reading titles off the screen; null if the camera can't add them. */
    private ImageReader stills;
    private final java.util.concurrent.ExecutorService asker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Looks for the TV on the Wi-Fi when it has gone quiet. */
    private final java.util.concurrent.ExecutorService finder = java.util.concurrent.Executors.newSingleThreadExecutor();
    private DatagramSocket socket;
    private DatagramSocket commands;
    private TextView status;
    private boolean resumed;
    private final Pulse pulse = new Pulse();
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            awake(Intent.ACTION_POWER_CONNECTED.equals(i.getAction()));
        }
    };

    // Everything below belongs to the camera thread.
    private final Map<String, String> conf = new LinkedHashMap<>();
    private long confStamp = -1;
    private long confCheckedAt, sentAt, snapshotAt, statusAt;
    private int frames;
    private long exposureUs;
    private int iso;
    /** Whether the camera takes a tone curve of ours. */
    private boolean ownCurve;
    /** The screen's pixels, every other one each way, as x | y << 16; null before lining up. */
    private int[] mask;
    private int maskW, maskH, maskPct;
    /** The screen's outline's bounding box, in the same pixels as the mask. */
    private Rect maskBox;
    /** Each of the mask's pixels' share of the screen ({@link #shares}). */
    private float[] area;
    /** A photo is being taken or read; since when. */
    private boolean snapping;
    private long snapSince;
    private int calib = NONE;
    /** When the TV said its test rhythm was starting; 0 when not lining up with sound. */
    private long soundStart;
    /** Matching colours: the patch the TV is showing (-1: not matching), since when, and each patch's camera sums. */
    private int cpatch = -1, cpatches;
    private long cpatchAt;
    private final double[][] csum = new double[24][3];
    private final int[] ccount = new int[24], ctarget = new int[24];
    /** How the camera's colours map to the TV's (linear light, 3x3, row by row), and the black it adds; null: as seen. */
    private double[] colourM, colourBlack;
    /** The other way: how the camera sees the TV's colours, from the last matching; null before it. */
    private double[] colourC;
    /** Matching the strip: the colour it shows (-1: not matching), since when, and each colour's sums over the wall. */
    private int spatch = -1;
    private long spatchAt;
    private int[] wall;
    private final double[][] wsum = new double[20][];
    private final int[] wcount = new int[20], wtarget = new int[20];
    private static final double[] LIN = new double[256];

    static {
        for (int i = 0; i < 256; i++) LIN[i] = lin(i / 255.0);
    }
    /** The groove last sent to the TV, for the status line. */
    private float lastGroove = -1f;
    private int calibFrames, whiteCount, blackCount;
    private float[] whiteSum, blackSum;
    private InetAddress tvAddress;
    /** When the TV last said "here", and when the phone last asked around for it. */
    private long tvHeardAt, askedAt;
    private boolean lost;
    /** Silent this long, the TV is looked for: its address may have changed. */
    private static final long LOST_MS = 15_000, ASK_MS = 5000;
    /** The camera in use, and the camera= setting it was opened for. */
    private String cameraId, openedFor;
    /** Whether the camera should run, and whether an open is on its way. */
    private boolean wanted, opening;
    /** Counts opens, so a late answer to an abandoned one closes its camera instead of using it. */
    private int generation;
    private long lastFrameAt;
    private final Runnable watchdog = this::watchdog;

    /** Whether the camera screen is in front: {@link Power}'s watchdog brings it back when not. */
    static volatile boolean showing;
    private static boolean crashHandler;
    /**
     * The TV's USB port drops power for a moment now and then while the TV is on; only power gone
     * for this long means the TV is off and the camera may sleep.
     */
    private static final long GRACE_MS = 3 * 60_000L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable sleep = this::sleep;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Shown over the (swipe-only) lock screen, so the camera can start when Power wakes the phone.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        status = new TextView(this);
        status.setTextColor(0xFF505050);
        status.setTextSize(12);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // One camera thread for the activity's whole life: waking over the lock screen pauses and
        // resumes it at once, and a thread per resume left the camera's callbacks on a dead one.
        thread = new HandlerThread("lightcam");
        thread.start();
        handler = new Handler(thread.getLooper());
        listen();
        // A crash relaunches the camera a few seconds later; the watchdog checks every two minutes.
        if (!crashHandler) {
            crashHandler = true;
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Context app = getApplicationContext();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                Power.restartSoon(app);
                if (previous != null) previous.uncaughtException(t, e);
            });
        }
        Power.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        showing = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, f);
        awake(Power.plugged(this));
    }

    /**
     * On power: screen kept on and the camera running. Power gone: it carries on for
     * {@link #GRACE_MS} (the TV's USB port blips), then the camera stops and the screen may sleep.
     */
    private void awake(boolean on) {
        ui.removeCallbacks(sleep);
        if (!resumed) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(this::open);
        if (on) {
            Log.i(TAG, "on power: camera on");
        } else {
            Log.i(TAG, "no power: carrying on for " + GRACE_MS / 60_000 + " min");
            ui.postDelayed(sleep, GRACE_MS);
        }
    }

    /** Still no power: the TV is off. Camera off until Power hears the power come back. */
    private void sleep() {
        if (Power.plugged(this)) return;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(this::close);
        status.setText("No power (TV off): camera off. It starts again with the TV.");
        Log.i(TAG, "no power for " + GRACE_MS / 60_000 + " min: camera off");
    }

    @Override
    protected void onPause() {
        resumed = false;
        showing = false;
        ui.removeCallbacks(sleep);
        unregisterReceiver(power);
        handler.post(this::close);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (commands != null) commands.close();
        handler.post(this::close);
        thread.quitSafely();
        super.onDestroy();
    }

    /** Commands from the TV while it lines the camera up. */
    private void listen() {
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(COMMANDS)) {
                commands = s;
                byte[] buf = new byte[64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (true) {
                    s.receive(p);
                    String cmd = new String(buf, 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                    InetAddress from = p.getAddress();
                    handler.post(() -> command(cmd, from));
                }
            } catch (IOException e) {
                Log.i(TAG, "commands closed: " + e.getMessage());
            }
        }, "lightcam-commands");
        t.setDaemon(true);
        t.start();
    }

    private void open() {
        wanted = true;
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, STALL_MS);
        if (camera != null || opening) return;
        if (reader != null) reader.close();
        if (socket != null) socket.close();
        readConf();
        pulse.start();
        try {
            socket = new DatagramSocket();
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            // The first back camera (the main one), or the one named by camera= in lightcam.conf.
            String want = conf.get("camera");
            String id = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(c);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                float[] focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                Log.i(TAG, "camera " + c + (back ? " back" : " front") + ", focal length "
                        + (focal == null || focal.length == 0 ? "?" : focal[0] + " mm"));
                if (back && (id == null || c.equals(want))) id = c;
            }
            // Phones hide some cameras (ultra-wide, telephoto) from the list; asked for by number, some
            // still open.
            if (want != null && !want.equals(id)) {
                try {
                    Integer facing = cm.getCameraCharacteristics(want).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) id = want;
                    Log.i(TAG, "unlisted camera " + want + " available: " + want.equals(id));
                } catch (IllegalArgumentException | CameraAccessException e) {
                    Log.i(TAG, "camera " + want + " not available: " + e.getMessage());
                }
            }
            cameraId = id;
            openedFor = want;
            if (id == null) {
                show("no back camera");
                return;
            }
            CameraCharacteristics chosen = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = chosen.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            ownCurve = takesCurve(chosen);
            Size size = smallest(map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    frame(img);
                } finally {
                    img.close();
                }
            }, handler);
            Size still = stillSize(map.getOutputSizes(ImageFormat.JPEG), size);
            stills = still == null ? null : ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, 1);
            if (stills != null) stills.setOnImageAvailableListener(this::still, handler);
            loadMask(size.getWidth(), size.getHeight());
            Log.i(TAG, "camera " + id + " at " + size + (mask == null ? ", not lined up" : ", screen " + maskPct + "%"));
            lastFrameAt = SystemClock.elapsedRealtime();
            opening = true;
            int gen = ++generation;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (gen != generation) { // given up on: the watchdog opened another
                        d.close();
                        return;
                    }
                    opening = false;
                    if (!wanted) { // closed while it was opening
                        d.close();
                        return;
                    }
                    camera = d;
                    start();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    if (gen != generation) {
                        d.close();
                        return;
                    }
                    opening = false;
                    d.close();
                    if (camera == d) camera = null;
                    show("camera taken by another app; trying again");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    if (gen != generation) {
                        d.close();
                        return;
                    }
                    opening = false;
                    d.close();
                    if (camera == d) camera = null;
                    show("camera error " + error + "; trying again");
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IOException e) {
            Log.w(TAG, "camera", e);
            show("no camera: " + e.getMessage());
        }
    }

    private void start() {
        try {
            camera.createCaptureSession(stills == null ? Collections.singletonList(reader.getSurface())
                            : Arrays.asList(reader.getSurface(), stills.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            repeat();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            if (stills != null) { // the colours matter more than the photos: without them
                                Log.w(TAG, "camera can't add full-resolution photos; going on without");
                                stills.close();
                                stills = null;
                                start();
                                return;
                            }
                            show("camera setup failed");
                        }
                    }, handler);
        } catch (CameraAccessException e) {
            show("camera: " + e.getMessage());
        }
    }

    /** (Re)starts the capture with the current exposure (automatic when it is 0). */
    private void repeat() {
        if (camera == null || session == null) return;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            if (exposureUs > 0) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureUs * 1000L);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L);
            }
            // The TV's white is close to daylight; a fixed balance keeps colours from drifting.
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
            // The camera's own tone curve lifts the darks and the middle far above the light (its
            // grey of a fifth of white's light read as two thirds), so every half-strength colour came
            // out nearly full. This camera already encodes its picture much like sRGB before the
            // curve: a straight one leaves its readings in step with the light, as sRGB (a quarter
            // of the exposure reads a quarter of the light, give or take 10 %, below about 120).
            if (ownCurve) {
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                b.set(CaptureRequest.TONEMAP_CURVE, STRAIGHT);
            }
            session.setRepeatingRequest(b.build(), null, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            show("camera: " + e.getMessage());
        }
    }

    private static final TonemapCurve STRAIGHT = new TonemapCurve(new float[] {0, 0, 1, 1}, new float[] {0, 0, 1, 1},
            new float[] {0, 0, 1, 1});

    private static boolean takesCurve(CameraCharacteristics ch) {
        int[] modes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        if (modes != null) for (int m : modes) if (m == CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE) return true;
        return false;
    }

    /** Reopens the camera when it was taken away or stopped sending frames. */
    private void watchdog() {
        if (!wanted) return;
        handler.postDelayed(watchdog, STALL_MS);
        if (SystemClock.elapsedRealtime() - lastFrameAt < STALL_MS) return;
        Log.w(TAG, "no frames: reopening the camera");
        release();
        open();
    }

    private void close() {
        wanted = false;
        handler.removeCallbacks(watchdog);
        release();
    }

    private void release() {
        pulse.stop();
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        if (stills != null) stills.close();
        if (socket != null) socket.close();
        session = null;
        camera = null;
        reader = null;
        stills = null;
        snapping = false;
        socket = null;
        calib = NONE;
        opening = false;
        generation++;
    }

    private void frame(Image img) {
        long now = SystemClock.elapsedRealtime();
        lastFrameAt = now;
        frames++;
        if (now - tvHeardAt > LOST_MS && now - askedAt > ASK_MS) {
            askedAt = now;
            askAround();
        }
        if (calib != NONE) {
            calibrate(img);
            return;
        }
        if (now - confCheckedAt > 3000) {
            confCheckedAt = now;
            if (readConf()) {
                String want = conf.get("camera");
                if (want == null ? openedFor != null : !want.equals(openedFor)) {
                    handler.post(() -> { // another camera: reopen, outside this frame's callback
                        release();
                        open();
                    });
                } else {
                    repeat();
                }
            }
        }
        if (now - snapshotAt > SNAPSHOT_MS) {
            snapshotAt = now;
            snapshot(img);
        }
        if (cpatch >= 0) measurePatch(img, now);
        if (spatch >= 0) measureWall(img, now);
        if (now - sentAt < SEND_MS) return;
        sentAt = now;
        colour(img, now);
    }

    /**
     * The colour of the TV's screen: a mean weighted towards vivid pixels (a plain mean of a film
     * frame is a muddy grey), at a brightness from the screen's mean light. Each pixel counts by how
     * much of the screen it shows: the phone films the TV at a steep angle, so a pixel on the far
     * side shows some five times as much of the picture as one on the near side.
     */
    private void colour(Image img, long now) {
        int w = img.getWidth(), h = img.getHeight();
        boolean lined = mask != null && maskW == w && maskH == h;
        int[] samples = lined ? mask : cropSamples(w, h);
        float[] share = lined ? area : null;
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        double sr = 0, sg = 0, sb = 0, sw = 0, light = 0, n = 0;
        for (int k = 0; k < samples.length; k++) {
            int x = samples[k] & 0xFFFF, y = samples[k] >>> 16;
            int Y = yb.get(y * yRow + x) & 0xFF;
            int uv = (y / 2) * uvRow + (x / 2) * uvPix;
            int U = (ub.get(uv) & 0xFF) - 128, V = (vb.get(uv) & 0xFF) - 128;
            int r = clamp(Y + 1.402 * V), g = clamp(Y - 0.344 * U - 0.714 * V), b = clamp(Y + 1.772 * U);
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            double sat = max == 0 ? 0 : (max - min) / (double) max;
            double a = share != null ? share[k] : 1;
            double weight = (0.04 + sat * sat * (max / 255.0)) * a;
            sr += weight * r;
            sg += weight * g;
            sb += weight * b;
            sw += weight;
            light += Y * a;
            n += a;
        }
        if (n == 0 || sw == 0) return;
        double mr = sr / sw, mg = sg / sw, mb = sb / sw;
        double[] cm = colourM, k0 = colourBlack;
        if (cm != null && k0 != null) { // as the TV showed it, not as this camera at this angle saw it
            double lr = Math.max(0, lin(mr / 255) - k0[0]), lg = Math.max(0, lin(mg / 255) - k0[1]),
                    lb = Math.max(0, lin(mb / 255) - k0[2]);
            mr = 255 * gam(Math.max(0, cm[0] * lr + cm[1] * lg + cm[2] * lb));
            mg = 255 * gam(Math.max(0, cm[3] * lr + cm[4] * lg + cm[5] * lb));
            mb = 255 * gam(Math.max(0, cm[6] * lr + cm[7] * lg + cm[8] * lb));
        }
        double top = Math.max(1, Math.max(mr, Math.max(mg, mb)));
        double r = mr / top, g = mg / top, b = mb / top; // brightest channel at 1
        // More vivid: saturation times `vivid`, hue kept. A grey stays grey; a tint becomes a colour.
        double sat = 1 - Math.min(r, Math.min(g, b));
        if (sat > 0) {
            double k = Math.min(1, sat * num("vivid", 2.0)) / sat;
            r = 1 - (1 - r) * k;
            g = 1 - (1 - g) * k;
            b = 1 - (1 - b) * k;
        }
        double black = num("black", 6), white = num("white", 150);
        double level = Math.max(0, Math.min(1, (light / n - black) / Math.max(1, white - black)));
        int R = (int) (r * 255), G = (int) (g * 255), B = (int) (b * 255);
        float beat = pulse.takeBeat();
        float groove = pulse.takeGroove();
        lastGroove = groove;
        send(PORT, "lightcam " + R + " " + G + " " + B + " " + (beat < 0 ? -1 : Math.round(beat * 100))
                + " " + Math.round(level * 100) + " " + (groove < 0 ? -1 : Math.round(groove * 100)));
        if (now - statusAt > 1000) {
            statusAt = now;
            int fps = frames;
            frames = 0;
            String text = String.format(Locale.ROOT, "→ %s   %d %d %d   %d fps   light %.0f   sound %.0f%% (%s)   groove %s   %s",
                    conf.get("tv") + (lost ? " (looking for the TV)" : ""), (int) (R * level), (int) (G * level), (int) (B * level), fps, light / n,
                    pulse.loudness() * 100, pulse.numbers(), lastGroove < 0 ? "-" : Math.round(lastGroove * 100) + "%",
                    mask == null ? "not lined up" : "screen " + maskPct + "%");
            status.post(() -> {
                status.setText(text);
                // Moves a little each minute so the line never burns into the OLED.
                long m = System.currentTimeMillis() / 60_000;
                status.setTranslationX((m % 7) * 40);
                status.setTranslationY((m % 5) * 30);
            });
        }
    }

    // ---- reading titles -----------------------------------------------------------------------

    /** The largest JPEG size up to 4096 wide with the colour frames' shape, so the mask lines up. */
    private static Size stillSize(Size[] sizes, Size frame) {
        if (sizes == null) return null;
        float want = frame.getWidth() / (float) frame.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 4096 || Math.abs(s.getWidth() / (float) s.getHeight() - want) > 0.02f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        return best;
    }

    /** One full-resolution photo of the screen, with the colours' exposure; read in {@link #still}. */
    private void snap() {
        long now = SystemClock.elapsedRealtime();
        if (snapping && now - snapSince < 20_000) return; // one at a time; a lost one is given up on
        if (stills == null || session == null || camera == null) {
            send(PORT, "lightcam title NONE");
            return;
        }
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(stills.getSurface());
            if (exposureUs > 0) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureUs * 1000L);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            }
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
            b.set(CaptureRequest.JPEG_ORIENTATION, 0); // the colour frames' way up, which the mask is in
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            snapping = true;
            snapSince = now;
            session.capture(b.build(), null, handler);
            Log.i(TAG, "snap: taking a photo of the screen");
        } catch (CameraAccessException | IllegalStateException e) {
            snapping = false;
            Log.w(TAG, "snap: " + e.getMessage());
        }
    }

    private void still(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        byte[] jpeg;
        int w = img.getWidth(), h = img.getHeight();
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            jpeg = new byte[buf.remaining()];
            buf.get(jpeg);
        } finally {
            img.close();
        }
        Rect screen = screenIn(w, h);
        File key = file("gemini.key"), keep = file("snap.jpg");
        String model = conf.containsKey("gemini_model") ? conf.get("gemini_model") : Snap.MODEL;
        asker.execute(() -> {
            String title = Snap.read(jpeg, screen, key, model, keep);
            String reply = title == null ? "NONE" : title.length() > 180 ? title.substring(0, 180) : title;
            handler.post(() -> {
                snapping = false;
                send(PORT, "lightcam title " + reply);
            });
        });
    }

    /** The screen in a photo of {@code w} by {@code h}, with a little room around it. */
    private Rect screenIn(int w, int h) {
        if (maskBox == null || maskW == 0 || maskH == 0) return new Rect(0, 0, w, h);
        float kx = w / (float) maskW, ky = h / (float) maskH;
        int mx = Math.round(w * 0.03f), my = Math.round(h * 0.03f);
        return new Rect(Math.max(0, Math.round(maskBox.left * kx) - mx), Math.max(0, Math.round(maskBox.top * ky) - my),
                Math.min(w, Math.round(maskBox.right * kx) + mx), Math.min(h, Math.round(maskBox.bottom * ky) + my));
    }

    // ---- lining up ---------------------------------------------------------------------------

    private void command(String cmd, InetAddress from) {
        if ("here".equals(cmd)) { // only the TV says it, so a command sent by hand doesn't move the colours
            heard(from);
            return;
        }
        if (reader == null) return;
        if (cmd.startsWith("cpatch ")) { // matching colours: the TV is showing patch i, colour rrggbb
            String[] p = cmd.split(" ");
            try {
                int i = Integer.parseInt(p[1]);
                if (i < 0 || i >= csum.length) return;
                if (i == 0 && cpatch != 0) {
                    for (double[] c : csum) Arrays.fill(c, 0);
                    Arrays.fill(ccount, 0);
                    cpatches = 0;
                }
                if (cpatch != i) cpatchAt = SystemClock.elapsedRealtime();
                cpatch = i;
                ctarget[i] = Integer.parseInt(p[2], 16);
                cpatches = Math.max(cpatches, i + 1);
                show("matching colours: " + (i + 1));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                Log.w(TAG, "bad colour patch: " + cmd);
            }
            return;
        }
        if ("colour done".equals(cmd)) {
            if (cpatch < 0) return; // a repeat of the command
            cpatch = -1;
            fitColours();
            return;
        }
        if (cmd.startsWith("spatch ")) { // matching the strip: it is showing rrggbb (LED power) on the wall
            String[] p = cmd.split(" ");
            try {
                int i = Integer.parseInt(p[1]);
                if (i < 0 || i >= wsum.length) return;
                if (i == 0 && spatch != 0) {
                    wall = wall(reader.getWidth(), reader.getHeight());
                    for (int j = 0; j < wsum.length; j++) wsum[j] = new double[3 * wall.length];
                    Arrays.fill(wcount, 0);
                }
                if (spatch != i) spatchAt = SystemClock.elapsedRealtime();
                spatch = i;
                wtarget[i] = Integer.parseInt(p[2], 16);
                show("matching the strip: " + (i + 1));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                Log.w(TAG, "bad strip patch: " + cmd);
            }
            return;
        }
        if ("strip done".equals(cmd)) {
            if (spatch < 0) return; // a repeat of the command
            spatch = -1;
            fitStrip();
            return;
        }
        int w = reader.getWidth(), h = reader.getHeight();
        switch (cmd) {
            case "calib white":
                calib = WHITE;
                colourM = colourBlack = colourC = null; // matched to the old exposure; the patches after this match again
                conf.remove("colour_m");
                conf.remove("colour_black");
                calibFrames = 0;
                whiteCount = 0;
                whiteSum = new float[w * h];
                if (exposureUs <= 0) exposureUs = 8000;
                repeat();
                show("lining up: white");
                break;
            case "calib black":
                if (calib != WHITE) return;
                calib = BLACK;
                calibFrames = 0;
                blackCount = 0;
                blackSum = new float[w * h];
                show("lining up: black");
                break;
            case "rec start": // recording real sound, for tuning the groove by hand
                pulse.recordMusic();
                show("recording the sound's levels");
                break;
            case "rec done":
                saveMusic();
                break;
            case "scal start": // lining up with sound: the TV is about to play its test rhythm
                soundStart = SystemClock.elapsedRealtime();
                pulse.recordStart();
                show("lining up with sound: listening");
                break;
            case "scal done":
                if (soundStart == 0) return; // a repeat of the command
                tuneSound();
                break;
            case "snap": // the TV asks what's playing (Continue watching)
                snap();
                break;
            case "calib done":
                if (calib != BLACK) return; // a repeat of the command
                calib = NONE;
                finishCalibration(w, h);
                break;
            default:
                Log.w(TAG, "unknown command " + cmd);
        }
    }

    /**
     * White: exposure settles so the brightest of the screen's white reads 100 to 140 (of 255), where
     * the camera still reads in step with the light, then frames add up.
     */
    private void calibrate(Image img) {
        calibFrames++;
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer yb = img.getPlanes()[0].getBuffer();
        int yRow = img.getPlanes()[0].getRowStride();
        if (calib == WHITE) {
            if (calibFrames < 4) return; // the last exposure change takes a few frames to show
            int[] hist = new int[256];
            for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) hist[yb.get(y * yRow + x) & 0xFF]++;
            int bright = percentile(hist, 0.98);
            // Longer exposure first (up to one frame), then more sensitivity.
            long next = exposureUs;
            int nextIso = iso;
            if (bright > 140 && iso > 100) nextIso = Math.max(100, iso * 2 / 3);
            else if (bright > 140 && exposureUs > 200) next = exposureUs * 6 / 10;
            else if (bright < 100 && exposureUs < 30_000) next = Math.min(30_000, exposureUs * 3 / 2);
            else if (bright < 100 && iso < 1600) nextIso = Math.min(1600, iso * 3 / 2);
            if ((next != exposureUs || nextIso != iso) && whiteCount == 0) {
                Log.i(TAG, "lining up: white at " + bright + " with " + exposureUs + " µs, iso " + iso);
                exposureUs = next;
                iso = nextIso;
                calibFrames = 0;
                repeat();
                return;
            }
            add(whiteSum, yb, yRow, w, h);
            whiteCount++;
        } else if (calib == BLACK) {
            if (calibFrames < 6) return; // the TV is still changing over
            add(blackSum, yb, yRow, w, h);
            blackCount++;
        }
    }

    private void finishCalibration(int w, int h) {
        if (whiteCount == 0 || blackCount == 0) {
            fail("not enough frames (white " + whiteCount + ", black " + blackCount + ")");
            return;
        }
        float[] diff = new float[w * h];
        int[] hist = new int[256];
        for (int i = 0; i < diff.length; i++) {
            diff[i] = whiteSum[i] / whiteCount - blackSum[i] / blackCount;
            hist[Math.max(0, Math.min(255, (int) diff[i]))]++;
        }
        float cut = Math.max(20, 0.45f * percentile(hist, 0.99));
        Log.i(TAG, "lining up: " + whiteCount + " white and " + blackCount + " black frames at " + exposureUs + " µs, iso " + iso
                + "; difference p50 " + percentile(hist, 0.5) + ", p90 " + percentile(hist, 0.9) + ", p99 " + percentile(hist, 0.99));
        boolean[] in = new boolean[w * h];
        for (int i = 0; i < diff.length; i++) in[i] = diff[i] > cut;
        // Shrink by four pixels, so the bezel, the screen's blurred edge and the strip's LEDs, which
        // shine just beside it, stay out.
        for (int pass = 0; pass < 4; pass++) {
            boolean[] next = new boolean[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    next[i] = in[i] && in[i - 1] && in[i + 1] && in[i - w] && in[i + w];
                }
            }
            in = next;
        }
        int count = 0;
        double white = 0, black = 0;
        for (int i = 0; i < in.length; i++) {
            if (!in[i]) continue;
            count++;
            white += whiteSum[i] / whiteCount;
            black += blackSum[i] / blackCount;
        }
        int pct = Math.round(100f * count / in.length);
        if (count < in.length / 200) {
            fail("the screen wasn't found (is the camera facing the TV?)");
            return;
        }
        saveMask(in, w, h);
        loadMask(w, h);
        if (mask != null && area != null) { // as the colours are read: each pixel by its share of the screen
            double sum = 0;
            white = black = 0;
            for (int k = 0; k < mask.length; k++) {
                int i = (mask[k] >>> 16) * w + (mask[k] & 0xFFFF);
                white += area[k] * whiteSum[i] / whiteCount;
                black += area[k] * blackSum[i] / blackCount;
                sum += area[k];
            }
            white /= sum;
            black /= sum;
        } else {
            white /= count;
            black /= count;
        }
        conf.remove("crop");
        conf.put("exposure_us", Long.toString(exposureUs));
        conf.put("iso", Integer.toString(iso));
        conf.put("black", String.format(Locale.ROOT, "%.0f", black + 2));
        conf.put("white", String.format(Locale.ROOT, "%.0f", black + (white - black) * 0.85));
        writeConf();
        whiteSum = blackSum = null;
        repeat();
        String done = "lined up: screen is " + pct + "% of the view, exposure " + exposureUs + " µs, white "
                + Math.round(white) + ", black " + Math.round(black);
        show(done);
        send(PORT, "lightcam calibrated " + pct);
    }

    /** Adds this frame's plain average over the screen to the patch on show, once the TV has settled on it. */
    private void measurePatch(Image img, long now) {
        long since = now - cpatchAt;
        if (since < 400 || since > 1100) return;
        int w = img.getWidth(), h = img.getHeight();
        int[] samples = mask != null && maskW == w && maskH == h ? mask : cropSamples(w, h);
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        double r = 0, g = 0, b = 0;
        for (int s : samples) {
            int x = s & 0xFFFF, y = s >>> 16;
            int Y = yb.get(y * yRow + x) & 0xFF;
            int uv = (y / 2) * uvRow + (x / 2) * uvPix;
            int U = (ub.get(uv) & 0xFF) - 128, V = (vb.get(uv) & 0xFF) - 128;
            r += clamp(Y + 1.402 * V);
            g += clamp(Y - 0.344 * U - 0.714 * V);
            b += clamp(Y + 1.772 * U);
        }
        int n = Math.max(1, samples.length);
        csum[cpatch][0] += r / n;
        csum[cpatch][1] += g / n;
        csum[cpatch][2] += b / n;
        ccount[cpatch]++;
    }

    /**
     * The patches have all shown. How the camera sees each of the TV's three colours of light, from
     * its own patch, in linear light after taking away the black the camera sees with the screen
     * black: C, a 3x3 matrix. Its inverse turns what the camera sees back into the TV's colour, for
     * every colour from now on (colour_m, colour_black). Only colours that are all or nothing in each
     * of red, green and blue are shown: this TV brightens a dim full-screen colour by itself (a
     * full-screen grey of 1 % of white's light came out at 20 %), so a half-strength patch isn't
     * what its number says. Two of its colours together add up (yellow is its red and green), but a
     * full-screen white is dimmed, so the white is only checked against the three, not matched.
     */
    private void fitColours() {
        double[] black = null;
        double[][] seen = new double[cpatches][];
        for (int i = 0; i < cpatches; i++) {
            if (ccount[i] == 0) continue;
            Log.i(TAG, String.format(Locale.ROOT, "patch %06x: camera %.1f %.1f %.1f (%d frames)", ctarget[i],
                    csum[i][0] / ccount[i], csum[i][1] / ccount[i], csum[i][2] / ccount[i], ccount[i]));
            double[] l = new double[3];
            for (int c = 0; c < 3; c++) l[c] = lin(csum[i][c] / ccount[i] / 255);
            if (ctarget[i] == 0) black = l;
            else seen[i] = l;
        }
        double[] r = patch(seen, 0xFF0000), g = patch(seen, 0x00FF00), b = patch(seen, 0x0000FF), w = patch(seen, 0xFFFFFF);
        if (black == null || r == null || g == null || b == null || w == null) {
            send(PORT, "lightcam colourfail black, white, red, green or blue wasn't seen");
            return;
        }
        for (double[] l : seen) if (l != null) for (int c = 0; c < 3; c++) l[c] = Math.max(0, l[c] - black[c]);
        double[] primaries = {r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]};
        double[] inv = inverse(primaries);
        if (inv == null) {
            send(PORT, "lightcam colourfail the colours couldn't be told apart");
            return;
        }
        double[] k = times(inv, w);
        Log.i(TAG, String.format(Locale.ROOT, "colour: the TV's full-screen white is %.2f of its red, %.2f of its green, %.2f of its blue",
                k[0], k[1], k[2]));
        double[] cm = primaries, m = inv;
        // How far off the other colours' hues were, and are now.
        double before = 0, after = 0;
        int counted = 0;
        for (int i = 0; i < cpatches; i++) {
            int t = ctarget[i];
            if (seen[i] == null || t == 0xFF0000 || t == 0x00FF00 || t == 0x0000FF || t == 0xFFFFFF) continue;
            double[] shown = {lin((t >> 16 & 0xFF) / 255.0), lin((t >> 8 & 0xFF) / 255.0), lin((t & 0xFF) / 255.0)};
            double want = hue(shown), was = hue(seen[i]), now = hue(times(m, seen[i]));
            before += angle(want, was);
            after += angle(want, now);
            counted++;
            Log.i(TAG, String.format(Locale.ROOT, "colour %06x: camera saw hue %.0f, now %.0f, wanted %.0f", t, was, now, want));
        }
        before /= Math.max(1, counted);
        after /= Math.max(1, counted);
        StringBuilder ms = new StringBuilder();
        for (double v : m) ms.append(ms.length() == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.4f", v));
        conf.put("colour_m", ms.toString());
        conf.put("colour_black", String.format(Locale.ROOT, "%.5f,%.5f,%.5f", black[0], black[1], black[2]));
        writeConf();
        colourC = cm;
        colourM = m;
        colourBlack = black;
        show(String.format(Locale.ROOT, "colours matched: hues %.0f° off before, %.0f° now", before, after));
        send(PORT, String.format(Locale.ROOT, "lightcam colour %.0f %.0f", before, after));
    }

    private double[] patch(double[][] seen, int rgb) {
        for (int i = 0; i < cpatches; i++) if (seen[i] != null && ctarget[i] == rgb) return seen[i];
        return null;
    }

    // ---- matching the strip ------------------------------------------------------------------

    /**
     * The wall around the screen, where the strip glows: the mask's grid (every other pixel each
     * way) outside the screen and at least 8 pixels from it.
     */
    private int[] wall(int w, int h) {
        if (mask == null || maskW != w || maskH != h) return new int[0];
        int gw = w / 2, gh = h / 2, reach = 4;
        boolean[] near = new boolean[gw * gh];
        for (int s : mask) {
            int gx = (s & 0xFFFF) / 2, gy = (s >>> 16) / 2;
            for (int y = Math.max(0, gy - reach); y <= Math.min(gh - 1, gy + reach); y++) {
                for (int x = Math.max(0, gx - reach); x <= Math.min(gw - 1, gx + reach); x++) near[y * gw + x] = true;
            }
        }
        int n = 0;
        for (boolean b : near) if (!b) n++;
        int[] out = new int[n];
        for (int i = 0, k = 0; i < near.length; i++) if (!near[i]) out[k++] = (i % gw) * 2 | (i / gw) * 2 << 16;
        return out;
    }

    /** Adds this frame's linear light, pixel by pixel over the wall, once the strip has faded to the colour on show. */
    private void measureWall(Image img, long now) {
        long since = now - spatchAt;
        if (since < 1100 || since > 2000 || wall == null || wall.length == 0) return;
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        double[] sum = wsum[spatch];
        for (int k = 0; k < wall.length; k++) {
            int x = wall[k] & 0xFFFF, y = wall[k] >>> 16;
            int Y = yb.get(y * yRow + x) & 0xFF;
            int uv = (y / 2) * uvRow + (x / 2) * uvPix;
            int U = (ub.get(uv) & 0xFF) - 128, V = (vb.get(uv) & 0xFF) - 128;
            sum[3 * k] += LIN[clamp(Y + 1.402 * V)];
            sum[3 * k + 1] += LIN[clamp(Y - 0.344 * U - 0.714 * V)];
            sum[3 * k + 2] += LIN[clamp(Y + 1.772 * U)];
        }
        wcount[spatch]++;
    }

    /**
     * The strip has shown its colours (the TV's Calibrate.STRIP), and the camera has filmed the wall
     * around the screen for each: where the strip's white lights the wall most and nothing clipped,
     * with the wall as it is with the strip off taken away. Each LED at half power alone sets how the
     * camera sees its light (S: its controller is still in step with the power there); every colour
     * is then told apart into each LED's light, in those units (1: the LED alone at full, were it
     * still in step). The TV answers for how much the LEDs dim each other from those, and drives the
     * strip with S⁻¹C times a colour's linear light (C: how the camera sees the TV's colours), so
     * the wall glows in the colour the screen shows, as this camera sees both.
     */
    private void fitStrip() {
        int off = wpatch(0x000000), w = wpatch(0xFFFFFF);
        int[] half = {wpatch(0x800000), wpatch(0x008000), wpatch(0x000080)};
        if (wall == null || wall.length == 0) {
            send(PORT, "lightcam stripfail the camera isn't lined up");
            return;
        }
        if (off < 0 || w < 0 || half[0] < 0 || half[1] < 0 || half[2] < 0) {
            send(PORT, "lightcam stripfail the strip's colours weren't all seen");
            return;
        }
        int n = wall.length;
        double top = LIN[250];
        double[] lift = new double[n];
        for (int k = 0; k < n; k++) {
            boolean clipped = false;
            for (int i = 0; i < wtarget.length; i++) {
                for (int c = 0; c < 3 && wcount[i] > 0; c++) clipped |= wsum[i][3 * k + c] / wcount[i] > top;
            }
            double lit = 0;
            for (int c = 0; c < 3; c++) lit += wsum[w][3 * k + c] / wcount[w] - wsum[off][3 * k + c] / wcount[off];
            lift[k] = clipped ? -1 : lit;
        }
        double[] sorted = lift.clone();
        Arrays.sort(sorted);
        double cut = Math.max(1e-9, sorted[(int) (n * 0.7)]); // the most brightly lit 30 %
        double[][] seen = new double[wtarget.length][];
        int used = 0;
        for (int k = 0; k < n; k++) if (lift[k] >= cut) used++;
        for (int i = 0; i < wtarget.length; i++) {
            if (wcount[i] == 0 || i == off) continue;
            double[] v = new double[3];
            for (int k = 0; k < n; k++) {
                if (lift[k] < cut) continue;
                for (int c = 0; c < 3; c++) v[c] += wsum[i][3 * k + c] / wcount[i] - wsum[off][3 * k + c] / wcount[off];
            }
            for (int c = 0; c < 3; c++) v[c] /= Math.max(1, used);
            seen[i] = v;
        }
        double white = seen[w][0] + seen[w][1] + seen[w][2];
        if (used < 50 || white < 0.005) {
            send(PORT, "lightcam stripfail its glow on the wall was too faint for the camera");
            return;
        }
        double[] sm = new double[9];
        for (int j = 0; j < 3; j++) {
            double duty = 0x80 / 255.0;
            for (int c = 0; c < 3; c++) sm[c * 3 + j] = seen[half[j]][c] / duty;
        }
        double[] si = inverse(sm);
        if (si == null) {
            send(PORT, "lightcam stripfail its colours couldn't be told apart");
            return;
        }
        double[] km = colourC != null ? product(si, colourC) : si;
        double[] kw = times(km, new double[] {1, 1, 1});
        double most = Math.max(kw[0], Math.max(kw[1], kw[2]));
        if (most <= 0) {
            send(PORT, "lightcam stripfail its colours couldn't be told apart");
            return;
        }
        StringBuilder out = new StringBuilder("lightcam strip");
        for (double v : km) out.append(' ').append(String.format(Locale.ROOT, "%.4f", v / most));
        for (int i = 0; i < wtarget.length; i++) {
            if (seen[i] == null) continue;
            double[] led = times(si, seen[i]);
            Log.i(TAG, String.format(Locale.ROOT, "strip %06x: the LEDs give %.3f %.3f %.3f", wtarget[i], led[0], led[1], led[2]));
            out.append(String.format(Locale.ROOT, " %06x %.4f %.4f %.4f", wtarget[i], led[0], led[1], led[2]));
        }
        show("strip matched");
        send(PORT, out.toString());
    }

    private int wpatch(int rgb) {
        for (int i = 0; i < wtarget.length; i++) if (wcount[i] > 0 && wtarget[i] == rgb) return i;
        return -1;
    }

    /** 3x3 matrices, row by row: m times v, a times b, and the inverse (null if there is none). */
    private static double[] times(double[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private static double[] product(double[] a, double[] b) {
        double[] out = new double[9];
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int k = 0; k < 3; k++) out[x * 3 + y] += a[x * 3 + k] * b[k * 3 + y];
        return out;
    }

    private static double[] inverse(double[] m) {
        double[][] r = invert(new double[][] {{m[0], m[1], m[2]}, {m[3], m[4], m[5]}, {m[6], m[7], m[8]}});
        return r == null ? null : new double[] {r[0][0], r[0][1], r[0][2], r[1][0], r[1][1], r[1][2], r[2][0], r[2][1], r[2][2]};
    }

    /** The hue (0..360) of a colour, in degrees; the same in linear light or not. */
    private static double hue(double[] c) {
        double max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        if (max - min < 1e-9) return 0;
        double h = max == c[0] ? (c[1] - c[2]) / (max - min) : max == c[1] ? 2 + (c[2] - c[0]) / (max - min)
                : 4 + (c[0] - c[1]) / (max - min);
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    private static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** sRGB to linear light, and back. */
    private static double lin(double x) {
        x = Math.max(0, x);
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    private static double gam(double x) {
        return x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
    }

    private static double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(det) < 1e-12) return null;
        double[][] r = new double[3][3];
        r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return r;
    }

    /** The test rhythm has played: the thresholds that catch it best become the bands' own. */
    private void tuneSound() {
        long start = soundStart;
        soundStart = 0;
        Object[] rec = pulse.recordStop();
        saveRecording(start, (long[]) rec[0], (double[][]) rec[1], (int) rec[2]);
        SoundTune.Result r = SoundTune.fit((long[]) rec[0], (double[][]) rec[1], (int) rec[2], start);
        if (r.why != null) {
            show("lining up with sound failed: " + r.why);
            send(PORT, "lightcam scalfail " + r.why);
            return;
        }
        String[] prefix = {"b", "m", "h"};
        StringBuilder got = new StringBuilder();
        for (int b = 0; b < 3; b++) {
            conf.put(prefix[b] + "_ratio", String.format(Locale.ROOT, "%.2f", r.params[b][0]));
            conf.put(prefix[b] + "_floor", String.format(Locale.ROOT, "%.2f", r.params[b][1]));
            conf.put(prefix[b] + "_peak", String.format(Locale.ROOT, "%.2f", r.params[b][2]));
            got.append(b == 0 ? "" : " ").append(r.caught[b]).append('/').append(r.total[b]).append('/').append(r.wrong[b]);
        }
        writeConf();
        pulse.tune(r.params);
        for (int b = 0; b < 3; b++) {
            Log.i(TAG, String.format(Locale.ROOT, "sound %s: room %.0f, hits %.0f -> sharp %.2f, over room %.2f, of loudest %.2f; caught %d/%d, stray %d",
                    SoundTune.NAMES[b], r.room[b], r.level[b], r.params[b][0], r.params[b][1], r.params[b][2],
                    r.caught[b], r.total[b], r.wrong[b]));
        }
        show("lined up with sound: caught " + got + " (caught/played/stray) for kicks, snares, hi-hats; delay "
                + r.offset + " ms");
        send(PORT, "lightcam scal " + got);
    }

    /** files/music.csv: real sound recorded with "rec start"/"rec done", block by block, for tuning. */
    private void saveMusic() {
        Object[] rec = pulse.stopMusic();
        long[] t = (long[]) rec[0];
        double[][] v = (double[][]) rec[1];
        int n = (int) rec[2];
        File f = file("music.csv");
        if (f == null || n == 0) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("ms,60,120,250,500,1k,2k,4k,8k,flux,loud,glow,groove,beat\n");
            for (int i = 0; i < n; i++) {
                StringBuilder row = new StringBuilder().append(t[i] - t[0]);
                for (double x : v[i]) row.append(',').append(String.format(Locale.ROOT, "%.2f", x));
                w.write(row.append('\n').toString());
            }
        } catch (IOException e) {
            Log.w(TAG, "music.csv: " + e);
        }
        show("recorded " + n + " blocks of sound to music.csv");
    }

    /** files/sound.csv: the last lining-up's levels, block by block, for checking the tuning by hand. */
    private void saveRecording(long start, long[] t, double[][] lv, int n) {
        File f = file("sound.csv");
        if (f == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("# start " + start + "\nms,bass,mids,highs\n");
            for (int i = 0; i < n; i++) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f%n", t[i] - start, lv[0][i], lv[1][i], lv[2][i]));
            }
        } catch (IOException e) {
            Log.w(TAG, "sound.csv: " + e);
        }
    }

    private void fail(String why) {
        whiteSum = blackSum = null;
        if (readConfForce()) repeat();
        show("lining up failed: " + why);
        send(PORT, "lightcam failed " + why);
    }

    private static void add(float[] sum, ByteBuffer yb, int yRow, int w, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) sum[y * w + x] += yb.get(y * yRow + x) & 0xFF;
    }

    private static int percentile(int[] hist, double q) {
        int total = 0;
        for (int c : hist) total += c;
        int want = (int) (total * q), seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= want) return i;
        }
        return 255;
    }

    private File file(String name) {
        File dir = getExternalFilesDir(null);
        return dir == null ? null : new File(dir, name);
    }

    private void saveMask(boolean[] in, int w, int h) {
        File f = file(maskName());
        if (f == null) return;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.writeInt(w);
            out.writeInt(h);
            byte[] bits = new byte[in.length];
            for (int i = 0; i < in.length; i++) bits[i] = (byte) (in[i] ? 1 : 0);
            out.write(bits);
        } catch (IOException e) {
            Log.w(TAG, "mask.bin: " + e);
        }
    }

    /** Each camera sees the screen differently: one outline each (mask.bin for the main camera). */
    private String maskName() {
        return cameraId == null || "0".equals(cameraId) ? "mask.bin" : "mask-" + cameraId + ".bin";
    }

    private void loadMask(int w, int h) {
        mask = null;
        File f = file(maskName());
        if (f == null || !f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != w || in.readInt() != h) return; // a different camera size
            byte[] bits = new byte[w * h];
            in.readFully(bits);
            int count = 0, all = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (bits[y * w + x] == 0) continue;
                    all++;
                    if (x % 2 == 0 && y % 2 == 0) count++;
                }
            }
            int[] s = new int[count];
            int k = 0;
            for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) if (bits[y * w + x] != 0) s[k++] = x | y << 16;
            mask = s;
            maskW = w;
            maskH = h;
            int x0 = w, y0 = h, x1 = 0, y1 = 0;
            for (int v : s) {
                int x = v & 0xFFFF, y = v >>> 16;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x + 2);
                y1 = Math.max(y1, y + 2);
            }
            maskBox = s.length == 0 ? null : new Rect(x0, y0, Math.min(w, x1), Math.min(h, y1));
            maskPct = Math.round(100f * all / (w * h));
            area = shares(bits, w, s);
            if (area != null) {
                float[] sorted = area.clone();
                Arrays.sort(sorted);
                Log.i(TAG, String.format(Locale.ROOT, "outline: %d pixels, each counting %.1f to %.1f times the mean for the angle",
                        s.length, sorted[sorted.length / 10], sorted[sorted.length * 9 / 10]));
            }
        } catch (IOException e) {
            Log.w(TAG, "mask.bin: " + e);
        }
    }

    /**
     * Each of the mask's pixels' share of the screen: the screen is a rectangle seen in perspective,
     * its corners the outline's furthest points along the diagonals; a pixel's share is the screen
     * area it covers (1 / w³ for the projective map from the picture onto a unit square), as a part
     * of the mean, kept between 0.2 and 3 (the far edge is a blur). Null if no shape is found.
     */
    private static float[] shares(byte[] bits, int w, int[] samples) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
        double[] q = new double[8];
        for (int i = 0; i < bits.length; i++) {
            if (bits[i] == 0) continue;
            int x = i % w, y = i / w;
            if (x + y < lo) { lo = x + y; q[0] = x; q[1] = y; }
            if (x - y > right) { right = x - y; q[2] = x; q[3] = y; }
            if (x + y > hi) { hi = x + y; q[4] = x; q[5] = y; }
            if (x - y < left) { left = x - y; q[6] = x; q[7] = y; }
        }
        double sx = q[0] - q[2] + q[4] - q[6], sy = q[1] - q[3] + q[5] - q[7];
        double dx1 = q[2] - q[4], dx2 = q[6] - q[4], dy1 = q[3] - q[5], dy2 = q[7] - q[5];
        double den = dx1 * dy2 - dx2 * dy1;
        if (samples.length == 0 || Math.abs(den) < 1e-9) return null;
        double g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
        double[][] toPicture = {{q[2] - q[0] + g * q[2], q[6] - q[0] + h * q[6], q[0]},
            {q[3] - q[1] + g * q[3], q[7] - q[1] + h * q[7], q[1]}, {g, h, 1}};
        double[][] toScreen = invert(toPicture);
        if (toScreen == null) return null;
        float[] out = new float[samples.length];
        double sum = 0;
        for (int k = 0; k < samples.length; k++) {
            double x = samples[k] & 0xFFFF, y = samples[k] >>> 16;
            double d = toScreen[2][0] * x + toScreen[2][1] * y + toScreen[2][2];
            out[k] = (float) (1 / Math.max(1e-9, Math.abs(d * d * d)));
            sum += out[k];
        }
        double mean = sum / samples.length;
        sum = 0;
        for (int k = 0; k < out.length; k++) {
            out[k] = (float) Math.max(0.2, Math.min(3, out[k] / mean));
            sum += out[k];
        }
        for (int k = 0; k < out.length; k++) out[k] *= out.length / sum;
        return out;
    }

    private int[] cropSamples(int w, int h) {
        float[] c = crop();
        int x0 = (int) (c[0] * w), y0 = (int) (c[1] * h), x1 = (int) (c[2] * w), y1 = (int) (c[3] * h);
        int[] s = new int[((x1 - x0 + 1) / 2) * ((y1 - y0 + 1) / 2)];
        int k = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) if (k < s.length) s[k++] = x | y << 16;
        return k == s.length ? s : Arrays.copyOf(s, k);
    }

    // ---- files and network --------------------------------------------------------------------

    /**
     * The TV said "here": from now on its colours go there. The router hands out addresses afresh
     * now and then, and a phone sending to the TV's old one lit nothing and followed no sound.
     */
    private void heard(InetAddress from) {
        tvHeardAt = SystemClock.elapsedRealtime();
        lost = false;
        String at = from.getHostAddress();
        if (at.equals(conf.get("tv"))) return;
        Log.i(TAG, "the TV is at " + at + " now (was " + conf.get("tv") + ")");
        conf.put("tv", at);
        tvAddress = from;
        writeConf();
    }

    /** Nothing from the TV for a while: "lightcam hello" to every address around the phone's own, which the TV answers. */
    private void askAround() {
        DatagramSocket out = socket;
        if (out == null) return;
        if (!lost) Log.i(TAG, "no word from the TV at " + conf.get("tv") + ": asking around the Wi-Fi");
        lost = true;
        finder.execute(() -> {
            Inet4Address own = ownAddress();
            if (own == null) return;
            byte[] a = own.getAddress();
            byte[] hello = "lightcam hello".getBytes(StandardCharsets.US_ASCII);
            for (int i = 1; i < 255; i++) {
                if (i == (a[3] & 0xFF)) continue;
                try {
                    InetAddress to = InetAddress.getByAddress(new byte[] {a[0], a[1], a[2], (byte) i});
                    out.send(new DatagramPacket(hello, hello.length, to, PORT));
                } catch (IOException e) {
                    return; // the camera stopped (the socket closed), or the Wi-Fi went
                }
            }
        });
    }

    private static Inet4Address ownAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return (Inet4Address) a;
                }
            }
        } catch (SocketException e) {
            // no network yet
        }
        return null;
    }

    private void send(int port, String msg) {
        String tv = conf.get("tv");
        if (socket == null || tv == null) return;
        try {
            if (tvAddress == null || !tv.equals(tvAddress.getHostAddress())) tvAddress = InetAddress.getByName(tv);
            byte[] b = msg.getBytes(StandardCharsets.UTF_8); // titles can be in any script
            socket.send(new DatagramPacket(b, b.length, tvAddress, port));
        } catch (IOException e) {
            Log.w(TAG, "send: " + e);
        }
    }

    /** The whole frame as files/frame.jpg, everything outside the screen darkened, for checking. */
    private void snapshot(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        boolean[] screen = new boolean[w * h];
        int[] samples = mask != null && maskW == w && maskH == h ? mask : cropSamples(w, h);
        for (int s : samples) {
            int x = s & 0xFFFF, y = s >>> 16;
            for (int dy = 0; dy < 2 && y + dy < h; dy++) for (int dx = 0; dx < 2 && x + dx < w; dx++) screen[(y + dy) * w + x + dx] = true;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = yb.get(y * yRow + x) & 0xFF;
                nv21[y * w + x] = (byte) (screen[y * w + x] ? v : v / 4);
            }
        }
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                int i = w * h + y * w + 2 * x, uv = y * uvRow + x * uvPix;
                nv21[i] = vb.get(uv);
                nv21[i + 1] = ub.get(uv);
            }
        }
        File out = file("frame.jpg"), tmp = file("frame.tmp");
        if (out == null) return;
        try (OutputStream o = new FileOutputStream(tmp)) {
            new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 85, o);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(out)) Log.w(TAG, "could not write frame.jpg");
    }

    /** True when the file changed. */
    private boolean readConf() {
        File f = file("lightcam.conf");
        long stamp = f == null || !f.isFile() ? 0 : f.lastModified();
        if (stamp == confStamp) return false;
        confStamp = stamp;
        conf.clear();
        if (stamp != 0) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int eq = line.indexOf('=');
                    if (!line.trim().startsWith("#") && eq > 0) conf.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            } catch (IOException e) {
                Log.w(TAG, "lightcam.conf: " + e);
            }
        }
        exposureUs = (long) num("exposure_us", 8000);
        iso = (int) num("iso", 100);
        colourM = numbers("colour_m", 9);
        colourBlack = numbers("colour_black", 3);
        pulse.bass(num("bass_hz", 250));
        // Tuned on a real song at the usual volume (Ritviz, Udd Gaye): twice the visible swing of the
        // first settings and twice as many pulses you can see.
        pulse.groove(num("groove_min", 3), num("groove_full", 18), num("groove_release", 0.85), num("groove_loud", 0.25));
        pulse.tune(new double[][] {
            {num("b_ratio", 1.52), num("b_floor", 2.34), num("b_peak", 0.39)},
            {num("m_ratio", 1.52), num("m_floor", 2.34), num("m_peak", 0.39)},
            {num("h_ratio", 1.77), num("h_floor", 2.5), num("h_peak", 0.45)}});
        Log.i(TAG, "settings " + conf);
        return true;
    }

    private boolean readConfForce() {
        confStamp = -1;
        return readConf();
    }

    private void writeConf() {
        File f = file("lightcam.conf");
        if (f == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : conf.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
        } catch (IOException e) {
            Log.w(TAG, "lightcam.conf: " + e);
        }
        confStamp = f.lastModified();
    }

    private float[] crop() {
        float[] c = {0, 0, 1, 1};
        String s = conf.get("crop");
        if (s == null) return c;
        String[] p = s.split("\\s*,\\s*");
        try {
            for (int i = 0; i < 4 && i < p.length; i++) c[i] = Math.max(0, Math.min(1, Float.parseFloat(p[i])));
        } catch (NumberFormatException e) {
            return new float[] {0, 0, 1, 1};
        }
        return c[2] > c[0] && c[3] > c[1] ? c : new float[] {0, 0, 1, 1};
    }

    /** A comma-separated list of n numbers from lightcam.conf, or null. */
    private double[] numbers(String key, int n) {
        String v = conf.get(key);
        if (v == null) return null;
        String[] p = v.split(",");
        if (p.length != n) return null;
        double[] out = new double[n];
        try {
            for (int i = 0; i < n; i++) out[i] = Double.parseDouble(p[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private double num(String key, double fallback) {
        try {
            return Double.parseDouble(conf.get(key));
        } catch (NullPointerException | NumberFormatException e) {
            return fallback;
        }
    }

    private void show(String text) {
        Log.i(TAG, text);
        status.post(() -> status.setText(text));
    }

    /** About 320 pixels wide: plenty for one average colour, and cheap. */
    private static Size smallest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            boolean fits = s.getWidth() >= 320 && s.getWidth() * 3 == s.getHeight() * 4;
            boolean bestFits = best.getWidth() >= 320 && best.getWidth() * 3 == best.getHeight() * 4;
            if (fits && (!bestFits || s.getWidth() < best.getWidth())) best = s;
        }
        return best;
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }
}

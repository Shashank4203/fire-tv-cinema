package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Magic Home LED controller on the local network (TCP 5577, no cloud). Colours are sent as
 * temporary (0x41), which the controller applies at once without saving them, so the colour the
 * owner set in the Magic Home app is still there to {@link #restore} afterwards.
 *
 * The controller's UDP discovery broadcast gets no answer through the TP-Link extender, so it is
 * found by asking every address on the TV's subnet, and the address that answered is remembered.
 * Not thread-safe: Lights uses it from its own thread only.
 */
final class Strip {
    private static final String TAG = "lights";
    private static final int PORT = 5577;
    /**
     * How long to wait for the controller. On a congested Wi-Fi its replies have taken over 2 s
     * (pings 0.15-2 s, 2026-11-15), and with 1.5 s to connect and 3 s for the heartbeat the TV kept
     * dropping a strip that was still there. Probes during a search wait less, as there are 254.
     */
    private static final int KNOWN_MS = 4000, SEARCH_MS = 2000, SILENT_MS = 8000;
    /** An unanswered status question is asked again after this, in a pause of QUIET_MS (see healthy). */
    private static final int ASK_AGAIN_MS = 3000, QUIET_MS = 700;

    private final SharedPreferences prefs;
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    /** The controller's state before we took over: its 14-byte status reply. */
    private byte[] saved;
    private long rememberedAt;
    /** The heartbeat: when we last asked the controller for its status, and when it last said anything. */
    private long queriedAt, answeredAt;
    /** The heartbeat's second try: no colours go out until quietUntil; the question goes at reaskAt. */
    private long quietUntil, reaskAt;
    /** How long this connection waits to connect or for a status reply (KNOWN_MS or SEARCH_MS). */
    private int patience = KNOWN_MS;
    /** The last colour we sent, exactly (prefs "sent" has it to within two seconds). */
    private int lastSent = -1;

    Strip(Context c) {
        prefs = c.getSharedPreferences("lights", Context.MODE_PRIVATE);
    }

    boolean connected() {
        return socket != null;
    }

    /** Connects (finding the controller if its address changed) and remembers its state. Blocking. */
    boolean take() {
        if (socket != null) return true;
        String host = prefs.getString("host", null);
        if (host == null || !open(host, KNOWN_MS)) {
            host = find();
            if (host == null || !open(host, KNOWN_MS)) return false;
            prefs.edit().putString("host", host).apply();
        }
        byte[] now = status();
        if (now == null) {
            close();
            return false;
        }
        if (saved == null) {
            // Kept on disk until restored: if the app was stopped mid-show, the strip still shows our
            // colour, and the owner's is the one saved then.
            String kept = prefs.getString("saved", null);
            String owner = prefs.getString("owner", null);
            // Nothing kept: the owner's colour as we know it. A different colour on the strip is
            // learned as the owner's new one only when we haven't touched the strip for a minute, it
            // isn't one of ours, and it reads the same twice: just after our own commands the
            // controller can answer with a colour we sent, or one it has saved from them.
            if (kept == null && owner != null) {
                boolean quiet = System.currentTimeMillis() - prefs.getLong("touchedAt", 0) > 60_000;
                boolean learn = quiet && now[2] == 0x23 && !ours(now) && !same(now, unhex(owner));
                if (learn) {
                    pause(1200); // longer than a fade: a colour still changing reads differently
                    byte[] again = status();
                    learn = again != null && same(now, again);
                    Log.i(TAG, "strip shows " + hex(now) + (learn ? ": the owner's new colour" : ": not steady, the owner's kept"));
                }
                if (!learn) kept = owner;
            }
            saved = kept != null ? unhex(kept) : now;
            // Always on disk while we hold the strip, however it was found: if the app is stopped
            // mid-show (an update force-stops it), the next start must still know the owner's state.
            prefs.edit().putString("saved", hex(saved)).commit();
            Log.i(TAG, "strip at " + host + ", owner's state " + hex(saved) + (kept != null ? " (kept)" : ""));
        }
        if (now[2] == 0x24) send(0x71, 0x23, 0x0F); // off: on for the show, off again after
        return true;
    }

    /**
     * The heartbeat, called with every frame and never blocking: every 5 s it asks the controller
     * for its status, and false means it hasn't answered for SILENT_MS. A strip that drops off the Wi-Fi
     * leaves a dead connection that takes our colours without an error for minutes, the strip
     * frozen meanwhile; this notices within seconds.
     *
     * The controller ignores a command that arrives straight after a burst of colours, and with a
     * colour every 50 ms the status question sometimes is that command: the TV then dropped and
     * reconnected a strip that was fine, every 25 s or so (2026-11-15). So a question still
     * unanswered after ASK_AGAIN_MS is asked again in a short pause with no colours, and only
     * silence after that counts.
     */
    boolean healthy() {
        if (socket == null) return false;
        long now = System.currentTimeMillis();
        try {
            byte[] buf = new byte[256];
            while (in.available() > 0 && in.read(buf) > 0) answeredAt = now; // anything at all counts
        } catch (IOException e) {
            return false;
        }
        if (queriedAt > answeredAt) {
            if (now - queriedAt > SILENT_MS) return false;
            if (reaskAt == 0 && quietUntil < queriedAt && now - queriedAt > ASK_AGAIN_MS) {
                quietUntil = now + QUIET_MS;
                reaskAt = now + QUIET_MS / 2; // half the pause before the question, half after
            } else if (reaskAt != 0 && now >= reaskAt) {
                reaskAt = 0;
                Log.i(TAG, "the strip didn't answer mid-stream: asking again in a pause");
                return send(0x81, 0x8A, 0x8B);
            }
            return true;
        }
        if (now - queriedAt > 5000) {
            queriedAt = now;
            reaskAt = 0;
            return send(0x81, 0x8A, 0x8B);
        }
        return true;
    }

    /** Switches the strip on or off, connecting just for that if we don't hold it. */
    void power(boolean on) {
        boolean opened = false;
        if (socket == null) {
            String host = prefs.getString("host", null);
            if (host == null || !open(host, KNOWN_MS)) return;
            opened = true;
        }
        send(0x71, on ? 0x23 : 0x24, 0x0F);
        prefs.edit().putLong("touchedAt", System.currentTimeMillis()).apply();
        if (opened) finish();
    }

    /** The owner's own colour, or -1 when the strip was off or running a built-in effect. */
    int owner() {
        byte[] s = saved;
        if (s == null || s[2] == 0x24) return -1;
        int pattern = s[3] & 0xFF;
        if (pattern >= 0x25 && pattern <= 0x38) return -1;
        return (s[6] & 0xFF) << 16 | (s[7] & 0xFF) << 8 | (s[8] & 0xFF);
    }

    /**
     * A temporary colour. False when the connection broke. The controller ignores pure black and
     * drifts back to its last colour, so black goes out as (1, 1, 1), which looks off.
     */
    boolean colour(int r, int g, int b) {
        if (r == 0 && g == 0 && b == 0) r = g = b = 1;
        long now = System.currentTimeMillis();
        if (now < quietUntil) return socket != null; // the heartbeat's pause: skipped, not failed
        lastSent = r << 16 | g << 8 | b;
        if (now - rememberedAt > 2000) { // so the next start can recognise it too
            rememberedAt = now;
            prefs.edit().putInt("sent", lastSent).putLong("touchedAt", now).apply();
        }
        return send(0x41, r, g, b, 0, 0xF0, 0x0F);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Same power, effect and colour in two status replies. */
    private static boolean same(byte[] a, byte[] b) {
        for (int i : new int[] {2, 3, 6, 7, 8, 9}) if (a[i] != b[i]) return false;
        return true;
    }

    /** Whether the strip is showing a colour we sent rather than one the owner set. */
    private boolean ours(byte[] status) {
        if ((status[3] & 0xFF) == 0x62) return true;
        int sent = lastSent >= 0 ? lastSent : prefs.getInt("sent", -1);
        if (sent < 0) return false;
        return Math.abs((status[6] & 0xFF) - (sent >> 16 & 0xFF)) <= 3
                && Math.abs((status[7] & 0xFF) - (sent >> 8 & 0xFF)) <= 3
                && Math.abs((status[8] & 0xFF) - (sent & 0xFF)) <= 3;
    }

    /** Puts back what the owner had (colour, built-in effect, or off) and disconnects. */
    void restore() {
        byte[] s = saved;
        if (s == null) {
            close();
            return;
        }
        String host = prefs.getString("host", null);
        if (socket == null && (host == null || !open(host, KNOWN_MS))) return; // unreachable: kept for next time
        // The controller ignores commands that arrive straight after a burst of colours, and it fades
        // to a new colour over about a second: switched off (or read) mid-fade, it keeps the
        // half-way colour as its own. So: a pause, the owner's colour, then the whole fade.
        pause(400);
        int pattern = s[3] & 0xFF;
        boolean ok = pattern >= 0x25 && pattern <= 0x38
                ? send(0x61, pattern, s[5] & 0xFF, 0x0F)
                : send(0x31, s[6] & 0xFF, s[7] & 0xFF, s[8] & 0xFF, s[9] & 0xFF, 0xF0, 0x0F);
        pause(1500);
        if (ok && s[2] == 0x24) ok = send(0x71, 0x24, 0x0F);
        if (ok) {
            saved = null;
            prefs.edit().remove("saved").putString("owner", hex(s))
                    .putLong("touchedAt", System.currentTimeMillis()).commit();
            Log.i(TAG, "strip restored to " + hex(s));
        }
        finish();
    }

    /**
     * Closes only after the controller has had the last command. Closing with its replies still
     * unread makes the TV reset the connection, and the controller then drops whatever it hadn't
     * applied yet: the restore was being lost that way.
     */
    private void finish() {
        if (socket == null) return;
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(300);
            byte[] buf = new byte[256];
            long end = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < end && in.read(buf) >= 0) {
                // reading the controller's replies until it closes its side or goes quiet
            }
        } catch (IOException e) {
            // quiet for 300 ms, or already gone: either way it has had the command
        }
        close();
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        socket = null;
        out = null;
        in = null;
    }

    private boolean open(String host, int ms) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, PORT), ms);
            s.setSoTimeout(ms);
            patience = ms;
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            in = s.getInputStream();
            queriedAt = answeredAt = System.currentTimeMillis();
            reaskAt = 0;
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    private boolean send(int... bytes) {
        if (out == null) return false;
        try {
            out.write(message(bytes));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "strip write failed: " + e);
            close();
            return false;
        }
    }

    /**
     * The 14-byte status, or null. Newer firmware also sends acknowledgements wrapped in
     * b0 b1 b2 b3 frames, so everything waiting is read first and the reply is picked out by its
     * header and checksum.
     */
    private byte[] status() {
        if (!send(0x81, 0x8A, 0x8B)) return null;
        byte[] buf = new byte[256];
        int n = 0;
        long end = System.currentTimeMillis() + patience;
        try {
            while (n < buf.length && System.currentTimeMillis() < end) {
                int k;
                try {
                    k = in.read(buf, n, buf.length - n);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (k < 0) break;
                n += k;
                byte[] found = statusIn(buf, n);
                if (found != null) return found;
            }
        } catch (IOException e) {
            Log.w(TAG, "strip read failed: " + e);
        }
        return statusIn(buf, n);
    }

    private static byte[] statusIn(byte[] buf, int n) {
        for (int i = 0; i + 14 <= n; i++) {
            if ((buf[i] & 0xFF) != 0x81) continue;
            int sum = 0;
            for (int j = i; j < i + 13; j++) sum += buf[j] & 0xFF;
            if ((sum & 0xFF) == (buf[i + 13] & 0xFF)) {
                byte[] s = new byte[14];
                System.arraycopy(buf, i, s, 0, 14);
                return s;
            }
        }
        return null;
    }

    /** Asks every address on the TV's /24 for a controller. A few seconds; blocking. */
    private static String find() {
        String base = subnet();
        if (base == null) return null;
        AtomicReference<String> hit = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(64); // 4 rounds of at most 2 x SEARCH_MS
        for (int i = 1; i < 255; i++) {
            String host = base + i;
            pool.execute(() -> {
                if (hit.get() != null) return;
                Strip probe = new Strip();
                if (probe.open(host, SEARCH_MS)) {
                    if (probe.status() != null) hit.compareAndSet(null, host);
                    probe.close();
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        Log.i(TAG, "strip search on " + base + "0/24: " + hit.get());
        return hit.get();
    }

    private Strip() {
        prefs = null;
    }

    /**
     * The TV's /24 on the home network: Wi-Fi or Ethernet first. Tunnels are skipped: the DNS
     * filter's VPN adds tun interfaces in 10.0.2.x, which came first in the list, and the strip
     * was searched for there, in vain, for as long as the TV was on.
     */
    private static String subnet() {
        String other = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()
                        || name.startsWith("tun") || name.startsWith("ppp")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        String base = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (name.startsWith("wlan") || name.startsWith("eth")) return base;
                        if (other == null) other = base;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "no network: " + e);
        }
        return other;
    }

    private static byte[] message(int... bytes) {
        byte[] m = new byte[bytes.length + 1];
        int sum = 0;
        for (int i = 0; i < bytes.length; i++) {
            m[i] = (byte) bytes[i];
            sum += bytes[i];
        }
        m[bytes.length] = (byte) sum;
        return m;
    }

    private static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }
}

package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The light strip's colours for what's playing, taken from the title's TMDB backdrop (a real
 * frame, so closer to how the film looks than its poster).
 *
 * Apps name what's playing differently: Hotstar gives only the episode ("S1 E1 No Strings").
 * cinema/pool/lights.txt, built daily on the Mac by refresh_pool.py, maps the pool's and the taste
 * profile's titles and every episode name of their series to a TMDB title. Anything else is
 * searched on TMDB with the key in cinema/tmdb.key. Answers are cached for good in prefs.
 */
final class Palette {
    private static final String TAG = "lights";
    static final Pattern EPISODE =
            Pattern.compile("(?i)^\\s*S(\\d+)\\s*[:.]?\\s*E(\\d+)\\s*[-:·.|]?\\s*(.*)$");
    /** Used until the title's own colours are known, and when it has none. */
    private static final int[] CINEMA = {0xFF7A2E, 0x2E5BFF, 0xB03AFF};
    private static final Map<String, int[]> APP = new HashMap<>();
    /** Bumped when {@link #extract} changes, so cached colours are worked out again. */
    private static final String COLOURS = "p2:";

    static {
        APP.put("com.netflix.ninja", new int[] {0xFF1A0A, 0xFF6A1E, 0x8A10FF});
        APP.put("in.startv.hotstar", new int[] {0x1E3CFF, 0x7A2EFF, 0x00B4FF});
        APP.put("com.amazon.firebat", new int[] {0x00A8FF, 0x1E50FF, 0x00E0C0});
    }

    private final File dir;
    private final SharedPreferences prefs;

    Palette(Context c) {
        dir = new File(c.getExternalFilesDir(null), "cinema");
        prefs = c.getSharedPreferences("lights", Context.MODE_PRIVATE);
    }

    /** Instant: the app's colours, before the title is looked up. */
    static int[] forApp(String pkg) {
        int[] p = APP.get(pkg);
        return p != null ? p : CINEMA;
    }

    /**
     * The colours for a title, from the cache, the Mac's index or TMDB. Blocking (network): off
     * the lights thread. Null when nothing was found.
     */
    int[] lookup(List<String> texts) {
        for (String text : texts) {
            String[] hit = match(text);
            if (hit == null) continue;
            int[] colours = colours(hit);
            Log.i(TAG, "\"" + text + "\" is " + hit[0] + (colours == null ? ", no colours" : ", " + describe(colours)));
            if (colours != null) return colours;
        }
        Log.i(TAG, "no title found for " + texts);
        return null;
    }

    /**
     * The TMDB title ("movie-123", "tv-456") and its image path for a name an app reported, or
     * null. Blocking (network).
     */
    String[] find(String text) {
        return match(text);
    }

    /** {key, image path} for a text the app reported, or null. */
    private String[] match(String text) {
        String norm = normal(text);
        if (norm.isEmpty()) return null;
        Matcher m = EPISODE.matcher(text);
        Map<String, String[]> index = index();
        if (m.matches()) {
            String name = normal(m.group(3));
            String[] hit = index.get("s" + Integer.parseInt(m.group(1)) + "e" + Integer.parseInt(m.group(2)) + " " + name);
            if (hit == null) hit = index.get(name);
            return hit; // an episode name means nothing to TMDB's search
        }
        String[] hit = index.get(norm);
        return hit != null ? hit : search(text, norm);
    }

    private Map<String, String[]> indexCache;
    private long indexStamp;

    /** pool/lights.txt: "match | key | image path". Reread when the Mac pushes a new one. */
    private Map<String, String[]> index() {
        File f = new File(dir, "pool/lights.txt");
        if (indexCache != null && f.lastModified() == indexStamp) return indexCache;
        Map<String, String[]> out = new HashMap<>();
        for (String line : Walls.read(f)) {
            if (line.startsWith("#")) continue;
            String[] p = line.split("\\s*\\|\\s*");
            if (p.length >= 3 && !out.containsKey(p[0])) out.put(p[0], new String[] {p[1], p[2]});
        }
        indexCache = out;
        indexStamp = f.lastModified();
        return out;
    }

    /** TMDB's best match for a film or series name, remembered (misses too). */
    private String[] search(String text, String norm) {
        String known = prefs.getString("t:" + norm, null);
        if (known != null) return known.equals("-") ? null : known.split("\\|", 2);
        List<String> keyFile = Walls.read(new File(dir, "tmdb.key"));
        String key = keyFile.isEmpty() ? "" : keyFile.get(0).trim();
        if (key.isEmpty()) return null;
        try {
            String q = URLEncoder.encode(text, "UTF-8");
            boolean bearer = key.length() > 40; // a v4 read token rather than a v3 api key
            JSONObject res = new JSONObject(new String(get("https://api.themoviedb.org/3/search/multi?query=" + q
                    + (bearer ? "" : "&api_key=" + key), bearer ? key : null), StandardCharsets.UTF_8));
            JSONArray results = res.optJSONArray("results");
            String[] hit = null;
            for (int i = 0; results != null && i < results.length() && hit == null; i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                String image = r.optString("backdrop_path", "");
                if (image.isEmpty() || "null".equals(image)) image = r.optString("poster_path", "");
                if (("movie".equals(type) || "tv".equals(type)) && !image.isEmpty() && !"null".equals(image)) {
                    hit = new String[] {type + "-" + r.getLong("id"), image};
                }
            }
            prefs.edit().putString("t:" + norm, hit == null ? "-" : hit[0] + "|" + hit[1]).apply();
            return hit;
        } catch (Exception e) {
            Log.w(TAG, "TMDB search failed for " + text + ": " + e);
            return null; // offline: try again next time
        }
    }

    /** The colours of a title's image, computed once and remembered. */
    private int[] colours(String[] hit) {
        String known = prefs.getString(COLOURS + hit[0], null);
        if (known != null) return parse(known);
        try {
            byte[] jpg = get("https://image.tmdb.org/t/p/w300" + hit[1], null);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            if (b == null) return null;
            int[] c = extract(b);
            b.recycle();
            StringBuilder s = new StringBuilder();
            for (int x : c) s.append(s.length() == 0 ? "" : ",").append(String.format("%06x", x));
            prefs.edit().putString(COLOURS + hit[0], s.toString()).apply();
            return c;
        } catch (IOException e) {
            Log.w(TAG, "image failed for " + hit[0] + ": " + e);
            return null;
        }
    }

    /**
     * Up to four LED colours: the image's strongest hues, each at least 25° from the others,
     * weighted by how vivid and how widespread they are. Pushed to full brightness and well
     * saturated, since an LED strip shows a dark or greyish colour as a dim white.
     */
    static int[] extract(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 48, 27, true);
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        if (b != src) b.recycle();
        int bins = 24;
        double[] weight = new double[bins];
        double[][] sum = new double[bins][3];
        float[] hsv = new float[3];
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.12f || hsv[1] < 0.18f) continue;
            double w = hsv[1] * hsv[1] * hsv[2];
            int bin = (int) (hsv[0] / 360f * bins) % bins;
            weight[bin] += w;
            sum[bin][0] += w * Color.red(p);
            sum[bin][1] += w * Color.green(p);
            sum[bin][2] += w * Color.blue(p);
        }
        List<Integer> picked = new ArrayList<>();
        List<Float> hues = new ArrayList<>();
        double top = 0;
        for (double w : weight) top = Math.max(top, w);
        while (picked.size() < 4) {
            int best = -1;
            for (int i = 0; i < bins; i++) {
                if (weight[i] <= 0 || weight[i] < top * 0.12) continue;
                if (best < 0 || weight[i] > weight[best]) best = i;
            }
            if (best < 0) break;
            double w = weight[best];
            weight[best] = 0;
            int c = Color.rgb((int) (sum[best][0] / w), (int) (sum[best][1] / w), (int) (sum[best][2] / w));
            Color.colorToHSV(c, hsv);
            boolean near = false;
            for (float h : hues) near |= Math.min(Math.abs(h - hsv[0]), 360 - Math.abs(h - hsv[0])) < 25;
            if (near) continue;
            hues.add(hsv[0]);
            hsv[1] = Math.min(1f, 0.25f + hsv[1] * 1.1f);
            hsv[2] = 1f;
            picked.add(Color.HSVToColor(hsv) & 0xFFFFFF);
        }
        if (picked.isEmpty()) return new int[] {0xFF9A50}; // a black-and-white image: warm white
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) out[i] = picked.get(i);
        return out;
    }

    static String normal(String s) {
        return s.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static int[] parse(String s) {
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i], 16);
        return out;
    }

    static String describe(int[] c) {
        StringBuilder s = new StringBuilder();
        for (int x : c) s.append(String.format(" #%06x", x));
        return s.toString().trim();
    }

    private static byte[] get(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}

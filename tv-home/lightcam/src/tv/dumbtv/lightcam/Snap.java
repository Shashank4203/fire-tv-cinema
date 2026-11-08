package tv.dumbtv.lightcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads the name of what's playing off the TV, for the home screen's Continue watching: Netflix
 * and Apple TV never tell the TV what they play, but their players show it on screen (when
 * paused, or when a button brings up the info bar). The TV asks with "snap"; a full-resolution
 * photo, cropped to the screen, goes to Gemini, which answers with the title it can see or NONE.
 * The answer goes back to the TV as "lightcam title <answer>".
 *
 * The key is files/gemini.key (deploy.sh copies it from the Mac); gemini_model= in lightcam.conf
 * picks the model.
 */
final class Snap {
    private static final String TAG = "lightcam";
    /**
     * The photo sent is at most this many pixels along its long side. 3072 read small episode
     * numbers no better (the TV checks those against TMDB by the episode's name instead) and
     * took twice as long.
     */
    private static final int MAX_SIDE = 1600;
    /** Reads a tilted test screen right in about 3.5 s; the full flash model took minutes. */
    static final String MODEL = "gemini-flash-lite-latest";
    private static final String PROMPT = "This photo was taken at an angle of a TV screen showing a streaming app "
            + "(such as Netflix or Apple TV). If the screen shows the name of the film or series that is playing "
            + "or paused (for example in the player's info bar, a title card or a pause screen), answer with only "
            + "that name, exactly as written. For a series episode answer: name | S<season> E<episode> | episode "
            + "title, leaving out any part you can't read. Ignore subtitles, menus, buttons, rows of other titles "
            + "and anything that isn't the name of what is playing. If no such name is visible, answer NONE.";

    private Snap() {}

    /**
     * The title Gemini reads in {@code jpeg} within {@code screen} (the TV, in the photo's
     * pixels), or null when it reads none or can't be asked; the photo sent is kept in
     * {@code keep}. Blocking: not on the camera thread.
     */
    static String read(byte[] jpeg, Rect screen, File keyFile, String model, File keep) {
        String key = firstLine(keyFile);
        if (key.isEmpty()) {
            Log.w(TAG, "no Gemini key in " + keyFile);
            return null;
        }
        byte[] photo = crop(jpeg, screen);
        if (photo == null) return null;
        if (keep != null) { // the last photo read, for checking what Gemini was shown
            try (OutputStream out = new java.io.FileOutputStream(keep)) {
                out.write(photo);
            } catch (IOException e) {
                Log.w(TAG, "snap.jpg: " + e);
            }
        }
        try {
            JSONObject image = new JSONObject().put("inline_data",
                    new JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(photo, Base64.NO_WRAP)));
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", PROMPT)).put(image))))
                    .put("generationConfig", new JSONObject().put("temperature", 0).put("maxOutputTokens", 1024));
            String res = post("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
                    key, body.toString());
            JSONArray candidates = new JSONObject(res).optJSONArray("candidates");
            JSONObject content = candidates == null || candidates.length() == 0 ? null
                    : candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (!p.optBoolean("thought")) text.append(p.optString("text", ""));
            }
            String answer = text.toString().trim();
            int nl = answer.indexOf('\n');
            if (nl >= 0) answer = answer.substring(0, nl).trim();
            Log.i(TAG, "Gemini read: " + (answer.isEmpty() ? "(nothing)" : answer));
            return answer.isEmpty() || answer.equalsIgnoreCase("NONE") ? null : answer;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Gemini: " + e);
            return null;
        }
    }

    /** The screen's part of the photo, shrunk to {@link #MAX_SIDE}, as a JPEG. */
    private static byte[] crop(byte[] jpeg, Rect screen) {
        try {
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
            Rect r = new Rect(screen);
            if (!r.intersect(0, 0, d.getWidth(), d.getHeight())) r.set(0, 0, d.getWidth(), d.getHeight());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (Math.max(r.width(), r.height()) / (o.inSampleSize * 2) >= MAX_SIDE) o.inSampleSize *= 2;
            Bitmap b = d.decodeRegion(r, o);
            d.recycle();
            if (b == null) return null;
            int side = Math.max(b.getWidth(), b.getHeight());
            if (side > MAX_SIDE) {
                float k = MAX_SIDE / (float) side;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                b.recycle();
                b = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            b.recycle();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "snap crop: " + e);
            return null;
        }
    }

    private static String post(String url, String key, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key); // a header, so the key is never in a logged URL
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + body.substring(0, Math.min(200, body.length())));
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String firstLine(File f) {
        if (f == null || !f.isFile()) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (IOException e) {
            return "";
        }
    }
}

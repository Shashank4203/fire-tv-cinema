package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the TV asks TMDB itself, with the key in cinema/tmdb.key: the Alexa results page and
 * Continue watching. Blocking calls: never on the main thread.
 */
final class Tmdb {
    private static final String TAG = "tmdb";
    static final String NETFLIX = "com.netflix.ninja", PRIME = "com.amazon.firebat",
            APPLE = "com.apple.atve.amazon.appletv";

    /** Where each app's title pages live (JioHotstar's TV app ignores links: it opens the app). */
    static final Map<String, String> LINK_HOSTS = new HashMap<>();
    /** TMDB genre ids, short names. */
    static final Map<Integer, String> GENRES = new HashMap<>();

    static {
        LINK_HOSTS.put(NETFLIX, "netflix.com");
        LINK_HOSTS.put(PRIME, "primevideo.com");
        LINK_HOSTS.put(APPLE, "tv.apple.com");
        String[][] g = {{"28", "Action"}, {"12", "Adventure"}, {"16", "Animation"}, {"35", "Comedy"}, {"80", "Crime"},
                {"99", "Documentary"}, {"18", "Drama"}, {"10751", "Family"}, {"14", "Fantasy"}, {"36", "History"},
                {"27", "Horror"}, {"10402", "Music"}, {"9648", "Mystery"}, {"10749", "Romance"}, {"878", "Sci-Fi"},
                {"53", "Thriller"}, {"10752", "War"}, {"37", "Western"}, {"10759", "Action"}, {"10762", "Kids"},
                {"10765", "Sci-Fi"}, {"10766", "Soap"}, {"10767", "Talk"}, {"10768", "War"}, {"10764", "Reality"}};
        for (String[] p : g) GENRES.put(Integer.parseInt(p[0]), p[1]);
    }

    private static final Pattern CLICKOUT = Pattern.compile("href=\"(https://click\\.justwatch\\.com[^\"]+)\"");

    private Tmdb() {}

    /** A TMDB API call, {@code path} from /3 on. */
    static String api(File dir, String path) throws IOException {
        List<String> keyFile = Walls.read(new File(dir, "tmdb.key"));
        String key = keyFile.isEmpty() ? "" : keyFile.get(0).trim();
        boolean bearer = key.length() > 40; // a v4 read token rather than a v3 api key
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
        return new String(get(url, bearer ? key : null, null), StandardCharsets.UTF_8);
    }

    static byte[] get(String url, String bearer, String agent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (agent != null) c.setRequestProperty("User-Agent", agent);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static Bitmap image(String url, Bitmap.Config config) {
        try {
            byte[] data = get(url, null, null);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = config;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The title's own page in {@code app}, from TMDB's "where to watch" page (it links each
     * service's page via JustWatch); "" when there is none or the app takes no links.
     */
    static String link(String kind, long id, String app) {
        String host = LINK_HOSTS.get(app);
        if (host == null) return "";
        try {
            // The TV's own identity: claiming to be a desktop browser over the TV's HTTP/1.1
            // connection is what TMDB's bot check turns away (403); the plain Android one passes.
            String page = new String(get("https://www.themoviedb.org/" + kind + "/" + id + "/watch?locale=IN", null, null),
                    StandardCharsets.UTF_8);
            Matcher m = CLICKOUT.matcher(page);
            while (m.find()) {
                String target = Uri.parse(m.group(1).replace("&amp;", "&")).getQueryParameter("r");
                if (target == null || Uri.parse(target).getHost() == null || !Uri.parse(target).getHost().contains(host)) continue;
                if (NETFLIX.equals(app)) {
                    String nid = Uri.parse(target).getLastPathSegment();
                    return nid != null && nid.matches("\\d+") ? "https://www.netflix.com/title/" + nid : target;
                }
                if (APPLE.equals(app)) { // the affiliate tracking goes; a series' episode keeps its show
                    String show = Uri.parse(target).getQueryParameter("showId");
                    return target.split("\\?")[0] + (show == null ? "" : "?showId=" + show);
                }
                return target;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no link for " + kind + "-" + id + ": " + e);
        }
        return "";
    }
}

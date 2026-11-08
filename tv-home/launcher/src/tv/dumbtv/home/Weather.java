package tv.dumbtv.home;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Current weather from Open-Meteo (free, no key). Shared by Home and Dream. The TV has no location
 * of its own, so it comes from cinema/weather.txt: "latitude longitude" on one line. Without that
 * file there is no weather and nothing goes online.
 */
final class Weather {
    static final long EVERY = 30 * 60_000L;

    final long temperature;
    final int icon;

    private Weather(long temperature, int icon) {
        this.temperature = temperature;
        this.icon = icon;
    }

    /** Blocking network call: off the UI thread. Null when offline or no location is set. */
    static Weather fetch(File dir) {
        try {
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(dir, "weather.txt")), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            String[] at = line.trim().split("[\\s,]+");
            double lat = Double.parseDouble(at[0]), lon = Double.parseDouble(at[1]);
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day&timezone=auto").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) sb.append(l);
            } finally {
                c.disconnect();
            }
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            return new Weather(Math.round(cur.getDouble("temperature_2m")),
                    icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 95) return R.drawable.wx_storm;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.drawable.wx_rain;
        return R.drawable.wx_cloud;
    }
}

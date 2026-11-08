package tv.dumbtv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Our page for an Alexa voice search, in place of Amazon's results: the Guard reads the query off
 * Amazon's results screen and opens this with it. Only titles streaming in India on the apps
 * installed here are shown (TMDB search + availability), with the focused one's backdrop, rating,
 * year, genres and app. OK opens it (the title's own page where the app takes links). Back shows
 * Amazon's own results, which stay underneath in their own task.
 */
public final class Search extends Activity {
    static final String QUERY = "query";
    private static final String TAG = "search";
    private static final String NETFLIX = Tmdb.NETFLIX, PRIME = Tmdb.PRIME, APPLE = Tmdb.APPLE;

    /** TMDB provider ids in India -> the TV app that plays them. */
    private static final Map<Integer, String> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put(8, NETFLIX);
        PROVIDERS.put(119, PRIME);
        PROVIDERS.put(350, APPLE);
        PROVIDERS.put(2336, "in.startv.hotstar");
        PROVIDERS.put(122, "in.startv.hotstar");
        PROVIDERS.put(232, "com.zee5.amazon");
        PROVIDERS.put(237, "com.onemainstream.sonyliv.android");
        PROVIDERS.put(510, "com.discoveryplus.tv.fire");
    }

    private static final class Hit {
        String kind, title, year, overview, poster, backdrop, app, genres, link;
        long id;
        double rating;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Map<String, Bitmap> backdrops = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 3;
        }
    };
    private File dir;
    private Typeface light, medium;
    private ImageView backdrop, appLogo;
    private TextView query, title, sub, overview, status;
    private LinearLayout row, details;
    private View bar, glow;
    private android.animation.ValueAnimator sweep, typing;
    private HorizontalScrollView scroll;
    private int generation;
    private String wantedBackdrop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dir = new File(getExternalFilesDir(null), "cinema");
        light = font("Outfit-Light.ttf", Ui.LIGHT);
        medium = font("Outfit-Medium.ttf", Ui.MEDIUM);
        setContentView(build());
        load(getIntent().getStringExtra(QUERY));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        load(intent.getStringExtra(QUERY));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Guard.arrived(this);
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    /** Back: Amazon's own results for the same search are right underneath. */
    @Override
    public void onBackPressed() {
        finish();
    }

    // ---- layout ---------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF2000000, 0xB3000000, 0x33000000}));
        root.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0xE6000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));

        TextView header = text(medium, 11, 0x80FFFFFF);
        header.setText("ALEXA SEARCH");
        header.setLetterSpacing(0.3f);
        root.addView(header, place(-2, -2, Gravity.TOP | Gravity.START, 56, 40, 0, 0));
        query = text(light, 22, 0xB3FFFFFF);
        root.addView(query, place(-2, -2, Gravity.TOP | Gravity.START, 56, 60, 0, 0));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        title = text(light, 32, 0xF2FFFFFF);
        title.setMaxWidth(dp(560));
        details.addView(title);
        sub = text(medium, 12, 0xB3FFFFFF);
        sub.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(12);
        details.addView(sub, slp);
        appLogo = new ImageView(this);
        appLogo.setScaleType(ImageView.ScaleType.FIT_START);
        appLogo.setAlpha(0.8f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(120), dp(22));
        alp.topMargin = dp(14);
        details.addView(appLogo, alp);
        overview = text(light, 14, 0xCCFFFFFF);
        overview.setMaxWidth(dp(520));
        overview.setMaxLines(3);
        overview.setLineSpacing(0, 1.15f);
        overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.topMargin = dp(14);
        details.addView(overview, olp);
        root.addView(details, place(-2, -2, Gravity.TOP | Gravity.START, 56, 100, 0, 0));
        this.details = details;

        status = text(light, 17, 0xB3FFFFFF);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(56), dp(20), dp(56), dp(14));
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        root.addView(scroll, place(-1, -2, Gravity.BOTTOM, 0, 0, 0, 46));

        // Alexa's light bar: sweeps along the top while searching, flares out when results land.
        glow = new View(this);
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x5500CAFF, 0x1500CAFF, 0x00000000}));
        glow.setAlpha(0f);
        root.addView(glow, new FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP));
        int barW = getResources().getDisplayMetrics().widthPixels / 2;
        bar = new View(this);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x0000CAFF, 0xFF00CAFF, 0xFF6B7BFF, 0xFF00CAFF, 0x0000CAFF}));
        bar.setAlpha(0f);
        root.addView(bar, new FrameLayout.LayoutParams(barW, dp(4), Gravity.TOP | Gravity.START));

        TextView hint = text(medium, 10.5f, 0x73FFFFFF);
        hint.setLetterSpacing(0.24f);
        hint.setText("OK  OPEN          BACK  AMAZON'S RESULTS");
        root.addView(hint, place(-2, -2, Gravity.BOTTOM | Gravity.START, 56, 0, 0, 24));
        return root;
    }

    // ---- search ---------------------------------------------------------------------------

    /** TMDB search, then each result's availability in India, keeping those on the apps here. */
    private void load(String q) {
        if (q == null || q.trim().isEmpty()) {
            finish();
            return;
        }
        int gen = ++generation;
        typeOut(q.trim());
        status.setText("Looking on your apps…");
        status.setVisibility(View.VISIBLE);
        status.setAlpha(0f);
        status.animate().alpha(1f).setStartDelay(400).setDuration(500).start();
        listen(true);
        details.setAlpha(0f);
        backdrop.animate().alpha(0f).setDuration(200).start();
        title.setText("");
        sub.setText("");
        overview.setText("");
        appLogo.setImageDrawable(null);
        row.removeAllViews();
        hits.clear();
        io.execute(() -> {
            List<Hit> found = new ArrayList<>();
            try {
                JSONObject res = new JSONObject(Tmdb.api(dir, "/search/multi?include_adult=false&query=" + URLEncoder.encode(q.trim(), "UTF-8")));
                JSONArray results = res.optJSONArray("results");
                List<Future<Hit>> checks = new ArrayList<>();
                for (int i = 0; results != null && i < results.length() && checks.size() < 20; i++) {
                    JSONObject r = results.getJSONObject(i);
                    String type = r.optString("media_type");
                    if (!"movie".equals(type) && !"tv".equals(type)) continue;
                    Hit h = hit(r, type);
                    checks.add(io.submit(() -> available(h) ? h : null));
                }
                for (Future<Hit> f : checks) {
                    Hit h = f.get();
                    if (h != null) found.add(h);
                }
            } catch (Exception e) {
                Log.w(TAG, "search failed for " + q + ": " + e);
            }
            ui.post(() -> {
                if (gen != generation || isDestroyed()) return;
                show(found);
            });
        });
    }

    private Hit hit(JSONObject r, String type) {
        Hit h = new Hit();
        h.kind = type;
        h.id = r.optLong("id");
        h.title = r.optString("movie".equals(type) ? "title" : "name");
        String date = r.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
        h.year = date.length() >= 4 ? date.substring(0, 4) : "";
        h.overview = r.optString("overview", "");
        h.poster = clean(r.optString("poster_path", ""));
        h.backdrop = clean(r.optString("backdrop_path", ""));
        h.rating = r.optDouble("vote_average", 0);
        JSONArray g = r.optJSONArray("genre_ids");
        List<String> names = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && names.size() < 2; i++) {
            String n = Tmdb.GENRES.get(g.optInt(i));
            if (n != null && !names.contains(n)) names.add(n);
        }
        h.genres = android.text.TextUtils.join(", ", names);
        return h;
    }

    /** Streaming (flat-rate) in India on an app installed on this TV? Sets the app. */
    private boolean available(Hit h) {
        try {
            JSONObject res = new JSONObject(Tmdb.api(dir, "/" + h.kind + "/" + h.id + "/watch/providers"));
            JSONObject in = res.optJSONObject("results") == null ? null : res.getJSONObject("results").optJSONObject("IN");
            JSONArray flat = in == null ? null : in.optJSONArray("flatrate");
            PackageManager pm = getPackageManager();
            for (int i = 0; flat != null && i < flat.length(); i++) {
                String app = PROVIDERS.get(flat.getJSONObject(i).optInt("provider_id"));
                if (app != null && pm.getLaunchIntentForPackage(app) != null
                        || app != null && pm.getLeanbackLaunchIntentForPackage(app) != null) {
                    h.app = app;
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "providers failed for " + h.title + ": " + e);
        }
        return false;
    }

    private void show(List<Hit> found) {
        hits.addAll(found);
        listen(false);
        if (hits.isEmpty()) {
            status.setText("Nothing from your apps for this.\nPress Back for Amazon's results.");
            return;
        }
        status.animate().alpha(0f).setStartDelay(0).setDuration(200).withEndAction(() -> status.setVisibility(View.GONE)).start();
        details.animate().alpha(1f).setDuration(300).start();
        // The posters rise into place one after another.
        for (int i = 0; i < hits.size(); i++) {
            View c = card(hits.get(i));
            row.addView(c);
            c.setAlpha(0f);
            c.setTranslationY(dp(48));
            c.setScaleX(0.9f);
            c.setScaleY(0.9f);
            c.animate().alpha(0.55f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(90L * Math.min(i, 8))
                    .setDuration(460).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        ui.postDelayed(() -> {
            if (row.getChildCount() > 0 && !isDestroyed()) row.getChildAt(0).requestFocus();
        }, 380);
    }

    /** Alexa's light: a bar sweeping along the top while searching; on results it flares and fades. */
    private void listen(boolean on) {
        int w = getResources().getDisplayMetrics().widthPixels;
        if (sweep != null) sweep.cancel();
        if (on) {
            bar.setScaleX(1f);
            bar.setAlpha(1f);
            glow.animate().alpha(1f).setDuration(400).start();
            sweep = android.animation.ValueAnimator.ofFloat(-w / 2f, w);
            sweep.setDuration(1150);
            sweep.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            sweep.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            sweep.addUpdateListener(a -> bar.setTranslationX((Float) a.getAnimatedValue()));
            sweep.start();
        } else {
            bar.animate().translationX(w / 4f).scaleX(2.4f).alpha(0f).setDuration(650)
                    .setInterpolator(new DecelerateInterpolator()).start();
            glow.animate().alpha(0f).setDuration(900).start();
        }
    }

    /** The query appears letter by letter, as if spoken. */
    private void typeOut(String q) {
        if (typing != null) typing.cancel();
        typing = android.animation.ValueAnimator.ofInt(0, q.length());
        typing.setDuration(Math.min(1200, 55L * q.length()));
        typing.addUpdateListener(a -> query.setText("\u201C" + q.substring(0, (Integer) a.getAnimatedValue())
                + ((Integer) a.getAnimatedValue() < q.length() ? "\u2009|" : "\u201D")));
        typing.start();
    }

    private View card(Hit h) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackground(Ui.round(0xFF16161C, dp(10)));
        v.setClipToOutline(true);
        v.setFocusable(true);
        v.setAlpha(0.55f);
        v.setContentDescription(h.title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(168));
        lp.rightMargin = dp(18);
        v.setLayoutParams(lp);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).alpha(has ? 1f : 0.55f)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            view.setElevation(has ? dp(10) : 0);
            if (has) focused(h);
        });
        v.setOnClickListener(view -> open(h));
        if (!h.poster.isEmpty()) {
            images.execute(() -> {
                Bitmap b = Tmdb.image("https://image.tmdb.org/t/p/w342" + h.poster, Bitmap.Config.RGB_565);
                ui.post(() -> {
                    if (b != null && !isDestroyed()) v.setImageBitmap(b);
                });
            });
        }
        return v;
    }

    /** The focused title's details, and its backdrop behind everything. */
    private void focused(Hit h) {
        title.setText(h.title);
        String rating = h.rating > 0 ? String.format(Locale.ROOT, "★ %.1f TMDB   ·   ", h.rating) : "";
        sub.setText(rating + (h.year.isEmpty() ? "" : h.year + "   ·   ") + ("movie".equals(h.kind) ? "Film" : "Series")
                + (h.genres.isEmpty() ? "" : "   ·   " + h.genres));
        overview.setText(h.overview);
        appLogo.setImageBitmap(logo(h.app));
        View[] parts = {title, sub, appLogo, overview};
        for (int i = 0; i < parts.length; i++) {
            parts[i].animate().cancel();
            parts[i].setAlpha(0f);
            parts[i].setTranslationX(-dp(16));
            parts[i].animate().alpha(parts[i] == appLogo ? 0.8f : 1f).translationX(0).setStartDelay(45L * i)
                    .setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
        }
        String url = h.backdrop.isEmpty() ? null : "https://image.tmdb.org/t/p/w1280" + h.backdrop;
        wantedBackdrop = url;
        if (url == null) {
            backdrop.animate().alpha(0f).setDuration(300).start();
            return;
        }
        Bitmap cached = backdrops.get(url);
        if (cached != null) {
            showBackdrop(cached);
            return;
        }
        images.execute(() -> {
            Bitmap b = Tmdb.image(url, Bitmap.Config.ARGB_8888);
            ui.post(() -> {
                if (b == null || isDestroyed()) return;
                backdrops.put(url, b);
                if (url.equals(wantedBackdrop)) showBackdrop(b);
            });
        });
    }

    private void showBackdrop(Bitmap b) {
        backdrop.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            backdrop.setImageBitmap(b);
            backdrop.setScaleX(1.08f);
            backdrop.setScaleY(1.08f);
            backdrop.animate().alpha(0.85f).scaleX(1f).scaleY(1f).setDuration(1400)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }).start();
    }

    private Bitmap logo(String app) {
        if (!logos.containsKey(app)) logos.put(app, BitmapFactory.decodeFile(new File(dir, "logos/" + app + ".png").getPath()));
        return logos.get(app);
    }

    // ---- opening --------------------------------------------------------------------------

    /** The title's own page where the app follows links (found on TMDB's watch page), else the app. */
    private void open(Hit h) {
        if (!Tmdb.LINK_HOSTS.containsKey(h.app) || h.link != null) {
            launch(h);
            return;
        }
        io.execute(() -> {
            h.link = Tmdb.link(h.kind, h.id, h.app);
            ui.post(() -> launch(h));
        });
    }

    private void launch(Hit h) {
        // Netflix and Apple TV never say what they play: this is how the light strip and Continue
        // watching know.
        Lights.opened(this, h.app, h.title);
        Watching.opened(this, h.app, h.title, h.link);
        PackageManager pm = getPackageManager();
        if (h.link != null && !h.link.isEmpty()) {
            Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(h.link)).setPackage(h.app);
            if (NETFLIX.equals(h.app)) link.putExtra("source", "30"); // Netflix follows links that say where from
            if (link.resolveActivity(pm) != null) {
                try {
                    startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    // the app itself, below
                }
            }
        }
        Intent app = PRIME.equals(h.app)
                ? new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PRIME, "com.amazon.firebatcore.deeplink.DeepLinkRoutingActivity"))
                : pm.getLeanbackLaunchIntentForPackage(h.app);
        if (app == null) app = pm.getLaunchIntentForPackage(h.app);
        try {
            startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException | NullPointerException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String clean(String path) {
        return path == null || "null".equals(path) ? "" : path;
    }

    private Typeface font(String name, Typeface fallback) {
        File f = new File(dir, "fonts/" + name);
        try {
            return f.isFile() ? Typeface.createFromFile(f) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView v = new TextView(this);
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(dp(8), 0, 0, 0x99000000);
        return v;
    }

    private FrameLayout.LayoutParams place(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}

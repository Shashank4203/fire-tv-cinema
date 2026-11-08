package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The wallpapers in cinema/: the fixed set plus the daily classics. Shared by Home and Dream. */
final class Walls {
    private Walls() {}

    /**
     * How many of the fixed set (Interstellar) join each day's films: with all ten among a dozen
     * classics, nearly every other wallpaper was Interstellar.
     */
    private static final int FIXED_PER_DAY = 2;

    /** Today's wallpapers: the daily films and a couple of the fixed set, in an order that changes each day. */
    static List<File> list(File dir) {
        long day = System.currentTimeMillis() / 86_400_000L;
        List<File> out = images(new File(dir, "pool/wallpapers"));
        List<File> fixed = images(new File(dir, "wallpapers"));
        Collections.sort(fixed);
        if (out.isEmpty()) {
            out.addAll(fixed); // no daily films yet: the fixed set alone
        } else {
            int n = Math.min(FIXED_PER_DAY, fixed.size());
            for (int i = 0; i < n; i++) out.add(fixed.get((int) ((day * n + i) % fixed.size())));
        }
        Collections.sort(out);
        Collections.shuffle(out, new Random(day));
        return out;
    }

    private static List<File> images(File folder) {
        File[] fs = folder.listFiles((d, n) -> n.matches("(?i).*\\.(png|jpe?g|webp)$"));
        return fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
    }

    /** "Film · year" for a wallpaper, from captions.txt beside it ("file | caption", "*" for all). */
    static String caption(File f) {
        String all = null;
        for (String line : read(new File(f.getParentFile(), "captions.txt"))) {
            String[] p = line.split("\\|", 2);
            if (p.length < 2) continue;
            String name = p[0].trim();
            if (name.equals(f.getName())) return p[1].trim();
            if (name.equals("*")) all = p[1].trim();
        }
        return all;
    }

    /** A line from the wallpaper's film, from quotes.txt beside it ("file | quote", "*" for all); null if none. */
    static String quote(File f) {
        List<String> mine = new ArrayList<>();
        for (String line : read(new File(f.getParentFile(), "quotes.txt"))) {
            String[] p = line.split("\\|", 2);
            if (p.length < 2) continue;
            String name = p[0].trim();
            if (name.equals(f.getName()) || name.equals("*")) mine.add(p[1].trim());
        }
        return mine.isEmpty() ? null : mine.get(new Random().nextInt(mine.size()));
    }

    /** Full-colour decode at about screen size: no banding in dark skies. */
    static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static List<String> read(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        } catch (IOException ignored) {
            // no file: nothing to read
        }
        return out;
    }
}

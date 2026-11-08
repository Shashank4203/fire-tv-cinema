package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How this strip turns LED power into light, from the light camera's matching (Calibrate, and
 * lightcam's fitStrip). Its controller switches all three LEDs on together at the start of every
 * cycle, and lit together they dim each other: the supply sags, and blue, which needs the most
 * voltage, loses most. Full white came out about 40 % red, 29 % green and 16 % blue of what each
 * LED gives alone; cyan, a quarter of each, was a green-teal; magenta a pinkish red. So the light
 * each LED gives per unit of power is measured alone, in pairs and all three, at a few total
 * powers (interpolated in between), and a colour's power is worked out backwards from the light
 * it should give.
 */
final class StripModel {
    /** From a colour's linear light to each LED's light, 3x3, row by row: the strip's own colours. */
    private final double[] k;
    /**
     * Per set of LEDs lit together (bits: 1 red, 2 green, 4 blue): rows of {total power, then each
     * LED's light per unit of its power}, by total power.
     */
    private final double[][][] eff = new double[8][][];

    private StripModel(double[] k) {
        this.k = k;
    }

    /** From the phone's answer without "strip": the matrix, then "rrggbb r g b" per colour shown; null if it isn't one. */
    static StripModel parse(String s) {
        String[] p = s.trim().split(" +");
        if (p.length < 9 || (p.length - 9) % 4 != 0) return null;
        try {
            double[] k = new double[9];
            for (int i = 0; i < 9; i++) k[i] = Double.parseDouble(p[i]);
            StripModel m = new StripModel(k);
            List<List<double[]>> rows = new ArrayList<>();
            for (int set = 0; set < 8; set++) rows.add(new ArrayList<>());
            for (int i = 9; i < p.length; i += 4) {
                int rgb = Integer.parseInt(p[i], 16);
                double[] power = {(rgb >> 16 & 0xFF) / 255.0, (rgb >> 8 & 0xFF) / 255.0, (rgb & 0xFF) / 255.0};
                double[] row = {power[0] + power[1] + power[2], 0, 0, 0};
                int set = 0;
                for (int c = 0; c < 3; c++) {
                    if (power[c] <= 0) continue;
                    set |= 1 << c;
                    row[1 + c] = Math.max(0.01, Double.parseDouble(p[i + 1 + c]) / power[c]);
                }
                if (set != 0) rows.get(set).add(row);
            }
            for (int set = 1; set < 8; set++) {
                double[][] r = rows.get(set).toArray(new double[0][]);
                Arrays.sort(r, (a, b) -> Double.compare(a[0], b[0]));
                m.eff[set] = r;
            }
            return m;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** How much of each LED's own light full white gives, as measured (red, green, blue). */
    double[] fullWhite() {
        return new double[] {eff(7, 0, 3), eff(7, 1, 3), eff(7, 2, 3)};
    }

    /** LED power (rrggbb) for a screen colour (sRGB) at brightness f (0..1, of the most this colour can give). */
    int drive(int rgb, double f) {
        double r = linear(rgb >> 16 & 0xFF), g = linear(rgb >> 8 & 0xFF), b = linear(rgb & 0xFF);
        double bright = Math.max(r, Math.max(g, b)) * f;
        double[] want = {Math.max(0, k[0] * r + k[1] * g + k[2] * b), Math.max(0, k[3] * r + k[4] * g + k[5] * b),
            Math.max(0, k[6] * r + k[7] * g + k[8] * b)};
        double top = Math.max(want[0], Math.max(want[1], want[2]));
        if (top <= 0 || bright <= 0) return 0;
        for (int c = 0; c < 3; c++) want[c] /= top;
        // The most light this colour can have, with the LEDs dimming each other: halving towards it.
        double[] power = new double[3];
        double lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            double mid = (lo + hi) / 2;
            if (solve(want, mid, power)) lo = mid;
            else hi = mid;
        }
        solve(want, lo * bright, power);
        return (int) Math.round(power[0] * 255) << 16 | (int) Math.round(power[1] * 255) << 8 | (int) Math.round(power[2] * 255);
    }

    /** The power that gives each LED want times s of light, in power; false if it can't. */
    private boolean solve(double[] want, double s, double[] power) {
        double[] got = new double[3];
        for (int c = 0; c < 3; c++) power[c] = Math.min(1, want[c] * s);
        for (int i = 0; i < 30; i++) {
            light(power, got);
            for (int c = 0; c < 3; c++) {
                power[c] = want[c] <= 0 ? 0 : Math.min(1, power[c] * want[c] * s / Math.max(1e-9, got[c]));
            }
        }
        light(power, got);
        for (int c = 0; c < 3; c++) if (Math.abs(got[c] - want[c] * s) > 0.02 * s) return false;
        return true;
    }

    /**
     * Each LED's light at this power. All switch on together, so the LED on least is lit alongside
     * the others for all its time, the next alongside the brightest for the rest of its, and so on.
     */
    private void light(double[] power, double[] out) {
        double total = power[0] + power[1] + power[2];
        int[] order = {0, 1, 2};
        for (int i = 1; i < 3; i++) {
            for (int j = i; j > 0 && power[order[j]] < power[order[j - 1]]; j--) {
                int t = order[j];
                order[j] = order[j - 1];
                order[j - 1] = t;
            }
        }
        out[0] = out[1] = out[2] = 0;
        double start = 0;
        for (int i = 0; i < 3; i++) {
            double end = power[order[i]];
            if (end <= start) continue;
            int set = 0;
            for (int j = i; j < 3; j++) set |= 1 << order[j];
            for (int j = i; j < 3; j++) out[order[j]] += (end - start) * eff(set, order[j], total);
            start = end;
        }
    }

    /** An LED's light per unit of power, lit with these others at this total power; 1 if never measured. */
    private double eff(int set, int c, double total) {
        double[][] e = eff[set];
        if (e == null || e.length == 0) return 1;
        if (total <= e[0][0]) return e[0][1 + c];
        for (int i = 1; i < e.length; i++) {
            if (total <= e[i][0]) {
                double t = (total - e[i - 1][0]) / Math.max(1e-9, e[i][0] - e[i - 1][0]);
                return e[i - 1][1 + c] + (e[i][1 + c] - e[i - 1][1 + c]) * t;
            }
        }
        return e[e.length - 1][1 + c];
    }

    static double linear(int v) {
        double x = v / 255.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }
}

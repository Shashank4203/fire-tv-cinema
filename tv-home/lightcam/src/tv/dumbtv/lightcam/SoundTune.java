package tv.dumbtv.lightcam;

import java.util.Arrays;

/**
 * Lining up with sound. The TV plays a test rhythm at the volume it is normally watched at (after
 * {@link #LEAD_MS} of quiet: four bars of kicks, snares and hi-hats at known moments, the same in
 * the TV app's SoundCalibrate), and the phone records each band's level as it hears it from where
 * it stands. Then, band by band, the thresholds are set from what was heard and replayed through
 * {@link Band} over the recording to check them against that band's own hits and any flashes
 * anywhere else (the quiet, the other drums).
 */
final class SoundTune {
    static final int LEAD_MS = 2000, BAR_MS = 2000, BARS = 4;
    /** Within a bar, in ms: kicks on 1 and 3, snares on 2 and 4, hi-hats on the off-beats. */
    private static final int[][] PATTERN = {{0, 1000}, {500, 1500}, {250, 750, 1250, 1750}};
    static final String[] NAMES = {"kicks", "snares", "hi-hats"};

    /** The musical defaults {how sharp, over the room, of the loudest} for bass, mids and highs. */
    static final double[][] DEFAULTS = {{1.52, 2.34, 0.39}, {1.52, 2.34, 0.39}, {1.77, 2.5, 0.45}};
    /** A hit counts as caught if a flash starts this soon around it (sound travels, blocks are 20 ms). */
    private static final long EARLY_MS = 150, LATE_MS = 150;

    static final class Result {
        final double[][] params = new double[3][];
        final int[] caught = new int[3], total = new int[3], wrong = new int[3];
        /** Per band: the room's noise and how loud the test hits arrived (for the log). */
        final double[] room = new double[3], level = new double[3];
        long offset;
        String why;
    }

    /**
     * t: block times on the phone's clock (ms); lv[band][i]: each block's level; start: when the
     * TV said the test was starting (it begins about {@link #LEAD_MS} later, plus the TV's delay).
     */
    static Result fit(long[] t, double[][] lv, int n, long start) {
        Result r = new Result();
        long[][] hits = new long[3][];
        for (int b = 0; b < 3; b++) {
            hits[b] = new long[PATTERN[b].length * BARS];
            for (int bar = 0, k = 0; bar < BARS; bar++) for (int at : PATTERN[b]) hits[b][k++] = bar * BAR_MS + at;
            r.total[b] = hits[b].length;
        }
        // When did the rhythm really start? The moment that best lines the kicks' onsets up with the
        // bass: loud just after, quiet just before (aiming at their loudest landed ~100 ms late).
        double bestScore = Double.NEGATIVE_INFINITY;
        for (long o = -200; o <= 1500; o += 10) {
            double score = 0;
            for (long k : hits[0]) {
                long at = start + LEAD_MS + o + k;
                score += maxIn(t, lv[0], n, at, at + 30) - 2 * maxIn(t, lv[0], n, at - 80, at - 20);
            }
            if (score > bestScore) {
                bestScore = score;
                r.offset = o;
            }
        }
        long s0 = start + LEAD_MS + r.offset;
        double[] quiet = new double[n];
        int q = 0;
        for (int i = 0; i < n; i++) if (t[i] < s0 - 300) quiet[q++] = lv[0][i];
        double room = q == 0 ? 0 : median(Arrays.copyOf(quiet, q));
        double[] kicks = new double[hits[0].length];
        for (int k = 0; k < kicks.length; k++) kicks[k] = maxIn(t, lv[0], n, s0 + hits[0][k], s0 + hits[0][k] + 80);
        if (median(kicks) < room * 3 + 5) {
            r.why = "the test beat was too quiet to hear. Is the volume up, and the phone near the speakers?";
            return r;
        }
        // Band by band. Only "how far over the room" depends on the volume and where the phone
        // stands: it goes to the geometric middle between the room's noise and how loud this band's
        // hits arrive. How sharp a hit is and how close to the loudest stay at their musical defaults
        // (clean test drums would pass almost anything, and real music is not so clean), and are
        // eased only as far as needed to catch three in four of the test hits (echo smears them).
        for (int b = 0; b < 3; b++) {
            double roomB;
            double[] own = new double[hits[b].length];
            for (int k = 0; k < own.length; k++) own[k] = maxIn(t, lv[b], n, s0 + hits[b][k], s0 + hits[b][k] + 80);
            double[] quietB = new double[n];
            int qb = 0;
            for (int i = 0; i < n; i++) if (t[i] < s0 - 300) quietB[qb++] = lv[b][i];
            roomB = Math.max(1, qb == 0 ? 1 : median(Arrays.copyOf(quietB, qb)));
            double hitB = median(own);
            r.room[b] = roomB;
            r.level[b] = hitB;
            double over = Math.max(1.5, Math.min(6, Math.sqrt(hitB / roomB)));
            double[] base = DEFAULTS[b];
            double[] chosen = null;
            int[] chosenScore = null;
            double top = Double.NEGATIVE_INFINITY;
            search:
            for (double ease = 0; ease <= 0.6001; ease += 0.1) {
                double ratio = Math.max(1.15, base[0] - ease);
                for (double ofPeak : new double[] {base[2], Math.max(0.2, base[2] - 0.1), 0.2}) {
                    int[] tpfp = replay(t, lv[b], n, hits[b], masks(hits, b), s0, new Band(ratio, over, ofPeak));
                    double score = tpfp[0] - 1.5 * tpfp[1];
                    if (score > top) {
                        top = score;
                        chosen = new double[] {ratio, over, ofPeak};
                        chosenScore = tpfp;
                    }
                    if (tpfp[0] * 4 >= own.length * 3 && tpfp[1] <= 1) {
                        chosen = new double[] {ratio, over, ofPeak};
                        chosenScore = tpfp;
                        break search;
                    }
                }
            }
            r.params[b] = chosen;
            r.caught[b] = chosenScore[0];
            r.wrong[b] = chosenScore[1];
        }
        return r;
    }

    /**
     * The hits of the bands that outrank this one (kicks over snares, both over hi-hats): a flash
     * of this band at the same moment is drowned by theirs live, so it counts neither way.
     */
    private static long[] masks(long[][] hits, int band) {
        int count = 0;
        for (int b = 0; b < band; b++) count += hits[b].length;
        long[] out = new long[count];
        for (int b = 0, k = 0; b < band; b++) for (long h : hits[b]) out[k++] = h;
        return out;
    }

    /** {caught, wrong}: the band's flashes (at most one per 250 ms, as live) against its own hits. */
    private static int[] replay(long[] t, double[] v, int n, long[] own, long[] masked, long s0, Band band) {
        boolean[] used = new boolean[own.length];
        int tp = 0, fp = 0;
        long last = Long.MIN_VALUE / 2;
        for (int i = 0; i < n; i++) {
            if (!band.hit(v[i]) || t[i] - last < Pulse.GAP_MS) continue;
            last = t[i];
            int match = -1;
            for (int k = 0; k < own.length && match < 0; k++) {
                long at = s0 + own[k];
                if (!used[k] && t[i] >= at - EARLY_MS && t[i] <= at + LATE_MS) match = k;
            }
            boolean drowned = false;
            for (long m : masked) drowned |= Math.abs(t[i] - (s0 + m)) <= LATE_MS;
            if (match >= 0) {
                used[match] = true;
                tp++;
            } else if (!drowned) {
                fp++;
            }
        }
        return new int[] {tp, fp};
    }

    private static double maxIn(long[] t, double[] v, int n, long from, long to) {
        double m = 0;
        for (int i = 0; i < n; i++) if (t[i] >= from && t[i] <= to) m = Math.max(m, v[i]);
        return m;
    }

    private static double median(double[] a) {
        if (a.length == 0) return 0;
        double[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}

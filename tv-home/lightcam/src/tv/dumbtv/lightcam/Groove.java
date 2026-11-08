package tv.dumbtv.lightcam;

/**
 * What the gradual mode follows: how much is going on in the sound, 0..1, every 20 ms, or -1 when
 * there is no sound to follow. Eight octave-wide bands, from 60 Hz to 8 kHz, are heard apart.
 *
 * Every rise in any band counts (the sum of each band's rise in dB over its last 60 ms, its
 * "spectral flux"): kicks, snares, claps and hi-hats, but also tabla and dholak strokes, plucked
 * strings and sung syllables, which the strobe's three strict beat bands pass over. A rise lifts
 * the glow at once, which then eases back down; and the overall loudness, against the last few
 * seconds, sets a base under it, so a loud passage glows brighter than a quiet one even with no
 * sharp beats. Rises are measured in dB, so the volume doesn't change how much a beat counts; a
 * band only counts once it is clear of the room's own noise.
 */
final class Groove {
    static final double[] CENTRES = {60, 120, 250, 500, 1000, 2000, 4000, 8000};
    /**
     * A band's rise counts only when the band is this far over the room's noise in it. Noise in a
     * low band jumps several dB from one 20 ms block to the next (an octave at 60 Hz is only 40 Hz
     * wide), so the margin is wide and levels are taken over two blocks.
     */
    private static final double ABOVE_ROOM_DB = 10;
    /** Less than this between the room and the recent loudest: nothing to follow. */
    private static final double QUIET_DB = 8;
    /** This long (in 20 ms blocks) with no rise and little loudness: a quiet passage, the picture's again. */
    private static final int STILL_BLOCKS = 75;

    /** Tunable from lightcam.conf: the flux (dB) that starts to show and the flux that shows fully. */
    volatile double fluxMin = 3, fluxFull = 18;
    /** How fast the glow eases back, per 20 ms (0.85: halves in about 0.09 s). */
    volatile double release = 0.85;
    /** How much of the light the loudness alone can give, under the beats' glow. */
    volatile double loudShare = 0.25;

    private final Biquad[] filters = new Biquad[CENTRES.length];
    private final double[] power = new double[CENTRES.length];
    /** Each band's power over the last two blocks (40 ms): steadier than one block's. */
    private final double[] smooth = new double[CENTRES.length];
    private final double[] room = new double[CENTRES.length];
    private final double[][] recent = new double[CENTRES.length][3];
    /** The last block's band levels (dB), for recording. */
    final double[] levels = new double[CENTRES.length];
    private double roomDb = Double.MAX_VALUE, peakDb, loud, glow;
    private int still;
    /** The last block's flux, loudness and glow, for recording and the status line. */
    double flux, loudness, glowing;

    Groove(int rate) {
        for (int b = 0; b < CENTRES.length; b++) {
            filters[b] = Biquad.bandPass(CENTRES[b], 1.41, rate);
            room[b] = Double.MAX_VALUE;
        }
    }

    void sample(double x) {
        for (int b = 0; b < filters.length; b++) {
            double y = filters[b].process(x);
            power[b] += y * y;
        }
    }

    /** Closes a block of n samples: the groove, 0..1, or -1 when there's no sound to follow. */
    double block(int n) {
        double total = 0;
        flux = 0;
        for (int b = 0; b < CENTRES.length; b++) {
            double p = power[b] / Math.max(1, n);
            power[b] = 0;
            total += p;
            double e = 10 * Math.log10((p + smooth[b]) / 2 + 1e-3);
            smooth[b] = p;
            levels[b] = e;
            room[b] = Math.min(e, room[b] + 0.01); // the room's noise, creeping up 0.5 dB/s
            double[] r = recent[b];
            double before = (r[0] + r[1] + r[2]) / 3;
            if (e > room[b] + ABOVE_ROOM_DB) flux += Math.max(0, e - before);
            r[0] = r[1];
            r[1] = r[2];
            r[2] = e;
        }
        double all = 10 * Math.log10(total + 1e-3);
        roomDb = Math.min(all, roomDb + 0.01);
        peakDb = Math.max(all, peakDb - 0.02); // the recent loudest, sinking 1 dB/s
        if (peakDb - roomDb < QUIET_DB) {
            loud = glow = loudness = glowing = 0;
            return -1;
        }
        double l = clamp((all - roomDb - 3) / Math.max(6, peakDb - roomDb - 3));
        loud += (l - loud) * 0.3;
        glow = Math.max(glow * release, clamp((flux - fluxMin) / Math.max(1, fluxFull - fluxMin)));
        loudness = loud;
        glowing = glow;
        // Music stopped, or a quiet scene: after 1.5 s the light goes back to the picture instead of
        // sitting dim until the loud part is forgotten (the recent loudest sinks only 1 dB a second).
        still = glow < 0.25 && loud < 0.15 ? still + 1 : 0;
        if (still > STILL_BLOCKS) return -1;
        double base = loudShare * loud;
        return 1 - (1 - base) * (1 - glow); // the beats' glow over the loudness's base
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}

package tv.dumbtv.lightcam;

/**
 * One band of the sound (bass, mids or highs): its level, its recent loudest, the room's noise in
 * it, and its last 60 ms. Fed one 20 ms block at a time; a hit is the band jumping well over its
 * last 60 ms ({@link #ratio}), well over the room ({@link #overFloor}) and not far below the recent
 * loudest ({@link #ofPeak}). The thresholds are set by lining up with sound ({@link SoundTune}).
 */
final class Band {
    volatile double ratio, overFloor, ofPeak;
    double env, peak, floor = Double.MAX_VALUE;
    int hold, hits;
    private final double[] recent = new double[3];

    Band(double ratio, double overFloor, double ofPeak) {
        this.ratio = ratio;
        this.overFloor = overFloor;
        this.ofPeak = ofPeak;
    }

    /** One block's level in; true for a hit. */
    boolean hit(double v) {
        if (v >= env) {
            env = v;
            hold = 5;
        } else if (hold > 0) {
            hold--;
        } else {
            env = Math.max(v, env * 0.8);
        }
        peak = Math.max(env, peak * 0.99); // recent loudest, halves in ~1.4 s
        // Room noise, creeping up only ~1 %/s: a song playing for a while must not become the
        // "quiet" it is measured against, or steady music would read as silence.
        floor = Math.min(env, floor * 1.0002 + 0.01);
        double before = (recent[0] + recent[1] + recent[2]) / 3;
        recent[0] = recent[1];
        recent[1] = recent[2];
        recent[2] = v;
        return v > before * ratio && v > floor * overFloor && v > peak * ofPeak;
    }

    /** Nothing worth pulsing to: the loudest of late is barely above the quietest. */
    boolean quiet() {
        return peak < floor * 1.3 + 4;
    }
}

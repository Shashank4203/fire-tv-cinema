package tv.dumbtv.lightcam;

/**
 * A second-order filter (the Audio EQ Cookbook's low-, high- and band-pass): 12 dB per octave,
 * steep enough that a kick drum stays out of the mids and a snare out of the highs.
 */
final class Biquad {
    private final double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        this.b0 = b0 / a0;
        this.b1 = b1 / a0;
        this.b2 = b2 / a0;
        this.a1 = a1 / a0;
        this.a2 = a2 / a0;
    }

    static Biquad lowPass(double hz, double rate) {
        double w = 2 * Math.PI * hz / rate, c = Math.cos(w), alpha = Math.sin(w) / (2 * 0.7071);
        return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }

    static Biquad highPass(double hz, double rate) {
        double w = 2 * Math.PI * hz / rate, c = Math.cos(w), alpha = Math.sin(w) / (2 * 0.7071);
        return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }

    /** A band-pass with 0 dB at its centre; Q 1.41 is about an octave wide. */
    static Biquad bandPass(double hz, double q, double rate) {
        double w = 2 * Math.PI * hz / rate, c = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        return new Biquad(alpha, 0, -alpha, 1 + alpha, -2 * c, 1 - alpha);
    }

    double process(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }
}

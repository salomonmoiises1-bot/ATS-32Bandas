package com.sjbstudio.eq32.core;

/**
 * Direct Form II Transposed Bi-quadratic filter implementation
 * Reference: Robert Bristow-Johnson "Audio EQ Cookbook"
 */
public final class Biquad {
    // Normalized coefficients (a0 is normalized to 1.0)
    private double b0 = 1.0;
    private double b1 = 0.0;
    private double b2 = 0.0;
    private double a1 = 0.0;
    private double a2 = 0.0;

    // Filter state delay registers (for single-channel real-time processing)
    private double z1 = 0.0;
    private double z2 = 0.0;

    public Biquad() {
        reset();
    }

    public void reset() {
        z1 = 0.0;
        z2 = 0.0;
    }

    /**
     * Configures a Peaking Equalizer filter.
     * @param f0 Center frequency in Hertz
     * @param Fs Sampling rate in Hertz
     * @param Q Quality factor (default 1.4142)
     * @param gainDb Gain in decibels (-12 dB to +12 dB)
     */
    public void setPeaking(double f0, double Fs, double Q, double gainDb) {
        if (Math.abs(gainDb) < 0.001) {
            setPassThrough();
            return;
        }

        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = (2.0 * Math.PI * f0) / Fs;
        double sinW0 = Math.sin(w0);
        double cosW0 = Math.cos(w0);
        double alpha = sinW0 / (2.0 * Q);

        double a0 = 1.0 + alpha / A;
        this.b0 = (1.0 + alpha * A) / a0;
        this.b1 = (-2.0 * cosW0) / a0;
        this.b2 = (1.0 - alpha * A) / a0;
        this.a1 = (-2.0 * cosW0) / a0;
        this.a2 = (1.0 - alpha / A) / a0;
    }

    /**
     * Configures a Low-Shelf Equalizer filter.
     * @param f0 Shelf turnover frequency in Hertz
     * @param Fs Sampling rate in Hertz
     * @param gainDb Gain in decibels
     * @param S Shelf slope parameter (typically 1.0)
     */
    public void setLowShelf(double f0, double Fs, double gainDb, double S) {
        if (Math.abs(gainDb) < 0.001) {
            setPassThrough();
            return;
        }

        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = (2.0 * Math.PI * f0) / Fs;
        double sinW0 = Math.sin(w0);
        double cosW0 = Math.cos(w0);
        double alpha = (sinW0 / 2.0) * Math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
        double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

        double a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha;
        this.b0 = (A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0;
        this.b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)) / a0;
        this.b2 = (A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0;
        this.a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW0)) / a0;
        this.a2 = ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0;
    }

    /**
     * Configures a High-Shelf Equalizer filter.
     * @param f0 Shelf turnover frequency in Hertz
     * @param Fs Sampling rate in Hertz
     * @param gainDb Gain in decibels
     * @param S Shelf slope parameter (typically 1.0)
     */
    public void setHighShelf(double f0, double Fs, double gainDb, double S) {
        if (Math.abs(gainDb) < 0.001) {
            setPassThrough();
            return;
        }

        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = (2.0 * Math.PI * f0) / Fs;
        double sinW0 = Math.sin(w0);
        double cosW0 = Math.cos(w0);
        double alpha = (sinW0 / 2.0) * Math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
        double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

        double a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha;
        this.b0 = (A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0;
        this.b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0;
        this.b2 = (A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0;
        this.a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0;
        this.a2 = ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0;
    }

    private void setPassThrough() {
        b0 = 1.0;
        b1 = 0.0;
        b2 = 0.0;
        a1 = 0.0;
        a2 = 0.0;
    }

    /**
     * Process a single audio sample through Direct Form II Transposed.
     */
    public float process(float in) {
        double out = b0 * in + z1;
        z1 = b1 * in - a1 * out + z2;
        z2 = b2 * in - a2 * out;
        return (float) out;
    }

    /**
     * Block processing of audio samples in-place or buffer-to-buffer.
     */
    public void process(float[] input, float[] output, int count) {
        for (int i = 0; i < count; i++) {
            output[i] = process(input[i]);
        }
    }

    /**
     * Calculates the filter's magnitude response at target frequency in decibels.
     */
    public double magnitudeAtDb(double freq, double Fs) {
        double phi = (2.0 * Math.PI * freq) / Fs;
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);
        double cos2Phi = Math.cos(2.0 * phi);
        double sin2Phi = Math.sin(2.0 * phi);

        double rNum = b0 + b1 * cosPhi + b2 * cos2Phi;
        double iNum = -b1 * sinPhi - b2 * sin2Phi;
        double numSq = rNum * rNum + iNum * iNum;

        double rDen = 1.0 + a1 * cosPhi + a2 * cos2Phi;
        double iDen = -a1 * sinPhi - a2 * sin2Phi;
        double denSq = rDen * rDen + iDen * iDen;

        if (denSq < 1e-12) return 0.0;
        double ratio = numSq / denSq;
        return ratio > 0 ? 10.0 * Math.log10(ratio) : -100.0;
    }

    public double getB0() { return b0; }
    public double getB1() { return b1; }
    public double getB2() { return b2; }
    public double getA1() { return a1; }
    public double getA2() { return a2; }
}

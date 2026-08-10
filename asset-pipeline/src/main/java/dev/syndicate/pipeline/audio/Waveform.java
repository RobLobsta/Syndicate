/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A mono buffer of 32-bit float samples, and the WAV file it becomes.
 *
 * <p>Kept in floats until the moment of writing, because everything the synthesiser does — summing
 * modes, applying envelopes, crossfading a loop — accumulates, and quantising between stages is how
 * a synthesised sound acquires a hiss nobody can find the source of.
 */
public final class Waveform {

    /** Sample rate. 48 kHz because that is what every audio device and every engine wants. */
    public static final int SAMPLE_RATE_HZ = 48_000;

    /** Peak the normaliser targets, in linear amplitude. About -1 dBFS, so nothing clips on mix. */
    public static final float NORMALISE_PEAK = 0.89f;

    private final float[] samples;

    public Waveform(int sampleCount) {
        this.samples = new float[Math.max(1, sampleCount)];
    }

    /** A buffer of the given duration. */
    public static Waveform ofSeconds(double seconds) {
        return new Waveform((int) Math.round(seconds * SAMPLE_RATE_HZ));
    }

    public int length() {
        return samples.length;
    }

    public float[] samples() {
        return samples;
    }

    public float get(int index) {
        return samples[index];
    }

    public void add(int index, float value) {
        samples[index] += value;
    }

    /** Seconds elapsed at a sample index. */
    public static double timeAt(int index) {
        return (double) index / SAMPLE_RATE_HZ;
    }

    /** Scales the whole buffer so its loudest sample sits at {@link #NORMALISE_PEAK}. */
    public Waveform normalise() {
        float peak = 0f;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        if (peak > 1e-9f) {
            float gain = NORMALISE_PEAK / peak;
            for (int i = 0; i < samples.length; i++) {
                samples[i] *= gain;
            }
        }
        return this;
    }

    /**
     * Applies a short fade at each end, so a one-shot neither clicks in nor clicks out.
     *
     * <p>A buffer that starts at a non-zero sample is a step function, and a step is a click across
     * the whole spectrum. Two milliseconds is inaudible as a fade and completely removes it.
     */
    public Waveform fadeEnds(double seconds) {
        int fade = Math.min(samples.length / 2, (int) Math.round(seconds * SAMPLE_RATE_HZ));
        for (int i = 0; i < fade; i++) {
            float gain = (float) i / fade;
            samples[i] *= gain;
            samples[samples.length - 1 - i] *= gain;
        }
        return this;
    }

    /**
     * Makes the buffer loop without a seam, by crossfading its tail over its head.
     *
     * <p>D15-R38 rules generative audio out for loops precisely because of the seam. A synthesised
     * loop can avoid one honestly: the last {@code seconds} of the buffer are mixed into the first
     * {@code seconds} with complementary equal-power gains and then discarded, so the sample after
     * the end is exactly the sample at the start and the join carries no discontinuity in value or
     * in slope.
     *
     * @return a new, shorter buffer that loops cleanly
     */
    public Waveform loopable(double seconds) {
        int fade = Math.min(samples.length / 3, (int) Math.round(seconds * SAMPLE_RATE_HZ));
        if (fade <= 1) {
            return this;
        }
        int kept = samples.length - fade;
        Waveform out = new Waveform(kept);
        System.arraycopy(samples, 0, out.samples, 0, kept);
        for (int i = 0; i < fade; i++) {
            // Equal power, so the crossfaded region does not dip in loudness the way a linear
            // crossfade of two uncorrelated signals does.
            double t = (double) i / fade;
            float headGain = (float) Math.sqrt(t);
            float tailGain = (float) Math.sqrt(1.0 - t);
            out.samples[i] = out.samples[i] * headGain + samples[kept + i] * tailGain;
        }
        return out;
    }

    /** Writes a 16-bit PCM mono RIFF/WAVE file. */
    public void writeWav(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            writeWav(out);
        }
    }

    /** Writes the WAV bytes to a stream, for a test that wants them without a file. */
    public void writeWav(OutputStream out) throws IOException {
        int dataBytes = samples.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16); // PCM chunk size
        header.putShort((short) 1); // PCM
        header.putShort((short) 1); // mono
        header.putInt(SAMPLE_RATE_HZ);
        header.putInt(SAMPLE_RATE_HZ * 2); // byte rate
        header.putShort((short) 2); // block align
        header.putShort((short) 16); // bits per sample
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(dataBytes);
        out.write(header.array());

        ByteBuffer data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            // Clamped before the cast: a float above 1.0 wraps to a large negative short, which is
            // the loudest possible click rather than the loudest possible sample.
            float clamped = Math.max(-1f, Math.min(1f, sample));
            data.putShort((short) Math.round(clamped * Short.MAX_VALUE));
        }
        out.write(data.array());
    }
}

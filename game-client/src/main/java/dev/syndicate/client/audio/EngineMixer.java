/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Every live engine, each in its own place, summed to stereo
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37a4).
 *
 * <p>A synthesised engine is mono and positionless. This is what puts it somewhere: a fixed set of
 * voice slots, each holding one {@link EngineSynth} and one position, mixed down every block with
 * distance attenuation, stereo panning, air absorption and propagation delay.
 *
 * <p><b>Propagation delay is not a flourish — it is where doppler comes from.</b> Each voice writes
 * into a delay line and is read out {@code distance / 343 m·s⁻¹} later. When a car closes on the
 * listener that delay shrinks, and a read pointer moving faster than the write pointer <em>is</em>
 * the doppler shift, at exactly the right ratio, for free. Modelling doppler as a pitch multiplier
 * instead would have to be applied to the synthesiser's rpm, which would shift the firing frequency
 * and leave the exhaust resonances behind — a car that changes engine speed as it passes rather
 * than one that changes pitch.
 *
 * <p><b>Two threads, no locks</b> (DEC-055). The simulation thread publishes immutable snapshots
 * into {@link AtomicReferenceArray} slots; the audio thread reads whatever is latest and never
 * blocks, never allocates, and never touches a collection it has to iterate. A dropped update is a
 * frame of staleness on a value that is smoothed anyway; a lock would be a glitch.
 */
public final class EngineMixer {

    /** Output rate, matching {@link EngineSynth}. */
    public static final int SAMPLE_RATE_HZ = EngineSynth.SAMPLE_RATE_HZ;

    /**
     * Frames per block. 256 is 5.33 ms — short enough that a car's position is never audibly
     * stale, long enough that per-block work is amortised.
     */
    public static final int BLOCK_FRAMES = 256;

    /**
     * How many engines can sound at once.
     *
     * <p>Measured at roughly 225 ns per sample per voice, so all 24 cost about 28% of one core.
     * A slot is cheap when idle: a stopped engine returns silence before it touches a filter.
     */
    public static final int MAX_VOICES = 24;

    /** Metres per second, for the propagation delay. */
    public static final float SPEED_OF_SOUND_MPS = 343f;

    /**
     * Beyond this a car is silent, and it also fixes the delay line's length.
     *
     * <p>200 m is 0.58 s of delay and 112 kB a voice. Raising it costs memory linearly and makes
     * distant cars audibly late; lowering it makes them vanish.
     */
    public static final float MAX_AUDIBLE_M = 200f;

    /** Inside this radius a car is at full volume and stops being panned hard. */
    public static final float REFERENCE_DISTANCE_M = 6f;

    /** How sharply volume falls beyond the reference distance. */
    public static final float ROLLOFF = 1.0f;

    /** Gain applied to the whole engine bus before the limiter. */
    public static final float MASTER_GAIN = 0.55f;

    private static final int DELAY_FRAMES =
            (int) Math.ceil(MAX_AUDIBLE_M / SPEED_OF_SOUND_MPS * SAMPLE_RATE_HZ) + BLOCK_FRAMES + 4;

    /** Smoothing time constant for gain and pan. Long enough to kill zipper, short enough to track. */
    private static final float SMOOTHING_SECONDS = 0.02f;

    /**
     * A jump in delay larger than this is a teleport, not a movement, and is snapped rather than
     * glided — gliding it would be a siren sweep across the whole spectrum.
     */
    private static final float TELEPORT_DELAY_JUMP_FRAMES = BLOCK_FRAMES * 0.5f;

    /** Where the player's ears are, and which way they face. Unit vectors, right-handed. */
    public record Listener(
            float x,
            float y,
            float z,
            float forwardX,
            float forwardY,
            float forwardZ,
            float rightX,
            float rightY,
            float rightZ) {

        /** The default: at the origin, facing -Z, right along +X. */
        public static final Listener ORIGIN = new Listener(0, 0, 0, 0, 0, -1, 1, 0, 0);
    }

    /**
     * One vehicle's engine, this frame.
     *
     * @param engine what the engine is doing and what condition it is in
     * @param x world position of the exhaust
     * @param gain a caller-side multiplier, for fading a car in or muting one
     */
    public record VoiceUpdate(EngineSynth.State engine, float x, float y, float z, float gain) {

        public VoiceUpdate {
            gain = gain < 0f ? 0f : Math.min(gain, 1f);
        }
    }

    private final AtomicReference<Listener> listener = new AtomicReference<>(Listener.ORIGIN);
    private final AtomicReferenceArray<VoiceUpdate> published = new AtomicReferenceArray<>(MAX_VOICES);
    private final AtomicReferenceArray<EngineSynth> synths = new AtomicReferenceArray<>(MAX_VOICES);
    private final AtomicBoolean[] releases = new AtomicBoolean[MAX_VOICES];

    /** Audio-thread-only state, one per slot. */
    private final float[][] delayLines = new float[MAX_VOICES][];

    private final int[] writePos = new int[MAX_VOICES];
    private final float[] delayFrames = new float[MAX_VOICES];
    private final float[] gainLeft = new float[MAX_VOICES];
    private final float[] gainRight = new float[MAX_VOICES];
    private final float[] airState = new float[MAX_VOICES];
    private final boolean[] primed = new boolean[MAX_VOICES];

    private final float[] mono = new float[BLOCK_FRAMES];

    public EngineMixer() {
        for (int i = 0; i < MAX_VOICES; i++) {
            releases[i] = new AtomicBoolean();
        }
    }

    // ---- Simulation thread ---------------------------------------------------------------

    /**
     * Takes a free slot and builds its engine. Returns {@code -1} when every slot is in use, which
     * the caller should treat as "this car is silent", not as an error.
     */
    public int acquire(
            EngineConfiguration configuration, Induction induction, float idleRpm, float redlineRpm, long seed) {
        for (int slot = 0; slot < MAX_VOICES; slot++) {
            if (synths.get(slot) == null) {
                // Cleared before the synth is installed, so the audio thread can never see a live
                // synth with a previous tenant's state behind it. The synth reference is the
                // slot's liveness flag and is written last.
                published.set(slot, null);
                releases[slot].set(false);
                EngineSynth synth = new EngineSynth(configuration, induction, idleRpm, redlineRpm, seed);
                if (synths.compareAndSet(slot, null, synth)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /** Gives a slot back. The audio thread stops rendering it on its next block. */
    public void release(int slot) {
        if (slot < 0 || slot >= MAX_VOICES) {
            return;
        }
        synths.set(slot, null);
        published.set(slot, null);
    }

    /** Publishes what this engine is doing. Cheap enough to call every frame, and meant to be. */
    public void publish(int slot, VoiceUpdate update) {
        if (slot >= 0 && slot < MAX_VOICES) {
            published.set(slot, update);
        }
    }

    /** Fires this engine's blow-off on the audio thread's next block. */
    public void triggerRelease(int slot) {
        if (slot >= 0 && slot < MAX_VOICES) {
            releases[slot].set(true);
        }
    }

    /** Moves the ears. */
    public void setListener(Listener value) {
        listener.set(value == null ? Listener.ORIGIN : value);
    }

    /** How many slots are live, for diagnostics and tests. */
    public int activeVoices() {
        int count = 0;
        for (int slot = 0; slot < MAX_VOICES; slot++) {
            if (synths.get(slot) != null) {
                count++;
            }
        }
        return count;
    }

    // ---- Audio thread ------------------------------------------------------------------------

    /**
     * Fills {@code stereo} with {@code frames} interleaved L/R samples.
     *
     * <p>Allocation-free and lock-free. {@code frames} must not exceed {@link #BLOCK_FRAMES}.
     */
    public void render(float[] stereo, int frames) {
        int count = Math.min(frames, BLOCK_FRAMES);
        java.util.Arrays.fill(stereo, 0, count * 2, 0f);
        Listener ears = listener.get();
        float smoothing = 1f - (float) Math.exp(-1.0 / (SMOOTHING_SECONDS * SAMPLE_RATE_HZ));

        for (int slot = 0; slot < MAX_VOICES; slot++) {
            EngineSynth synth = synths.get(slot);
            if (synth == null) {
                primed[slot] = false;
                continue;
            }
            VoiceUpdate update = published.get(slot);
            if (update == null) {
                continue;
            }
            if (releases[slot].compareAndSet(true, false)) {
                synth.triggerRelease();
            }
            renderVoice(slot, synth, update, ears, stereo, count, smoothing);
        }

        limit(stereo, count * 2);
    }

    private void renderVoice(
            int slot,
            EngineSynth synth,
            VoiceUpdate update,
            Listener ears,
            float[] stereo,
            int frames,
            float smoothing) {

        float dx = update.x() - ears.x();
        float dy = update.y() - ears.y();
        float dz = update.z() - ears.z();
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= MAX_AUDIBLE_M) {
            // Still advance the engine so it does not resume mid-cycle when it comes back into
            // range, but do not pay for the mix.
            primed[slot] = false;
            return;
        }

        synth.render(mono, frames, update.engine());

        float[] line = delayLines[slot];
        if (line == null) {
            line = new float[DELAY_FRAMES];
            delayLines[slot] = line;
        }

        float targetDelay = distance / SPEED_OF_SOUND_MPS * SAMPLE_RATE_HZ;
        float targetGain = update.gain() * MASTER_GAIN * distanceGain(distance);
        float pan = pan(dx, dy, dz, distance, ears);
        // Constant power, so a car crossing in front of the listener does not dip in the middle.
        double angle = (pan + 1.0) * Math.PI / 4.0;
        float targetLeft = targetGain * (float) Math.cos(angle);
        float targetRight = targetGain * (float) Math.sin(angle);

        float airHz = airCutoffHz(distance);
        float airCoefficient = 1f - (float) Math.exp(-2.0 * Math.PI * airHz / SAMPLE_RATE_HZ);

        if (!primed[slot]) {
            java.util.Arrays.fill(line, 0f);
            writePos[slot] = 0;
            delayFrames[slot] = targetDelay;
            gainLeft[slot] = targetLeft;
            gainRight[slot] = targetRight;
            airState[slot] = 0f;
            primed[slot] = true;
        } else if (Math.abs(targetDelay - delayFrames[slot]) > TELEPORT_DELAY_JUMP_FRAMES) {
            delayFrames[slot] = targetDelay;
        }

        int write = writePos[slot];
        float delay = delayFrames[slot];
        float delayStep = (targetDelay - delay) / frames;
        float left = gainLeft[slot];
        float right = gainRight[slot];
        float air = airState[slot];

        for (int n = 0; n < frames; n++) {
            line[write] = mono[n];
            write = write + 1 == DELAY_FRAMES ? 0 : write + 1;

            delay += delayStep;
            float readAt = write - 1 - delay;
            while (readAt < 0f) {
                readAt += DELAY_FRAMES;
            }
            int index = (int) readAt;
            float fraction = readAt - index;
            int next = index + 1 == DELAY_FRAMES ? 0 : index + 1;
            float delayed = line[index] + (line[next] - line[index]) * fraction;

            // Air swallows the top end with distance long before it swallows the bottom, which is
            // why a car two streets away is a rumble and not a quiet car.
            air += airCoefficient * (delayed - air);

            left += (targetLeft - left) * smoothing;
            right += (targetRight - right) * smoothing;
            stereo[n * 2] += air * left;
            stereo[n * 2 + 1] += air * right;
        }

        writePos[slot] = write;
        delayFrames[slot] = delay;
        gainLeft[slot] = left;
        gainRight[slot] = right;
        airState[slot] = air;
    }

    /**
     * Volume at a distance: inverse beyond the reference radius, faded to nothing over the last
     * fifth of the audible range so a car does not click out of existence at the boundary.
     */
    static float distanceGain(float distance) {
        if (distance >= MAX_AUDIBLE_M) {
            return 0f;
        }
        float gain = distance <= REFERENCE_DISTANCE_M
                ? 1f
                : REFERENCE_DISTANCE_M / (REFERENCE_DISTANCE_M + ROLLOFF * (distance - REFERENCE_DISTANCE_M));
        float fadeFrom = MAX_AUDIBLE_M * 0.8f;
        if (distance > fadeFrom) {
            gain *= 1f - (distance - fadeFrom) / (MAX_AUDIBLE_M - fadeFrom);
        }
        return gain;
    }

    /** Where the top end has gone by this distance. */
    static float airCutoffHz(float distance) {
        return (float) Math.max(900.0, 18_000.0 * Math.exp(-distance / 90.0));
    }

    /**
     * Left-right placement in {@code [-1,1]}.
     *
     * <p>Collapsed toward the centre at close range: a car whose exhaust is a metre to the left is
     * not entirely in the left ear, and hard-panning one that is about to drive through you is the
     * fastest way to make a mix sound like a tech demo.
     */
    private static float pan(float dx, float dy, float dz, float distance, Listener ears) {
        if (distance < 1e-3f) {
            return 0f;
        }
        float right = (dx * ears.rightX() + dy * ears.rightY() + dz * ears.rightZ()) / distance;
        float spread = Math.min(1f, distance / REFERENCE_DISTANCE_M);
        return Math.max(-1f, Math.min(1f, right)) * spread;
    }

    /**
     * A soft knee on the engine bus.
     *
     * <p>Twenty-four engines summing is not a mix that stays under unity, and a hard clamp on a
     * pulse train is broadband distortion at exactly the moment the most is happening. This is
     * gentle enough to be inaudible below the knee and to sound like loudness above it.
     */
    private static void limit(float[] buffer, int count) {
        for (int i = 0; i < count; i++) {
            float v = buffer[i];
            if (v > 0.7f) {
                buffer[i] = 0.7f + (1f - 0.7f) * (1f - (float) Math.exp(-(v - 0.7f) / (1f - 0.7f)));
            } else if (v < -0.7f) {
                buffer[i] = -(0.7f + (1f - 0.7f) * (1f - (float) Math.exp((v + 0.7f) / (1f - 0.7f))));
            }
        }
    }
}

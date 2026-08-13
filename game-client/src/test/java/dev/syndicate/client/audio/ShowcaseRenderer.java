/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one audition take per vehicle to WAV, so a sound can be listened to before it ships
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a15).
 *
 * <p><b>Every defect this synthesiser has had was reported by ear and only then measured.</b> The
 * filter gate, the beep at the head of a start, the missing lope, the overrun crackle that sounded
 * like popcorn — each was heard first, and each took a new measurement to pin down afterwards
 * because none of the existing ones asked about it. A test suite can only check the things somebody
 * already thought to ask; a rendered take is the only artefact that can be judged against the
 * question that has not been asked yet.
 *
 * <p><b>A take is a performance, not a steady tone.</b> Start, settle, idle long enough for a lope
 * to be audible, two throttle blips with lifts between them so the overrun bangs, then a pull to the
 * limiter. Faults hide in steady state and show up in transitions.
 *
 * <p>Rendered through {@link EngineMixer} and {@link EngineRunState} rather than by driving
 * {@link EngineSynth} directly, so what is auditioned is the path the game actually uses —
 * spatialisation, propagation delay and all.
 *
 * <p>Not a test. It lives in the test source set because it exists for the same reason the tests do
 * and shares their fixtures. Run it with:
 *
 * <pre>{@code ./gradlew :game-client:showcaseAudio -Pout=build/audio-showcase}</pre>
 */
public final class ShowcaseRenderer {

    private ShowcaseRenderer() {}

    private static final int SR = EngineSynth.SAMPLE_RATE_HZ;
    private static final int BLOCK = EngineMixer.BLOCK_FRAMES;
    private static final double TAKE_SECONDS = 11.0;

    /** How far the listener stands from the car, in metres. Close enough to hear it work. */
    private static final float LISTEN_DISTANCE_M = 2.5f;

    /** One car to audition. */
    record Subject(
            String file,
            String description,
            EngineConfiguration configuration,
            Induction induction,
            float idleRpm,
            float redlineRpm,
            float peakPowerW,
            long seed) {}

    static final List<Subject> SUBJECTS = List.of(
            new Subject(
                    "stampede_v8_supercharged",
                    "Stampede — supercharged cross-plane V8",
                    EngineConfiguration.V8,
                    Induction.SUPERCHARGED,
                    750f,
                    7600f,
                    608_000f,
                    11L),
            new Subject(
                    "eclipse_v6_twinturbo",
                    "Eclipse — twin-turbo V6",
                    EngineConfiguration.V6,
                    Induction.TURBO,
                    850f,
                    8000f,
                    463_000f,
                    22L),
            new Subject(
                    "economy_i4",
                    "small naturally-aspirated four",
                    EngineConfiguration.I4,
                    Induction.NATURALLY_ASPIRATED,
                    800f,
                    6500f,
                    110_000f,
                    33L),
            new Subject(
                    "straight_six_turbo",
                    "turbo straight six",
                    EngineConfiguration.I6,
                    Induction.TURBO,
                    700f,
                    7000f,
                    320_000f,
                    44L),
            new Subject(
                    "v10_natural",
                    "naturally-aspirated V10",
                    EngineConfiguration.V10,
                    Induction.NATURALLY_ASPIRATED,
                    900f,
                    8500f,
                    470_000f,
                    55L),
            new Subject(
                    "v12_natural",
                    "naturally-aspirated V12",
                    EngineConfiguration.V12,
                    Induction.NATURALLY_ASPIRATED,
                    800f,
                    9000f,
                    420_000f,
                    66L),
            // The same car as the first, a different vehicle. Two of a model must not be identical.
            new Subject(
                    "stampede_v8_second_car",
                    "a second Stampede, same model, different car",
                    EngineConfiguration.V8,
                    Induction.SUPERCHARGED,
                    750f,
                    7600f,
                    608_000f,
                    777L));

    /**
     * Driver demand over the take, in seconds.
     *
     * <p>The idle is long on purpose: a lope is a 6 Hz pattern and needs several seconds to be heard
     * as a pattern rather than as unsteadiness. The two lifts are what make the exhaust bang.
     */
    static float throttleAt(double seconds) {
        if (seconds < 3.6) {
            return 0f; // start, catch, settle, then idle — the lope lives here
        }
        if (seconds < 4.4) {
            return 0.9f; // blip
        }
        if (seconds < 5.4) {
            return 0f; // lift: overrun bangs
        }
        if (seconds < 6.1) {
            return 1.0f; // second, harder blip
        }
        if (seconds < 7.3) {
            return 0f; // and lift again
        }
        return 1.0f; // away to the limiter
    }

    /** Renders every subject into {@code directory} and returns what it wrote. */
    public static List<Path> renderAll(Path directory) throws IOException {
        Files.createDirectories(directory);
        List<Path> written = new ArrayList<>();
        for (Subject subject : SUBJECTS) {
            Path path = directory.resolve(subject.file() + ".wav");
            writeWav(path, take(subject));
            written.add(path);
            System.out.printf("  %-34s %s%n", path.getFileName(), subject.description());
        }
        return written;
    }

    /** One whole take, interleaved stereo, as the mixer produces it. */
    static float[] take(Subject subject) {
        float dt = (float) BLOCK / SR;
        EngineMixer mixer = new EngineMixer();
        int slot = mixer.acquire(
                subject.configuration(),
                subject.induction(),
                subject.idleRpm(),
                subject.redlineRpm(),
                subject.peakPowerW(),
                subject.seed());
        mixer.setListener(new EngineMixer.Listener(0, 0, 0, 0, 0, -1, 1, 0, 0));
        EngineRunState run = new EngineRunState(subject.configuration().cylinders(), subject.idleRpm(), subject.seed());

        float[] block = new float[BLOCK * 2];
        int blocks = (int) (TAKE_SECONDS / dt);
        float[] out = new float[blocks * BLOCK * 2];
        float rpm = 0f;
        int at = 0;
        for (int i = 0; i < blocks; i++) {
            float demand = throttleAt(i * dt);
            // A crude driveline: revs chase the throttle, quicker up than down, capped at the limit.
            float target = subject.idleRpm() + demand * (subject.redlineRpm() - subject.idleRpm());
            rpm += (target - rpm) * Math.min(1f, (demand > 0.5f ? 2.6f : 1.9f) * dt);
            EngineSynth.State state = run.advance(dt, Math.max(subject.idleRpm(), rpm), demand, demand, 1f);
            mixer.publish(slot, new EngineMixer.VoiceUpdate(state, 0f, 0f, LISTEN_DISTANCE_M, 1f));
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, out, at, block.length);
            at += block.length;
        }
        return out;
    }

    /** 16-bit stereo WAV, normalised so quiet takes are still audible against loud ones. */
    static void writeWav(Path path, float[] interleaved) throws IOException {
        float peak = 0f;
        for (float v : interleaved) {
            peak = Math.max(peak, Math.abs(v));
        }
        float scale = peak < 1e-6f ? 1f : 0.89f / peak;
        int bytes = interleaved.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + bytes);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 2);
        header.putInt(SR);
        header.putInt(SR * 4);
        header.putShort((short) 4);
        header.putShort((short) 16);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(bytes);

        ByteBuffer data = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : interleaved) {
            data.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v * scale)) * 32767f));
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(header.array());
            out.write(data.array());
        }
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : "build/audio-showcase");
        System.out.println("Rendering audition takes to " + directory.toAbsolutePath());
        renderAll(directory);
    }
}

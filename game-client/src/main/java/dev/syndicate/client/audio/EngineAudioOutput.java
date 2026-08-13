/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.utils.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thread that keeps {@link EngineMixer} fed, and the device it writes to
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37a4).
 *
 * <p><b>Why a raw device rather than a {@code Sound}.</b> libGDX's {@code Sound} and {@code Music}
 * play files; neither takes samples from the caller. {@code AudioDevice} does — it is a blocking
 * stereo sink — and taking it means the engine bus is ours end to end: our distance model, our
 * panning, our doppler, and one output stream regardless of how many cars are running. The rest of
 * the bank stays on {@code Sound} exactly as it was, because a gunshot is a file and gains nothing
 * from being synthesised.
 *
 * <p><b>The write blocks, so it owns a thread.</b> {@code writeSamples} returns when the device has
 * taken the block, which is what paces the loop: no sleeping, no timer, no drift. The thread is a
 * daemon so it cannot hold the process open, and it runs at {@link Thread#MAX_PRIORITY} because a
 * late block is an audible dropout while a late frame is not.
 *
 * <p><b>Degrades to silence rather than failing</b> (G18). Every CI runner and this project's own
 * sandbox has no audio device; {@link #isAvailable()} says so once, in a log line, and the game
 * runs.
 *
 * <p><b>Owner of the {@link AudioDevice} and of the thread</b> (G19). {@link #dispose()} stops the
 * thread before closing the device, because closing a device out from under a blocked write is how
 * a shutdown turns into a native crash.
 */
public final class EngineAudioOutput implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(EngineAudioOutput.class);

    private final EngineMixer mixer;
    private final AudioDevice device;
    private final Thread thread;
    private volatile boolean running;

    private EngineAudioOutput(EngineMixer mixer, AudioDevice device) {
        this.mixer = mixer;
        this.device = device;
        this.thread = device == null ? null : new Thread(this::pump, "syndicate-engine-audio");
    }

    /**
     * Opens the engine bus, or returns a silent one when there is no device.
     *
     * @param mixer the mixer to pull from; this does not take ownership of it
     */
    public static EngineAudioOutput open(EngineMixer mixer) {
        if (Gdx.audio == null) {
            LOG.warn("no audio backend; engines will be silent");
            return new EngineAudioOutput(mixer, null);
        }
        AudioDevice device;
        try {
            device = Gdx.audio.newAudioDevice(EngineMixer.SAMPLE_RATE_HZ, false);
        } catch (RuntimeException e) {
            LOG.warn("no audio device ({}); engines will be silent", e.getMessage());
            return new EngineAudioOutput(mixer, null);
        }
        EngineAudioOutput output = new EngineAudioOutput(mixer, device);
        output.running = true;
        output.thread.setDaemon(true);
        output.thread.setPriority(Thread.MAX_PRIORITY);
        output.thread.start();
        LOG.info("engine audio bus open at {} Hz stereo", EngineMixer.SAMPLE_RATE_HZ);
        return output;
    }

    /** Whether anything will actually be heard. */
    public boolean isAvailable() {
        return device != null;
    }

    private void pump() {
        float[] stereo = new float[EngineMixer.BLOCK_FRAMES * 2];
        while (running) {
            try {
                mixer.render(stereo, EngineMixer.BLOCK_FRAMES);
                device.writeSamples(stereo, 0, stereo.length);
            } catch (RuntimeException e) {
                // One bad block must not take the game down, and must not spin: a device that has
                // gone away throws every time, so stop rather than log a million lines.
                LOG.warn("engine audio stopped: {}", e.toString());
                running = false;
            }
        }
    }

    @Override
    public void dispose() {
        running = false;
        if (thread != null) {
            try {
                // Long enough for the device to drain the block it is holding, short enough that a
                // wedged driver cannot stop the process exiting.
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (device != null) {
            device.dispose();
        }
    }
}

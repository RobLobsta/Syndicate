/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.PlayerInputComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which device is driving, from which one the player last touched.
 *
 * <p><b>Not a setting.</b> Asking a player to choose their input device in a menu is asking them to
 * answer a question the game can see the answer to: they pick up a pad, the game notices, the
 * prompts change. That is what "based on input type" means in practice, and it is also what handles
 * the case a settings screen handles worst — a player who drives with a pad and types with a
 * keyboard, switching several times a minute.
 *
 * <p><b>Both sources are polled every frame, and only the active one's output is kept.</b> Polling
 * only the active source cannot work: the router would never see the other device move, so nothing
 * could ever switch to it. Polling both into scratch and copying one is the cost of the design and
 * it is a few hundred bytes and no allocation.
 *
 * <p>Switching needs hysteresis or it flickers. A gamepad's sticks are never perfectly still and a
 * mouse on a desk picks up a pixel of movement from a passing lorry, so a switch requires the rival
 * device to exceed {@link #SWITCH_THRESHOLD} — deliberate movement, not noise — and the active
 * device to have been quiet for {@link #QUIET_SECONDS}.
 */
public final class InputRouter {

    private static final Logger LOG = LoggerFactory.getLogger(InputRouter.class);

    /**
     * How much a rival device must move to take over.
     *
     * <p>Well above a resting stick's jitter after its dead zone, and well below anything a person
     * does on purpose. A player who nudges a stick to see if it works gets the switch they wanted.
     */
    public static final float SWITCH_THRESHOLD = 0.15f;

    /**
     * How long the active device must be idle before another may take over.
     *
     * <p>Zero would mean a hand resting on a keyboard steals control from a pad mid-corner. Half a
     * second is longer than any gap in real driving input and shorter than the pause before somebody
     * who has just put a pad down starts typing.
     */
    public static final float QUIET_SECONDS = 0.5f;

    private final List<InputSource> sources = new ArrayList<>();
    private final PlayerInputComponent scratch = new PlayerInputComponent();

    private InputSource active;
    private float activeIdleSeconds;

    public InputRouter(InputSource... candidates) {
        for (InputSource source : candidates) {
            sources.add(Objects.requireNonNull(source, "source"));
        }
    }

    /** Which device is currently driving. */
    public InputDeviceKind activeKind() {
        return active == null ? InputDeviceKind.NONE : active.kind();
    }

    /**
     * Polls every source, switches if the player has picked up a different device, and writes the
     * active one's intent.
     *
     * @param out receives the driver's intent; untouched when nothing is available
     * @param dtSeconds the frame's elapsed time
     * @return true when {@code out} was written
     */
    public boolean poll(PlayerInputComponent out, float dtSeconds) {
        InputSource loudest = null;
        float loudestActivity = 0f;
        float activeActivity = 0f;
        boolean wroteActive = false;

        for (InputSource source : sources) {
            if (!source.isAvailable()) {
                if (source == active) {
                    // Unplugged mid-game. Hand over immediately rather than after the quiet
                    // period: there is nothing to be loyal to.
                    switchTo(null);
                }
                continue;
            }
            PlayerInputComponent target = source == active ? out : scratch;
            float activity = source.poll(target, dtSeconds);
            if (source == active) {
                activeActivity = activity;
                wroteActive = true;
            }
            if (activity > loudestActivity) {
                loudestActivity = activity;
                loudest = source;
            }
        }

        if (active == null) {
            if (loudest != null && loudestActivity > 0f) {
                switchTo(loudest);
                // Poll the new source into `out` this frame rather than making the player press
                // twice: the input that caused the switch is input they meant.
                active.poll(out, dtSeconds);
                return true;
            }
            return firstAvailableIdle(out);
        }

        activeIdleSeconds = activeActivity > 0.01f ? 0f : activeIdleSeconds + dtSeconds;
        if (loudest != null
                && loudest != active
                && loudestActivity > SWITCH_THRESHOLD
                && activeIdleSeconds >= QUIET_SECONDS) {
            switchTo(loudest);
            active.poll(out, dtSeconds);
            return true;
        }
        return wroteActive;
    }

    /**
     * Zeroes the intent when a device is present but nothing has been touched yet.
     *
     * <p>Without it the component keeps whatever it held before the player let go — which, at the
     * start of a match, is whatever the last match left there.
     */
    private boolean firstAvailableIdle(PlayerInputComponent out) {
        for (InputSource source : sources) {
            if (source.isAvailable()) {
                out.throttle = 0f;
                out.steer = 0f;
                out.brake = 0f;
                out.collective = 0f;
                out.fireMask = 0;
                return true;
            }
        }
        return false;
    }

    private void switchTo(InputSource source) {
        if (active == source) {
            return;
        }
        if (active != null) {
            // The outgoing source is integrating — a keyboard's steering ramp, a gamepad's aim
            // angle — and leaving that half-applied is a car that pulls to one side when the
            // player comes back to it.
            active.reset();
        }
        active = source;
        activeIdleSeconds = 0f;
        LOG.info("input device is now {}", activeKind());
    }
}

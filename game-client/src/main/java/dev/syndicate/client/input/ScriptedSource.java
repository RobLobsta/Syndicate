/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.PlayerInputComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A third {@link InputSource} that plays a written-down drive, for a process with no hands.
 *
 * <p><b>Why this exists.</b> {@code --capture} runs the real client and writes one PNG, which is
 * what every visual claim in this project has been checked against since DISC-051. It has one hole,
 * and it is the biggest one: <b>a capture has no keyboard</b>. Everything the player <em>does</em> —
 * driving, aiming, pulling the trigger — could only ever be photographed as the first frame of a car
 * sitting still, so the half of the game that is verbs was the half nobody could look at. {@code
 * --vehicle}, {@code --garage-row} and {@code --fit} each exist for exactly this reason, one screen
 * at a time; this is the same answer for the match.
 *
 * <p><b>It is not a replay and not a demo format.</b> It writes {@link PlayerInputComponent} — the
 * same component a keyboard writes, through the same router, into the same simulation. Nothing here
 * can produce an input a player could not, which is what keeps a scripted run evidence about the
 * game rather than evidence about the script. It is also strictly a client-side authoring
 * convenience: {@code game-core} neither knows nor can know.
 *
 * <p><b>The format</b> is a timeline of segments, each a time in seconds and the inputs that hold
 * from then until the next segment:
 *
 * <pre>
 *   t=0 throttle=1 | t=3 steer=0.4 fire=1 | t=7 steer=-0.4 | t=11 throttle=0 brake=1
 * </pre>
 *
 * <p>Recognised keys are {@code throttle}, {@code brake}, {@code steer}, {@code collective},
 * {@code fire}, {@code aimYaw} and {@code aimPitch}. A key a segment does not mention keeps the value
 * the previous segment set, so a script says what changes rather than restating the whole state —
 * which is what makes a long drive readable.
 */
public final class ScriptedSource implements InputSource {

    /**
     * What the router sees as this source's activity, every frame.
     *
     * <p>Large and constant. {@link InputRouter} hands control to whichever device the player last
     * touched, and a script that reported its real activity would lose the car to an idle keyboard
     * during any segment that holds a steady throttle. A scripted run is one where the script is
     * driving, from the first frame to the last; there is no sharing to arbitrate.
     */
    private static final float ACTIVITY = 1000f;

    private final List<Segment> segments;
    private float elapsedSeconds;

    /**
     * The script a launch flag asked for, or null.
     *
     * <p>A static launch option rather than a constructor argument, following the precedent
     * {@code RenderEnvironment.setLaunchNightFraction} already sets for this client: the input
     * sources are built four constructors deep inside {@code ClientSystemProvider}, and threading a
     * capture-only flag through all four would put a test affordance in every signature between here
     * and {@code ClientMain}.
     */
    private static String launchScript;

    /** Records the {@code --script} a launch asked for. Null clears it. */
    public static void setLaunchScript(String script) {
        launchScript = script;
    }

    /** The scripted source a launch asked for, or null when nothing did. */
    public static ScriptedSource launchSource() {
        return launchScript == null ? null : parse(launchScript);
    }

    private ScriptedSource(List<Segment> segments) {
        this.segments = List.copyOf(segments);
    }

    /**
     * Parses the timeline form above.
     *
     * @throws IllegalArgumentException on an unrecognised key or an unparseable number — loudly,
     *     because a script that silently ignored half of itself would produce a capture of a car
     *     doing nothing and no indication of why
     */
    public static ScriptedSource parse(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("an empty script drives nothing");
        }
        List<Segment> segments = new ArrayList<>();
        // Each segment inherits the last one's values, so a script states changes rather than state.
        PlayerInputComponent running = new PlayerInputComponent();
        for (String part : script.split("\\|")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            float atSeconds = 0f;
            PlayerInputComponent frame = copyOf(running);
            for (String token : trimmed.split("\\s+")) {
                int equals = token.indexOf('=');
                if (equals < 0) {
                    throw new IllegalArgumentException("script token \"" + token + "\" is not key=value");
                }
                String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                float value = parseFloat(token.substring(equals + 1), token);
                switch (key) {
                    case "t" -> atSeconds = value;
                    case "throttle" -> frame.throttle = value;
                    case "brake" -> frame.brake = value;
                    case "collective" -> frame.collective = value;
                    case "steer" -> frame.steer = value;
                    case "aimyaw" -> frame.aimYawRad = value;
                    case "aimpitch" -> frame.aimPitchRad = value;
                        // One bit, because a scripted run only ever needs the primary group; a
                        // script that had to name weapon groups would be a worse thing to read than
                        // the loadout flags that put the weapons there.
                    case "fire" -> frame.fireMask = value > 0.5f ? 1 : 0;
                    default -> throw new IllegalArgumentException("unknown script key \"" + key + "\"");
                }
            }
            segments.add(new Segment(atSeconds, frame));
            running = copyOf(frame);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("an empty script drives nothing");
        }
        segments.sort((a, b) -> Float.compare(a.atSeconds, b.atSeconds));
        return new ScriptedSource(segments);
    }

    @Override
    public InputDeviceKind kind() {
        return InputDeviceKind.KEYBOARD_MOUSE;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public float poll(PlayerInputComponent out, float dtSeconds) {
        elapsedSeconds += dtSeconds;
        Segment current = segments.get(0);
        for (Segment segment : segments) {
            if (segment.atSeconds <= elapsedSeconds) {
                current = segment;
            } else {
                break;
            }
        }
        copyInto(current.input, out);
        return ACTIVITY;
    }

    @Override
    public void reset() {
        // Deliberately does not rewind. The router resets a source it switches away from, and a
        // script that restarted every time the clock was touched would replay its opening segment
        // forever rather than finishing the drive.
    }

    /** How far through the script the playhead is, seconds — for the capture's log line. */
    public float elapsedSeconds() {
        return elapsedSeconds;
    }

    /** How many segments the script holds. */
    public int segmentCount() {
        return segments.size();
    }

    private static float parseFloat(String text, String token) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("script token \"" + token + "\" has an unparseable number");
        }
    }

    private static PlayerInputComponent copyOf(PlayerInputComponent source) {
        PlayerInputComponent copy = new PlayerInputComponent();
        copyInto(source, copy);
        return copy;
    }

    private static void copyInto(PlayerInputComponent from, PlayerInputComponent to) {
        to.throttle = from.throttle;
        to.brake = from.brake;
        to.collective = from.collective;
        to.steer = from.steer;
        to.fireMask = from.fireMask;
        to.aimYawRad = from.aimYawRad;
        to.aimPitchRad = from.aimPitchRad;
    }

    /** One leg of the timeline: when it starts, and what is held from then on. */
    private record Segment(float atSeconds, PlayerInputComponent input) {}
}

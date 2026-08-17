/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.syndicate.model.AssetPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code articulation} block of every part that moves (docs/17_weapon_system.md#D17-S4.4).
 *
 * <p>Read here rather than in {@code game-core}'s loader, for exactly the reason {@link PartLamps} is:
 * <b>G6</b>. A recoiling barrel, a turning feed drum and an opening door are all cosmetic. The
 * collision hull does not move with them, no simulation value derives from them, and nothing here is
 * replicated — two clients drawing the same vehicle at different frame rates see the same shots hit
 * the same parts, because the thing they disagree about is a render transform and nothing else.
 *
 * <p><b>One block, five motions.</b> D15-S5.6 introduced this block for doors; D17-S4.4 extends it
 * rather than adding a second, because "a rigid transform of one part about a declared axis" is one
 * concept. {@code HINGE} is the default when {@code motion} is absent, so the door parts both shipped
 * vehicles already carry keep working with no file rewritten.
 *
 * <p>Cached by part type, because a vehicle fitted with two identical machine guns has one
 * articulation definition between them.
 */
public final class PartArticulation {

    private static final Logger LOG = LoggerFactory.getLogger(PartArticulation.class);

    /** What kind of motion a part makes (D17-R12). */
    public enum Motion {
        /** Rotates about the axis to {@code openDeg}. The door case, and the default. */
        HINGE,
        /** Slides along the axis by {@code travelM} on each shot, returning over {@code returnSeconds}. */
        RECOIL,
        /** Rotates about the axis continuously at {@code rateDegPerSec}. */
        SPIN,
        /** Rotates by {@code 360 / indexSteps} per shot, easing over {@code returnSeconds}. */
        INDEX,
        /** Rotates about the axis to follow commanded aim, limited to {@code travelDeg}. */
        ELEVATE
    }

    /** What the motion is a function of (D17-R13). */
    public enum Driver {
        /** The part's own open state. The door case, and the default. */
        OPEN,
        /** Seconds since the owning weapon last fired. */
        FIRE,
        /** Match time, while the weapon is live. */
        CONTINUOUS,
        /** Commanded aim, relative to the rest pose. */
        AIM,
        /**
         * The rotor's own governed speed, read off {@code RotorControllerComponent} (DEC-090).
         *
         * <p>Not {@link #CONTINUOUS}, which is keyed to a weapon firing and coasts to a stop
         * between bursts — a main rotor turns whether or not anything is shooting, and stops
         * only when the disc is destroyed. It reads authoritative state and writes nothing
         * back, which is the one direction G6 permits.
         */
        ROTOR
    }

    /**
     * One part's articulation, in its own local space.
     *
     * @param motion which of the five
     * @param driver what advances it
     * @param axis unit axis, in the part's local frame
     * @param pivot a point on that axis, in the part's local frame
     * @param openDeg {@code HINGE}: the signed angle it opens to
     * @param travelM {@code RECOIL}: metres it slides back
     * @param returnSeconds {@code RECOIL} and {@code INDEX}: seconds to settle
     * @param rateDegPerSec {@code SPIN}: degrees per second
     * @param indexSteps {@code INDEX}: positions per revolution
     * @param travelDeg {@code ELEVATE}: the limit it tracks within
     */
    public record Articulation(
            Motion motion,
            Driver driver,
            Vector3 axis,
            Vector3 pivot,
            float openDeg,
            float travelM,
            float returnSeconds,
            float rateDegPerSec,
            int indexSteps,
            float travelDeg) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path assetRoot;
    private final Map<String, Articulation> byPartType = new HashMap<>();

    public PartArticulation(Path assetRoot) {
        this.assetRoot = Objects.requireNonNull(assetRoot, "assetRoot");
    }

    /** The articulation a part type carries, or null when it carries none. */
    public Articulation forPart(String partTypeId) {
        if (byPartType.containsKey(partTypeId)) {
            return byPartType.get(partTypeId);
        }
        Articulation articulation = read(partTypeId);
        byPartType.put(partTypeId, articulation);
        return articulation;
    }

    /** How many part types have been inspected, for a capture's log line. */
    public int inspectedCount() {
        return byPartType.size();
    }

    /**
     * Writes the local transform a part is drawn with, given how far through its motion it is.
     *
     * <p>{@code phase} is normalised to {@code [0,1]} by the caller, which is what keeps this method
     * free of any clock: the same phase always produces the same pose, so it is directly testable and
     * cannot drift between two clients. A destroyed part is never handed a phase that advances — that
     * is the caller's job (D17-R15), and it is the one place a cosmetic system legitimately reads
     * authoritative state.
     *
     * @param out receives the local offset transform; identity when the part does not move
     * @return {@code out}
     */
    public static Matrix4 pose(Articulation articulation, float phase, Matrix4 out) {
        out.idt();
        if (articulation == null) {
            return out;
        }
        Vector3 axis = articulation.axis();
        switch (articulation.motion()) {
            case RECOIL -> {
                // Negative: a barrel recoils *backwards* along the bore, which is the axis it points
                // forward along. Getting this sign wrong makes the gun grow when it fires.
                float slide = -articulation.travelM() * phase;
                out.setToTranslation(axis.x * slide, axis.y * slide, axis.z * slide);
            }
            case HINGE -> rotateAbout(out, axis, articulation.pivot(), articulation.openDeg() * phase);
            case SPIN -> rotateAbout(out, axis, articulation.pivot(), 360f * phase);
            case INDEX -> {
                int steps = Math.max(1, articulation.indexSteps());
                rotateAbout(out, axis, articulation.pivot(), (360f / steps) * phase);
            }
            case ELEVATE -> {
                // Phase is signed for elevation: -1 is full depression, +1 full elevation, and the
                // rest pose is 0. Every other motion runs 0..1 from rest.
                rotateAbout(out, axis, articulation.pivot(), articulation.travelDeg() * phase);
            }
        }
        return out;
    }

    /** A rotation of {@code degrees} about {@code axis} through {@code pivot}, rather than the origin. */
    private static void rotateAbout(Matrix4 out, Vector3 axis, Vector3 pivot, float degrees) {
        out.idt()
                .translate(pivot.x, pivot.y, pivot.z)
                .rotate(axis.x, axis.y, axis.z, degrees)
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    private Articulation read(String partTypeId) {
        Path directory = AssetPaths.partDirectory(assetRoot, partTypeId);
        Path file = directory == null ? null : directory.resolve("part.json");
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(file.toFile()).path("articulation");
            if (!node.isObject()) {
                return null;
            }
            Motion motion = parseMotion(node.path("motion").asText(null), partTypeId);
            Driver driver = parseDriver(node.path("driver").asText(null), partTypeId);
            Vector3 axis = vector(node.path("axisLocal"), 0f, 1f, 0f);
            if (axis.isZero()) {
                // A220: a degenerate axis is a part that cannot rotate meaningfully. Static beats
                // spinning about nothing, and it is a warning rather than a failure (D17-E11).
                LOG.warn("part {} has a zero articulation axis; it will not move", partTypeId);
                return null;
            }
            return new Articulation(
                    motion,
                    driver,
                    axis.nor(),
                    vector(node.path("pivotLocal"), 0f, 0f, 0f),
                    (float) node.path("openDeg").asDouble(0d),
                    (float) node.path("travelM").asDouble(0d),
                    (float) node.path("returnSeconds").asDouble(0.2d),
                    (float) node.path("rateDegPerSec").asDouble(0d),
                    node.path("indexSteps").asInt(0),
                    (float) node.path("travelDeg").asDouble(0d));
        } catch (IOException e) {
            // G18: an unreadable block is a part that does not move, not a client that will not start.
            LOG.warn("part {} has an unreadable articulation block; it will not move", partTypeId, e);
            return null;
        }
    }

    /** D17-R12: an absent motion is {@code HINGE}, which is what the block meant before D17. */
    private static Motion parseMotion(String name, String partTypeId) {
        if (name == null || name.isBlank()) {
            return Motion.HINGE;
        }
        for (Motion candidate : Motion.values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        LOG.warn(
                "part {} has articulation motion \"{}\", which is not a Motion; treating it as HINGE (A220)",
                partTypeId,
                name);
        return Motion.HINGE;
    }

    /** D17-R13: an absent driver is {@code OPEN}, which is what the block meant before D17. */
    private static Driver parseDriver(String name, String partTypeId) {
        if (name == null || name.isBlank()) {
            return Driver.OPEN;
        }
        for (Driver candidate : Driver.values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        LOG.warn(
                "part {} has articulation driver \"{}\", which is not a Driver; treating it as OPEN (A220)",
                partTypeId,
                name);
        return Driver.OPEN;
    }

    private static Vector3 vector(JsonNode node, float dx, float dy, float dz) {
        return new Vector3(
                (float) node.path("x").asDouble(dx), (float) node.path("y").asDouble(dy), (float)
                        node.path("z").asDouble(dz));
    }
}

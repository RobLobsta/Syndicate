/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.graphics.Color;
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
 * The {@code light} block of every part that carries one (docs/08_asset_pipeline.md#D08-R6).
 *
 * <p>Read here rather than in {@code game-core}'s loader, and the reason is G6 rather than
 * convenience: a lamp lights nothing the simulation can observe. A headlight is exactly as cosmetic
 * as a spark, so it follows the same rule the visual mesh and the morph target names follow — the
 * presentation layer reads it, and {@link dev.syndicate.core.asset.PartType} never learns it exists.
 *
 * <p>What it is <em>not</em> is guesswork. Where a lamp points, how far it throws, what colour it is
 * and whether it casts at all are authored by the preparation pipeline from the part's own geometry
 * (D15-R51), so the renderer never has to infer a headlight from a part id.
 *
 * <p>Cached by part type, because eight cars share two lamp definitions between them.
 */
public final class PartLamps {

    private static final Logger LOG = LoggerFactory.getLogger(PartLamps.class);

    /**
     * One lamp, in its part's own local space.
     *
     * @param origin where the light leaves the lens, relative to the part's origin
     * @param direction unit; where it points, relative to the part's rotation
     * @param colour the emitted colour
     * @param intensity gdx-gltf's punctual intensity, in its own units
     * @param rangeM metres beyond which the light contributes nothing
     * @param coneOuterDeg the full angle at which the cone has fallen to zero
     * @param coneInnerDeg the full angle within which the cone is at full brightness
     * @param castsBeam whether this lamp lights the world. False for a tail light, which glows and
     *     illuminates nothing — true of the real ones, and what keeps eight cars to sixteen lights.
     */
    public record Lamp(
            Vector3 origin,
            Vector3 direction,
            Color colour,
            float intensity,
            float rangeM,
            float coneOuterDeg,
            float coneInnerDeg,
            boolean castsBeam) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path assetRoot;
    private final Map<String, Lamp> byPartType = new HashMap<>();

    public PartLamps(Path assetRoot) {
        this.assetRoot = Objects.requireNonNull(assetRoot, "assetRoot");
    }

    /**
     * The lamp a part type carries, or null when it carries none.
     *
     * <p>Every part is looked up at most once; a part with no block caches a null so the miss is
     * not a file read on every frame of every match.
     */
    public Lamp lampFor(String partTypeId) {
        if (byPartType.containsKey(partTypeId)) {
            return byPartType.get(partTypeId);
        }
        Lamp lamp = read(partTypeId);
        byPartType.put(partTypeId, lamp);
        return lamp;
    }

    /** How many part types have been inspected, for a capture's log line. */
    public int inspectedCount() {
        return byPartType.size();
    }

    private Lamp read(String partTypeId) {
        Path directory = AssetPaths.partDirectory(assetRoot, partTypeId);
        Path file = directory == null ? null : directory.resolve("part.json");
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode light = mapper.readTree(file.toFile()).path("light");
            if (!light.isObject()) {
                return null;
            }
            JsonNode rgb = light.path("colorRgb");
            Lamp lamp = new Lamp(
                    vector(light.path("originLocal"), 0f, 0f, 0f),
                    vector(light.path("directionLocal"), 0f, 0f, 1f).nor(),
                    new Color(
                            (float) rgb.path("r").asDouble(1d),
                            (float) rgb.path("g").asDouble(1d),
                            (float) rgb.path("b").asDouble(1d),
                            1f),
                    (float) light.path("intensity").asDouble(20d),
                    (float) light.path("rangeM").asDouble(40d),
                    (float) light.path("coneOuterDeg").asDouble(35d),
                    (float) light.path("coneInnerDeg").asDouble(14d),
                    light.path("castsBeam").asBoolean(false));
            LOG.debug("part {} carries a lamp: casts={} range={}", partTypeId, lamp.castsBeam(), lamp.rangeM());
            return lamp;
        } catch (IOException e) {
            // G18: an unreadable lamp is a car with its lights off, not a client that will not start.
            LOG.warn("part {} has an unreadable light block; it will not light", partTypeId, e);
            return null;
        }
    }

    private static Vector3 vector(JsonNode node, float dx, float dy, float dz) {
        return new Vector3(
                (float) node.path("x").asDouble(dx), (float) node.path("y").asDouble(dy), (float)
                        node.path("z").asDouble(dz));
    }
}

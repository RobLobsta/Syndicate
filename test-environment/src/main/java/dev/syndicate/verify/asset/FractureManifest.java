/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.asset;

import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The fracture manifest of docs/09_blender_destruction_tool.md#D09-S4.4, as the harness reads it.
 *
 * <p>The harness treats this as a *claim*, never as truth. Every field it names is re-derived from
 * the exported meshes and compared (ASSET-004, ASSET-006, PHYS-002, PHYS-003) — that comparison is
 * the entire point of the harness existing alongside the tool's own self-verification (D09-R21).
 *
 * <p>Unknown properties are ignored so a manifest written by a newer tool version still loads: a
 * harness that refused to read it could not report *why* it disagreed, which is the one thing it is
 * for.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FractureManifest {

    public String schemaVersion;
    public String toolVersion;
    public String blenderVersion;
    public String partTypeId;
    public String materialId;
    public long seed;
    public float partMassKg;
    public float partVolumeM3;
    public float densityKgPerM3;
    public Vec3 comLocal = new Vec3();
    public Vec3 inertiaDiagonal = new Vec3();
    public Vec3 aabbMin = new Vec3();
    public Vec3 aabbMax = new Vec3();
    public List<String> morphTargets = new ArrayList<>();
    public int shardCount;
    public List<Shard> shards = new ArrayList<>();
    public String topologyHash;

    /** One shard's declared properties. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Shard {
        public String id;
        public String name;
        public int index;
        public float massKg;
        public float volumeM3;
        public Vec3 centroid = new Vec3();
        public Vec3 aabbMin = new Vec3();
        public Vec3 aabbMax = new Vec3();
        public int vertexCount;
        public int faceCount;
        public int hullVertexCount;
        public String materialId;
    }

    /** A manifest vector. Separate from {@link Vector3} because Jackson binds by field name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Vec3 {
        public float x;
        public float y;
        public float z;

        public Vector3 toVector(Vector3 out) {
            return out.set(x, y, z);
        }

        @Override
        public String toString() {
            return String.format("(%.4f, %.4f, %.4f)", x, y, z);
        }
    }

    /** Total declared shard mass, the left side of the G7 conservation check (ASSET-006). */
    public float declaredShardMassKg() {
        float total = 0f;
        for (Shard shard : shards) {
            total += shard.massKg;
        }
        return total;
    }

    /**
     * Reads and parses a manifest.
     *
     * @throws ManifestException when the file is missing or malformed; maps to exit 20/21
     *     (D14-S4.2), which are distinct so a caller can tell "you pointed at nothing" from "the
     *     tool wrote something wrong"
     */
    public static FractureManifest load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ManifestException("manifest not found: " + path, true);
        }
        try {
            return new ObjectMapper().readValue(Files.readString(path), FractureManifest.class);
        } catch (IOException e) {
            throw new ManifestException("manifest is invalid: " + path + " (" + e.getMessage() + ")", false);
        }
    }

    /** A missing or malformed manifest. */
    public static final class ManifestException extends UncheckedIOException {
        /** True when the file was absent (exit 20) rather than unparseable (exit 21). */
        public final transient boolean notFound;

        public ManifestException(String message, boolean notFound) {
            super(new IOException(message));
            this.notFound = notFound;
        }
    }
}

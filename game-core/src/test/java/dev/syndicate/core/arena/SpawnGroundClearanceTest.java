/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A spawn point has to be on the ground it spawns onto (D16-S5.6, DISC-067).
 *
 * <p>An arena authors each spawn's position in world space, including its {@code y}, and both
 * shipped arenas author {@code y = 1.0} for every one. The terrain under them is procedural: the
 * desert's relief spans −5 m to +50 m, and the pad that flattens the ground at a spawn levels it to
 * <b>the terrain's own height there</b>, not to the authored {@code y}.
 *
 * <p>Nothing reconciles the two. {@code SpawnSystem} places the chassis at the transform it is
 * handed, so wherever the flattened pad sits above 1 m the vehicle is created inside the ground and
 * Bullet's penetration resolution throws it out — which is what the first scripted drive of the
 * desert looked like: the car shed 26 of its 40 parts in a second and a half and finished upside
 * down and immobile with the throttle still open.
 */
@Tag("unit")
class SpawnGroundClearanceTest {

    /** Metres. How far a spawn may sit above its ground before it is a drop rather than a spawn. */
    private static final float MAX_DROP_M = 3.0f;

    /**
     * Metres. How far below the ground a spawn may sit: none.
     *
     * <p>A small negative tolerance would be a licence to spawn slightly buried, and slightly buried
     * is not a milder version of the bug — Bullet resolves any penetration by ejection, and the
     * impulse is a function of the overlap and the timestep rather than of how bad the authoring is.
     */
    private static final float MAX_PENETRATION_M = 0.0f;

    private static TerrainField terrainFor(ArenaTheme theme, float extentM, List<Vector3> spawns) {
        TerrainParams params = TerrainParams.of(theme, 12345L, extentM);
        float half = extentM / 2f;
        List<TerrainGenerator.Pad> pads = new ArrayList<>();
        for (Vector3 spawn : spawns) {
            pads.add(new TerrainGenerator.Pad(spawn.x, spawn.z, 12.0f, spawn.y));
        }
        return TerrainGenerator.generate(
                new Vector3(-half, -40f, -half), new Vector3(half, 120f, half), 0f, params, pads, List.of());
    }

    /** The desert's authored spawn ring, as `assets/arenas/arena_desert_01/arena.json` has it. */
    private static List<Vector3> desertSpawns() {
        List<Vector3> spawns = new ArrayList<>();
        for (float z = -110f; z <= 10f; z += 40f) {
            spawns.add(new Vector3(-140f, 1.0f, z));
        }
        for (float z = -110f; z <= 10f; z += 40f) {
            spawns.add(new Vector3(140f, 1.0f, z));
        }
        return spawns;
    }

    /**
     * DISC-067: every spawn sits on its own ground, within a drop a suspension can absorb.
     *
     * <p>Asserted on the generated terrain rather than on the authored file, because the authored
     * file is exactly what does not know the answer — the ground under a spawn does not exist until
     * the seed is expanded.
     */
    @Test
    void everySpawnPointSitsOnTheGroundItSpawnsOnto() {
        List<Vector3> spawns = desertSpawns();
        TerrainField field = terrainFor(ArenaTheme.DESERT_HIGHWAY, 600f, spawns);

        List<String> buried = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Vector3 spawn : spawns) {
            float ground = field.heightAt(spawn.x, spawn.z);
            float clearance = spawn.y - ground;
            if (clearance < -MAX_PENETRATION_M) {
                buried.add(
                        String.format("(%.0f, %.0f) ground %.2f m, spawn y %.2f", spawn.x, spawn.z, ground, spawn.y));
            } else if (clearance > MAX_DROP_M) {
                dropped.add(
                        String.format("(%.0f, %.0f) ground %.2f m, spawn y %.2f", spawn.x, spawn.z, ground, spawn.y));
            }
        }

        assertThat(buried)
                .as("spawns inside the terrain, which Bullet ejects violently (DISC-067)")
                .isEmpty();
        assertThat(dropped)
                .as("spawns more than %.1f m above their ground".formatted(MAX_DROP_M))
                .isEmpty();
    }
}

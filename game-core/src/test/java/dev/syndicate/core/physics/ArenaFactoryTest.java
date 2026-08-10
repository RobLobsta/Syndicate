/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.ValidationIssue;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.GameMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The arena of docs/08_asset_pipeline.md#D08-S4.7 and docs/04_entity_component_model.md#D04-S5.4.
 *
 * <p>Two things worth asserting and one worth asserting loudly. That the shipped arena loads and
 * validates; that {@link ArenaFactory} turns it into a floor a vehicle can stand on and walls it
 * cannot leave; and that a spawn point below {@code MIN_SPAWN_SEPARATION_M} of clearance is rejected
 * rather than quietly accepted, because the failure it causes — two vehicles resolving out of each
 * other at the start of a match — looks like a physics bug rather than a content one.
 */
@Tag("unit")
class ArenaFactoryTest {

    /** The repository's own {@code assets/}, found from the module's working directory. */
    private static final Path ASSET_ROOT = Path.of("..", "assets");

    @Test
    void theShippedArenaLoadsAndValidates() {
        AssetLoader loader = new AssetLoader(new GltfCollisionMeshSource());
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        loader.loadArena(ASSET_ROOT.resolve("arenas").resolve("arena_scrapyard_01"), index);

        assertThat(loader.issues())
                .as("no A4xx findings on the shipped arena")
                .filteredOn(ValidationIssue::isBlocking)
                .isEmpty();

        ArenaDef arena = index.arena(AssetId.of("arena_scrapyard_01"));
        assertThat(arena).isNotNull();
        assertThat(arena.spawnPoints()).hasSize(6);
        assertThat(arena.supports(GameMode.DEATHMATCH)).isTrue();
        assertThat(arena.supports(GameMode.PAYLOAD)).isFalse();
        // Every spawn point is inside the bounds and above the floor: A401's rule, asserted on the
        // content rather than only on the loader that checks it.
        for (ArenaDef.SpawnPoint point : arena.spawnPoints()) {
            assertThat(arena.contains(point.position())).isTrue();
            assertThat(point.position().y).isGreaterThan(arena.groundY());
            assertThat(point.clearanceRadiusM()).isGreaterThanOrEqualTo(ArenaDef.MIN_SPAWN_SEPARATION_M);
        }
    }

    /** A402: a spawn point with too little clearance is rejected, not accepted with a shrug. */
    @Test
    void aCrampedSpawnPointIsRejected(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path directory = temp.resolve("arena_cramped_01");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("arena.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "arenaId": "arena_cramped_01",
                  "boundsMin": { "x": -50, "y": -30, "z": -50 },
                  "boundsMax": { "x": 50, "y": 50, "z": 50 },
                  "groundY": 0.0,
                  "killPlaneY": -40.0,
                  "spawnPoints": [
                    { "id": "sp_a", "team": 0, "position": { "x": 0, "y": 1, "z": 0 },
                      "yawDeg": 0, "clearanceRadiusM": 1.0 },
                    { "id": "sp_b", "team": 1, "position": { "x": 900, "y": 1, "z": 0 },
                      "yawDeg": 0, "clearanceRadiusM": 12.0 }
                  ],
                  "modes": ["DEATHMATCH"]
                }
                """);

        AssetLoader loader = new AssetLoader(new GltfCollisionMeshSource());
        loader.loadArena(directory, new InMemoryAssetIndex());

        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A402", "A401", "A403");
    }

    /** D04-S5.4: loading an arena gives the world a floor and four walls, all static. */
    @Test
    void loadingAnArenaCreatesStaticGeometry() {
        try (DestructionTestScene scene = new DestructionTestScene(7L)) {
            ArenaDef arena = new ArenaDef(
                    AssetId.of("arena_test_01"),
                    "Test",
                    new Vector3(-20f, -10f, -20f),
                    new Vector3(20f, 30f, 20f),
                    -15f,
                    0f,
                    List.of(new ArenaDef.SpawnPoint("sp_a", 0, new Vector3(0f, 1f, 0f), 90f, 12f)),
                    java.util.Set.of(GameMode.DEATHMATCH),
                    null);

            List<Integer> surfaces = ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), arena);

            assertThat(surfaces).as("a floor and four walls").hasSize(5);
            for (int entityId : surfaces) {
                RigidBodyComponent body = scene.world().getComponent(entityId, RigidBodyComponent.class);
                assertThat(body).isNotNull();
                assertThat(body.massKg).as("arena geometry is static").isZero();
                assertThat(scene.physics().contains(body.body)).isTrue();
            }
        }
    }

    /** A spawn transform faces the way the arena says, and wraps rather than running off the list. */
    @Test
    void spawnTransformsComeFromTheArena() {
        ArenaDef arena = new ArenaDef(
                AssetId.of("arena_test_01"),
                "Test",
                new Vector3(-20f, -10f, -20f),
                new Vector3(20f, 30f, 20f),
                -15f,
                0f,
                List.of(
                        new ArenaDef.SpawnPoint("sp_a", 0, new Vector3(-5f, 1f, 0f), 90f, 12f),
                        new ArenaDef.SpawnPoint("sp_b", 0, new Vector3(5f, 1f, 0f), 270f, 12f)),
                java.util.Set.of(GameMode.DEATHMATCH),
                null);

        Matrix4 out = new Matrix4();
        Vector3 translation = new Vector3();

        ArenaFactory.spawnTransform(arena, 0, 0, out).getTranslation(translation);
        assertThat(translation.x).isCloseTo(-5f, within(1e-4f));
        ArenaFactory.spawnTransform(arena, 0, 1, out).getTranslation(translation);
        assertThat(translation.x).isCloseTo(5f, within(1e-4f));
        // Index 2 wraps to the first point rather than throwing: more vehicles than spawn points is
        // an ordinary situation, and D06-E7 asks for the next candidate, not a failure.
        ArenaFactory.spawnTransform(arena, 0, 2, out).getTranslation(translation);
        assertThat(translation.x).isCloseTo(-5f, within(1e-4f));
        // A team with no points of its own falls back to every point rather than to none.
        assertThat(arena.spawnPointsFor(9)).hasSize(2);
    }

    /** D01-E3: below the kill plane is dead, and the plane comes from the arena file. */
    @Test
    void theKillPlaneIsWhereTheArenaSaysItIs() {
        ArenaDef arena = new ArenaDef(
                AssetId.of("arena_test_01"),
                "Test",
                new Vector3(-20f, -10f, -20f),
                new Vector3(20f, 30f, 20f),
                -15f,
                0f,
                List.of(),
                java.util.Set.of(),
                null);
        assertThat(ArenaFactory.isBelowKillPlane(arena, new Vector3(0f, -14.9f, 0f)))
                .isFalse();
        assertThat(ArenaFactory.isBelowKillPlane(arena, new Vector3(0f, -15.1f, 0f)))
                .isTrue();
    }
}

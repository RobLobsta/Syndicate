/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.ClosestRayResultCallback;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.arena.TerrainField;
import dev.syndicate.core.arena.TerrainParams;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.GameMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Generated ground inside the real physics world
 * (docs/16_procedural_arena_generation.md#D16-S5.8).
 *
 * <p>Three things are being checked here and none of them is "the generator works" — that is
 * {@code TerrainGeneratorTest}'s job. These are the three ways a correct height field still ends up
 * wrong once Bullet has it, each recorded as a requirement before it was written and each verified
 * here for the first time:
 *
 * <ul>
 *   <li><b>Placement</b> (D16-R48). Bullet centres a height field on the midpoint of its own height
 *       range, so a body placed at the ground datum sits half the relief out. The symptom is terrain
 *       you fall through or stand above, and it looks exactly like a rendering bug.
 *   <li><b>Ray accuracy</b> (D16-R50, DISC-017). The whole vehicle model rides on downward rays, and
 *       Bullet's convex ray test is inaccurate on shapes this size. A height field is triangles, so
 *       it should be exact — this measures whether it is.
 *   <li><b>Buffer lifetime</b> (D16-R47). Bullet borrows the height data rather than copying it.
 * </ul>
 */
@Tag("integration")
class TerrainCollisionTest {

    private static final long SEED = 20260814L;
    private static final float SPAN_M = 600f;

    private static ArenaDef desertArena() {
        return new ArenaDef(
                AssetId.of("arena_desert_test"),
                "Desert",
                new Vector3(-300f, -40f, -300f),
                new Vector3(300f, 120f, 300f),
                -30f,
                0f,
                List.of(new ArenaDef.SpawnPoint("sp_a", -1, new Vector3(0f, 1f, 0f), 0f, 12f)),
                Set.of(GameMode.DEATHMATCH),
                null,
                TerrainParams.desert(SEED, SPAN_M));
    }

    /** An arena with a terrain block gets one height field body and no walls (D16-S5.5). */
    @Test
    void aTerrainArenaIsOneBodyAndNoWalls() {
        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            ArenaFactory.LoadedArena loaded =
                    ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), desertArena());

            assertThat(loaded.entities())
                    .as("one height field, and the border rise is the boundary rather than four walls")
                    .hasSize(1);
            assertThat(loaded.terrain())
                    .as("the generated ground comes back to its caller")
                    .isNotNull();

            RigidBodyComponentAssert.assertStatic(scene, loaded.entities().get(0));
        }
    }

    /**
     * T-D16-13 and D16-R48: a dropped body rests on the surface the field says is there.
     *
     * <p>This is the placement trap, and it is worth stating what failure would look like: the
     * arena's relief runs from about −4 m to +39 m, so a body placed at the datum instead of the
     * AABB midpoint would be out by roughly 17 m. Not subtly wrong — a car spawning in the sky or
     * inside a dune.
     */
    @Test
    void aDroppedBodyRestsOnTheGeneratedSurface() {
        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            ArenaFactory.LoadedArena loaded =
                    ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), desertArena());
            TerrainField field = loaded.terrain();

            // Somewhere in the open, on ground gentle enough that a box will settle rather than slide.
            float x = 40f;
            float z = -60f;
            float half = 0.5f;
            float groundHere = field.heightAt(x, z);

            btBoxShape shape = new btBoxShape(new Vector3(half, half, half));
            shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
            NativeResourceTracker.register("btBoxShape");
            btDefaultMotionState motionState =
                    new btDefaultMotionState(new Matrix4().setToTranslation(x, groundHere + 4f, z));
            NativeResourceTracker.register("btDefaultMotionState");
            Vector3 inertia = new Vector3();
            shape.calculateLocalInertia(10f, inertia);
            btRigidBody.btRigidBodyConstructionInfo info =
                    new btRigidBody.btRigidBodyConstructionInfo(10f, motionState, shape, inertia);
            btRigidBody body = new btRigidBody(info);
            NativeResourceTracker.register("btRigidBody");
            info.dispose();
            scene.physics().addBody(body, CollisionLayer.DEBRIS);

            for (int tick = 0; tick < 240; tick++) {
                scene.physics().step();
            }

            Matrix4 out = new Matrix4();
            Vector3 position = new Vector3();
            body.getMotionState().getWorldTransform(out);
            out.getTranslation(position);

            // It landed near where it was dropped, on ground the field agrees is there. The tolerance
            // is generous across x/z because a box on a slope creeps; the Y check is the assertion.
            float groundUnderIt = field.heightAt(position.x, position.z);
            assertThat(position.y)
                    .as("resting on the surface, not through it or above it")
                    .isCloseTo(groundUnderIt + half, within(0.25f));

            scene.physics().removeBody(body);
            body.dispose();
            NativeResourceTracker.release("btRigidBody");
            motionState.dispose();
            NativeResourceTracker.release("btDefaultMotionState");
            shape.dispose();
            NativeResourceTracker.release("btBoxShape");
        }
    }

    /**
     * T-D16-14 and D16-R50: a downward ray finds the ground where the field says, to the millimetre.
     *
     * <p>The point that retires DISC-017 for the ground. Against the old flat arena's alternative —
     * a 600 m convex box — the same cast came back up to <b>0.14 m</b> off, differently every tick,
     * which strobed the wheels through the arches. A height field is ray-tested per triangle, so the
     * error here should be the bilinear interpolation's own and nothing more.
     */
    @Test
    void aDownwardRayFindsTheGroundExactly() {
        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            ArenaFactory.LoadedArena loaded =
                    ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), desertArena());
            TerrainField field = loaded.terrain();

            float worstError = 0f;
            int sampled = 0;
            for (float x = -200f; x <= 200f; x += 37f) {
                for (float z = -200f; z <= 200f; z += 41f) {
                    // Sample at exact grid coordinates: between samples the field's bilinear surface
                    // and Bullet's triangulation are two different interpolations of the same corners,
                    // and their difference is a property of the diagonal, not of the ray test.
                    float sx = Math.round(x);
                    float sz = Math.round(z);
                    float expected = field.heightAt(sx, sz);

                    Vector3 from = new Vector3(sx, expected + 30f, sz);
                    Vector3 to = new Vector3(sx, expected - 30f, sz);
                    ClosestRayResultCallback callback = new ClosestRayResultCallback(from, to);
                    scene.physics().dynamicsWorld().rayTest(from, to, callback);
                    if (callback.hasHit()) {
                        Vector3 hit = new Vector3();
                        callback.getHitPointWorld(hit);
                        worstError = Math.max(worstError, Math.abs(hit.y - expected));
                        sampled++;
                    }
                    callback.dispose();
                }
            }

            assertThat(sampled).as("the rays found ground").isGreaterThan(100);
            // A collision margin is 0.01 m; DISC-017's convex box was 14x that. Anything in this
            // range means the suspension is riding on the surface rather than on a search tolerance.
            assertThat(worstError)
                    .as("ray hits agree with the field to well inside a collision margin (DISC-017)")
                    .isLessThan(PhysicsWorld.COLLISION_MARGIN_M * 2f);
        }
    }

    /**
     * D16-R47: the shape's height buffer stays reachable for as long as the shape does.
     *
     * <p>What this can and cannot prove is worth being honest about. It cannot provoke a
     * use-after-free on demand — that needs a collection at the wrong moment. What it does check is
     * the invariant that prevents one: the cache holds a reference for the shape's whole life and
     * releases it only after disposal, and the native census balances either way. A future change
     * that stopped holding the buffer would leave this green and crash in the field, so the comment
     * on {@code ShapeCache.heightBuffers} is the real guard and this is the tripwire under it.
     */
    @Test
    void theHeightBufferOutlivesEveryUseOfItsShape() {
        NativeResourceTracker.install();
        try {
            try (DestructionTestScene scene = new DestructionTestScene(11L)) {
                ArenaFactory.LoadedArena loaded =
                        ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), desertArena());
                assertThat(loaded.terrain()).isNotNull();

                // Force the collection that would free a buffer nothing referenced, then keep using
                // the shape. If the cache had let go of it, this is where Bullet reads freed memory.
                System.gc();
                for (int tick = 0; tick < 30; tick++) {
                    scene.physics().step();
                }
                Vector3 from = new Vector3(10f, 200f, 10f);
                Vector3 to = new Vector3(10f, -100f, 10f);
                ClosestRayResultCallback callback = new ClosestRayResultCallback(from, to);
                scene.physics().dynamicsWorld().rayTest(from, to, callback);
                assertThat(callback.hasHit())
                        .as("the shape still has its heights")
                        .isTrue();
                callback.dispose();
            }
            assertThat(NativeResourceTracker.outstanding())
                    .as("every native object the terrain path created was released: "
                            + NativeResourceTracker.describeOutstanding())
                    .isZero();
        } finally {
            NativeResourceTracker.uninstall();
        }
    }

    /** A spawn point stranded up the border rim is refused at load, not discovered by a player. */
    @Test
    void anArenaWithAStrandedSpawnPointIsRefused() {
        ArenaDef stranded = new ArenaDef(
                AssetId.of("arena_desert_test"),
                "Desert",
                new Vector3(-300f, -40f, -300f),
                new Vector3(300f, 120f, 300f),
                -30f,
                0f,
                List.of(
                        new ArenaDef.SpawnPoint("sp_a", -1, new Vector3(0f, 1f, 0f), 0f, 12f),
                        new ArenaDef.SpawnPoint("sp_b", -1, new Vector3(297f, 1f, 297f), 0f, 12f)),
                Set.of(GameMode.DEATHMATCH),
                null,
                TerrainParams.desert(SEED, SPAN_M));

        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), stranded))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("A411");
        }
    }

    /**
     * The shipped desert arena loads, validates, and produces ground a match could be played on.
     *
     * <p>The one test here that runs against real content rather than a fixture, and the one that
     * would catch the failure a fixture cannot: that the twelve spawn points authored in that file,
     * against that seed, are on ground a vehicle can drive off and reach the others from. Which
     * spawn lands on a dune face is a property of the seed, so this is not a question a reviewer can
     * answer by reading the JSON.
     */
    @Test
    void theShippedDesertArenaGeneratesAPlayableWorld() {
        dev.syndicate.core.asset.AssetLoader loader =
                new dev.syndicate.core.asset.AssetLoader(new dev.syndicate.core.asset.GltfCollisionMeshSource());
        dev.syndicate.core.asset.InMemoryAssetIndex index = new dev.syndicate.core.asset.InMemoryAssetIndex();
        loader.loadArena(java.nio.file.Path.of("..", "assets", "arenas", "arena_desert_01"), index);

        assertThat(loader.issues())
                .filteredOn(dev.syndicate.core.asset.ValidationIssue::isBlocking)
                .as("no blocking findings on the shipped desert arena")
                .isEmpty();

        ArenaDef arena = index.arena(AssetId.of("arena_desert_01"));
        assertThat(arena).isNotNull();
        assertThat(arena.hasTerrain()).as("it declares a terrain block").isTrue();

        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            ArenaFactory.LoadedArena loaded = ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), arena);
            TerrainField field = loaded.terrain();
            assertThat(field).isNotNull();

            // Every spawn point sits on levelled, drivable ground rather than on a face.
            for (ArenaDef.SpawnPoint point : arena.spawnPoints()) {
                assertThat(field.isDrivable(point.position().x, point.position().z))
                        .as("spawn " + point.id() + " is on drivable ground")
                        .isTrue();
                assertThat(field.heightAt(point.position().x, point.position().z))
                        .as("spawn " + point.id() + " is above the kill plane")
                        .isGreaterThan(arena.killPlaneY());
            }
            // ArenaFactory would have thrown on a disconnected arena; asserting it directly says
            // which property failed rather than leaving a bare exception to be interpreted.
            assertThat(field.drivableFraction()).isGreaterThan(0.55f);
        }
    }

    /** Reads better at the call site than three lines of component fetching. */
    private static final class RigidBodyComponentAssert {
        static void assertStatic(DestructionTestScene scene, int entityId) {
            dev.syndicate.core.component.RigidBodyComponent body =
                    scene.world().getComponent(entityId, dev.syndicate.core.component.RigidBodyComponent.class);
            assertThat(body).isNotNull();
            assertThat(body.massKg).as("terrain is static").isZero();
            assertThat(body.layer).isEqualTo(CollisionLayer.STATIC);
        }
    }
}

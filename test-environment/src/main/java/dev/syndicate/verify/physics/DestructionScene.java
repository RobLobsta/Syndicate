/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.util.Pcg32;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.verify.asset.FractureManifest;
import dev.syndicate.verify.asset.MeshData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The destruction progression of docs/14_test_environment.md#D14-S5.6: an intact part, then the
 * fracture that replaces it with its shards.
 *
 * <p>This is the harness's model of what {@code FractureSystem} does in the game
 * (docs/07_damage_destruction_model.md#D07-S5.6), reproduced here so the shards can be checked
 * before that system exists. The rules it must obey are the same ones:
 *
 * <ul>
 *   <li>The intact body is removed in the same step the shards appear — never both at once
 *       (PROG-005).
 *   <li>Shards inherit the parent's velocity, so total momentum is conserved across the fracture
 *       (PROG-004, G7).
 *   <li>Scatter is drawn from a seeded stream, so a fracture replays identically (G4, G11).
 *   <li>Fracture is one-way: once fractured, nothing restores the single body (G9, PROG-012).
 * </ul>
 */
public final class DestructionScene {

    /** Scatter impulse per kilogram at full strength. Tuned so shards separate visibly in ~0.3 s. */
    private static final float SCATTER_IMPULSE_PER_KG = 3.2f;

    /** Upward bias, so an explosion lifts rather than only spreading sideways. */
    private static final float SCATTER_UP_BIAS = 0.45f;

    private final TestWorld world;
    private final FractureManifest manifest;
    private final Map<String, MeshData> shardMeshes = new LinkedHashMap<>();
    private final MeshData intactMesh;
    private final Vector3 origin = new Vector3();

    private btRigidBody intactBody;
    private final List<btRigidBody> shardBodies = new ArrayList<>();
    private final List<MeshData> shardOrder = new ArrayList<>();

    private boolean hasFractured;
    private long fractureTick = -1;

    /**
     * @param intactMesh the part's intact mesh, used for the pre-fracture body
     * @param shards the shard meshes, matched to the manifest by node name
     * @param origin where the part sits in world space
     */
    public DestructionScene(
            TestWorld world, FractureManifest manifest, MeshData intactMesh, List<MeshData> shards, Vector3 origin) {
        this.world = world;
        this.manifest = manifest;
        this.intactMesh = intactMesh;
        this.origin.set(origin);
        for (MeshData shard : shards) {
            shardMeshes.put(shard.name(), shard);
        }
    }

    /** True once {@link #fracture} has run. Never returns to false (G9). */
    public boolean hasFractured() {
        return hasFractured;
    }

    /** The tick the fracture happened on, or {@code -1}. */
    public long fractureTick() {
        return fractureTick;
    }

    /** The intact body, or null after the fracture. */
    public btRigidBody intactBody() {
        return intactBody;
    }

    /** The shard bodies, in manifest order. Empty before the fracture. */
    public List<btRigidBody> shardBodies() {
        return shardBodies;
    }

    /** The shard meshes in the same order as {@link #shardBodies()}, for rendering. */
    public List<MeshData> shardMeshOrder() {
        return shardOrder;
    }

    /** Spawns the intact part as one rigid body (PROG-001). */
    public btRigidBody spawnIntact() {
        btConvexHullShape hull = world.buildHull(intactMesh, 64);
        Matrix4 transform = new Matrix4().setToTranslation(origin);
        intactBody = world.addBody(hull, manifest.partMassKg, transform, 0.7f, 0.05f);
        return intactBody;
    }

    /**
     * Replaces the intact body with its shards, giving each a share of the parent's momentum plus
     * a seeded radial scatter (D07-S5.6).
     *
     * <p>The parent's linear and angular velocity are sampled *before* it is removed and applied to
     * every shard, which is what makes PROG-004 hold: the scatter impulses are equal and opposite
     * about the centre of mass, so they add no net momentum, and the inherited velocity carries the
     * rest.
     *
     * @param strength scatter multiplier; {@code 0} makes the part crumble in place, {@code 1} is a
     *     normal destruction, higher values are an explosion
     * @param seed the fracture stream's seed, so a replay scatters identically (G4)
     */
    public void fracture(float strength, long seed) {
        if (hasFractured) {
            // G9: fracture is one-way. A second call is a no-op rather than an error, because the
            // game's damage pipeline can legitimately re-deliver a destruction event.
            return;
        }

        Vector3 parentPosition = new Vector3();
        Vector3 parentLinear = new Vector3();
        Vector3 parentAngular = new Vector3();
        if (intactBody != null) {
            intactBody.getWorldTransform().getTranslation(parentPosition);
            parentLinear.set(intactBody.getLinearVelocity());
            parentAngular.set(intactBody.getAngularVelocity());
            world.removeBody(intactBody);
            intactBody = null;
        } else {
            parentPosition.set(origin);
        }

        Pcg32 random = new Pcg32(seed, 0x5CA77E1);
        List<FractureManifest.Shard> ordered = new ArrayList<>(manifest.shards);
        // Sorted by index so the spawn order — and therefore the random draw each shard gets — is
        // the manifest's order rather than whatever the glTF node order happened to be (G3).
        ordered.sort(Comparator.comparingInt(s -> s.index));

        List<MeshData> spawnedMeshes = new ArrayList<>();
        List<Float> spawnedMasses = new ArrayList<>();
        List<Vector3> impulses = new ArrayList<>();
        List<Vector3> spins = new ArrayList<>();

        // Pass 1: choose every shard's impulse. Nothing is applied yet, because the set has to be
        // corrected as a whole before any of it becomes velocity.
        Vector3 centroid = new Vector3();
        for (FractureManifest.Shard declared : ordered) {
            MeshData mesh = shardMeshes.get(declared.name);
            if (mesh == null) {
                continue; // ASSET-002 reports the mismatch; spawning is not the place to fail
            }
            float mass = Math.max(declared.massKg, SimulationConstants.MIN_BODY_MASS_KG);
            Vector3 direction = new Vector3();
            if (strength > 0f) {
                mesh.centroid(centroid);
                direction.set(centroid).sub(manifest.comLocal.x, manifest.comLocal.y, manifest.comLocal.z);
                if (direction.len2() < 1e-8f) {
                    // A shard sitting on the centre of mass has no radial direction; give it a
                    // seeded one so it does not sit still while everything around it flies apart.
                    direction.set(random.nextFloat(-1f, 1f), random.nextFloat(-1f, 1f), random.nextFloat(-1f, 1f));
                }
                direction.nor();
                direction.y += SCATTER_UP_BIAS;
                direction.nor();
                direction.scl(SCATTER_IMPULSE_PER_KG * strength * random.nextFloat(0.75f, 1.25f) * mass);
            }
            spawnedMeshes.add(mesh);
            spawnedMasses.add(mass);
            impulses.add(direction);
            spins.add(new Vector3(
                    random.nextFloat(-6f, 6f) * strength,
                    random.nextFloat(-6f, 6f) * strength,
                    random.nextFloat(-6f, 6f) * strength));
        }

        // Momentum correction. Scatter impulses are *internal* to the part: whatever pushed the
        // shards apart pushed them apart from each other, so they must sum to zero. Radial
        // directions come close on their own, but the upward bias and the per-shard jitter do not
        // cancel, and the residual is a free shove the fracture gives a vehicle for nothing. That
        // is exactly what PROG-004 measures, and it caught this before the correction existed.
        //
        // Subtracting the mean *per shard* rather than the mass-weighted mean per kilogram would
        // change light shards' velocities far more than heavy ones; distributing the correction in
        // proportion to mass leaves the relative scatter pattern intact.
        Vector3 residual = new Vector3();
        float totalMass = 0f;
        for (int i = 0; i < impulses.size(); i++) {
            residual.add(impulses.get(i));
            totalMass += spawnedMasses.get(i);
        }
        if (totalMass > 0f && !residual.isZero()) {
            Vector3 correctionPerKg = new Vector3(residual).scl(-1f / totalMass);
            for (int i = 0; i < impulses.size(); i++) {
                impulses.get(i).mulAdd(correctionPerKg, spawnedMasses.get(i));
            }
        }

        // Pass 2: spawn the bodies and apply the corrected impulses.
        for (int i = 0; i < spawnedMeshes.size(); i++) {
            MeshData mesh = spawnedMeshes.get(i);
            float mass = spawnedMasses.get(i);
            btConvexHullShape hull = world.buildHull(mesh, 32);

            // The shard mesh already carries its own offset within the part, so the body's
            // transform is the part's transform: adding the centroid again would double it.
            Matrix4 transform = new Matrix4().setToTranslation(parentPosition);
            btRigidBody body = world.addBody(hull, mass, transform, 0.6f, 0.15f);

            body.setLinearVelocity(parentLinear);
            body.setAngularVelocity(parentAngular);
            if (strength > 0f) {
                body.applyCentralImpulse(impulses.get(i));
                body.setAngularVelocity(spins.get(i));
            }

            // Debris must not sleep while it is still visibly moving; the game's debris budget
            // despawns it on a timer instead (D07-S5.8).
            body.setActivationState(4 /* DISABLE_DEACTIVATION */);
            shardBodies.add(body);
            shardOrder.add(mesh);
        }

        hasFractured = true;
        fractureTick = world.tick();
    }

    /** Total live shard mass, for the post-fracture conservation check (PROG-007). */
    public float liveShardMassKg() {
        float total = 0f;
        for (btRigidBody body : shardBodies) {
            total += 1f / body.getInvMass();
        }
        return total;
    }

    /** Summed linear momentum of the shards, for PROG-004. */
    public Vector3 shardMomentum(Vector3 out) {
        out.set(0f, 0f, 0f);
        Vector3 velocity = new Vector3();
        for (btRigidBody body : shardBodies) {
            velocity.set(body.getLinearVelocity());
            out.mulAdd(velocity, 1f / body.getInvMass());
        }
        return out;
    }

    /** The fastest shard's speed, for the plausibility bound of PROG-009. */
    public float maxShardSpeedMps() {
        float max = 0f;
        for (btRigidBody body : shardBodies) {
            max = Math.max(max, body.getLinearVelocity().len());
        }
        return max;
    }
}

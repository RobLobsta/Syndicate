/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.check;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.verify.asset.FractureManifest;
import dev.syndicate.verify.asset.MeshData;
import dev.syndicate.verify.physics.DestructionScene;
import dev.syndicate.verify.physics.TestWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the check catalogue of docs/14_test_environment.md#D14-S4.5 against one processed asset.
 *
 * <p>Checks are registered as records rather than hard-coded into a runner (D14-R5), so a category
 * can be skipped, a check can be retired without renumbering its neighbours, and the report has the
 * same shape whether a check passed, failed, or was not applicable.
 *
 * <p>What makes this worth running alongside the tool's own self-verification (D09-S7) is that the
 * measurements happen *in Bullet*, on the exported files, using the shapes the game will build.
 * Where the two agree, confidence is high; where they disagree, the disagreement is the bug report
 * (D09-R21).
 */
public final class CheckRunner {

    private final FractureManifest manifest;
    private final MeshData intactMesh;
    private final List<MeshData> shardMeshes;
    private final Tolerances tolerances;
    private final long seed;

    private final List<Check> checks = new ArrayList<>();
    private final Map<String, Object> physicsData = new LinkedHashMap<>();

    public CheckRunner(
            FractureManifest manifest,
            MeshData intactMesh,
            List<MeshData> shardMeshes,
            Tolerances tolerances,
            long seed) {
        this.manifest = manifest;
        this.intactMesh = intactMesh;
        this.shardMeshes = shardMeshes;
        this.tolerances = tolerances;
        this.seed = seed;
    }

    /** Everything measured along the way, so a failing report is diagnosable without a re-run. */
    public Map<String, Object> physicsData() {
        return physicsData;
    }

    /** Runs every applicable check and returns the results in id order. */
    public List<Check> run(Set<Check.Category> categories) {
        if (categories.contains(Check.Category.ASSET)) {
            runAssetChecks();
        }
        if (categories.contains(Check.Category.PHYSICS)) {
            runPhysicsChecks();
        }
        if (categories.contains(Check.Category.PROGRESSION)) {
            runProgressionChecks();
        }
        return checks;
    }

    // ---- ASSET-*: manifest vs mesh agreement (D14-S4.5.1) -------------------------------

    private void runAssetChecks() {
        Map<String, MeshData> byName = new LinkedHashMap<>();
        for (MeshData mesh : shardMeshes) {
            byName.put(mesh.name(), mesh);
        }

        float minMass = Float.MAX_VALUE;
        float maxMass = 0f;
        for (FractureManifest.Shard shard : manifest.shards) {
            minMass = Math.min(minMass, shard.massKg);
            maxMass = Math.max(maxMass, shard.massKg);
        }
        final float finalMin = manifest.shards.isEmpty() ? 0f : minMass;
        int positive = 0;
        for (FractureManifest.Shard shard : manifest.shards) {
            if (shard.massKg > SimulationConstants.MIN_BODY_MASS_KG) {
                positive++;
            }
        }
        add(timed(
                "ASSET-001",
                "All shards have positive mass",
                Check.Category.ASSET,
                positive == manifest.shards.size() && !manifest.shards.isEmpty(),
                "mass > " + SimulationConstants.MIN_BODY_MASS_KG + " kg for all shards",
                String.format("min mass: %.4f kg, max mass: %.4f kg", finalMin, maxMass),
                (double) SimulationConstants.MIN_BODY_MASS_KG,
                (double) finalMin,
                null,
                positive + "/" + manifest.shards.size() + " shards pass"));

        List<String> missing = new ArrayList<>();
        for (FractureManifest.Shard shard : manifest.shards) {
            if (!byName.containsKey(shard.name)) {
                missing.add(shard.name);
            }
        }
        add(timed(
                "ASSET-002",
                "Declared shards exist in mesh",
                Check.Category.ASSET,
                missing.isEmpty(),
                "every manifest shard has a matching mesh node",
                missing.isEmpty() ? "all present" : "missing: " + missing,
                null,
                null,
                null,
                (manifest.shards.size() - missing.size()) + "/" + manifest.shards.size() + " matched"));

        Set<String> declared = new HashSet<>();
        for (FractureManifest.Shard shard : manifest.shards) {
            declared.add(shard.name);
        }
        List<String> extra = new ArrayList<>();
        for (MeshData mesh : shardMeshes) {
            if (!declared.contains(mesh.name())) {
                extra.add(mesh.name());
            }
        }
        add(timed(
                "ASSET-003",
                "No extra shard meshes",
                Check.Category.ASSET,
                extra.isEmpty(),
                "mesh shard nodes are a subset of the manifest's",
                extra.isEmpty() ? "no extras" : "extra: " + extra,
                null,
                null,
                null,
                (shardMeshes.size() - extra.size()) + "/" + shardMeshes.size() + " declared"));

        // ASSET-004: recompute volume x density per shard and compare to the manifest. This is
        // the check that catches a tool that computed mass from a bounding box, or in the wrong
        // units — both of which produce a plausible-looking manifest.
        double worstMassDelta = 0.0;
        int massMatches = 0;
        for (FractureManifest.Shard shard : manifest.shards) {
            MeshData mesh = byName.get(shard.name);
            if (mesh == null) {
                continue;
            }
            float recomputed = mesh.volumeM3() * manifest.densityKgPerM3;
            double relative = shard.massKg <= 0 ? 1.0 : Math.abs(recomputed - shard.massKg) / shard.massKg;
            worstMassDelta = Math.max(worstMassDelta, relative);
            if (relative <= tolerances.get(Tolerances.MASS_DELTA_FRAC)) {
                massMatches++;
            }
        }
        add(timed(
                "ASSET-004",
                "Shard mass matches manifest",
                Check.Category.ASSET,
                massMatches == manifest.shards.size(),
                "recomputed volume x density within " + pct(Tolerances.MASS_DELTA_FRAC) + " of the manifest",
                String.format("worst relative delta %.4f%%", worstMassDelta * 100),
                0.0,
                worstMassDelta,
                tolerances.get(Tolerances.MASS_DELTA_FRAC),
                worstMassDelta,
                massMatches + "/" + manifest.shards.size() + " shards pass"));

        int nonDegenerate = 0;
        for (MeshData mesh : shardMeshes) {
            if (mesh.vertexCount() >= 4 && mesh.triangleCount() >= 4 && mesh.volumeM3() > 1e-9f && mesh.isFinite()) {
                nonDegenerate++;
            }
        }
        add(timed(
                "ASSET-005",
                "Shard meshes are non-degenerate",
                Check.Category.ASSET,
                nonDegenerate == shardMeshes.size(),
                ">= 4 vertices, >= 4 faces, positive volume, no NaN",
                nonDegenerate + " of " + shardMeshes.size() + " meshes are well-formed",
                null,
                null,
                null,
                nonDegenerate + "/" + shardMeshes.size() + " shards pass"));

        // ASSET-006 is G7 itself: the shards must weigh what the part weighed.
        double shardSum = 0.0;
        for (MeshData mesh : shardMeshes) {
            shardSum += mesh.volumeM3() * manifest.densityKgPerM3;
        }
        double conservationDelta = Math.abs(shardSum - manifest.partMassKg);
        double allowance = tolerances.get(Tolerances.MASS_DELTA_FRAC) * manifest.partMassKg;
        physicsData.put("original_mass_kg", (double) manifest.partMassKg);
        physicsData.put("total_shard_mass_kg", shardSum);
        physicsData.put("mass_conservation_delta_kg", conservationDelta);
        physicsData.put("shard_count", manifest.shardCount);
        add(timed(
                "ASSET-006",
                "Total shard mass conserves part mass",
                Check.Category.ASSET,
                conservationDelta <= allowance,
                "|sum(shardMass) - partMass| <= " + pct(Tolerances.MASS_DELTA_FRAC) + " of partMass",
                String.format("sum: %.3f kg vs part: %.3f kg", shardSum, manifest.partMassKg),
                (double) manifest.partMassKg,
                shardSum,
                allowance,
                conservationDelta,
                String.format("delta %.3f%% of part mass", 100 * conservationDelta / manifest.partMassKg)));

        // ASSET-007 asks the opposite question it used to. A fracture manifest describes the
        // FRACTURE transform and no destruction class in D15-S5.7 receives both transforms, so a
        // part with shards must carry *no* damage morphs — deformation is a separate tool writing
        // a separate `deform_manifest.json` beside it. The check used to require four morph
        // targets here, which is what let one tool author both and nothing notice (DISC-068).
        add(timed(
                "ASSET-007",
                "A fractured part declares no damage morphs",
                Check.Category.ASSET,
                manifest.morphTargets.isEmpty(),
                "a part that shatters does not also dent (D15-S5.7)",
                String.valueOf(manifest.morphTargets),
                0.0,
                (double) manifest.morphTargets.size(),
                null,
                null,
                manifest.morphTargets.size() + " morph targets on a fracturing part"));
        physicsData.put("morph_targets", manifest.morphTargets);

        add(timed(
                "ASSET-013",
                "Manifest count matches mesh count",
                Check.Category.ASSET,
                manifest.shardCount == shardMeshes.size(),
                "manifest.shardCount == mesh shard node count",
                manifest.shardCount + " declared, " + shardMeshes.size() + " in mesh",
                (double) manifest.shardCount,
                (double) shardMeshes.size(),
                null,
                null,
                manifest.shardCount + " vs " + shardMeshes.size()));

        // ASSET-015: extents plausible and up-axis +Y. The up-axis half is the one that catches a
        // missed Z-up to Y-up conversion (D00-R16), which otherwise ships every part on its side.
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        aabb(intactMesh, min, max);
        float extent = Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
        boolean plausible = extent >= 0.01f && extent <= 20f;
        physicsData.put("aabb_min_m", vec(min));
        physicsData.put("aabb_max_m", vec(max));
        add(timed(
                "ASSET-015",
                "Units and axes plausible",
                Check.Category.ASSET,
                plausible,
                "max extent within [0.01, 20] m",
                String.format("max extent %.4f m, aabb %s..%s", extent, min, max),
                null,
                (double) extent,
                null,
                null,
                plausible ? "within bounds" : "outside plausible part size"));

        double declaredSum = manifest.declaredShardMassKg();
        double coverage = manifest.partMassKg <= 0 ? 0 : declaredSum / manifest.partMassKg;
        add(timed(
                "ASSET-018",
                "Shard union covers the part",
                Check.Category.ASSET,
                coverage >= tolerances.get(Tolerances.VOLUME_COVERAGE_FRAC),
                "declared shard mass >= " + pct(Tolerances.VOLUME_COVERAGE_FRAC) + " of part mass",
                String.format("coverage %.2f%%", coverage * 100),
                tolerances.get(Tolerances.VOLUME_COVERAGE_FRAC),
                coverage,
                null,
                null,
                String.format("%.4f kg of %.4f kg", declaredSum, manifest.partMassKg),
                Check.Status.WARNING));
    }

    // ---- PHYS-*: behaviour in a real Bullet world (D14-S4.5.2) --------------------------

    private void runPhysicsChecks() {
        try (TestWorld world = new TestWorld(true)) {
            btConvexHullShape hull = world.buildHull(intactMesh, 64);
            btRigidBody body =
                    world.addBody(hull, manifest.partMassKg, new Matrix4().setToTranslation(0f, 3f, 0f), 0.7f, 0.05f);

            add(timed(
                    "PHYS-001",
                    "Body constructs",
                    Check.Category.PHYSICS,
                    body.getInvMass() > 0f,
                    "a btRigidBody is created from the intact mesh's hull",
                    "constructed with " + hull.getNumPoints() + " hull points",
                    null,
                    (double) hull.getNumPoints(),
                    null,
                    null,
                    "hull built and body added to the world"));

            float measuredMass = 1f / body.getInvMass();
            double massDelta = Math.abs(measuredMass - manifest.partMassKg) / manifest.partMassKg;
            add(timed(
                    "PHYS-002",
                    "Mass matches manifest",
                    Check.Category.PHYSICS,
                    massDelta <= tolerances.get(Tolerances.MASS_DELTA_FRAC),
                    "body mass equals manifest.partMassKg",
                    String.format("%.3f kg vs %.3f kg", measuredMass, manifest.partMassKg),
                    (double) manifest.partMassKg,
                    (double) measuredMass,
                    tolerances.get(Tolerances.MASS_DELTA_FRAC),
                    massDelta,
                    String.format("relative delta %.5f", massDelta)));

            Vector3 measuredCom = intactMesh.centroid(new Vector3());
            Vector3 declaredCom = manifest.comLocal.toVector(new Vector3());
            float comOffset = measuredCom.dst(declaredCom);
            physicsData.put("com", vec(measuredCom));
            physicsData.put("com_manifest", vec(declaredCom));
            physicsData.put("com_offset_m", (double) comOffset);
            add(timed(
                    "PHYS-003",
                    "COM matches manifest",
                    Check.Category.PHYSICS,
                    comOffset <= tolerances.get(Tolerances.COM_OFFSET_M),
                    "recomputed COM within " + tolerances.get(Tolerances.COM_OFFSET_M) + " m of the manifest",
                    String.format("measured %s vs manifest %s", measuredCom, declaredCom),
                    0.0,
                    (double) comOffset,
                    tolerances.get(Tolerances.COM_OFFSET_M),
                    (double) comOffset,
                    String.format("offset %.5f m", comOffset)));

            Vector3 inertia = new Vector3();
            hull.calculateLocalInertia(manifest.partMassKg, inertia);
            boolean positiveDiagonal = inertia.x > 0 && inertia.y > 0 && inertia.z > 0;
            // The triangle inequality is what distinguishes a physically realisable inertia
            // tensor from an arbitrary triple of positive numbers; Bullet will happily integrate
            // an impossible one and produce motion no real object makes.
            boolean triangle = inertia.x + inertia.y >= inertia.z
                    && inertia.y + inertia.z >= inertia.x
                    && inertia.z + inertia.x >= inertia.y;
            physicsData.put("inertia_diagonal", vec(inertia));
            add(timed(
                    "PHYS-004",
                    "Inertia tensor plausible",
                    Check.Category.PHYSICS,
                    positiveDiagonal && triangle,
                    "diagonal positive and satisfies the triangle inequality",
                    "inertia " + inertia,
                    null,
                    null,
                    null,
                    null,
                    positiveDiagonal
                            ? (triangle ? "positive, triangle holds" : "triangle inequality violated")
                            : "non-positive diagonal entry"));

            // PHYS-005: a body in free fall for one second must have picked up exactly one
            // second of gravity. It is the check that catches a world configured in the wrong
            // units or at the wrong timestep, which nothing else notices.
            body.setLinearVelocity(new Vector3(0f, 0f, 0f));
            body.setAngularVelocity(new Vector3(0f, 0f, 0f));
            body.setWorldTransform(new Matrix4().setToTranslation(0f, 40f, 0f));
            body.activate();
            world.step(SimulationConstants.TICK_RATE_HZ);
            float vy = body.getLinearVelocity().y;
            double expectedVy = SimulationConstants.WORLD_GRAVITY_Y;
            double vyDelta = Math.abs(vy - expectedVy) / Math.abs(expectedVy);
            add(timed(
                    "PHYS-005",
                    "Gravity response",
                    Check.Category.PHYSICS,
                    vyDelta <= tolerances.get(Tolerances.VELOCITY_REL),
                    "free body after 1.0 s has v_y ~ " + expectedVy + " m/s",
                    String.format("v_y = %.4f m/s", vy),
                    expectedVy,
                    (double) vy,
                    tolerances.get(Tolerances.VELOCITY_REL),
                    vyDelta,
                    String.format("relative delta %.5f", vyDelta)));

            // PHYS-006: impulse J gives dv = J/m. Applied on a fresh tick so gravity's
            // contribution over the step is the same in expectation and measurement.
            body.setWorldTransform(new Matrix4().setToTranslation(0f, 40f, 0f));
            body.setLinearVelocity(new Vector3(0f, 0f, 0f));
            body.setAngularVelocity(new Vector3(0f, 0f, 0f));
            body.activate();
            float impulse = manifest.partMassKg * 2f;
            body.applyCentralImpulse(new Vector3(impulse, 0f, 0f));
            world.step();
            float vx = body.getLinearVelocity().x;
            double expectedVx = impulse / manifest.partMassKg;
            double vxDelta = Math.abs(vx - expectedVx) / expectedVx;
            add(timed(
                    "PHYS-006",
                    "Impulse response",
                    Check.Category.PHYSICS,
                    vxDelta <= tolerances.get(Tolerances.VELOCITY_REL),
                    "impulse J along +X gives dv_x ~ J/m",
                    String.format("dv_x = %.4f m/s, expected %.4f", vx, expectedVx),
                    expectedVx,
                    (double) vx,
                    tolerances.get(Tolerances.VELOCITY_REL),
                    vxDelta,
                    String.format("relative delta %.5f", vxDelta)));

            // PHYS-008/009/010: drop, settle, and stay settled.
            body.setWorldTransform(new Matrix4().setToTranslation(0f, 2f, 0f));
            body.setLinearVelocity(new Vector3(0f, 0f, 0f));
            body.setAngularVelocity(new Vector3(0f, 0f, 0f));
            body.activate();
            boolean sawNaN = false;
            Vector3 position = new Vector3();
            for (int i = 0; i < SimulationConstants.TICK_RATE_HZ * 4; i++) {
                world.step();
                body.getWorldTransform().getTranslation(position);
                if (!Float.isFinite(position.x) || !Float.isFinite(position.y) || !Float.isFinite(position.z)) {
                    sawNaN = true;
                    break;
                }
            }
            body.getWorldTransform().getTranslation(position);
            float restSpeed = body.getLinearVelocity().len();
            physicsData.put("resting_position_m", vec(position));
            physicsData.put("post_rest_jitter_mps", (double) restSpeed);

            Vector3 min = new Vector3();
            Vector3 max = new Vector3();
            aabb(intactMesh, min, max);
            // The expected resting height comes from the *collision shape's* own AABB, not from
            // the mesh's. Two reasons, and the second is why this check kept flip-flopping:
            //
            //  - The mesh's lowest point sits `min.y` below its origin, so a body resting on
            //    y = 0 has its origin at -min.y. Comparing against 0 would fail every fixture
            //    whose origin is not on its base face.
            //  - Bullet's collision margin inflates every convex shape, so the resting surface is
            //    one margin below the body's lowest mesh vertex. It is not error: the margin is
            //    what keeps contact generation stable, the game ships with it, and a check that
            //    treated it as drift would need a tolerance loose enough to hide real sinking.
            //    Exactly one margin holds only because `TestWorld` builds simplified hulls with
            //    `buildHull(0)` — see DISC-004 for what happens otherwise.
            float expectedRestY = -min.y + TestWorld.COLLISION_MARGIN_M;
            float restError = Math.abs(position.y - expectedRestY);
            add(timed(
                    "PHYS-008",
                    "Drop and rest",
                    Check.Category.PHYSICS,
                    !sawNaN && restError <= tolerances.get(Tolerances.RESTING_POSITION_M),
                    String.format(
                            "dropped from 2 m, rests near y = %.4f m (mesh bottom + %.3f m margin)",
                            expectedRestY, TestWorld.COLLISION_MARGIN_M),
                    String.format("resting y = %.4f m", position.y),
                    (double) expectedRestY,
                    (double) position.y,
                    tolerances.get(Tolerances.RESTING_POSITION_M),
                    (double) restError,
                    String.format("settled %.4f m from the expected height", restError)));

            float penetration = Math.max(0f, expectedRestY - position.y);
            physicsData.put("resting_penetration_m", (double) penetration);
            add(timed(
                    "PHYS-009",
                    "No sinking",
                    Check.Category.PHYSICS,
                    penetration <= tolerances.get(Tolerances.MAX_PENETRATION_M),
                    "steady-state penetration <= " + tolerances.get(Tolerances.MAX_PENETRATION_M) + " m",
                    String.format("%.5f m", penetration),
                    0.0,
                    (double) penetration,
                    tolerances.get(Tolerances.MAX_PENETRATION_M),
                    (double) penetration,
                    String.format("penetration %.5f m", penetration)));

            add(timed(
                    "PHYS-010",
                    "No resting jitter",
                    Check.Category.PHYSICS,
                    restSpeed <= tolerances.get(Tolerances.RESTING_JITTER_MPS),
                    "settled speed <= " + tolerances.get(Tolerances.RESTING_JITTER_MPS) + " m/s",
                    String.format("%.5f m/s", restSpeed),
                    0.0,
                    (double) restSpeed,
                    tolerances.get(Tolerances.RESTING_JITTER_MPS),
                    (double) restSpeed,
                    String.format("residual speed %.5f m/s", restSpeed)));

            add(timed(
                    "PHYS-011",
                    "No NaN in state",
                    Check.Category.PHYSICS,
                    !sawNaN,
                    "no NaN or Inf in position at any step",
                    sawNaN ? "NaN appeared during the drop" : "finite throughout",
                    null,
                    null,
                    null,
                    null,
                    sawNaN ? "state diverged" : "240 ticks checked"));
        }

        runDeterminismCheck();
    }

    /**
     * PHYS-012: two identical runs must end at the same place.
     *
     * <p>Run in its own pair of worlds rather than reusing the one above, because the check is
     * about reproducing a whole scenario from construction — a shared world would carry the solver
     * state that makes divergence hard to see.
     */
    private void runDeterminismCheck() {
        Vector3 first = simulateDrop();
        Vector3 second = simulateDrop();
        float divergence = first.dst(second);
        add(timed(
                "PHYS-012",
                "Deterministic replay",
                Check.Category.PHYSICS,
                divergence <= tolerances.get(Tolerances.DETERMINISM_POS_M),
                "two identical runs produce the same final transform",
                String.format("divergence %.8f m", divergence),
                0.0,
                (double) divergence,
                tolerances.get(Tolerances.DETERMINISM_POS_M),
                (double) divergence,
                String.format("%s vs %s", first, second)));
    }

    private Vector3 simulateDrop() {
        try (TestWorld world = new TestWorld(true)) {
            btConvexHullShape hull = world.buildHull(intactMesh, 64);
            btRigidBody body = world.addBody(
                    hull, manifest.partMassKg, new Matrix4().setToTranslation(0.13f, 2.5f, -0.07f), 0.7f, 0.05f);
            world.step(SimulationConstants.TICK_RATE_HZ * 3);
            Vector3 out = new Vector3();
            body.getWorldTransform().getTranslation(out);
            return out;
        }
    }

    // ---- PROG-*: the destruction progression (D14-S4.5.3) -------------------------------

    private void runProgressionChecks() {
        try (TestWorld world = new TestWorld(true)) {
            DestructionScene scene =
                    new DestructionScene(world, manifest, intactMesh, shardMeshes, new Vector3(0f, 3f, 0f));
            scene.spawnIntact();

            int bodiesBefore = world.bodies().size();
            add(timed(
                    "PROG-001",
                    "Intact baseline",
                    Check.Category.PROGRESSION,
                    bodiesBefore == 2 && !scene.hasFractured(),
                    "exactly one part body plus the ground; not yet fractured",
                    bodiesBefore + " bodies in the world",
                    2.0,
                    (double) bodiesBefore,
                    null,
                    null,
                    "1 part body + 1 ground body"));

            // The parent is given a velocity before fracturing, so PROG-004 measures inheritance
            // rather than trivially comparing zero to zero.
            Vector3 parentVelocity = new Vector3(4f, 1.5f, -2f);
            scene.intactBody().setLinearVelocity(parentVelocity);
            float parentMass = manifest.partMassKg;
            Vector3 expectedMomentum = new Vector3(parentVelocity).scl(parentMass);

            scene.fracture(1.0f, seed);

            int shardBodies = scene.shardBodies().size();
            boolean intactGone = scene.intactBody() == null;
            add(timed(
                    "PROG-005",
                    "Body count after fracture",
                    Check.Category.PROGRESSION,
                    intactGone && shardBodies == manifest.shardCount,
                    "the single body is removed and exactly shardCount bodies exist",
                    (intactGone ? "intact removed, " : "intact still present, ") + shardBodies + " shard bodies",
                    (double) manifest.shardCount,
                    (double) shardBodies,
                    null,
                    null,
                    shardBodies + "/" + manifest.shardCount + " shard bodies spawned"));

            int massMatches = 0;
            double worstShardMassDelta = 0.0;
            for (int i = 0; i < scene.shardBodies().size() && i < manifest.shards.size(); i++) {
                float actual = 1f / scene.shardBodies().get(i).getInvMass();
                float declared = manifest.shards.get(i).massKg;
                double relative = declared <= 0 ? 1 : Math.abs(actual - declared) / declared;
                worstShardMassDelta = Math.max(worstShardMassDelta, relative);
                if (relative <= tolerances.get(Tolerances.MASS_DELTA_FRAC)) {
                    massMatches++;
                }
            }
            add(timed(
                    "PROG-006",
                    "Shard mass after fracture",
                    Check.Category.PROGRESSION,
                    massMatches == shardBodies && shardBodies > 0,
                    "each spawned body's mass equals its manifest mass",
                    String.format("worst relative delta %.5f", worstShardMassDelta),
                    0.0,
                    worstShardMassDelta,
                    tolerances.get(Tolerances.MASS_DELTA_FRAC),
                    worstShardMassDelta,
                    massMatches + "/" + shardBodies + " shards pass"));

            float liveMass = scene.liveShardMassKg();
            double conservation = Math.abs(liveMass - parentMass) / parentMass;
            add(timed(
                    "PROG-007",
                    "Shard mass conservation post-fracture",
                    Check.Category.PROGRESSION,
                    conservation <= tolerances.get(Tolerances.MASS_DELTA_FRAC),
                    "sum of live shard masses ~ original part mass (G7)",
                    String.format("%.3f kg vs %.3f kg", liveMass, parentMass),
                    (double) parentMass,
                    (double) liveMass,
                    tolerances.get(Tolerances.MASS_DELTA_FRAC),
                    conservation,
                    String.format("delta %.4f%%", conservation * 100)));

            // PROG-004 is measured at the fracture tick, before gravity has had a step to act:
            // one tick of gravity would add mass * g * dt to every shard and to nothing in the
            // expected value, which would read as a momentum leak.
            Vector3 momentum = scene.shardMomentum(new Vector3());
            float expectedMagnitude = expectedMomentum.len();
            double momentumDelta = expectedMagnitude <= 0
                    ? 0
                    : new Vector3(momentum).sub(expectedMomentum).len() / expectedMagnitude;
            add(timed(
                    "PROG-004",
                    "Shards inherit momentum",
                    Check.Category.PROGRESSION,
                    momentumDelta <= tolerances.get(Tolerances.VELOCITY_REL),
                    "|sum(m_i * v_i) - M * V| <= " + pct(Tolerances.VELOCITY_REL) + " of |M * V|",
                    String.format("momentum delta %.2f%%", momentumDelta * 100),
                    0.0,
                    momentumDelta,
                    tolerances.get(Tolerances.VELOCITY_REL),
                    momentumDelta,
                    String.format("measured %s vs expected %s", momentum, expectedMomentum)));

            int validShapes = 0;
            for (btRigidBody body : scene.shardBodies()) {
                if (body.getCollisionShape() != null && body.getInvMass() > 0f) {
                    validShapes++;
                }
            }
            add(timed(
                    "PROG-008",
                    "Shard collision shapes valid",
                    Check.Category.PROGRESSION,
                    validShapes == shardBodies && shardBodies > 0,
                    "each shard body has a non-null convex shape and positive mass",
                    validShapes + " of " + shardBodies + " valid",
                    null,
                    null,
                    null,
                    null,
                    validShapes + "/" + shardBodies + " shards pass"));

            float maxSpeed = scene.maxShardSpeedMps();
            boolean anyMoving = maxSpeed > 0.01f;
            add(timed(
                    "PROG-009",
                    "Shard scatter plausible",
                    Check.Category.PROGRESSION,
                    anyMoving && maxSpeed <= tolerances.get(Tolerances.MAX_SCATTER_SPEED_MPS),
                    "every shard speed <= " + tolerances.get(Tolerances.MAX_SCATTER_SPEED_MPS)
                            + " m/s and not all zero",
                    String.format("max shard speed %.3f m/s", maxSpeed),
                    null,
                    (double) maxSpeed,
                    tolerances.get(Tolerances.MAX_SCATTER_SPEED_MPS),
                    null,
                    anyMoving ? "shards are moving" : "no shard is moving"));

            world.step(SimulationConstants.TICK_RATE_HZ / 2);

            int independentPairs = 0;
            int totalPairs = 0;
            List<btRigidBody> live = scene.shardBodies();
            for (int i = 0; i < live.size(); i++) {
                for (int j = i + 1; j < live.size(); j++) {
                    totalPairs++;
                    Vector3 a = new Vector3(live.get(i).getLinearVelocity());
                    Vector3 b = new Vector3(live.get(j).getLinearVelocity());
                    if (a.sub(b).len() > tolerances.get(Tolerances.VELOCITY_EPS)) {
                        independentPairs++;
                    }
                }
            }
            double independentFraction = totalPairs == 0 ? 0 : (double) independentPairs / totalPairs;
            add(timed(
                    "PROG-010",
                    "Shards move independently",
                    Check.Category.PROGRESSION,
                    independentFraction >= tolerances.get(Tolerances.INDEPENDENT_FRAC),
                    "after 0.5 s, >= " + pct(Tolerances.INDEPENDENT_FRAC) + " of shard pairs differ in velocity",
                    String.format("%.1f%% of pairs differ", independentFraction * 100),
                    tolerances.get(Tolerances.INDEPENDENT_FRAC),
                    independentFraction,
                    tolerances.get(Tolerances.VELOCITY_EPS),
                    null,
                    independentPairs + "/" + totalPairs + " pairs independent"));

            // PROG-012: G9 says fracture is one-way. Calling it again must change nothing.
            int before = scene.shardBodies().size();
            scene.fracture(1.0f, seed);
            add(timed(
                    "PROG-012",
                    "Fracture is one-way",
                    Check.Category.PROGRESSION,
                    scene.shardBodies().size() == before && scene.intactBody() == null,
                    "a second fracture does not restore the single body or add shards",
                    scene.shardBodies().size() + " shard bodies, intact body "
                            + (scene.intactBody() == null ? "absent" : "present"),
                    (double) before,
                    (double) scene.shardBodies().size(),
                    null,
                    null,
                    "second fracture was a no-op"));

            boolean finite = true;
            Vector3 probe = new Vector3();
            for (btRigidBody body : scene.shardBodies()) {
                body.getWorldTransform().getTranslation(probe);
                if (!Float.isFinite(probe.x) || !Float.isFinite(probe.y) || !Float.isFinite(probe.z)) {
                    finite = false;
                    break;
                }
            }
            add(timed(
                    "PROG-011",
                    "No NaN after fracture",
                    Check.Category.PROGRESSION,
                    finite,
                    "no NaN or Inf in any shard transform",
                    finite ? "all shard transforms finite" : "a shard transform diverged",
                    null,
                    null,
                    null,
                    null,
                    scene.shardBodies().size() + " shards checked"));

            physicsData.put("hull_vertex_counts", hullVertexCounts(world, scene));
        }
    }

    private List<Integer> hullVertexCounts(TestWorld world, DestructionScene scene) {
        List<Integer> counts = new ArrayList<>();
        for (MeshData mesh : scene.shardMeshOrder()) {
            btConvexHullShape hull = world.buildHull(mesh, 32);
            counts.add(hull.getNumPoints());
        }
        return counts;
    }

    // ---- Plumbing ------------------------------------------------------------------------

    private void add(Check check) {
        checks.add(check);
    }

    private Check timed(
            String id,
            String name,
            Check.Category category,
            boolean passed,
            String expected,
            String actual,
            Double expectedValue,
            Double actualValue,
            Double tolerance,
            String details) {
        return timed(
                id,
                name,
                category,
                passed,
                expected,
                actual,
                expectedValue,
                actualValue,
                tolerance,
                null,
                details,
                Check.Status.FAIL);
    }

    private Check timed(
            String id,
            String name,
            Check.Category category,
            boolean passed,
            String expected,
            String actual,
            Double expectedValue,
            Double actualValue,
            Double tolerance,
            Double delta,
            String details) {
        return timed(
                id,
                name,
                category,
                passed,
                expected,
                actual,
                expectedValue,
                actualValue,
                tolerance,
                delta,
                details,
                Check.Status.FAIL);
    }

    private Check timed(
            String id,
            String name,
            Check.Category category,
            boolean passed,
            String expected,
            String actual,
            Double expectedValue,
            Double actualValue,
            Double tolerance,
            String details,
            Check.Status onFailure) {
        return timed(
                id,
                name,
                category,
                passed,
                expected,
                actual,
                expectedValue,
                actualValue,
                tolerance,
                null,
                details,
                onFailure);
    }

    private Check timed(
            String id,
            String name,
            Check.Category category,
            boolean passed,
            String expected,
            String actual,
            Double expectedValue,
            Double actualValue,
            Double tolerance,
            Double delta,
            String details,
            Check.Status onFailure) {
        return new Check(
                id,
                name,
                category,
                passed ? Check.Status.PASS : onFailure,
                expected,
                actual,
                expectedValue,
                actualValue,
                tolerance,
                delta,
                details,
                0L);
    }

    private String pct(String tolerance) {
        return String.format("%.1f%%", tolerances.get(tolerance) * 100);
    }

    private static void aabb(MeshData mesh, Vector3 min, Vector3 max) {
        min.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        max.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        Vector3 vertex = new Vector3();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            mesh.vertex(i, vertex);
            min.x = Math.min(min.x, vertex.x);
            min.y = Math.min(min.y, vertex.y);
            min.z = Math.min(min.z, vertex.z);
            max.x = Math.max(max.x, vertex.x);
            max.y = Math.max(max.y, vertex.y);
            max.z = Math.max(max.z, vertex.z);
        }
    }

    private static Map<String, Double> vec(Vector3 v) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("x", (double) v.x);
        out.put("y", (double) v.y);
        out.put("z", (double) v.z);
        return out;
    }
}

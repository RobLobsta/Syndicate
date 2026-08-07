<!-- D14-S0 --># 14 — Test Environment (Verification Harness)

**Document ID:** D14
**Owns:** The verification harness: check catalogue, tolerances, fixtures, golden comparison, report schema, visual and headless modes.

---

<!-- D14-S1 -->## 1. Purpose

This document specifies the **test environment**: a libGDX + Bullet harness that loads processed meshes and physics data produced by the Blender destruction tool (D09) and proves, before those assets reach the game, that

1. the asset is internally consistent and matches its manifest, and
2. the asset behaves correctly as physics — mass, centre of mass, inertia, force response, resting contact, and
3. the **destruction progression** works end to end: intact → morph-interpolated damage → fracture → independent shards → constraint break, and
4. the asset integrates into a vehicle without breaking mass, COM, or handling.

It is the bridge between D09's output contract and D06/D07's runtime behaviour. It runs **locally with rendering** for a human or agent to inspect a fracture visually, and **headlessly with assertions only** for CI. Both modes run the same checks and emit the same machine-readable JSON report.

The harness is the module `test-environment` (`dev.syndicate.verify`, D02-S4.5).

Requirements are numbered `R1..Rn`, cited as `D14-R12`.

---

<!-- D14-S2 -->## 2. Scope

<!-- D14-S2.1 -->### 2.1 In Scope

- Two verification layers: asset-level and physics-level.
- Destruction progression verification (the critical path).
- Vehicle integration verification.
- Visual verification mode: scene, camera, console commands, debug overlays, slow motion, side-by-side comparison.
- Headless automated mode: same checks, JSON report, exit-code mapping.
- Canonical test fixtures and golden manifests.
- The verification report schema.
- The tolerance table, with rationale per tolerance.
- The check registration pattern for adding new checks.

<!-- D14-S2.2 -->### 2.2 Non-Goals

- **NG1.** The Blender tool's own internal self-verification — that is D09-S7. The harness re-verifies independently, in the engine, on the engine's own terms. Duplication here is deliberate: the tool checks what it produced; the harness checks what the game will actually load.
- **NG2.** Gameplay balance testing — see `docs/11_ai_bots_and_match_simulation.md#D11-S5.8`.
- **NG3.** The CI system itself — see `docs/12_testing_validation_ci.md#D12-S5.4`. This document specifies what CI *runs*.
- **NG4.** Rendering fidelity, shading, or art-direction review. The visual mode is a physics/geometry inspector, not a look-dev tool.
- **NG5.** Network verification — see `docs/10_networking_multiplayer.md#D10-S8`.
- **NG6.** Unit tests of `game-core` classes — see `docs/12_testing_validation_ci.md#D12-S5.1`. The harness tests *assets in the engine*, not code in isolation.

---

<!-- D14-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/09_blender_destruction_tool.md#D09-S4.4` | The fracture manifest schema — the harness's primary input contract |
| `docs/09_blender_destruction_tool.md#D09-S7` | What the tool already checked, so the harness knows what it is independently re-verifying |
| `docs/06_physics_simulation.md#D06-S5.1` | Bullet world construction, reused verbatim |
| `docs/06_physics_simulation.md#D06-S5.7` | Mass property computation the harness asserts against |
| `docs/06_physics_simulation.md#D06-S4.4` | Collision layers used in test worlds |
| `docs/07_damage_destruction_model.md#D07-S5.3` | Damage state machine driven by the progression checks |
| `docs/07_damage_destruction_model.md#D07-S5.5` | Health → morph weight mapping the harness asserts |
| `docs/07_damage_destruction_model.md#D07-S5.6` | Fracture trigger and momentum inheritance |
| `docs/05_vehicle_part_system.md#D05-S5.4` | Degradation curve the harness asserts stat values against |
| `docs/05_vehicle_part_system.md#D05-S5.5` | Detachment effect on vehicle physics |
| `docs/08_asset_pipeline.md#D08-S4.2` | Part definition schema |
| `docs/12_testing_validation_ci.md#D12-S5.4` | CI stage that runs `verifyFixtures` |
| `docs/02_technical_architecture.md#D02-S4.5` | Module boundaries and allowed dependencies |
| External: gdx-bullet, gdx-gltf, Jackson | Physics, mesh loading, report serialisation |

---

<!-- D14-S4 -->## 4. Data Contracts

<!-- D14-S4.1 -->### 4.1 Harness Inputs

**R1.** The harness accepts exactly one *verification target*, described by a `VerificationTarget`:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `assetDir` | path | yes | Directory containing the processed asset (`mesh.glb`, `shards.glb`, `fracture_manifest.json`) |
| `partDefinitionPath` | path | no | `part.json` (D08-S4.2). If absent, only asset-level and physics-level checks run; degradation and vehicle checks are skipped with status `skipped`. |
| `goldenManifestPath` | path | no | Reference manifest for regression comparison (D14-S7.2) |
| `assemblyPath` | path | no | Assembly manifest (D08-S4.4) for vehicle integration checks |
| `checkCategories` | set | no | Subset of categories to run; default all applicable |
| `seed` | long | no | Seed for the harness's own RNG; default `1337` |
| `tolerancesOverride` | path | no | JSON overriding named tolerances (D14-S6.4); every override is echoed into the report |

<!-- D14-S4.2 -->### 4.2 CLI Contract

**R2.** The harness is invoked as:

```
syndicate-verify [--visual | --headless]
                 --asset <dir>
                 [--part <part.json>]
                 [--assembly <assembly.json>]
                 [--golden <golden_manifest.json>]
                 [--categories asset,physics,progression,vehicle]
                 [--report <out.json>]
                 [--seed <long>]
                 [--tolerances <overrides.json>]
                 [--fail-fast]
                 [--verbose]
```

| Flag | Default | Notes |
|---|---|---|
| `--headless` | on when no display is available | Mutually exclusive with `--visual`. |
| `--visual` | — | Opens a window; still writes a report on exit. |
| `--report` | `build/verify/<assetName>.report.json` | Parent directories created as needed. |
| `--fail-fast` | off | Stop at the first `fail`. Off by default so a full report is produced in one run. |
| `--categories` | all | Categories in D14-S4.3. |

**R3.** Exit codes (D14-S5.9 defines the mapping logic):

| Code | Name | Meaning |
|---|---|---|
| 0 | `OK` | All applicable checks passed (warnings allowed) |
| 10 | `ASSET_CHECK_FAILED` | One or more `ASSET-*` checks failed |
| 11 | `PHYSICS_CHECK_FAILED` | One or more `PHYS-*` checks failed |
| 12 | `PROGRESSION_CHECK_FAILED` | One or more `PROG-*` checks failed |
| 13 | `VEHICLE_CHECK_FAILED` | One or more `VEH-*` checks failed |
| 14 | `GOLDEN_MISMATCH` | One or more `GOLD-*` checks failed |
| 20 | `INPUT_NOT_FOUND` | Asset dir, manifest, or mesh missing |
| 21 | `MANIFEST_INVALID` | Manifest fails schema validation |
| 22 | `MESH_LOAD_FAILED` | glTF could not be parsed or contains no usable mesh |
| 30 | `HARNESS_ERROR` | Internal error, unhandled exception, native init failure |
| 40 | `TIMEOUT` | A check exceeded its time budget |

**R4.** When multiple categories fail, the exit code is the **lowest-numbered failing category code** (asset < physics < progression < vehicle < golden), because a failure early in the chain usually causes the later ones. The report always lists all failures regardless.

<!-- D14-S4.3 -->### 4.3 Check Definition

**R5.** A check is a declarative record. Checks are registered, not hard-coded into a runner.

```pseudo
record Check:
    string      id            # e.g. "ASSET-001". Stable forever. Never reused.
    string      name          # one-line human description
    Category    category      # ASSET | PHYSICS | PROGRESSION | VEHICLE | GOLDEN
    Severity    severity      # BLOCKING (fail) | ADVISORY (warning)
    string[]    requires      # ids of checks that must pass first; skipped otherwise
    ToleranceRef tolerance     # named tolerance from D14-S6.4, or NONE
    Predicate   assertion     # (Context) -> Measurement
    Precondition applicable    # (Target) -> boolean; false => status "skipped"
    Duration    budget        # max wall time; exceeded => TIMEOUT

record Measurement:
    boolean passed
    string  expected          # human-readable expected condition
    string  actual            # human-readable measured value
    number? expectedValue     # machine-readable, when scalar
    number? actualValue
    number? tolerance
    number? delta
    string  details           # e.g. "12/12 shards pass"
```

**R6.** Check IDs are globally unique and permanent. Retiring a check leaves its ID retired (listed in `RETIRED_CHECKS`), never reused — the same discipline as blueprint section IDs (D00-R7), for the same reason: reports are archived and compared over time.

<!-- D14-S4.4 -->### 4.4 Verification Report Schema

**R7.** The report is JSON, validated against `schemas/verification_report.schema.json` (D08-S6.1). Canonical example:

```json
{
  "schemaVersion": "1.0.0",
  "asset": "assets/parts/armor_plate_medium_01",
  "timestamp": "2026-08-07T14:22:31Z",
  "mode": "headless",
  "harnessVersion": "0.1.0",
  "toolVersion": "0.1.0",
  "seed": 1337,
  "target": {
    "assetDir": "assets/parts/armor_plate_medium_01",
    "manifest": "assets/parts/armor_plate_medium_01/fracture_manifest.json",
    "partDefinition": "assets/parts/armor_plate_medium_01/part.json",
    "assembly": null,
    "golden": "fixtures/golden/armor_plate_medium_01.manifest.json"
  },
  "checks": [
    {
      "id": "ASSET-001",
      "name": "All shards have positive mass",
      "category": "ASSET",
      "status": "pass",
      "expected": "mass > 0.01 kg for all shards",
      "actual": "min mass: 0.42kg, max mass: 1.87kg",
      "expectedValue": 0.01,
      "actualValue": 0.42,
      "tolerance": null,
      "delta": null,
      "details": "12/12 shards pass",
      "durationMs": 3
    },
    {
      "id": "ASSET-006",
      "name": "Total shard mass conserves part mass",
      "category": "ASSET",
      "status": "pass",
      "expected": "|sum(shardMass) - partMass| <= 2% of partMass",
      "actual": "sum: 15.38 kg vs part: 15.40 kg",
      "expectedValue": 15.40,
      "actualValue": 15.38,
      "tolerance": 0.308,
      "delta": 0.02,
      "details": "delta 0.13% of part mass",
      "durationMs": 1
    },
    {
      "id": "PROG-004",
      "name": "Shards inherit parent velocity at fracture",
      "category": "PROGRESSION",
      "status": "fail",
      "expected": "|sum(m_i * v_i) - M * V| <= 5% of |M * V|",
      "actual": "momentum delta 11.2%",
      "expectedValue": 0.0,
      "actualValue": 0.112,
      "tolerance": 0.05,
      "delta": 0.062,
      "details": "shard 7 velocity magnitude 41.3 m/s exceeds plausible bound",
      "durationMs": 47
    }
  ],
  "summary": {
    "total": 24,
    "passed": 22,
    "failed": 1,
    "warnings": 0,
    "skipped": 1,
    "durationMs": 812
  },
  "physics_data": {
    "original_mass_kg": 15.4,
    "total_shard_mass_kg": 15.38,
    "mass_conservation_delta_kg": 0.02,
    "shard_count": 12,
    "com": { "x": 0.01, "y": 0.48, "z": -0.03 },
    "com_manifest": { "x": 0.01, "y": 0.48, "z": -0.02 },
    "com_offset_m": 0.01,
    "inertia_tensor": [
      [2.145, 0.0, 0.0],
      [0.0, 3.017, 0.0],
      [0.0, 0.0, 2.882]
    ],
    "aabb_min_m": { "x": -1.0, "y": 0.0, "z": -0.05 },
    "aabb_max_m": { "x": 1.0, "y": 0.5, "z": 0.05 },
    "resting_position_m": { "x": 0.0, "y": 0.0501, "z": 0.0 },
    "resting_penetration_m": 0.0009,
    "post_rest_jitter_mps": 0.004,
    "hull_vertex_counts": [42, 38, 51, 33, 47, 29, 55, 40, 36, 44, 31, 49],
    "morph_targets": ["dmg_25", "dmg_50", "dmg_75", "dmg_100"]
  },
  "tolerances_applied": {
    "MASS_DELTA_FRAC": 0.02,
    "COM_OFFSET_M": 0.02,
    "INERTIA_REL": 0.05,
    "VELOCITY_REL": 0.05,
    "RESTING_POSITION_M": 0.005,
    "RESTING_JITTER_MPS": 0.01,
    "MORPH_WEIGHT_ABS": 0.001
  },
  "exit_code": 12
}
```

**R8.** Field rules:

| Field | Rule |
|---|---|
| `schemaVersion` | Semver of the report schema. Consumers must reject unknown majors. |
| `status` | Exactly one of `pass`, `fail`, `warning`, `skipped`. |
| `expectedValue` / `actualValue` / `tolerance` / `delta` | Numbers or `null`. Present whenever the check is scalar-valued, so an agent can reason about *how far off* a failure is. |
| `details` | Always populated. For per-item checks, the `n/m` form is required. |
| `physics_data` | Always present, even on failure, populated as far as the run got. This is what makes a failing report diagnosable without re-running. |
| `tolerances_applied` | Echoes the effective tolerances including any overrides — a report is not interpretable without them. |
| `exit_code` | Mirrors the process exit code so an archived report is self-contained. |

<!-- D14-S4.5 -->### 4.5 Check Catalogue

**R9.** The following checks exist. IDs are permanent.

<!-- D14-S4.5.1 -->#### Asset-level (`ASSET-*`) — manifest ↔ mesh agreement

| ID | Name | Assertion | Tolerance | Sev |
|---|---|---|---|---|
| ASSET-001 | All shards have positive mass | every `shard.massKg > MIN_BODY_MASS_KG` | none | BLOCKING |
| ASSET-002 | Declared shards exist in mesh | every manifest shard has a matching mesh node by name | none | BLOCKING |
| ASSET-003 | No extra shard meshes | mesh shard nodes ⊆ manifest shards | none | BLOCKING |
| ASSET-004 | Shard mass matches manifest | recomputed `volume × density` per shard equals manifest value | `MASS_DELTA_FRAC` | BLOCKING |
| ASSET-005 | Shard meshes are non-degenerate | each shard has ≥ 4 vertices, ≥ 4 faces, volume > `MIN_SHARD_VOLUME_M3`, no zero-area faces, no NaN/Inf coordinates | none | BLOCKING |
| ASSET-006 | Total shard mass conserves part mass | `\|Σ shardMass − partMass\| ≤ MASS_DELTA_FRAC × partMass` (G7) | `MASS_DELTA_FRAC` | BLOCKING |
| ASSET-007 | Shape keys present | all morph targets named in the manifest exist on the intact mesh | none | BLOCKING |
| ASSET-008 | Shape keys non-degenerate | no NaN/Inf in any morph delta; max delta magnitude ≥ `MORPH_MIN_DELTA_M`; no morph produces zero-area faces at weight 1.0 | `MORPH_MIN_DELTA_M` | BLOCKING |
| ASSET-009 | Shape key ordering is monotonic | mean displacement magnitude increases across `dmg_25 → dmg_50 → dmg_75 → dmg_100` | none | BLOCKING |
| ASSET-010 | Convex hull generates per shard | `btConvexHullShape` builds for every shard and yields non-zero volume | none | BLOCKING |
| ASSET-011 | Hull within polygon budget | each simplified hull ≤ `MAX_HULL_VERTICES` (64) | none | BLOCKING |
| ASSET-012 | Hull encloses shard | every shard vertex lies within its hull + `HULL_ENCLOSE_M` | `HULL_ENCLOSE_M` | BLOCKING |
| ASSET-013 | Manifest count matches mesh count | `manifest.shardCount == meshShardNodes.size` | none | BLOCKING |
| ASSET-014 | Manifest schema valid | validates against `schemas/fracture_manifest.schema.json` | none | BLOCKING |
| ASSET-015 | Units and axes plausible | AABB within `[MIN_PART_EXTENT_M, MAX_PART_EXTENT_M]`; up axis is +Y (D00-R14) | none | BLOCKING |
| ASSET-016 | Material density resolves | every shard's `materialId` exists in the material table (D09-S6.3) | none | BLOCKING |
| ASSET-017 | Shards do not gross-overlap | pairwise AABB overlap volume ≤ `SHARD_OVERLAP_FRAC` of the smaller AABB | `SHARD_OVERLAP_FRAC` | ADVISORY |
| ASSET-018 | Shard union covers the part | Σ shard volume ≥ `VOLUME_COVERAGE_FRAC` × part volume | `VOLUME_COVERAGE_FRAC` | ADVISORY |

<!-- D14-S4.5.2 -->#### Physics-level (`PHYS-*`) — behaviour in a real Bullet world

| ID | Name | Assertion | Tolerance | Sev |
|---|---|---|---|---|
| PHYS-001 | Body constructs | a `btRigidBody` is created from the intact mesh's collision shape without error | none | BLOCKING |
| PHYS-002 | Mass matches manifest | `body.getMass()` equals `manifest.partMassKg` | `MASS_DELTA_FRAC` | BLOCKING |
| PHYS-003 | COM matches manifest | computed COM equals `manifest.comLocal` | `COM_OFFSET_M` | BLOCKING |
| PHYS-004 | Inertia tensor plausible | diagonal entries positive; satisfies the triangle inequality `Ixx+Iyy ≥ Izz` (and permutations); within `INERTIA_REL` of the manifest's | `INERTIA_REL` | BLOCKING |
| PHYS-005 | Gravity response | free body after 1.0 s has `v_y ≈ −9.81` | `VELOCITY_REL` | BLOCKING |
| PHYS-006 | Impulse response | impulse `J` along +X gives `Δv_x ≈ J/m` | `VELOCITY_REL` | BLOCKING |
| PHYS-007 | Torque response | torque `τ` about +Y for `t` gives `ω_y ≈ τ·t/I_yy` | `ANGULAR_VELOCITY_REL` | BLOCKING |
| PHYS-008 | Drop and rest | dropped from 2 m, the body comes to rest within `REST_SETTLE_S`; resting height matches the support height | `RESTING_POSITION_M` | BLOCKING |
| PHYS-009 | No sinking | steady-state penetration into the ground plane ≤ `MAX_PENETRATION_M` | `MAX_PENETRATION_M` | BLOCKING |
| PHYS-010 | No resting jitter | after settling, linear speed ≤ `RESTING_JITTER_MPS` for `REST_HOLD_S` | `RESTING_JITTER_MPS` | BLOCKING |
| PHYS-011 | No NaN in state | no NaN/Inf in position, rotation, velocity at any step (G-D00-R13) | none | BLOCKING |
| PHYS-012 | Deterministic replay | two identical seeded runs produce identical final transforms | `DETERMINISM_POS_M` | BLOCKING |
| PHYS-013 | Sleep behaviour | body deactivates within `SLEEP_EXPECT_S` after resting | none | ADVISORY |

<!-- D14-S4.5.3 -->#### Destruction progression (`PROG-*`) — the critical path

| ID | Name | Assertion | Tolerance | Sev |
|---|---|---|---|---|
| PROG-001 | Intact baseline | at `healthFraction = 1.0`: exactly one rigid body; `DamageState == INTACT`; all morph weights == 0.0 | `MORPH_WEIGHT_ABS` | BLOCKING |
| PROG-002 | Morph interpolation tracks health | at 75/50/25% health, morph weights equal the D07-S5.5 mapping | `MORPH_WEIGHT_ABS` | BLOCKING |
| PROG-003 | Stats degrade per curve | `effectiveStats` at 75/50/25% equal the D05-S5.4 curve for the part's category | `STAT_REL` | BLOCKING |
| PROG-004 | Shards inherit momentum | at fracture, `\|Σ(m_i·v_i) − M·V\| ≤ VELOCITY_REL × \|M·V\|` | `VELOCITY_REL` | BLOCKING |
| PROG-005 | Body count after fracture | the single body is removed and exactly `shardCount` bodies exist | none | BLOCKING |
| PROG-006 | Shard mass after fracture | each spawned body's mass equals its manifest mass | `MASS_DELTA_FRAC` | BLOCKING |
| PROG-007 | Shard mass conservation post-fracture | Σ live shard masses ≈ original part mass (G7) | `MASS_DELTA_FRAC` | BLOCKING |
| PROG-008 | Shard collision shapes valid | each shard body has a non-null convex shape with positive volume | none | BLOCKING |
| PROG-009 | Shard scatter plausible | every shard speed ≤ `MAX_SCATTER_SPEED_MPS`; none NaN; none zero for all shards | `MAX_SCATTER_SPEED_MPS` | BLOCKING |
| PROG-010 | Shards move independently | after 0.5 s, at least `INDEPENDENT_FRAC` of shard pairs have differing velocities beyond `VELOCITY_EPS` | `VELOCITY_EPS` | BLOCKING |
| PROG-011 | Damage state monotonic | recorded state sequence is non-decreasing in severity (G8) | none | BLOCKING |
| PROG-012 | Fracture is one-way | after fracture, re-raising health does not restore the single body (G9) | none | BLOCKING |
| PROG-013 | Constraint breaks at threshold | applying a ramping force, the joint breaks within `[breakImpulse × (1−BREAK_REL), breakImpulse × (1+BREAK_REL)]` | `BREAK_REL` | BLOCKING |
| PROG-014 | Constraint holds below threshold | at 80% of the break impulse, sustained 2 s, the joint does not break | none | BLOCKING |
| PROG-015 | No orphaned native objects | after a full progression, tracker outstanding == 0 (G19) | none | BLOCKING |
| PROG-016 | Debris lifetime honoured | shards despawn within `DEBRIS_LIFETIME_S + 1 tick` | none | ADVISORY |

<!-- D14-S4.5.4 -->#### Vehicle integration (`VEH-*`)

| ID | Name | Assertion | Tolerance | Sev |
|---|---|---|---|---|
| VEH-001 | Part mass enters vehicle mass | `vehicleMass_with − vehicleMass_without == partMass` | `MASS_DELTA_FRAC` | BLOCKING |
| VEH-002 | Part shifts vehicle COM | measured COM equals the analytic weighted COM | `COM_OFFSET_M` | BLOCKING |
| VEH-003 | Heavier vehicle accelerates slower | with the part attached, time to 10 m/s is longer by the predicted ratio | `ACCEL_REL` | BLOCKING |
| VEH-004 | Asymmetric mass biases heading | with an off-centre part, straight-throttle heading drift is non-zero and in the predicted direction | `HEADING_DRIFT_RAD` | ADVISORY |
| VEH-005 | Destruction updates mass live | destroying the part mid-drive reduces vehicle mass within one tick | `MASS_DELTA_FRAC` | BLOCKING |
| VEH-006 | Destruction updates COM live | COM returns toward the baseline within one tick | `COM_OFFSET_M` | BLOCKING |
| VEH-007 | Handling changes after loss | post-destruction acceleration matches the lighter-vehicle prediction | `ACCEL_REL` | BLOCKING |
| VEH-008 | Detached parts become debris | detached bodies are in the world, are not children of the vehicle, and have the debris collision layer (D06-S4.4) | none | BLOCKING |
| VEH-009 | Vehicle remains stable | no NaN, no tumbling from a mass discontinuity: `\|Δv\|` at the detach tick ≤ `DETACH_VELOCITY_STEP_MPS` | `DETACH_VELOCITY_STEP_MPS` | BLOCKING |
| VEH-010 | Slot graph consistent | after detach, `SlotGraph` contains no reference to the removed part; `structuralVersion` incremented | none | BLOCKING |

<!-- D14-S4.5.5 -->#### Golden regression (`GOLD-*`)

| ID | Name | Assertion | Tolerance | Sev |
|---|---|---|---|---|
| GOLD-001 | Shard count matches golden | equal | none | BLOCKING |
| GOLD-002 | Per-shard mass matches golden | matched by shard id | `MASS_DELTA_FRAC` | BLOCKING |
| GOLD-003 | Total mass matches golden | equal within tolerance | `MASS_DELTA_FRAC` | BLOCKING |
| GOLD-004 | COM matches golden | equal within tolerance | `COM_OFFSET_M` | BLOCKING |
| GOLD-005 | Morph target set matches golden | same names, same order | none | BLOCKING |
| GOLD-006 | Shard centroids match golden | per-shard centroid distance ≤ tolerance | `CENTROID_M` | BLOCKING |
| GOLD-007 | Topology hash matches golden | manifest `topologyHash` equal (determinism, G11) | none | ADVISORY |
| GOLD-008 | Tool version recorded | golden and current `toolVersion` compared; difference downgrades GOLD-007 to informational | none | ADVISORY |

---

<!-- D14-S5 -->## 5. Logic & Algorithms

<!-- D14-S5.1 -->### 5.1 Harness Structure

```pseudo
package dev.syndicate.verify

  VerifyMain            # CLI parse, mode select, exit code
  CheckRegistry         # id -> Check; registration and lookup (D14-S5.10)
  VerificationRunner    # executes checks, builds the report
  VerificationContext   # everything a check may read (below)
  TestWorld             # a minimal Bullet world sharing game-core's PhysicsWorld config
  AssetLoader           # glTF -> mesh data + morph targets + shard nodes
  ManifestLoader        # JSON -> FractureManifest, schema-validated
  ReportWriter          # Measurement[] -> report JSON
  visual/               # VisualScene, DebugOverlayRenderer, ConsoleCommands, OrbitCamera

record VerificationContext:
    FractureManifest manifest
    PartDefinition?  partDef
    MeshData         intactMesh          # vertices, indices, morph targets
    MeshData[]       shardMeshes
    TestWorld        world
    Tolerances       tolerances
    RandomSource     rng                 # seeded
    Recorder         recorder            # timeline of states for progression checks
```

**R10.** The harness constructs its Bullet world through the **same** `PhysicsWorld.create()` used by the game (D06-S5.1), with the same gravity, solver, and layer configuration. A harness that verified against a bespoke physics setup would prove nothing about the game.

<!-- D14-S5.2 -->### 5.2 Top-Level Run Sequence

```pseudo
function run(target, options):
    report = new Report(target, options, harnessVersion, seed = options.seed)
    try:
        manifest = ManifestLoader.load(target.assetDir + "/fracture_manifest.json")
                                                    # exit 20 if missing, 21 if invalid
        meshes   = AssetLoader.load(target.assetDir) # exit 22 on parse failure
        partDef  = target.partDefinitionPath ? PartDefinitionLoader.load(...) : null
        Bullet.init(useRefCounting = false)          # once per process (D02-R3)
        world    = TestWorld.create(gravity = WORLD_GRAVITY, groundPlane = true)
        ctx      = new VerificationContext(manifest, partDef, meshes, world,
                                           Tolerances.load(options.tolerancesOverride),
                                           new RandomSource(options.seed))

        for check in CheckRegistry.ordered(options.categories):     # ASSET, PHYS, PROG, VEH, GOLD
            if not check.applicable(ctx):
                report.add(skipped(check, reason)); continue
            if any(dep is not passed for dep in check.requires):
                report.add(skipped(check, "prerequisite failed: " + firstFailedDep)); continue
            measurement = runWithBudget(check, ctx)                  # TIMEOUT -> exit 40
            report.add(toResult(check, measurement))
            if options.failFast and result.status == "fail": break

        report.physicsData = collectPhysicsData(ctx)                 # always, even on failure
    catch HarnessException e:
        report.error = e; report.exitCode = 30
    finally:
        world?.dispose()                                             # D02-S5.7 order
        assert NativeResourceTracker.outstanding() == 0
        ReportWriter.write(report, options.reportPath)
    return computeExitCode(report)                                   # D14-S5.9
```

**R11.** Checks run in category order, and within a category in ascending ID order. Order is deterministic so two runs produce comparable reports.

<!-- D14-S5.3 -->### 5.3 Asset Verification Sequence

```pseudo
function verifyAsset(ctx):
    m = ctx.manifest

    # --- Structural agreement -------------------------------------------------
    meshShardNames = set(node.name for node in ctx.shardMeshes)
    manifestNames  = set(s.name for s in m.shards)
    ASSET-013: assert m.shardCount == meshShardNames.size
    ASSET-002: assert manifestNames ⊆ meshShardNames    # each declared shard exists
    ASSET-003: assert meshShardNames ⊆ manifestNames    # and nothing extra

    # --- Per-shard geometry and mass ------------------------------------------
    totalMass = 0; minMass = +INF; maxMass = -INF; failures = []
    for s in m.shards:
        mesh = ctx.shardMeshes[s.name]
        ASSET-005: assert mesh.vertexCount >= 4 and mesh.faceCount >= 4
                   assert none(isNaN(v) or isInf(v) for v in mesh.vertices)
                   assert none(triangleArea(f) < MIN_FACE_AREA_M2 for f in mesh.faces)
                   assert mesh.isClosed()                    # watertight => volume is meaningful
        volume  = signedVolume(mesh)                          # divergence theorem, D14-S5.4
        ASSET-005: assert volume > MIN_SHARD_VOLUME_M3
        density = MaterialTable.densityOf(s.materialId)       # D09-S6.3
        ASSET-016: assert density != null
        computed = volume * density
        ASSET-004: assert relDelta(computed, s.massKg) <= MASS_DELTA_FRAC
        ASSET-001: assert s.massKg > MIN_BODY_MASS_KG
        totalMass += s.massKg; minMass = min(...); maxMass = max(...)

    ASSET-006: assert abs(totalMass - m.partMassKg) <= MASS_DELTA_FRAC * m.partMassKg

    # --- Morph targets ---------------------------------------------------------
    ASSET-007: for name in m.morphTargets: assert ctx.intactMesh.hasMorph(name)
    prevMeanDisp = 0
    for name in m.morphTargets in declared order:                 # dmg_25..dmg_100
        deltas = ctx.intactMesh.morphDeltas(name)
        ASSET-008: assert none(isNaN(d) or isInf(d) for d in deltas)
                   assert max(|d| for d in deltas) >= MORPH_MIN_DELTA_M
                   assert noZeroAreaFacesAtWeight(ctx.intactMesh, name, weight = 1.0)
        meanDisp = mean(|d| for d in deltas)
        ASSET-009: assert meanDisp > prevMeanDisp; prevMeanDisp = meanDisp

    # --- Collision hulls -------------------------------------------------------
    for s in m.shards:
        hull = ConvexHullBuilder.build(ctx.shardMeshes[s.name])   # same code as D06-S5.2
        ASSET-010: assert hull != null and hullVolume(hull) > 0
        ASSET-011: assert hull.vertexCount <= MAX_HULL_VERTICES
        ASSET-012: for v in ctx.shardMeshes[s.name].vertices:
                       assert signedDistanceToHull(hull, v) <= HULL_ENCLOSE_M

    # --- Sanity / advisory -----------------------------------------------------
    ASSET-015: aabb = ctx.intactMesh.aabb()
               assert MIN_PART_EXTENT_M <= aabb.maxExtent <= MAX_PART_EXTENT_M
    ASSET-017: for (a, b) in pairs(m.shards):
                   warnIf(aabbOverlapVolume(a,b) > SHARD_OVERLAP_FRAC * min(vol(a), vol(b)))
    ASSET-018: warnIf(sum(volumes) < VOLUME_COVERAGE_FRAC * partVolume)
```

<!-- D14-S5.4 -->### 5.4 Volume, Mass and Inertia Computation

```pseudo
# Closed triangle mesh volume by the divergence theorem — the same routine the
# Blender tool uses (D09-S6.2), reimplemented here on purpose so the two agree
# only if both are right.
function signedVolume(mesh):
    v6 = 0
    for (a, b, c) in mesh.triangles:
        v6 += dot(a, cross(b, c))
    return abs(v6) / 6.0

function centroid(mesh):
    num = ZERO_VEC; den = 0
    for (a, b, c) in mesh.triangles:
        vol = dot(a, cross(b, c)) / 6.0
        num += vol * (a + b + c) / 4.0
        den += vol
    return num / den

# Inertia tensor of a uniform-density closed mesh about its centroid.
function inertiaTensor(mesh, density):
    I = ZERO_MATRIX3
    c = centroid(mesh)
    for (a, b, cc) in mesh.triangles:
        # Tetrahedron (origin, a, b, cc) contribution; standard covariance formulation.
        detJ = dot(a, cross(b, cc))
        I   += tetrahedronCovariance(a - c, b - c, cc - c) * detJ
    I *= density
    return toInertiaFromCovariance(I)

function validateInertia(I):
    assert I.xx > 0 and I.yy > 0 and I.zz > 0
    assert I.xx + I.yy >= I.zz - EPS      # triangle inequalities: a physical body must
    assert I.yy + I.zz >= I.xx - EPS      # satisfy these; violation means bad geometry
    assert I.zz + I.xx >= I.yy - EPS      # or a bad density/volume computation
```

<!-- D14-S5.5 -->### 5.5 Physics Verification Sequence

```pseudo
function verifyPhysics(ctx):
    shape = ConvexHullBuilder.build(ctx.intactMesh)                # or compound, per part.json
    PHYS-001: body = ctx.world.addRigidBody(mass = ctx.manifest.partMassKg,
                                            shape = shape,
                                            transform = identityAt(y = 2.0),
                                            layer = LAYER_DEBRIS)  # D06-S4.4
    PHYS-002: assertClose(body.getMass(), ctx.manifest.partMassKg, MASS_DELTA_FRAC, relative)
    PHYS-003: assertClose(computeCom(ctx.intactMesh), ctx.manifest.comLocal, COM_OFFSET_M, absolute)
    PHYS-004: I = inertiaTensor(ctx.intactMesh, density)
              validateInertia(I)
              assertClose(I.diagonal(), ctx.manifest.inertiaDiagonal, INERTIA_REL, relative)

    # --- Force response, each in a fresh isolated sub-world --------------------
    PHYS-005:  # gravity
        w = freshWorld(); b = w.add(body.clone(), gravityEnabled = true, damping = 0)
        stepFor(w, 1.0 s)                                          # 60 ticks at TICK_DT
        assertClose(b.linearVelocity.y, -9.81 * 1.0, VELOCITY_REL, relative)

    PHYS-006:  # impulse
        w = freshWorld(gravity = ZERO); b = w.add(body.clone(), damping = 0)
        J = 100.0                                                   # N·s along +X
        b.applyCentralImpulse(vec(J, 0, 0)); step(w, 1 tick)
        assertClose(b.linearVelocity.x, J / mass, VELOCITY_REL, relative)

    PHYS-007:  # torque
        w = freshWorld(gravity = ZERO); b = w.add(body.clone(), angularDamping = 0)
        tau = 50.0; t = 0.5 s
        for tick in ticksIn(t): b.applyTorque(vec(0, tau, 0)); step(w, 1 tick)
        assertClose(b.angularVelocity.y, tau * t / I.yy, ANGULAR_VELOCITY_REL, relative)

    # --- Drop, rest, and stability --------------------------------------------
    PHYS-008/009/010:
        w = freshWorld(groundPlane = true)
        b = w.add(body.clone(), at y = 2.0)
        settledTick = stepUntil(w, predicate = speed(b) < RESTING_JITTER_MPS,
                                   timeout = REST_SETTLE_S)
        assert settledTick != TIMEOUT                                 # PHYS-008
        supportHeight = lowestPointOffset(shape, b.rotation)
        assertClose(b.position.y, supportHeight, RESTING_POSITION_M, absolute)   # PHYS-008
        assert penetrationDepth(w, b) <= MAX_PENETRATION_M                        # PHYS-009
        for tick in ticksIn(REST_HOLD_S):                                          # PHYS-010
            step(w, 1 tick); assert speed(b) <= RESTING_JITTER_MPS

    PHYS-011: every step above asserts allFinite(position, rotation, velocity)
    PHYS-012: run the drop twice with the same seed; assertClose(finalTransformA,
                                                                 finalTransformB,
                                                                 DETERMINISM_POS_M)
    PHYS-013: warnIf(not b.isDeactivated() after SLEEP_EXPECT_S at rest)
```

<!-- D14-S5.6 -->### 5.6 Destruction Progression Verification

```pseudo
function verifyProgression(ctx):
    part = spawnPartUnderTest(ctx)          # a PART entity in a world using game-core systems
    rec  = ctx.recorder

    # ---- 1. Intact state ------------------------------------------------------
    PROG-001:
        assert ctx.world.bodyCountFor(part) == 1
        assert part.DamageState.state == INTACT
        assert all(w == 0.0 ± MORPH_WEIGHT_ABS for w in part.DamageVisual.morphWeights)

    # ---- 2. Damage progression -----------------------------------------------
    for f in [0.75, 0.50, 0.25]:
        setHealthFraction(part, f)                    # programmatic; no projectile needed
        runSystemsForOneTick(ctx.world)               # DamageSystem + VehicleStatsSystem +
                                                      # DamageVisualSystem (D04-S4.4)
        PROG-002:
            expected = morphWeightsForHealth(f)        # exactly the D07-S5.5 mapping
            assertClose(part.DamageVisual.morphWeights, expected, MORPH_WEIGHT_ABS, absolute)
        PROG-003:
            expectedStats = degradationCurve(part.category, f)   # D05-S5.4
            for statName in part.PartStats.baseStats.names():
                assertClose(part.PartStats.effectiveStats[statName],
                            expectedStats[statName], STAT_REL, relative)
        PROG-011:
            assert severityOf(part.DamageState.state) >= severityOf(rec.lastState)
            rec.record(part.DamageState.state)

    # ---- 3. Fracture trigger --------------------------------------------------
    givenVelocity = vec(8.0, 0.0, 3.0)
    setLinearVelocity(part, givenVelocity)
    M = part.massKg;  P_before = M * givenVelocity
    setHealthFraction(part, 0.0)
    runSystemsForOneTick(ctx.world)                    # FractureSystem fires (D07-S5.6)

    PROG-005:
        assert ctx.world.bodyExists(part.previousBodyHandle) == false
        assert ctx.world.debrisBodiesFrom(part).count == ctx.manifest.shardCount
    PROG-004:
        P_after = sum(b.massKg * b.linearVelocity for b in shardBodies)
        assertClose(magnitude(P_after - P_before), 0.0,
                    VELOCITY_REL * magnitude(P_before), absolute)
    PROG-006/007:
        for b in shardBodies:
            assertClose(b.massKg, ctx.manifest.shardById(b.shardId).massKg,
                        MASS_DELTA_FRAC, relative)
        assertClose(sum(b.massKg), ctx.manifest.partMassKg, MASS_DELTA_FRAC, relative)
    PROG-008:
        for b in shardBodies: assert b.shape != null and hullVolume(b.shape) > 0
    PROG-009:
        for b in shardBodies:
            assert allFinite(b.linearVelocity)
            assert magnitude(b.linearVelocity) <= MAX_SCATTER_SPEED_MPS
        assert any(magnitude(b.linearVelocity) > VELOCITY_EPS for b in shardBodies)
    PROG-010:
        stepFor(ctx.world, 0.5 s)
        differing = count(pairs (a,b) where magnitude(a.v - b.v) > VELOCITY_EPS)
        assert differing >= INDEPENDENT_FRAC * totalPairs
    PROG-012:
        setHealthFraction(part, 1.0); runSystemsForOneTick(ctx.world)
        assert ctx.world.bodyCountFor(part) == 0        # no resurrection (G9)
        assert part.FractureData.hasFractured == true

    # ---- 4. Constraint breaking ----------------------------------------------
    if ctx.partDef.attachesViaBreakableJoint:
        fresh = spawnAttachedToTestChassis(ctx)
        threshold = fresh.SlotAttachment.breakImpulseN
        PROG-014:
            applySustainedForce(fresh, 0.8 * threshold / TICK_DT, duration = 2.0 s)
            assert fresh.SlotAttachment.constraintHandle != null      # still attached
        PROG-013:
            broke = rampForceUntilBreak(fresh, from = 0.5*threshold, to = 2.0*threshold,
                                        rampSeconds = 2.0)
            assert broke.occurred
            assertClose(broke.impulseAtBreak, threshold, BREAK_REL, relative)

    PROG-015: assert NativeResourceTracker.outstanding() == 0 after teardown
    PROG-016: stepFor(ctx.world, DEBRIS_LIFETIME_S + TICK_DT)
              warnIf(ctx.world.debrisBodiesFrom(part).count != 0)
```

<!-- D14-S5.7 -->### 5.7 Vehicle Integration Verification

```pseudo
function verifyVehicleIntegration(ctx):
    # Baseline: reference chassis with no part under test.
    base = spawnTestVehicle(ctx, assembly = REFERENCE_CHASSIS_ASSEMBLY)
    m0   = base.VehicleChassis.totalMassKg
    com0 = base.VehicleChassis.comLocal
    t0   = measureTimeToSpeed(base, targetMps = 10.0, maxSeconds = 20.0)

    # With the part attached at an off-centre slot.
    withPart = spawnTestVehicle(ctx, assembly = REFERENCE_CHASSIS_ASSEMBLY
                                                 .withPart(ctx.partDef, slot = "side_right_01"))
    m1   = withPart.VehicleChassis.totalMassKg
    com1 = withPart.VehicleChassis.comLocal
    t1   = measureTimeToSpeed(withPart, targetMps = 10.0, maxSeconds = 20.0)

    VEH-001: assertClose(m1 - m0, ctx.manifest.partMassKg, MASS_DELTA_FRAC, relative)
    VEH-002: expectedCom = (com0 * m0 + slotWorldPos("side_right_01") * partMass) / m1
             assertClose(com1, expectedCom, COM_OFFSET_M, absolute)
    VEH-003: expectedRatio = m1 / m0                  # constant engine force => t ∝ m
             assertClose(t1 / t0, expectedRatio, ACCEL_REL, relative)
    VEH-004: drift = headingDriftOverDistance(withPart, throttle = 1.0, distanceM = 50)
             warnIf(sign(drift) != sign(lateralComOffset(com1)))
             warnIf(abs(drift) < HEADING_DRIFT_RAD)   # a heavy side part should be felt

    # --- Live destruction while driving ---------------------------------------
    driveUntil(withPart, speedMps = 12.0)
    beforeV = withPart.velocity
    destroyPart(withPart, slotPath = "root/side_right_01")     # health -> 0
    runSystemsForOneTick(world)                                 # Fracture + Detach + MassProperty

    VEH-005: assertClose(withPart.VehicleChassis.totalMassKg, m0, MASS_DELTA_FRAC, relative)
    VEH-006: assertClose(withPart.VehicleChassis.comLocal, com0, COM_OFFSET_M, absolute)
    VEH-009: assert magnitude(withPart.velocity - beforeV) <= DETACH_VELOCITY_STEP_MPS
             assert allFinite(withPart.velocity, withPart.transform)
    VEH-008: for b in debrisFromPart:
                 assert b.layer == LAYER_DEBRIS
                 assert b.parentEntity == NULL_ENTITY
                 assert world.contains(b)
    VEH-010: assert withPart.SlotGraph.find("root/side_right_01") == null
             assert withPart.SlotGraph.structuralVersion > versionBeforeDetach

    t2 = measureTimeToSpeed(withPart, targetMps = 10.0, maxSeconds = 20.0)
    VEH-007: assertClose(t2, t0, ACCEL_REL, relative)   # handling returns to baseline
```

<!-- D14-S5.8 -->### 5.8 Golden Manifest Comparison

```pseudo
function compareToGolden(current, golden):
    GOLD-008: if current.toolVersion != golden.toolVersion:
                  note("tool version differs: " + golden.toolVersion + " -> " + current.toolVersion)
                  demote(GOLD-007, to = INFORMATIONAL)   # topology hash may legitimately change

    GOLD-001: assert current.shardCount == golden.shardCount
    GOLD-005: assert current.morphTargets == golden.morphTargets          # names and order

    # Shards are matched by stable id, never by array position: the tool guarantees
    # stable ids for a fixed seed (D09-S8), and position-matching would produce
    # noisy diffs the moment ordering changed for an unrelated reason.
    for g in golden.shards:
        c = current.shardById(g.id)
        assert c != null : "missing shard " + g.id
        GOLD-002: assertClose(c.massKg, g.massKg, MASS_DELTA_FRAC, relative)
        GOLD-006: assertClose(distance(c.centroid, g.centroid), 0.0, CENTROID_M, absolute)

    GOLD-003: assertClose(current.partMassKg, golden.partMassKg, MASS_DELTA_FRAC, relative)
    GOLD-004: assertClose(current.comLocal, golden.comLocal, COM_OFFSET_M, absolute)
    GOLD-007: assert current.topologyHash == golden.topologyHash          # exact (G11)

function updateGolden(fixtureName):
    # Deliberately a separate, explicit command: `syndicate-verify --update-golden`.
    # Golden files are NEVER auto-updated by a failing run. Updating one requires
    # a human/agent decision and a memory entry when the change is unexpected
    # (D13-S5.3 W2).
    run the Blender tool with the fixture's recorded seed
    copy the produced manifest to fixtures/golden/<fixtureName>.manifest.json
    print a unified diff of old vs new for review
```

<!-- D14-S5.9 -->### 5.9 Exit Code Mapping

```pseudo
function computeExitCode(report):
    if report.harnessError:                     return 30    # HARNESS_ERROR
    if report.timedOut:                         return 40    # TIMEOUT
    if report.inputMissing:                     return 20    # INPUT_NOT_FOUND
    if report.manifestInvalid:                  return 21    # MANIFEST_INVALID
    if report.meshLoadFailed:                   return 22    # MESH_LOAD_FAILED

    failed = report.checks.filter(status == "fail")
    if failed.isEmpty():                        return 0     # OK (warnings do not fail)

    # Lowest-numbered failing category wins (D14-R4): earliest failure in the chain
    # is the most actionable for the agent reading this report.
    for (category, code) in [(ASSET,10), (PHYSICS,11), (PROGRESSION,12),
                             (VEHICLE,13), (GOLDEN,14)]:
        if failed.anyIn(category):              return code
    return 30
```

<!-- D14-S5.10 -->### 5.10 Check Registration (Extensibility)

**R12.** New checks are added declaratively. No runner code changes.

```pseudo
# Registration — one call per check, in a category's registrar class.
CheckRegistry.register(
    id        = "ASSET-019",
    name      = "Shard aspect ratio is not degenerate",
    category  = ASSET,
    severity  = ADVISORY,
    requires  = ["ASSET-005"],                    # only meaningful if geometry is sane
    tolerance = ToleranceRef("ASPECT_RATIO_MAX"),
    applicable = (target) -> target.manifest.shardCount > 0,
    budget    = 2.seconds,
    assertion = (ctx) -> {
        worst = 0; worstId = null
        for s in ctx.manifest.shards:
            aabb  = ctx.shardMeshes[s.name].aabb()
            ratio = aabb.maxExtent / max(aabb.minExtent, EPS)
            if ratio > worst: worst = ratio; worstId = s.id
        return Measurement(
            passed        = worst <= ctx.tolerances.ASPECT_RATIO_MAX,
            expected      = "max aspect ratio <= " + ctx.tolerances.ASPECT_RATIO_MAX,
            actual        = "worst " + worst + " (shard " + worstId + ")",
            expectedValue = ctx.tolerances.ASPECT_RATIO_MAX,
            actualValue   = worst,
            tolerance     = ctx.tolerances.ASPECT_RATIO_MAX,
            delta         = worst - ctx.tolerances.ASPECT_RATIO_MAX,
            details       = countOf(shards exceeding) + "/" + shardCount + " exceed")
    })

# Rules for new checks (R13..R17):
R13. The id must be new; retired ids are never reused.
R14. Every check must produce a Measurement even when it passes — reports are used
     for trend analysis, so "pass with no numbers" is not acceptable for scalar checks.
R15. A check that needs a tolerance must reference a NAMED tolerance from D14-S6.4,
     never an inline literal. Adding a tolerance requires adding a row with a rationale.
R16. A check must be pure with respect to the asset: it may build worlds and step them,
     but it must not write to the asset directory.
R17. ADVISORY checks never affect the exit code. Promote to BLOCKING only when the
     failure mode has been observed to break the game, and record it (D13-S5.3 W1).
```

<!-- D14-S5.11 -->### 5.11 Visual Mode

```pseudo
function runVisualMode(target, options):
    app    = new Lwjgl3Application(new VisualScene(target, options), config)

class VisualScene:
    OrbitCamera   camera            # LMB orbit, MMB pan, wheel zoom, F = frame selection
    TestWorld     world             # ground plane grid, WORLD_GRAVITY
    DebugDrawer   bulletDebug       # btIDebugDraw bound to a shape renderer
    Console       console
    float         timeScale = 1.0
    boolean       paused = false

    function create():
        world = TestWorld.create(groundPlane = true)
        loadAsset(target)                          # spawns the intact part at origin + 1 m
        runAllChecks(async)                        # results shown in an overlay panel

    function render(deltaSeconds):
        if not paused:
            accumulate(deltaSeconds * timeScale)   # same accumulator as D06-S5.4;
            while accumulator >= TICK_DT:          # timeScale changes how many ticks
                world.tick(TICK_DT)                # per frame, never TICK_DT itself
                accumulator -= TICK_DT
        drawScene()
        drawOverlays()
        drawConsoleAndHud()

CONSOLE COMMANDS:
    load <manifestPath>            spawn a processed asset by manifest path
    health <0..1>                  set health fraction of the selected part
    damage <hp> [type]             apply damage of a damage type (D07-S4.3)
    destroy                        force health to 0 (triggers fracture)
    detach <slotPath>              force a detach event
    reset                          respawn the asset intact
    slowmo <multiplier>            set timeScale (0.01 .. 2.0)
    pause | step [n]               pause; advance n ticks
    overlay <name> on|off          toggle an overlay (list below)
    compare on|off                 side-by-side original vs processed (D14-S5.12)
    camera focus <shardId|part>    frame an object
    checks run [category]          re-run checks and refresh the panel
    report [path]                  write the JSON report now
    quit

OVERLAYS (each independently toggleable):
    collision    wireframe of every collision shape (btIDebugDraw DBG_DrawWireframe)
    com          centre-of-mass marker: yellow sphere on the vehicle/part COM,
                 plus a smaller marker per shard after fracture
    mass         floating text label per body: "<id>  <mass> kg"
    velocity     per-body arrow, length ∝ speed, colour ramp by magnitude
    joints       constraint pivots as crosses; line between anchor pairs;
                 colour = current impulse / break threshold (green -> red)
    aabb         broadphase AABBs
    contacts     contact points and normals from the current manifold set
    shardcolor   each shard rendered in a distinct hue (golden-angle palette),
                 with the intact mesh drawn as a translucent wireframe over it
    normals      face normals of the selected mesh
    labels       slot names and slot paths at their world positions

function drawOverlays():
    if overlay.collision: bulletDebug.setModes(DBG_DrawWireframe | DBG_DrawConstraints)
                          world.debugDrawWorld()
    if overlay.com:
        for body in world.bodies:
            drawSphere(body.worldCom(), radius = 0.06, color = YELLOW)
        if vehicle != null: drawSphere(vehicle.worldCom(), radius = 0.12, color = ORANGE)
    if overlay.mass:
        for body in world.bodies:
            drawBillboardText(body.worldCom() + UP*0.15,
                              format("%s  %.2f kg", body.id, body.massKg))
    if overlay.velocity:
        for body in world.bodies:
            v = body.linearVelocity
            if |v| > 0.05: drawArrow(body.worldCom(), body.worldCom() + v * 0.1,
                                     color = rampBlueToRed(|v| / MAX_SCATTER_SPEED_MPS))
    if overlay.joints:
        for c in world.constraints:
            drawCross(c.pivotA, 0.08); drawCross(c.pivotB, 0.08)
            drawLine(c.pivotA, c.pivotB, color = lerp(GREEN, RED,
                                                      c.appliedImpulse / c.breakImpulse))
    if overlay.shardcolor:
        for i, shard in enumerate(shardBodies):
            renderMesh(shard, color = hsv(hue = (i * 137.507) mod 360, s = 0.65, v = 0.95))
        renderWireframe(originalIntactMesh, color = WHITE_ALPHA_30)

# Fracture events are fast. Slow motion is the primary inspection tool:
#   slowmo 0.05  -> 20x slower; each tick still advances exactly TICK_DT so the
#                   simulation is identical to real time, only observed more finely.
```

<!-- D14-S5.12 -->### 5.12 Side-by-Side Comparison Mode

```pseudo
function renderComparison(scene):
    # Left:  the original unprocessed mesh loaded from the source export.
    # Right: the processed mesh (intact) or the shard set (after fracture).
    leftOrigin  = vec(-spacing/2, 0, 0)
    rightOrigin = vec(+spacing/2, 0, 0)
    renderMesh(scene.originalMesh, at = leftOrigin,  label = "ORIGINAL")
    renderProcessed(scene,         at = rightOrigin, label = "PROCESSED")

    # Numeric diff panel — the point of the mode is the numbers, not the pictures.
    panel.rows = [
        ("triangles",  original.triCount,   processed.triCount),
        ("volume m³",  volume(original),    sum(volume(s) for s in shards)),
        ("mass kg",    declaredPartMass,    sum(s.massKg for s in shards)),
        ("AABB",       original.aabb,       processed.aabb),
        ("centroid",   centroid(original),  centroid(processed)),
        ("shards",     "-",                 shardCount),
        ("morphs",     "-",                 join(morphTargets)) ]
    highlightRowsWhereRelativeDeltaExceeds(MASS_DELTA_FRAC)
```

<!-- D14-S5.13 -->### 5.13 Headless Runner

```pseudo
function runHeadless(target, options):
    # Identical checks to visual mode; the only difference is that no LWJGL3 window,
    # GL context, or render system is created (G17).
    app = new HeadlessApplication(new HeadlessVerifyListener(target, options))
    listener.create():
        report   = VerificationRunner.run(target, options)     # D14-S5.2
        ReportWriter.write(report, options.reportPath)
        if options.verbose: printHumanSummary(report)
        else:               printOneLine(report)               # "FAIL 1/24  PROG-004  <asset>"
        Gdx.app.exit()
    return computeExitCode(report)

# Batch mode for CI: verify every processed fixture, aggregate, never stop early.
function runBatch(assetDirs, options):
    results = []
    for dir in sorted(assetDirs):
        results.append(runHeadless(target(dir), options.withReport(dir)))
    writeAggregate("build/verify/summary.json", results)
    return max-severity exit code across results     # non-zero if any asset failed
```

---

<!-- D14-S6 -->## 6. Acceptance Criteria

<!-- D14-S6.1 -->### 6.1 Functional Acceptance

- [ ] **AC-D14-1.** `syndicate-verify --headless --asset <dir>` runs every applicable check and writes a schema-valid report.
- [ ] **AC-D14-2.** `syndicate-verify --visual --asset <dir>` opens a window, spawns the asset, and supports every console command in D14-S5.11.
- [ ] **AC-D14-3.** Both modes produce reports with identical check IDs and identical pass/fail results for the same asset and seed.
- [ ] **AC-D14-4.** Every check in D14-S4.5 is implemented and registered; `CheckRegistry.size()` equals the catalogue size.
- [ ] **AC-D14-5.** Exit codes follow D14-S5.9 exactly for each failure category.
- [ ] **AC-D14-6.** Every report validates against `schemas/verification_report.schema.json`.
- [ ] **AC-D14-7.** `physics_data` is populated even when checks fail.
- [ ] **AC-D14-8.** All five canonical fixtures (D14-S7.1) verify green against their golden manifests.
- [ ] **AC-D14-9.** Corrupting a golden manifest's shard mass by 10% makes `GOLD-002` fail with exit 14.
- [ ] **AC-D14-10.** The harness builds its physics world via `game-core`'s `PhysicsWorld.create()`, not a private copy (verified by an ArchUnit rule).
- [ ] **AC-D14-11.** `NativeResourceTracker.outstanding() == 0` after every run, pass or fail.
- [ ] **AC-D14-12.** A new check can be added by one `CheckRegistry.register(...)` call with no runner modification.
- [ ] **AC-D14-13.** Every tolerance used by a check appears in D14-S6.4 with a rationale and is echoed in `tolerances_applied`.
- [ ] **AC-D14-14.** Headless mode runs with no display and no GL driver present.
- [ ] **AC-D14-15.** A full fixture batch completes within the CI budget (D12-S5.6): ≤ 120 s for all five fixtures.
- [ ] **AC-D14-16.** `--update-golden` never runs implicitly; a failing `GOLD-*` check never rewrites a golden file.

<!-- D14-S6.2 -->### 6.2 Coverage Acceptance

- [ ] **AC-D14-17.** Asset-level checks cover: shard existence, shard mass vs manifest, morph presence and non-degeneracy, hull generation, mass conservation, manifest/mesh count agreement.
- [ ] **AC-D14-18.** Physics-level checks cover: mass, COM, inertia, gravity, impulse, torque, drop-and-rest, penetration, jitter, determinism.
- [ ] **AC-D14-19.** Progression checks cover: intact baseline, morph interpolation at 25/50/75%, stat degradation, fracture body swap, momentum inheritance, per-shard mass and shape, scatter plausibility, independence, one-way fracture, constraint break threshold both above and below.
- [ ] **AC-D14-20.** Vehicle checks cover: mass inclusion, COM shift, handling change, live destruction updating mass/COM/handling, debris independence, slot graph consistency.

<!-- D14-S6.3 -->### 6.3 Report Consumability Acceptance

- [ ] **AC-D14-21.** For every failing check, the report contains `expectedValue`, `actualValue`, `tolerance`, and `delta` when the check is scalar-valued.
- [ ] **AC-D14-22.** `details` identifies the specific failing item (shard id, morph name, slot path) — never just "failed".
- [ ] **AC-D14-23.** An agent can decide the next action from the report alone, without re-running the harness (validated by the decision table in D14-S8, E-column).

<!-- D14-S6.4 -->### 6.4 Tolerance Definitions

**R18.** Every tolerance is named, has a value, a comparison kind (absolute or relative), and a rationale. Checks reference tolerances by name only (R15).

| Name | Value | Kind | Applies to | Rationale |
|---|---|---|---|---|
| `MASS_DELTA_FRAC` | 0.02 | relative | ASSET-004/006, PHYS-002, PROG-006/007, VEH-001/005, GOLD-002/003 | Voronoi cutting leaves sub-millimetre gaps at shard boundaries and hull simplification alters volume slightly. 2% is well below what a player can perceive in handling, and well above float accumulation over ~64 shards. Matches G7. |
| `COM_OFFSET_M` | 0.02 m | absolute | PHYS-003, VEH-002/006, GOLD-004 | 2 cm is roughly the visual resolution of a COM marker at typical camera distance and is an order of magnitude below the wheelbase-scale offsets that change handling. Absolute (not relative) because a small COM error on a small part matters as much as on a large one. |
| `INERTIA_REL` | 0.05 | relative | PHYS-004 | Inertia is a second moment, so it amplifies the geometric error that `MASS_DELTA_FRAC` allows — roughly by the square of the extent ratio. 5% keeps rotational response indistinguishable while tolerating hull simplification. |
| `VELOCITY_REL` | 0.05 | relative | PHYS-005/006, PROG-004/009 | Bullet's solver introduces small impulse errors per step; over the 60-tick windows used here, 5% covers solver drift without hiding a real momentum bug (a broken inheritance shows as >50%). |
| `ANGULAR_VELOCITY_REL` | 0.08 | relative | PHYS-007 | Looser than linear because angular response also carries the `INERTIA_REL` error; 0.05 (velocity) compounded with 0.05 (inertia) justifies ~0.08. |
| `RESTING_POSITION_M` | 0.005 m | absolute | PHYS-008 | Bullet's default contact processing leaves a small allowed penetration; 5 mm is above that margin and below anything visible as a floating or sunken object. |
| `MAX_PENETRATION_M` | 0.01 m | absolute | PHYS-009 | Twice Bullet's typical margin. Sustained penetration beyond 1 cm indicates a bad hull or a mass/scale error, which is exactly what this check exists to catch. |
| `RESTING_JITTER_MPS` | 0.01 m/s | absolute | PHYS-010, PROG rest checks | Below the default linear sleeping threshold, so a body that satisfies this will actually sleep. Jitter above it means unstable contacts (usually a degenerate hull). |
| `DETERMINISM_POS_M` | 0.001 m | absolute | PHYS-012 | Same binary, same platform, same seed should be near-exact; 1 mm allows only benign non-associativity in threaded broadphase ordering. Any larger difference means a genuine determinism defect (D02-R4). |
| `MORPH_WEIGHT_ABS` | 0.001 | absolute | PROG-001/002 | Morph weight is computed arithmetic, not simulation; the only permitted error is float rounding. |
| `MORPH_MIN_DELTA_M` | 0.005 m | absolute | ASSET-008 | A damage morph that moves no vertex more than 5 mm is invisible at gameplay camera distance and is almost certainly an authoring or generation failure. |
| `STAT_REL` | 0.001 | relative | PROG-003 | The degradation curve is deterministic arithmetic (D05-S5.4); only rounding is permitted. |
| `HULL_ENCLOSE_M` | 0.002 m | absolute | ASSET-012 | Convex hull simplification may cut a hair inside a vertex; 2 mm keeps collision faithful while allowing decimation. |
| `MAX_HULL_VERTICES` | 64 | count | ASSET-011 | Bullet's `btConvexHullShape` performance degrades noticeably past ~64 vertices, and `btShapeHull` simplification targets this range. |
| `MIN_SHARD_VOLUME_M3` | 1e-6 | absolute | ASSET-005 | 1 cm³. Smaller shards are visually meaningless and produce ill-conditioned inertia tensors. |
| `MIN_FACE_AREA_M2` | 1e-8 | absolute | ASSET-005 | Below this, a face is numerically degenerate and can produce NaN normals in hull construction. |
| `SHARD_OVERLAP_FRAC` | 0.10 | relative | ASSET-017 (advisory) | Some AABB overlap is unavoidable for interlocking shards; >10% of the smaller shard's AABB suggests the fracture produced duplicated geometry. |
| `VOLUME_COVERAGE_FRAC` | 0.95 | relative | ASSET-018 (advisory) | Voronoi cells should tile the part; losing >5% of volume means cells were dropped or the boolean failed. |
| `MAX_SCATTER_SPEED_MPS` | 50 m/s | absolute | PROG-009 | Above ~50 m/s shards leave the play space within a frame and read as an explosion glitch; it is also the classic signature of a solver blow-up from overlapping spawn positions. |
| `VELOCITY_EPS` | 0.05 m/s | absolute | PROG-010 | Distinguishes genuinely independent motion from numerically identical motion. |
| `INDEPENDENT_FRAC` | 0.80 | relative | PROG-010 | Some shard pairs legitimately move together briefly; requiring 80% of pairs to differ catches "shards welded into one body" without false-failing on grouped motion. |
| `BREAK_REL` | 0.15 | relative | PROG-013 | Bullet applies the break check per solver iteration against accumulated impulse, so the observed break point quantises to the tick. 15% covers that quantisation at `TICK_DT`. |
| `ACCEL_REL` | 0.10 | relative | VEH-003/007 | Drivetrain response includes suspension settling and tyre slip; 10% isolates the mass effect from those second-order terms. |
| `HEADING_DRIFT_RAD` | 0.02 rad | absolute | VEH-004 (advisory) | ~1.1°over 50 m — the smallest drift a driver would notice as "pulling to one side". |
| `DETACH_VELOCITY_STEP_MPS` | 1.0 m/s | absolute | VEH-009 | A part leaving should not kick the vehicle. Anything above 1 m/s in one tick means momentum was created rather than conserved. |
| `CENTROID_M` | 0.01 m | absolute | GOLD-006 | Seeded Voronoi should reproduce centroids near-exactly; 1 cm tolerates float differences across Blender patch versions. |
| `ASPECT_RATIO_MAX` | 25.0 | ratio | ASSET-019 (advisory) | Slivers beyond ~25:1 give poor hulls and unstable contacts. |
| `MIN_PART_EXTENT_M` / `MAX_PART_EXTENT_M` | 0.05 / 12.0 m | absolute | ASSET-015 | Catches unit errors: a part smaller than 5 cm or larger than 12 m almost always means centimetres or millimetres were exported as metres. |
| `REST_SETTLE_S` | 4.0 s | absolute | PHYS-008 | Generous for a 2 m drop; exceeding it means bouncing or jitter, not slow settling. |
| `REST_HOLD_S` | 1.0 s | absolute | PHYS-010 | 60 ticks of quiet is enough to distinguish rest from slow creep. |
| `SLEEP_EXPECT_S` | 3.0 s | absolute | PHYS-013 (advisory) | Bullet's default deactivation time is 2 s; 3 s allows margin. |

**R19.** Tolerance overrides via `--tolerances` are permitted for experimentation but are echoed into `tolerances_applied`, and CI runs with **no** overrides. A report whose `tolerances_applied` differs from the defaults is not a valid CI pass.

---

<!-- D14-S7 -->## 7. Test Fixtures

<!-- D14-S7.1 -->### 7.1 Canonical Fixture Meshes

**R20.** These five fixtures live in `fixtures/meshes/` and are checked in. Each exercises a distinct fracture scenario. Each has a recorded seed and a golden manifest in `fixtures/golden/`.

| Fixture | Geometry | Material / density | Expected mass | Seed | Exercises |
|---|---|---|---|---|---|
| `test_cube_1m.glb` | Solid cube, 1×1×1 m, origin at centre of the base face | `steel`, 7850 kg/m³ | 7850.0 kg | 1001 | Baseline. Uniform cells, exact analytic volume/COM/inertia, so any error is the tool's, not the geometry's. COM must be `(0, 0.5, 0)`; inertia analytically `m(h²+d²)/12`. |
| `test_plate_2x1x0.1.glb` | Thin armour plate, 2 × 1 × 0.1 m | `steel_hardened`, 7850 kg/m³ | 1570.0 kg | 1002 | Slab-like fracture. Voronoi cells must not degenerate across the thin axis; hulls must not be slivers (`ASPECT_RATIO_MAX`); resting on the large face must be stable. |
| `test_cylinder_r0.5_h1.glb` | Cylinder, r = 0.5 m, h = 1 m, 48 radial segments | `aluminium`, 2700 kg/m³ | ≈ 2120.6 kg | 1003 | Curved-surface fracture. Tests that cell boundaries follow the curved boundary and that hulls approximate curvature within `HULL_ENCLOSE_M`; also tests rolling rest (no jitter while rolling to a stop). |
| `test_complex_hollow.glb` | Hollow box 1×1×1 m, 0.05 m walls, one internal cross rib | `steel`, 7850 kg/m³ | ≈ 2500 kg (declared exactly in the fixture's part.json) | 1004 | Internal shard generation. Interior cells must exist; volume coverage must account for the cavity, so `signedVolume` on a non-convex closed mesh is genuinely tested; verifies mass is **not** computed from the bounding box. |
| `test_vehicle_chassis.glb` | Simplified frame: 2.4 × 1.2 × 0.4 m box with four wheel wells and two hardpoint bosses; four named slots (`wheel_fl/fr/rl/rr`), two named slots (`hardpoint_left/right`) | `steel`, 7850 kg/m³ | ≈ 900 kg | 1005 | Multi-part assembly. Used as `REFERENCE_CHASSIS_ASSEMBLY` for all `VEH-*` checks; exercises slot metadata round-trip, compound shape construction, and vehicle mass/COM aggregation. |

**R21.** Fixture invariants: every fixture mesh is watertight, has exactly one material, is authored at 1 unit = 1 m, has its origin documented above, and contains no modifiers, no n-gons above 4 sides, and no loose geometry. `AssetValidator` (D08-S5.4) runs over the fixtures in CI so a broken fixture is reported as a fixture problem, not as a tool failure.

**R22.** `test_cube_1m` additionally carries **analytic** expected values (mass, COM, principal inertia) in `fixtures/golden/test_cube_1m.analytic.json`. It is the only fixture whose physics checks compare against closed-form truth rather than against the manifest, so it validates the manifest generator itself.

<!-- D14-S7.2 -->### 7.2 Golden Manifests

**R23.** `fixtures/golden/<fixture>.manifest.json` is a full fracture manifest (D09-S4.4) produced by a recorded `toolVersion` at the fixture's recorded seed. Golden files record, at minimum: `toolVersion`, `seed`, `shardCount`, per-shard `id`/`massKg`/`centroid`/`vertexCount`, `partMassKg`, `comLocal`, `inertiaDiagonal`, `morphTargets`, and `topologyHash`.

**R24.** Golden updates are explicit and reviewed (D14-S5.8 `updateGolden`). When a golden changes, the change must be explained: either an intended tool change (record a `discoveries/` or `decisions/` memory entry per D13-S5.3) or a regression to fix.

<!-- D14-S7.3 -->### 7.3 Fixture Pipeline

```pseudo
# CI (D12-S5.4) runs exactly this, in order:
1. :blender-tool:processFixtures
     for fixture in fixtures/meshes/*.glb:
         blender --background --factory-startup --python -m syndicate_fracture -- \
             --input fixtures/meshes/<f>.glb --out build/fixtures-out/<f>/ \
             --seed <recordedSeed> --shards <recordedShardCount> --damage-morphs 4
         assert exitCode == 0                            # D09-S4.3

2. :test-environment:verifyFixtures
     for fixture in build/fixtures-out/*:
         syndicate-verify --headless --asset build/fixtures-out/<f> \
             --part fixtures/parts/<f>.part.json \
             --golden fixtures/golden/<f>.manifest.json \
             --report build/verify/<f>.report.json
         collect exit code
     aggregate -> build/verify/summary.json
     fail the stage if any exit code != 0

3. :test-environment:verifyVehicleIntegration
     syndicate-verify --headless --asset build/fixtures-out/test_plate_2x1x0.1 \
         --part fixtures/parts/test_plate.part.json \
         --assembly fixtures/assemblies/reference_chassis.assembly.json \
         --categories vehicle
```

---

<!-- D14-S8 -->## 8. Edge Cases & Failure Modes

| # | Condition | Required behaviour | Agent's next action from the report |
|---|---|---|---|
| E1 | Manifest present, mesh file missing | Exit 20, `INPUT_NOT_FOUND`, report names the missing path | Re-run the Blender tool; check its exit code |
| E2 | Manifest fails schema validation | Exit 21, report lists each schema violation with a JSON pointer | Fix the tool's manifest writer (D09-S4.4) |
| E3 | glTF parses but has zero meshes | Exit 22, `MESH_LOAD_FAILED` | Check the tool's export stage (D09-S5.6) |
| E4 | Shard declared in manifest but absent from mesh | ASSET-002 fails naming the shard id | Tool bug: manifest written before export filtering |
| E5 | Shard has zero or negative mass | ASSET-001 fails; dependent PHYS/PROG checks skip with "prerequisite failed" | Check density lookup and volume computation (D09-S6.2/S6.3) |
| E6 | Mass conservation off by >2% | ASSET-006 fails with the exact delta in kg and % | Usually decimation or a dropped cell; compare `VOLUME_COVERAGE_FRAC` (ASSET-018) in the same report |
| E7 | Morph target contains NaN | ASSET-008 fails naming the morph and vertex index | Tool bug in shape key generation (D09-S5.3) |
| E8 | Morph deltas all near zero | ASSET-008 fails on `MORPH_MIN_DELTA_M` | Deformation amplitude parameter too low |
| E9 | Morph severity not monotonic | ASSET-009 fails naming the offending pair | Morph ordering or naming mismatch |
| E10 | Convex hull generation fails on a shard | ASSET-010 fails naming the shard | Non-manifold or degenerate shard geometry |
| E11 | Hull exceeds vertex budget | ASSET-011 fails with the count | Increase decimation in the tool; do not raise the tolerance |
| E12 | Inertia tensor violates triangle inequality | PHYS-004 fails with the three sums | Geometry is not watertight, or volume is wrong |
| E13 | Body sinks through the ground | PHYS-009 fails with the penetration depth | Hull is inverted or degenerate; check ASSET-012 in the same report |
| E14 | Body jitters forever | PHYS-010 fails; `post_rest_jitter_mps` recorded | Sliver hull or a mass far too small for the contact scale |
| E15 | Two identical runs differ | PHYS-012 fails with the position delta | Non-determinism in harness setup (unsorted iteration, unseeded RNG) — never "just re-run" |
| E16 | Fracture spawns wrong body count | PROG-005 fails with expected vs actual | Fracture system not reading `shardCount`, or shard spawn filtered |
| E17 | Momentum not conserved at fracture | PROG-004 fails with the % delta | Shards not inheriting parent velocity (D07-S5.6) |
| E18 | One shard flies off at 500 m/s | PROG-009 fails naming the shard and speed | Overlapping spawn positions producing huge separation impulses |
| E19 | Shards move identically | PROG-010 fails | Shards spawned as one compound body instead of separate bodies |
| E20 | Health restored after fracture restores the body | PROG-012 fails | Violates G9; fracture must be one-way |
| E21 | Joint never breaks | PROG-013 fails after the ramp completes; report records max impulse reached | Break threshold not applied, or units mismatched (N vs N·s) |
| E22 | Joint breaks below 80% threshold | PROG-014 fails | Threshold too low, or impulse accumulated across ticks incorrectly |
| E23 | Vehicle mass unchanged after attach | VEH-001 fails | Mass aggregation not invoked; check `structuralVersion` handling (G10) |
| E24 | Vehicle gains velocity when a part detaches | VEH-009 fails with Δv | Momentum created by removing mass without preserving linear momentum |
| E25 | Debris still parented to the vehicle | VEH-008 fails | Detach did not clear `parentEntity` / constraint |
| E26 | Golden mismatch after a legitimate tool change | GOLD-* fails; GOLD-008 notes the version difference | Review the diff, then `--update-golden` deliberately + memory entry (D13-S5.3 W2) |
| E27 | No display available but `--visual` requested | Exit 30 with `no display available; use --headless` | Re-run headless |
| E28 | A check exceeds its time budget | Exit 40; report marks that check `fail` with `details: "timeout after Ns"` | Usually an unstable simulation that never settles |
| E29 | Part definition absent | Degradation and vehicle checks report `skipped` with the reason; exit code unaffected | Supply `--part` to get full coverage |
| E30 | Fixture mesh itself is broken | `AssetValidator` fails the fixture in CI before the tool runs, so the failure is attributed to the fixture | Fix the fixture, not the tool |
| E31 | Harness crashes mid-run | Exit 30; a partial report is still written with `physics_data` as far as it got | Read the partial report before re-running |
| E32 | Native leak at teardown | Run fails with the per-type outstanding counts | Fix ownership per D02-S5.7 |

---

<!-- D14-S9 -->## 9. Test Cases

These are tests **of the harness itself** — the harness must be trustworthy before its verdicts mean anything.

| ID | Scenario | Expected |
|---|---|---|
| T-D14-1 | Verify `test_cube_1m` against its analytic golden | All checks pass; mass 7850 kg ± 2%; COM `(0, 0.5, 0)` ± 0.02 m; principal inertia within `INERTIA_REL` of `m/6` (for a unit cube about its centre) |
| T-D14-2 | Verify all five fixtures headlessly | Exit 0 for each; aggregate summary reports 0 failures |
| T-D14-3 | Zero one shard's mass in a manifest copy | ASSET-001 fails; dependent PROG checks skip; exit 10 |
| T-D14-4 | Inflate total shard mass by 10% | ASSET-006 fails with delta ≈ 10%; exit 10 |
| T-D14-5 | Inject NaN into one morph delta | ASSET-008 fails naming the morph and index; exit 10 |
| T-D14-6 | Replace a shard mesh with two coincident triangles | ASSET-005 and ASSET-010 fail; exit 10 |
| T-D14-7 | Swap `dmg_25` and `dmg_75` in the manifest | ASSET-009 fails (non-monotonic) |
| T-D14-8 | Set part mass to 0 in the manifest | ASSET-006 and PHYS-002 fail; no crash; report written |
| T-D14-9 | Run PHYS checks on a 1 m³ steel cube | Gravity, impulse, torque all within tolerance; drop rests at y = 0.5 ± 0.005 m |
| T-D14-10 | Run the drop test 50 times with the same seed | Final positions identical within `DETERMINISM_POS_M` every time |
| T-D14-11 | Progression at 75/50/25% health on `test_plate` | Morph weights match D07-S5.5 exactly; stats match D05-S5.4 exactly |
| T-D14-12 | Fracture a part moving at 8 m/s | Momentum conserved within 5%; 12 bodies spawned; no shard above 50 m/s |
| T-D14-13 | Restore health to 1.0 after fracture | PROG-012 passes (no resurrection) |
| T-D14-14 | Ramp force on a joint with a 4000 N·s threshold | Break observed at 4000 ± 600 N·s; no break at 3200 N·s held 2 s |
| T-D14-15 | Attach the plate to the reference chassis at `side_right_01` | Vehicle mass +1570 kg; COM shifts right by the analytic amount |
| T-D14-16 | Destroy the plate at 12 m/s | Mass and COM return to baseline within one tick; Δv ≤ 1 m/s; debris on `LAYER_DEBRIS` |
| T-D14-17 | Corrupt a golden shard mass by 10% | GOLD-002 fails; exit 14; `--update-golden` not invoked |
| T-D14-18 | Run with `--tolerances` doubling `MASS_DELTA_FRAC` | Report's `tolerances_applied` shows 0.04; CI treats it as an invalid pass |
| T-D14-19 | Run headless on a machine with no GPU | Completes normally; exit code reflects checks only |
| T-D14-20 | Request `--visual` with no display | Exit 30 with the guidance message |
| T-D14-21 | Register a new advisory check that always fails | Appears in the report as `warning`; exit code unchanged |
| T-D14-22 | Register a new check with an inline tolerance literal | Build-time/registration-time rejection (R15) |
| T-D14-23 | Run the harness 100 times in one process | `NativeResourceTracker.outstanding() == 0` after each; RSS stable |
| T-D14-24 | Validate 20 archived reports against the schema | All valid; no unknown `status` values |
| T-D14-25 | Delete `fracture_manifest.json` | Exit 20 with the missing path named |
| T-D14-26 | Time the full fixture batch | ≤ 120 s total |
| T-D14-27 | Compare visual-mode and headless-mode reports for one asset | Identical check IDs and statuses |
| T-D14-28 | Verify `test_complex_hollow` | Volume accounts for the cavity; mass ≈ 2500 kg, not the solid-box 7850 kg (proves ASSET-004 is not bounding-box based) |

---

<!-- D14-S10 -->## 10. Cross-References

| Topic | Section |
|---|---|
| Global invariants (G7 mass conservation, G9 one-way fracture, G17 headless parity, G19 native ownership) | `docs/00_master_index.md#D00-S5.2` |
| Reserved constants (`TICK_DT`, `MIN_BODY_MASS_KG`, `DEBRIS_LIFETIME_S`) | `docs/00_master_index.md#D00-S6.4` |
| `test-environment` module and dependency rules | `docs/02_technical_architecture.md#D02-S4.5` |
| Native disposal ordering | `docs/02_technical_architecture.md#D02-S5.7` |
| Component/system model reused by the harness | `docs/04_entity_component_model.md#D04-S4.4` |
| **Degradation curves asserted by PROG-003** | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Detachment effect on vehicle physics (VEH-005..010) | `docs/05_vehicle_part_system.md#D05-S5.5` |
| Vehicle stat aggregation (VEH-003/007) | `docs/05_vehicle_part_system.md#D05-S5.6` |
| **Bullet world construction reused by TestWorld** | `docs/06_physics_simulation.md#D06-S5.1` |
| Collision shape construction (hulls) | `docs/06_physics_simulation.md#D06-S5.2` |
| Collision layers used by debris checks | `docs/06_physics_simulation.md#D06-S4.4` |
| Fixed timestep the harness steps with | `docs/06_physics_simulation.md#D06-S5.4` |
| Mass property computation asserted by PHYS-002/003/004 | `docs/06_physics_simulation.md#D06-S5.7` |
| Breakable constraint configuration (PROG-013/014) | `docs/06_physics_simulation.md#D06-S5.6` |
| **Damage state machine driven by PROG-011** | `docs/07_damage_destruction_model.md#D07-S5.3` |
| **Health → morph weight mapping asserted by PROG-002** | `docs/07_damage_destruction_model.md#D07-S5.5` |
| **Fracture trigger and momentum inheritance (PROG-004/005)** | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Detachment logic (VEH-008/010) | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Debris lifetime (PROG-016) | `docs/07_damage_destruction_model.md#D07-S5.8` |
| Part definition schema (`--part`) | `docs/08_asset_pipeline.md#D08-S4.2` |
| Assembly manifest schema (`--assembly`) | `docs/08_asset_pipeline.md#D08-S4.4` |
| JSON schema locations incl. the report schema | `docs/08_asset_pipeline.md#D08-S6.1` |
| Asset validation rules reused for fixtures | `docs/08_asset_pipeline.md#D08-S5.4` |
| **Blender tool output contract (manifest schema)** | `docs/09_blender_destruction_tool.md#D09-S4.4` |
| **Blender tool CLI and exit codes** | `docs/09_blender_destruction_tool.md#D09-S4.3` |
| Tool's own verification pipeline (what the harness re-checks) | `docs/09_blender_destruction_tool.md#D09-S7` |
| Material density table used by ASSET-004/016 | `docs/09_blender_destruction_tool.md#D09-S6.3` |
| Tool determinism guarantee behind GOLD-007 | `docs/09_blender_destruction_tool.md#D09-S8` |
| **CI stages that run this harness** | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Performance budgets (AC-D14-15) | `docs/12_testing_validation_ci.md#D12-S5.6` |
| Deterministic physics test pattern | `docs/12_testing_validation_ci.md#D12-S5.2` |
| Recording findings from harness failures | `docs/13_persistent_memory_system.md#D13-S5.3` |

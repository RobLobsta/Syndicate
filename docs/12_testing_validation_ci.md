<!-- D12-S0 --># 12 — Testing, Validation, and CI

**Document ID:** D12
**Owns:** Test strategy and levels, deterministic physics regression pattern, CI pipeline and gates, performance budgets, regression scenario catalogue and how to extend it.

---

<!-- D12-S1 -->## 1. Purpose

This document specifies how the project proves it works: the test levels and what belongs at each, the seed-locked physics regression pattern, the asset and tool validation stages, the headless match smoke test, the CI pipeline with its blocking gates, the performance budgets and how they are measured, and the procedure for adding a new regression scenario.

Requirements are numbered `R1..Rn`, cited as `D12-R11`.

---

<!-- D12-S2 -->## 2. Scope

<!-- D12-S2.1 -->### 2.1 In Scope

- Test levels: unit, integration, physics regression, asset validation, tool validation, headless smoke, performance.
- Deterministic (seed-locked) physics test pattern.
- CI pipeline stages, ordering, gates, and artifacts.
- Documentation and memory lint gates.
- Performance budgets with measurement method.
- Regression scenario catalogue and the process to add one.
- Flaky-test policy.

<!-- D12-S2.2 -->### 2.2 Non-Goals

- **NG1.** The verification harness's checks — `docs/14_test_environment.md#D14-S4.5`. This document specifies when CI *runs* it.
- **NG2.** The Blender tool's self-verification — `docs/09_blender_destruction_tool.md#D09-S7`.
- **NG3.** Release, versioning, or deployment processes.
- **NG4.** Manual QA plans or playtest protocols.
- **NG5.** Choice of CI provider. Stages are specified as Gradle tasks so any provider can run them.

---

<!-- D12-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.3` | Cross-reference validator run as a CI gate |
| `docs/02_technical_architecture.md#D02-S4.5` | Module structure and the checks that guard it |
| `docs/03_runtime_modes.md#D03-S5.4` | Headless mode used by smoke tests |
| `docs/06_physics_simulation.md#D06-S5.8` | Determinism guarantees these tests can rely on |
| `docs/09_blender_destruction_tool.md#D09-S4.3` | Tool exit codes CI branches on |
| `docs/13_persistent_memory_system.md#D13-S5.8` | Memory lint rules |
| `docs/14_test_environment.md#D14-S7.3` | Fixture pipeline CI runs |

---

<!-- D12-S4 -->## 4. Data Contracts

<!-- D12-S4.1 -->### 4.1 Test Levels

**R1.** Every test belongs to exactly one level. The level determines where it lives, how fast it must be, and which CI stage runs it.

| Level | Tag | Location | Runs in | Budget | Scope |
|---|---|---|---|---|---|
| **L1 Unit** | `@Tag("unit")` | `<module>/src/test/java` | Every commit | < 5 s total per module | One class or one pure function. No Bullet world, no file IO, no network. |
| **L2 Integration** | `@Tag("integration")` | `<module>/src/test/java` | Every commit | < 60 s total | Several systems together in a real `World`; Bullet allowed; no rendering, no sockets. |
| **L3 Physics regression** | `@Tag("physics")` | `game-core/src/test/java/.../physics` | Every commit | < 120 s total | Seed-locked scenarios with recorded expected outcomes (D12-S5.2). |
| **L4 Asset validation** | Gradle task | `asset-pipeline` | Every commit | < 30 s | Schemas, references, balance rules over `assets/` and `fixtures/` (D08-S5.4). |
| **L5 Tool validation** | Gradle task | `blender-tool` | Every commit (if Blender available; always on CI) | < 5 min | Runs the tool over fixtures; asserts exit codes and manifests (D09-S12). |
| **L6 Harness verification** | Gradle task | `test-environment` | Every commit | < 120 s | Runs D14's checks over processed fixtures, compares to goldens. |
| **L7 Headless smoke** | `@Tag("smoke")` | `game-server-headless` | Every commit | < 90 s | A full bot match, end to end, headless (D12-S5.5). |
| **L8 Performance** | JMH + instrumented runs | `game-core`, `test-environment` | Nightly + on demand | < 20 min | Budgets in D12-S5.6. |
| **L9 Balance sweep** | Gradle task | `game-core` | Nightly | < 30 min | 500 offline matches (D11-S5.8). |
| **L10 Soak** | Gradle task | `game-server-headless` | Nightly | 60 min | Long-running server; leak and drift detection. |

**R2.** A test that needs a display, a GPU, or a network socket is **not permitted** at L1–L7. The only rendering-dependent test is the harness's visual mode, which is run manually, never in CI.

<!-- D12-S4.2 -->### 4.2 Test Naming and Structure

**R3.** Test method names state the behaviour and the expectation:

```
void detachingWheel_remapsRemainingWheelIndices()
void damageToDestroyedPart_isDiscarded()
void snapshotAppliedTwice_yieldsIdenticalState()
```

**R4.** Every test that corresponds to a blueprint test case cites it in a comment on the first line:

```java
// T-D05-11 (docs/05_vehicle_part_system.md#D05-S8)
```

This makes the blueprint's Test Cases tables auditable: a CI check greps for every `T-Dnn-n` id and reports which have no implementing test.

<!-- D12-S4.3 -->### 4.3 Physics Regression Scenario Format

**R5.** A physics regression scenario is data, not code, so adding one requires no new test class.

```json
{
  "scenarioId": "PHYS-REG-004",
  "description": "Medium vehicle loses a right-side armour plate at 12 m/s and must not veer beyond tolerance",
  "seed": 1337,
  "arena": "arena_flat_test",
  "durationTicks": 600,
  "setup": {
    "vehicles": [
      { "assemblyId": "vehicle_medium_raider_01",
        "position": { "x": 0, "y": 1.0, "z": 0 },
        "yawDeg": 0.0 }
    ]
  },
  "script": [
    { "atTick": 0,   "action": "setInput", "vehicle": 0, "throttle": 1.0, "steer": 0.0 },
    { "atTick": 240, "action": "assert", "check": "speedMps", "expected": 12.0, "tolerance": 1.0 },
    { "atTick": 240, "action": "destroyPart", "vehicle": 0, "slotPath": "root/side_right_01" },
    { "atTick": 241, "action": "assert", "check": "totalMassKg", "expected": 1460.0,
      "tolerance": 29.2 },
    { "atTick": 600, "action": "assert", "check": "lateralDriftM", "expected": 0.0,
      "tolerance": 2.5 }
  ],
  "expected": {
    "finalPosition": { "x": 0.31, "y": 0.94, "z": -142.66 },
    "finalSpeedMps": 14.82,
    "positionToleranceM": 0.5,
    "speedToleranceMps": 0.3,
    "recordedOn": { "platform": "linux-x86_64", "bulletVersion": "2.89",
                    "buildVersion": "0.1.0" }
  }
}
```

**R6.** Two assertion tiers, because native Bullet is not cross-platform bit-identical (D06-S5.8):

| Tier | Assertions | Runs on |
|---|---|---|
| **Tight** | `expected.finalPosition` / `finalSpeedMps` within `positionToleranceM` / `speedToleranceMps` | The CI reference platform only (`recordedOn.platform`) |
| **Loose** | The `script`'s inline assertions (mass, drift, speed bands) | Every platform |

A developer on Windows runs the loose tier and sees real regressions; the tight tier catches subtle numerical drift on the one platform where it is meaningful.

---

<!-- D12-S5 -->## 5. Logic & Algorithms

<!-- D12-S5.1 -->### 5.1 Unit and Integration Testing

```pseudo
# L1 UNIT — what belongs here:
#   pure functions: degradationMultiplier (D05-S5.4), morphWeightsForHealth (D07-S5.5),
#                   armour formulas (D07-S5.2), stat aggregation (D05-S5.6),
#                   quantisation/dequantisation (D10-S4.3), path string-pulling,
#                   entity id packing (D04-S5.1), manifest parsing.
#   These are the highest-value tests in the project: they are exact, fast, and they
#   cover the arithmetic that every other layer depends on.

# L2 INTEGRATION — what belongs here:
#   a real World with a real Bullet world and a subset of systems.
example:
    world = TestWorldBuilder()
              .withSystems(VehicleStatsSystem, PhysicsSystem, DamageSystem,
                           FractureSystem, DetachSystem, MassPropertySystem,
                           EntityDestroySystem)
              .withSeed(1337).build()
    vehicle = spawnVehicle(world, "vehicle_medium_raider_01", origin)
    massBefore = vehicle.totalMassKg
    applyDamage(world, killShotAt("root/side_right_01"))
    world.tick(1)
    assertThat(vehicle.totalMassKg).isCloseTo(massBefore - 160.0, within(3.2))
    assertThat(NativeResourceTracker.outstanding()).isZero()

# R7. Every L2 test asserts NativeResourceTracker.outstanding() == 0 in an @AfterEach.
#     Native leaks are otherwise invisible until a soak test finds them days later.
# R8. Every L2 test constructs its world from a fixed seed and never reads wall-clock
#     time, so a failure is always reproducible from the test name alone.
```

<!-- D12-S5.2 -->### 5.2 Deterministic Physics Regression

```pseudo
function runPhysicsRegression(scenarioFile):
    s     = readJson(scenarioFile)
    world = WorldFactory.create(headlessConfig(seed = s.seed, arena = s.arena))
    setupWorld(world, s.setup)                     # deterministic spawn order

    looseFailures = []
    for tick in 0 .. s.durationTicks-1:
        for action in s.script.where(atTick == tick):
            switch action.type:
                case setInput:     applyScriptedInput(world, action)
                case destroyPart:  setHealth(world, action.vehicle, action.slotPath, 0)
                case applyImpulse: applyImpulse(world, action)
                case assert:
                    measured = measure(world, action.check, action.vehicle)
                    if abs(measured - action.expected) > action.tolerance:
                        looseFailures.append(describe(action, measured))   # LOOSE tier
        world.tick(tick)

    # TIGHT tier: only on the platform the expectations were recorded on.
    tightFailures = []
    if currentPlatform() == s.expected.recordedOn.platform:
        finalPos = world.vehicle(0).position
        if distance(finalPos, s.expected.finalPosition) > s.expected.positionToleranceM:
            tightFailures.append("final position drift {} m"
                                 .format(distance(finalPos, s.expected.finalPosition)))
        if abs(speed - s.expected.finalSpeedMps) > s.expected.speedToleranceMps:
            tightFailures.append("final speed drift")
    else:
        log.info("tight assertions skipped: recorded on {}, running on {}",
                 s.expected.recordedOn.platform, currentPlatform())

    # Self-consistency: the same scenario twice in the same process must agree exactly.
    second = rerunInFreshWorld(s)
    if distance(second.finalPosition, finalPos) > DETERMINISM_POS_M:
        fail("NON-DETERMINISM: same seed, same process, different result — "
             "this is always a bug, never a tolerance problem")

    assertEmpty(looseFailures + tightFailures)

# R9. The self-consistency rerun is the single most valuable assertion here. A drifting
#     expectation is a tuning problem; a run-to-run difference is a correctness bug
#     (unsorted iteration, unseeded RNG, wall-clock read) and must fail loudly.
# R10. Re-recording expectations is a DELIBERATE action:
#         ./gradlew :game-core:rerecordPhysicsExpectations -PscenarioId=PHYS-REG-004
#      It prints a diff of old vs new and requires the scenario file to be committed.
#      A failing test NEVER re-records automatically.
```

<!-- D12-S5.3 -->### 5.3 Asset and Tool Validation

```pseudo
# L4 — assets
task validateAssets:
    report = AssetValidator.validateAll("assets/", strict = true)      # D08-S5.4
    writeJson("build/asset-validation.json", report)
    fail if report.errors.isNotEmpty()

task validateFixtures:
    report = AssetValidator.validateAll("fixtures/", strict = true)
    # Runs BEFORE the tool stage so a broken fixture is attributed to the fixture,
    # not blamed on the Blender tool (D14-E30).
    fail if report.errors.isNotEmpty()

# L5 — Blender tool
task blenderToolTest:
    if not blenderAvailable():
        if env.SYNDICATE_REQUIRE_BLENDER == "1": fail("Blender required on CI")
        else: skip with warning                                        # D02-E4
    run pytest tests/unit                       # pure-Python, no Blender
    run blender --background --python tests/blender/run_all.py         # in-Blender tests
    for fixture in fixtures/meshes/*:
        exit = runTool(fixture, seed = recordedSeed(fixture))
        assert exit == 0
        assertManifestValid(outputOf(fixture))
    runTool(brokenFixture("open_mesh")) -> assert exit == 66           # negative tests
    runTool(brokenFixture("unknown_material")) -> assert exit == 67
    runTool(withArgs("--shardz 4")) -> assert exit == 64
    runTool(withArgs("--verify-determinism")) -> assert exit == 0

# L6 — harness
task verifyFixtures:
    for out in build/fixtures-out/*:
        exit = runHarness(out, golden = goldenFor(out))                # D14-S7.3
        collect(exit)
    aggregate to build/verify/summary.json
    fail if any exit != 0
```

<!-- D12-S5.4 -->### 5.4 CI Pipeline

**R11.** Stages run in this order. Every stage is a Gradle task so it is reproducible locally. A **gate** stage blocks everything after it.

```
┌── STAGE 0: Fast checks (< 60 s) ──────────────────────────────── GATE ──┐
│  :spotlessCheck                    formatting                            │
│  :checkLayering                    module dependency rules (D02-S5.6)    │
│  :game-core:checkHeadlessSafety    no rendering in game-core (D02-R9)    │
│  :validateDocs                     cross-reference validator (D00-S5.3)  │
│                                    + required-section check (D00-S5.4)   │
│  :memory-system:lintMemory         memory entry lint (D13-S5.8)          │
│  :blender-tool:lint                ruff                                  │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 1: Compile (< 3 min) ─────────────────────────────────── GATE ──┐
│  :assemble        all modules, Java 17 toolchain                         │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 2: Unit + integration (< 4 min) ──────────────────────── GATE ──┐
│  :test -Ptags=unit,integration     all modules                           │
│  :game-core:archUnitTest           sub-package layering (D02-E11)        │
│  :checkReplicableClassification    no Replicable on cosmetic (D04-R21)   │
│  :checkCosmeticIsolation           game-core never reads cosmetic (D07-R6)│
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 3: Asset validation (< 1 min) ────────────────────────── GATE ──┐
│  :asset-pipeline:validateFixtures                                        │
│  :asset-pipeline:validateAssets                                          │
│  :asset-pipeline:buildIndex                                              │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 4: Physics regression (< 2 min) ──────────────────────── GATE ──┐
│  :game-core:test -Ptags=physics    all scenarios, loose + tight tiers    │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 5: Tool + harness (< 8 min) ──────────────────────────── GATE ──┐
│  :blender-tool:unitTest                                                  │
│  :blender-tool:blenderTest                                               │
│  :blender-tool:processFixtures                                           │
│  :test-environment:verifyFixtures  (asset+physics+progression+golden)    │
│  :test-environment:verifyVehicleIntegration                              │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 6: Smoke (< 2 min) ───────────────────────────────────── GATE ──┐
│  :game-server-headless:test -Ptags=smoke    full headless bot match      │
│  :headlessClientSmoke                       client boots headless-safe   │
└──────────────────────────────────────────────────────────────────────────┘
┌── STAGE 7: Package (< 2 min) ────────────────────────────────────────────┐
│  :game-client:distZip  :game-server-headless:shadowJar                   │
│  :test-environment:shadowJar  :asset-pipeline:shadowJar                  │
│  :blender-tool:package                                                   │
└──────────────────────────────────────────────────────────────────────────┘
┌── NIGHTLY (not per-commit) ──────────────────────────────────────────────┐
│  :game-core:jmh                    performance benchmarks (D12-S5.6)     │
│  :game-core:balanceSweep           500 offline matches (D11-S5.8)        │
│  :game-server-headless:soak        60-minute server run                  │
│  :test-environment:verifyAllAssets harness over every shipped part       │
└──────────────────────────────────────────────────────────────────────────┘

ARTIFACTS published on every run (kept 30 days):
  build/asset-validation.json          build/verify/*.report.json
  build/verify/summary.json            build/reports/tests/**
  build/fixtures-out/**                build/balance/sweep.json   (nightly)
  build/perf/benchmarks.json (nightly) server/soak logs           (nightly)
```

**R12.** Stage 0 is deliberately first and deliberately cheap: formatting, layering, documentation integrity, and memory lint fail in under a minute and account for a large share of real breakages. A contributor learns about a dangling doc reference before waiting three minutes for compilation.

<!-- D12-S5.5 -->### 5.5 Headless Smoke Test

```pseudo
# The single most valuable end-to-end test: if this passes, the game runs.
function headlessMatchSmokeTest():
    config = LaunchConfig {
        mode = DEDICATED_SERVER, headless = true, autoStart = true,
        gameMode = TEAM_DEATHMATCH, arenaId = "arena_scrapyard_01",
        botCount = 8, botDifficulty = NORMAL,
        matchSeed = 1337, timeLimitSeconds = 60, strictAssets = true }

    process = launch("syndicate-server", config, timeout = 90 s)

    assertions:
      1. Process exits 0 within the timeout.                # a hang is a failure
      2. Match reached RESULTS phase.
      3. A winner or an explicit draw was declared.
      4. At least 1 vehicle was destroyed.                  # bots actually fought
      5. At least 20 parts were destroyed.                  # destruction actually ran
      6. At least 1 fracture occurred and spawned shards.   # the core feature ran
      7. Zero ERROR-level log lines.
      8. Zero NaN events (physics.nan metric == 0).
      9. NativeResourceTracker.outstanding() == 0 at shutdown.
     10. Peak entity count < MAX_ENTITIES * 0.5.
     11. Debris count never exceeded MAX_DEBRIS_BODIES.
     12. p99 tick duration < TICK_BUDGET_MS (D12-S5.6).
     13. No texture, shader, audio, or morph-geometry load occurred (D03-R13).
     14. Every bot moved at least 50 m (they are not stuck at spawn).

# R13. Assertions 4–6 exist because a smoke test that only checks "exit 0" passes
#      happily on a match where nothing happened. Asserting that destruction OCCURRED
#      is what makes this a test of this game rather than of any game.
```

<!-- D12-S5.6 -->### 5.6 Performance Budgets

**R14.** Budgets are measured, not aspirational. Each has a measurement method and a CI-enforced threshold at the nightly stage.

| Budget | Target | Measured how | Enforced |
|---|---|---|---|
| `TICK_BUDGET_MS` (server, 12 vehicles, 8 bots) | mean ≤ 6.0 ms, p99 ≤ 12.0 ms | Instrumented tick timing over 3600 ticks | Nightly + smoke (p99 only) |
| Physics step share | ≤ 55% of tick time | Per-system profiler | Nightly |
| Bot decision cost (11 bots) | ≤ 0.8 ms/tick mean | Per-system profiler | Nightly |
| Damage + destruction systems | ≤ 1.0 ms/tick mean, ≤ 4.0 ms on a heavy destruction tick | Per-system profiler | Nightly |
| Client frame time (1080p, 12 vehicles) | ≤ 8.0 ms mean, ≤ 16.6 ms p99 | Frame timing over a scripted replay | Nightly, reference GPU |
| Steady-state allocation per tick | 0 bytes | Allocation profiler, 10,000 ticks after warm-up | Nightly |
| Startup: asset load | ≤ 6.0 s cold, ≤ 2.5 s warm | Timed from process start to LOBBY | Every commit (smoke) |
| Fracture event cost (24 shards) | ≤ 3.0 ms | JMH microbenchmark | Nightly |
| Snapshot build (12 peers) | ≤ 1.2 ms/tick at 20 Hz | JMH + instrumented server | Nightly |
| Bandwidth per client | ≤ 128 kbit/s down, ≤ 32 kbit/s up | Wire capture over a 5-minute 12-player match | Nightly |
| Harness fixture batch | ≤ 120 s for 5 fixtures | Wall clock | Every commit (stage 5) |
| Blender tool per part (24 shards) | ≤ 25 s | Wall clock | Every commit (stage 5) |
| Memory: server RSS after 60 min | ≤ 1.5 GB, growth ≤ 3% over the last 30 min | Soak test sampling | Nightly |

```pseudo
function enforcePerformanceBudget(metric, measured, budget):
    if measured > budget.threshold:
        if measured > budget.threshold * 1.25: fail("budget exceeded by >25%")
        else: warn("budget exceeded: {} vs {}", measured, budget.threshold)
              # A 0–25% overshoot warns and is tracked; only a large regression breaks
              # the build, because benchmark noise on shared CI runners is real and
              # failing on it trains people to ignore the signal.
    recordTimeSeries(metric, measured)         # trend matters more than any single run
```

**R15.** Every budget breach — warning or failure — is recorded as a time series. A metric that drifts 3% per week passes every individual check and doubles in six months; only the trend catches that.

<!-- D12-S5.7 -->### 5.7 Regression Scenario Catalogue

**R16.** Catalogue of the physics regression scenarios that must exist. Each is a JSON file in `game-core/src/test/resources/physics-scenarios/`.

| ID | Scenario | Primarily guards |
|---|---|---|
| PHYS-REG-001 | Cube drop and rest from 2 m | Basic body config, contact stability (D06-S4.2) |
| PHYS-REG-002 | Vehicle accelerates to top speed on flat ground | Vehicle model, derived stats (D05-S5.6, D06-S5.5) |
| PHYS-REG-003 | Vehicle full-lock turn at 8 m/s | Steering, friction, roll influence |
| PHYS-REG-004 | Vehicle loses a side plate at 12 m/s | Mass/COM update, no velocity kick (D05-R23, G10) |
| PHYS-REG-005 | Vehicle loses a front wheel while turning | Wheel index remapping (D05-R24) |
| PHYS-REG-006 | Part fractures at 8 m/s with 2 rad/s spin | Momentum inheritance (D07-S5.6) |
| PHYS-REG-007 | Chassis destroyed with 20 parts attached | Wreck path, debris budget (D07-S5.7) |
| PHYS-REG-008 | Two vehicles head-on at 20 m/s each | Collision damage from impulse (D07-S5.2) |
| PHYS-REG-009 | Breakable joint ramp to failure | Impulse threshold semantics (D06-R22) |
| PHYS-REG-010 | 200 debris bodies settling | Debris budget, sleeping, despawn (D06-S5.10) |
| PHYS-REG-011 | Projectile at 600 m/s into a 5 cm plate | No tunnelling (D06-S5.9) |
| PHYS-REG-012 | Vehicle drives off a 10 m drop | Airborne suspension, landing stability |
| PHYS-REG-013 | Vehicle on a 30° bank at 15 m/s | Roll resistance, downforce |
| PHYS-REG-014 | All wheels destroyed while moving | Immobile-but-alive (D01-E4, D05-E1) |
| PHYS-REG-015 | 600-tick determinism replay | Self-consistency (D12-R9) |

```pseudo
# ADDING A NEW SCENARIO — the whole procedure.
1. Write the scenario JSON with `script` LOOSE assertions only (no `expected` block).
2. Run:  ./gradlew :game-core:test --tests "*PhysicsRegression*" -PscenarioId=<ID>
   It fails with "no recorded expectations".
3. Inspect the behaviour: run the same scenario in the harness's visual mode
   (D14-S5.11) and confirm with your own eyes that it does the right thing.
   NEVER record expectations for behaviour you have not verified — a recorded
   expectation of a bug makes the bug permanent.
4. Record:  ./gradlew :game-core:rerecordPhysicsExpectations -PscenarioId=<ID>
5. Commit the scenario file including `expected.recordedOn`.
6. Add the row to this catalogue (D12-S5.7) and cite the blueprint sections it guards.
7. If the scenario was created in response to a bug, write a `discoveries/` memory
   entry describing the root cause (D13-S5.3 W5).
```

<!-- D12-S5.8 -->### 5.8 Flaky Test Policy

```pseudo
# A flaky test is worse than no test: it trains everyone to re-run instead of look.
POLICY:
  1. There is NO automatic retry of any test in CI. Not one.
  2. A test that fails intermittently is QUARANTINED within one working day:
       - tagged @Tag("quarantined"), excluded from gating stages
       - an issue is opened, and a `discoveries/` memory entry records what is known
       - the quarantine has an explicit owner and a date
  3. A quarantined test that is not fixed within 10 working days is DELETED, and its
     deletion is recorded. A permanently quarantined test is a lie about coverage.
  4. Physics tests are NEVER quarantined for non-determinism. Non-determinism in a
     seeded scenario is a real bug (D12-R9), and quarantining it hides the exact class
     of defect this project is most vulnerable to.
  5. Timing-sensitive assertions use tick counts, never wall-clock durations, so they
     cannot flake under CI load.
```

---

<!-- D12-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D12-1.** Every test carries exactly one level tag; an untagged test fails the build.
- [ ] **AC-D12-2.** No L1–L7 test requires a display, GPU, or network socket.
- [ ] **AC-D12-3.** Every CI stage is runnable locally via the same Gradle task.
- [ ] **AC-D12-4.** Stage 0 completes in under 60 s.
- [ ] **AC-D12-5.** The full per-commit pipeline (stages 0–7) completes in under 20 minutes.
- [ ] **AC-D12-6.** `:validateDocs` fails on a dangling cross-reference, a missing required section, or a malformed section ID.
- [ ] **AC-D12-7.** `:memory-system:lintMemory` fails on any violation of D13-S5.8.
- [ ] **AC-D12-8.** Every physics scenario in D12-S5.7 exists and passes.
- [ ] **AC-D12-9.** The self-consistency rerun in every physics scenario passes exactly (within `DETERMINISM_POS_M`).
- [ ] **AC-D12-10.** Tight-tier assertions are skipped with a log line on non-reference platforms and run on the reference platform.
- [ ] **AC-D12-11.** Expectations are never re-recorded by a failing test; only the explicit task rewrites them.
- [ ] **AC-D12-12.** The headless smoke test asserts all 14 conditions in D12-S5.5.
- [ ] **AC-D12-13.** Every L2 test asserts zero outstanding native resources.
- [ ] **AC-D12-14.** Every performance budget in D12-S5.6 has an implemented measurement and a recorded time series.
- [ ] **AC-D12-15.** No test is automatically retried anywhere in CI.
- [ ] **AC-D12-16.** Every `T-Dnn-n` id in the blueprint documents maps to an implementing test, or is listed in a reported coverage gap.
- [ ] **AC-D12-17.** CI publishes every artifact listed in D12-S5.4.
- [ ] **AC-D12-18.** A broken fixture fails at stage 3, before the Blender tool stage runs.
- [ ] **AC-D12-19.** The Blender tool's negative tests assert specific exit codes, not merely non-zero.
- [ ] **AC-D12-20.** The nightly balance sweep completes and publishes `sweep.json`.

---

<!-- D12-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Blender unavailable on a developer machine | L5 skips with a warning; CI (`SYNDICATE_REQUIRE_BLENDER=1`) fails. |
| E2 | Physics test fails only on Windows | Tight tier is skipped there, so the failure is in the loose tier — a real behavioural regression, not numerical drift. Investigate, do not widen tolerances. |
| E3 | Physics test fails on the reference platform after a Bullet upgrade | Expected: the upgrade changed numerics. Verify behaviour visually (D12-S5.7 step 3), re-record deliberately, and write a `discoveries/` entry. |
| E4 | The self-consistency rerun fails | Always a bug. Never re-record, never quarantine (D12-S5.8 rule 4). |
| E5 | Golden manifest mismatch after a Blender patch upgrade | GOLD-008 notes the version difference and demotes the topology check (D14-S5.8); masses and counts must still match. |
| E6 | CI runner is slower than the reference, breaching a time budget | Time budgets warn up to 25% and are tracked as a trend (D12-R14); only large regressions fail. |
| E7 | A test needs a display | It does not belong in CI. Move it to the harness's visual mode, which is manual. |
| E8 | Smoke test times out | Failure, not a retry. A hang is the most serious class of bug this suite can find. |
| E9 | Asset validation fails on a content-only change | Correct behaviour; content is code here. Fix the content. |
| E10 | Memory lint fails because `INDEX.md` was hand-edited | Regenerate (D13-S5.5). |
| E11 | Doc validator fails after renaming a section | Section IDs are stable across renames (D00-R8); if the ID was changed, that is the bug. |
| E12 | Two developers add scenarios with the same ID | Stage 3 fails on duplicate scenario ids; ids are allocated monotonically like memory entry ids. |
| E13 | Nightly soak finds a slow leak | Fails the nightly, publishes the RSS series, and requires a `discoveries/` entry with the leak's source. |
| E14 | A blueprint test case has no implementing test | Reported as a coverage gap in the build summary; not a hard failure in v1, but tracked. |
| E15 | JMH benchmark noise on a shared runner | Benchmarks warn, never gate, except for a >25% regression against the trailing 7-day median. |
| E16 | A quarantined test hits day 10 | It is deleted, and the deletion is recorded in the memory system. |

---

<!-- D12-S8 -->## 8. Test Cases

Tests of the test system itself.

| ID | Scenario | Expected |
|---|---|---|
| T-D12-1 | Run the full pipeline on a clean clone | All stages pass in < 20 min |
| T-D12-2 | Introduce a dangling doc reference | Stage 0 fails naming file and line |
| T-D12-3 | Remove a required section from a doc | Stage 0 fails naming the doc and the section |
| T-D12-4 | Hand-edit `.agent-memory/INDEX.md` | Stage 0 memory lint fails (L13) |
| T-D12-5 | Add a `game-core` dependency on a rendering backend | Stage 0 layering/headless-safety fails |
| T-D12-6 | Break `degradationMultiplier` by 1% | Unit tests fail; PHYS-REG scenarios also fail |
| T-D12-7 | Introduce a `HashMap` iteration in a gameplay path | Self-consistency rerun fails in at least one physics scenario |
| T-D12-8 | Introduce a `System.nanoTime()` read in a system | Determinism assertion fails (G5) |
| T-D12-9 | Run physics scenarios on Windows and Linux | Loose tier passes on both; tight tier runs only on the reference platform |
| T-D12-10 | Corrupt a golden manifest | Stage 5 fails with exit 14 from the harness |
| T-D12-11 | Make a fixture mesh non-watertight | Stage 3 fails before the tool runs |
| T-D12-12 | Make the smoke match end with zero destruction | Smoke assertions 4–6 fail |
| T-D12-13 | Leak a `btRigidBody` in an L2 test | That test's `@AfterEach` fails with the outstanding count |
| T-D12-14 | Slow `DamageSystem` by 3× | Nightly performance stage fails (>25% over budget) |
| T-D12-15 | Slow it by 10% | Warns, records the trend, does not fail |
| T-D12-16 | Add an untagged test | Build fails |
| T-D12-17 | Add a physics scenario without expectations, then run | Fails with "no recorded expectations"; the re-record task then succeeds |
| T-D12-18 | Attempt to configure a CI retry | No retry mechanism exists to configure (D12-S5.8 rule 1) |
| T-D12-19 | Run the nightly balance sweep | `sweep.json` published; flags reported |
| T-D12-20 | Run the 60-minute soak | RSS growth ≤ 3% over the last 30 minutes; zero outstanding natives at shutdown |

---

<!-- D12-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Cross-reference validator | `docs/00_master_index.md#D00-S5.3` |
| Required section structure check | `docs/00_master_index.md#D00-S5.4` |
| Module layering and headless-safety checks | `docs/02_technical_architecture.md#D02-S5.6`, `#D02-S4.5` |
| Native resource tracking | `docs/02_technical_architecture.md#D02-S5.7` |
| Headless server loop under test | `docs/03_runtime_modes.md#D03-S5.4` |
| System schedule assertions | `docs/04_entity_component_model.md#D04-S4.4` |
| `Replicable` classification check | `docs/04_entity_component_model.md#D04-S5.8` |
| Degradation curve unit tests | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Determinism guarantees and limits | `docs/06_physics_simulation.md#D06-S5.8` |
| Debris budget under load | `docs/06_physics_simulation.md#D06-S5.10` |
| Cosmetic isolation check | `docs/07_damage_destruction_model.md#D07-S4.2` |
| Fracture momentum regression | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Asset validation rules | `docs/08_asset_pipeline.md#D08-S5.4` |
| Blender tool exit codes CI asserts | `docs/09_blender_destruction_tool.md#D09-S4.3` |
| Tool determinism self-check | `docs/09_blender_destruction_tool.md#D09-S8` |
| Bandwidth budget | `docs/10_networking_multiplayer.md#D10-S6` |
| Offline match simulation and sweeps | `docs/11_ai_bots_and_match_simulation.md#D11-S5.8` |
| Memory lint rules | `docs/13_persistent_memory_system.md#D13-S5.8` |
| Harness checks and fixture pipeline | `docs/14_test_environment.md#D14-S4.5`, `#D14-S7.3` |
| Harness exit codes | `docs/14_test_environment.md#D14-S4.2` |

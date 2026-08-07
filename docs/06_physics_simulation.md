<!-- D06-S0 --># 06 — Physics Simulation

**Document ID:** D06
**Owns:** Bullet integration, collision shapes and layers, rigid body configuration, vehicle physics model, constraints, fixed timestep, mass properties, debris physics, determinism.

---

<!-- D06-S1 -->## 1. Purpose

This document specifies how the simulation is built on Bullet through gdx-bullet: how the world is constructed and stepped, how collision shapes are derived from assets, how bodies are parameterised, which vehicle model is used and why, how parts are attached and how those attachments break, how collision filtering works, how mass properties are computed and kept correct as parts are lost, and what determinism guarantees exist.

Requirements are numbered `R1..Rn`, cited as `D06-R14`.

---

<!-- D06-S2 -->## 2. Scope

<!-- D06-S2.1 -->### 2.1 In Scope

- Bullet world construction and configuration.
- Collision shape derivation, caching, and compound assembly.
- Rigid body parameters: mass, friction, restitution, damping, CCD, sleeping.
- Vehicle physics model selection and configuration.
- Constraints for attached and breakable parts.
- Collision layers and masks.
- The fixed timestep and its ordering guarantees.
- Mass, centre of mass, and inertia computation and runtime updates.
- Debris bodies and their budget.
- Projectile integration.
- Determinism and seeded randomness.

<!-- D06-S2.2 -->### 2.2 Non-Goals

- **NG1.** Damage maths and destruction state — `docs/07_damage_destruction_model.md#D07-S5`.
- **NG2.** Part/slot semantics — `docs/05_vehicle_part_system.md#D05-S4`.
- **NG3.** Networked physics reconciliation — `docs/10_networking_multiplayer.md#D10-S5.5`.
- **NG4.** Rendering of physics debug geometry — `docs/14_test_environment.md#D14-S5.11` (harness) and `game-client` (in-game debug draw).
- **NG5.** Soft bodies, cloth, fluids, and vehicle deformation of the *collision* mesh. Collision geometry never deforms; only visual meshes morph (D07-S5.5).
- **NG6.** Cross-platform bit-identical determinism — explicitly not guaranteed (D06-S5.10).

---

<!-- D06-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S4.4` | Y-up right-handed coordinate system |
| `docs/00_master_index.md#D00-S6.4` | `TICK_DT`, `MAX_SUBSTEPS`, `WORLD_GRAVITY`, `MIN_BODY_MASS_KG` |
| `docs/02_technical_architecture.md#D02-S4.2` | gdx-bullet choice and initialisation rules |
| `docs/02_technical_architecture.md#D02-S5.7` | Native resource ownership |
| `docs/04_entity_component_model.md#D04-S4.4` | `PhysicsSystem` and `MassPropertySystem` slots |
| `docs/05_vehicle_part_system.md#D05-S5.5` | Detachment sequence that drives mass updates |
| `docs/09_blender_destruction_tool.md#D09-S6.3` | Material densities used for mass |
| External: Bullet 2.8x via gdx-bullet 1.14.2 | The engine itself |

---

<!-- D06-S4 -->## 4. Data Contracts

<!-- D06-S4.1 -->### 4.1 World Configuration

**R1.** Exactly one `btDiscreteDynamicsWorld` exists per `World` (per match, or per harness test scene).

| Parameter | Value | Rationale |
|---|---|---|
| Broadphase | `btDbvtBroadphase` | Handles the large, dynamic body-count swings that destruction produces without manual world bounds. |
| Collision configuration | `btDefaultCollisionConfiguration` | |
| Dispatcher | `btCollisionDispatcher` | |
| Solver | `btSequentialImpulseConstraintSolver` | Deterministic given fixed ordering; the parallel solver is not used, precisely because its ordering is not stable. |
| Gravity | `WORLD_GRAVITY` = (0, −9.81, 0) m/s² | D00-S6.4 |
| Solver iterations | 10 | Bullet's default; raised to 20 for the vehicle solver group if stacking artefacts appear (record via D13). |
| `solverInfo.m_splitImpulse` | true | Prevents penetration recovery from adding energy — important with heavy debris piles. |
| `solverInfo.m_splitImpulsePenetrationThreshold` | −0.02 | |
| `solverInfo.m_erp` / `m_erp2` | 0.2 / 0.8 | Defaults; `erp2` governs the split-impulse pass. |
| `solverInfo.m_numIterations` | 10 | |
| `solverInfo.m_solverMode` | `SOLVER_USE_WARMSTARTING \| SOLVER_SIMD` | Warm starting improves stacking stability. |
| Fixed internal timestep | `TICK_DT` | See D06-S5.4 |
| `MAX_SUBSTEPS` | 4 | See D06-S5.4 |

<!-- D06-S4.2 -->### 4.2 Rigid Body Configuration

**R2.** Body classes and their parameters. Any body not matching one of these classes is a specification gap and must be recorded (D13-S5.3 W1).

| Body class | Mass | Shape | Friction | Rolling friction | Restitution | Lin/Ang damping | CCD | Sleeping |
|---|---|---|---|---|---|---|---|---|
| Vehicle chassis | total assembly mass | `btCompoundShape` of convex hulls | 0.6 | 0.0 | 0.05 | 0.05 / 0.10 | On (radius = 0.3× min extent, threshold = 2 m) | Disabled |
| Arena static | 0 | `btBvhTriangleMeshShape` | 0.8 | — | 0.0 | — | — | Static |
| Arena dynamic prop | authored | convex hull | 0.6 | 0.02 | 0.1 | 0.05 / 0.10 | Off | Enabled |
| Debris shard | manifest mass | `btConvexHullShape` | 0.5 | 0.03 | 0.15 | 0.10 / 0.20 | Off | Enabled (2 s) |
| Detached part | part mass | `btConvexHullShape` | 0.55 | 0.02 | 0.1 | 0.08 / 0.15 | Off | Enabled (2 s) |
| Projectile (physical) | 0.1–5 kg | `btSphereShape` | 0.2 | — | 0.0 | 0 / 0 | **On** | Disabled |

**R3.** Mass rules (G13): a dynamic body has `mass > MIN_BODY_MASS_KG = 0.01`; a static body has `mass == 0` exactly. No other value is legal. A shard whose computed mass falls below the minimum is **merged into its nearest neighbour** by the Blender tool (D09-S6.2), never clamped at runtime.

**R4.** Sleeping is **disabled for vehicle chassis bodies**. A sleeping vehicle would ignore an incoming projectile's activation for one tick and would not respond to a mass change from detachment. Debris and detached parts sleep normally — that is where the CPU saving actually matters.

**R5.** CCD is enabled for projectiles and vehicles. Debris does not use CCD: hundreds of CCD bodies is a large cost, and a shard tunnelling through the ground is cosmetically irrelevant and is caught by the despawn budget anyway.

<!-- D06-S4.3 -->### 4.3 Collision Shape Rules

**R6.** Shape selection by role:

| Role | Shape | Source |
|---|---|---|
| Part (in a vehicle) | `btConvexHullShape`, simplified to ≤ `MAX_HULL_VERTICES` (64) | Blender tool hull output (D09-S5.5) |
| Vehicle | `btCompoundShape` of the chassis hull + each non-wheel part hull at its slot transform | Built at spawn (D06-S5.3) |
| Shard | `btConvexHullShape`, ≤ 32 vertices | Blender tool, decimated further than parts |
| Arena | `btBvhTriangleMeshShape` (static, precomputed BVH) | Arena asset |
| Projectile | `btSphereShape` | Radius from weapon definition |
| Wheel | **none** | Wheels are ray casts, not bodies (D06-S5.5) |

**R7.** Concave part geometry is **never** used directly. If a part needs concavity (a hollow frame), the Blender tool emits multiple convex hulls and the part contributes several children to the compound. `btGImpactShape` is prohibited: unstable and slow for this body count.

**R8.** Shapes are immutable, cached, and shared. `ShapeCache` keys on `(assetId, lodIndex)` and owns every shape (D02-S5.7 rule 2). A body never disposes a shape.

<!-- D06-S4.4 -->### 4.4 Collision Layers and Masks

**R9.** Layers are bit flags. A pair collides only if each side's mask contains the other's layer.

| Layer | Bit | Collides with |
|---|---|---|
| `LAYER_STATIC` | `1<<0` | vehicle, debris, projectile, prop |
| `LAYER_VEHICLE` | `1<<1` | static, vehicle, projectile, prop, debris(*) |
| `LAYER_PROJECTILE` | `1<<2` | static, vehicle, prop |
| `LAYER_DEBRIS` | `1<<3` | static, prop, debris, vehicle(*) |
| `LAYER_PROP` | `1<<4` | static, vehicle, projectile, debris, prop |
| `LAYER_TRIGGER` | `1<<5` | vehicle |
| `LAYER_SENSOR_RAY` | `1<<6` | static, vehicle, prop (ray tests only) |

```pseudo
MASKS:
  STATIC     = VEHICLE | PROJECTILE | DEBRIS | PROP
  VEHICLE    = STATIC | VEHICLE | PROJECTILE | PROP | DEBRIS | TRIGGER
  PROJECTILE = STATIC | VEHICLE | PROP                    # projectiles pass through debris
  DEBRIS     = STATIC | PROP | DEBRIS | VEHICLE
  PROP       = STATIC | VEHICLE | PROJECTILE | DEBRIS | PROP
  TRIGGER    = VEHICLE
  SENSOR_RAY = STATIC | VEHICLE | PROP
```

**R10.** (*) Debris–vehicle collisions are enabled but produce **no damage** (D07-S5.2 rejects `COLLISION` damage from `LAYER_DEBRIS` sources). Debris bouncing off a vehicle is a visual and physical event, never a gameplay one — otherwise a player could be killed by their own scrap.

**R11.** Projectiles ignore debris deliberately: a cloud of shards must not act as spaced armour, which would make outcomes depend on cosmetic-looking clutter.

<!-- D06-S4.5 -->### 4.5 Vehicle Tuning Parameters

**R12.** `btRaycastVehicle.btVehicleTuning` and per-wheel parameters. Values are per-wheel content (D05-S4.5); these are the reference chassis defaults.

| Parameter | Default | Meaning |
|---|---|---|
| `suspensionStiffness` | 30.0 | Higher = stiffer; ~20 soft, ~50 race |
| `suspensionCompression` | 2.4 | Damping while compressing |
| `suspensionDamping` | 2.3 | Damping while relaxing |
| `maxSuspensionTravelCm` | 25.0 | Travel limit |
| `maxSuspensionForce` | 15000 N | Cap per wheel |
| `frictionSlip` | 2.0 | Tyre grip; degraded per D05-S5.4 |
| `rollInfluence` | 0.15 | 0 = no roll from lateral force, 1 = full; low keeps heavy vehicles from flipping constantly |
| `wheelRadiusM` | 0.42 | From the wheel part |
| `suspensionRestLengthM` | 0.30 | |
| `wheelDirectionLocal` | (0, −1, 0) | Down |
| `wheelAxleLocal` | (−1, 0, 0) | Left |
| `downforceCoefficient` | 6.0 | N per (m/s)²; applied at the COM (D01-S5.2 mild assist) |

---

<!-- D06-S5 -->## 5. Logic & Algorithms

<!-- D06-S5.1 -->### 5.1 World Construction

```pseudo
function PhysicsWorld.create(config):
    # OWNER: PhysicsWorld owns every object allocated here (D02-S5.7).
    collisionConfig = new btDefaultCollisionConfiguration()
    dispatcher      = new btCollisionDispatcher(collisionConfig)
    broadphase      = new btDbvtBroadphase()
    solver          = new btSequentialImpulseConstraintSolver()
    world           = new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig)

    world.setGravity(WORLD_GRAVITY)
    info = world.getSolverInfo()
    info.numIterations = 10
    info.splitImpulse = true; info.splitImpulsePenetrationThreshold = -0.02
    info.solverMode = SOLVER_USE_WARMSTARTING | SOLVER_SIMD

    # Deterministic contact ordering: Bullet's manifold order follows broadphase pair
    # order, which is insertion-dependent. We never iterate manifolds directly for
    # gameplay; contacts are collected, then SORTED before use (D06-S5.6). This is the
    # single most important determinism decision in the physics layer (G3).
    world.setInternalTickCallback(collectContacts, isPreTick = false)

    if config.debugDraw: world.setDebugDrawer(new GdxDebugDrawer())
    return new PhysicsWorld(world, dispatcher, broadphase, solver, collisionConfig)
```

<!-- D06-S5.2 -->### 5.2 Collision Shape Construction and Caching

```pseudo
function ShapeCache.hullFor(assetId, meshData, maxVertices):
    key = (assetId, maxVertices)
    if cache.contains(key): return cache[key]

    raw = new btConvexHullShape()
    for v in meshData.vertices: raw.addPoint(v, /*recalcAABB*/ false)
    raw.recalcLocalAabb()

    if meshData.vertexCount > maxVertices:
        # btShapeHull simplifies to a bounded vertex count.
        hullUtil  = new btShapeHull(raw)
        hullUtil.buildHull(raw.getMargin())
        simplified = new btConvexHullShape()
        for v in hullUtil.getVertexPointer(): simplified.addPoint(v, false)
        simplified.recalcLocalAabb()
        raw.dispose()                       # the intermediate is not cached
        shape = simplified
    else:
        shape = raw

    shape.setMargin(0.01)                   # 1 cm; Bullet's default 0.04 is too fat for
                                            # 10 cm-thick armour plates and causes visible
                                            # floating (a classic gdx-bullet gotcha)
    assert shapeVolume(shape) > 0
    cache[key] = shape                      # OWNER: ShapeCache
    return shape
```

**R13.** Collision margin is **0.01 m** for all convex shapes. Bullet's default (0.04 m) is comparable to the thickness of thin armour plates and produces both visible gaps and incorrect mass-to-contact relationships. Reducing it below 0.005 m degrades solver stability, so 0.01 is the floor.

<!-- D06-S5.3 -->### 5.3 Vehicle Compound Shape

```pseudo
function buildVehicleCompound(assembly):
    compound = new btCompoundShape(/*enableDynamicAabbTree*/ true)
    childIndexBySlotPath = {}

    # Iterate in slotPath order so the child index assignment is deterministic (G3).
    parts = assembly.parts.filter(p -> p.category != wheel).sortedBy(slotPath)
    for part in parts:
        hull      = ShapeCache.hullFor(part.typeId, part.collisionMesh, MAX_HULL_VERTICES)
        transform = worldTransformOfSlotChain(part)       # accumulated slot transforms
        compound.addChildShape(transform, hull)
        childIndexBySlotPath[part.slotPath] = compound.getNumChildShapes() - 1

    return (compound, childIndexBySlotPath)

# R14. Child indices are POSITIONAL and shift on removal, exactly like wheel indices
#      (D05-R24). removeChildShapeByIndex() moves the LAST child into the removed slot.
#      Therefore: after any removal, rebuild childIndexBySlotPath from the compound's
#      current children. Never cache a child index across a structural change.
function removeCompoundChild(vehicle, slotPath):
    idx = vehicle.childIndexBySlotPath[slotPath]
    vehicle.compound.removeChildShapeByIndex(idx)          # swaps last into idx
    rebuildChildIndexMap(vehicle)                          # mandatory
    vehicle.compound.recalculateLocalAabb()
```

<!-- D06-S5.4 -->### 5.4 Timestep and Fixed Update

```pseudo
# The accumulator lives in the runtime loop (D03-S5.3 / D03-S5.4). PhysicsSystem is
# called once per tick with exactly TICK_DT and never with anything else.

function PhysicsSystem.update(world, dtSeconds, tick):
    assert dtSeconds == TICK_DT : "physics must never receive a variable dt (G2)"

    # 1. Push component state into Bullet (forces, vehicle controls already applied by
    #    VehicleControlSystem in slot 7).
    applyPendingImpulses(world)

    # 2. Step. maxSubSteps = 0 means: do EXACTLY ONE step of the given size and do no
    #    interpolation. This is what we want, because our own accumulator already
    #    guarantees fixed steps; letting Bullet also sub-step would produce
    #    double-fixed-stepping and non-reproducible substep counts.
    world.physics.stepSimulation(TICK_DT, /*maxSubSteps*/ 0, /*fixedTimeStep*/ TICK_DT)

    # 3. Pull Bullet state back into components, in ascending EntityId order (G3).
    for entity in world.family(RigidBody, Transform).iterate():     # sorted by id
        body = entity.RigidBody.body
        body.getWorldTransform(scratchMatrix)
        entity.Transform.position.set(scratchMatrix.translation)
        entity.Transform.rotation.set(scratchMatrix.rotation)
        entity.Velocity.linear.set(body.getLinearVelocity())
        entity.Velocity.angular.set(body.getAngularVelocity())
        assertAllFinite(entity.Transform, entity.Velocity)          # D00-R13

# R15. maxSubSteps is 0, not MAX_SUBSTEPS. MAX_SUBSTEPS (D00-S6.4) is the CATCH-UP
#      cap enforced by the runtime loop's accumulator (D03-S5.3), which limits how many
#      times PhysicsSystem is called in one frame. It is not passed to Bullet.
#      Conflating the two produces variable-length effective steps and breaks G2.
# R16. Bullet's own interpolation (which maxSubSteps > 0 enables) must stay off:
#      it writes interpolated transforms into motion states, which would make the
#      transforms we read back depend on frame timing.
```

**R17.** Ordering within a tick is fixed by the system schedule (D04-S4.4). Within `PhysicsSystem`, every loop iterates a sorted family. There is no iteration over a `HashMap`, a Bullet-internal array, or an insertion-ordered list anywhere in the gameplay path.

<!-- D06-S5.5 -->### 5.5 Vehicle Physics Model

**R18.** Vehicles use **`btRaycastVehicle`** (ray-cast wheels on a single rigid body), not rigid-body wheels with constraints.

| Option | Verdict | Reasoning |
|---|---|---|
| **`btRaycastVehicle`** | **CHOSEN** | One body per vehicle plus N ray casts is fast and, crucially, *stable*: no wheel-to-chassis constraint can explode when the chassis mass and inertia change abruptly at detachment (which happens constantly in this game). Handles high speed without tunnelling because the wheel is a ray, not a body. Standard, well-understood tuning parameters. Directly supported by gdx-bullet. |
| Rigid-body wheels + `btHinge2Constraint` | Rejected | More physically faithful, and genuinely better for wheels that must be knocked off and roll away — but every mass/COM change requires re-tuning constraint parameters, and heavy vehicles with many constraints jitter under Bullet's sequential-impulse solver. The instability appears exactly when the game is most interesting (mid-destruction). |
| Custom tyre model on a rigid body | Rejected for v1 | Best feel ceiling, highest cost, and would need its own determinism story. Revisit only with a `decisions/` memory entry. |

**R19.** A destroyed/detached wheel is removed from the vehicle controller and spawned as a **debris rigid body** with the wheel's hull, so it visibly bounces away. This recovers the main visual benefit of rigid-body wheels without their cost.

```pseudo
function VehicleControlSystem.update(world, dt, tick):
    for vehicle in world.family(VehicleChassis, PlayerInput, VehicleStats).iterate():
        input = vehicle.PlayerInput; stats = vehicle.VehicleStats
        ctrl  = vehicle.VehicleChassis.vehicleController

        # Steering: rate-limited toward the target so input is smoothed identically
        # on client and server (prediction correctness, D10-S5.5).
        targetSteer  = input.steer * stats.maxSteerRad
        maxDelta     = stats.steerRateRadPerSec * dt
        currentSteer = moveToward(currentSteer, targetSteer, maxDelta)

        driveWheels = wheelsWhere(isDriven and not destroyed)
        forcePerWheel = (input.throttle * stats.engineForceN) / max(driveWheels.size, 1)

        for w in vehicle.wheels:
            if w.destroyed: continue
            if w.isSteering: ctrl.setSteeringValue(currentSteer, w.wheelIndex)
            if w.isDriven:   ctrl.applyEngineForce(forcePerWheel, w.wheelIndex)
            ctrl.setBrake(input.brake * stats.brakeForceN / vehicle.wheels.size, w.wheelIndex)
            # Per-wheel friction reflects that wheel's own degradation (D05-S5.4).
            ctrl.getWheelInfo(w.wheelIndex).frictionSlip = w.effectiveStats.frictionSlip

        # Mild downforce keeps heavy vehicles planted at speed (D01-S5.2). Applied at
        # the COM so it cannot induce a torque.
        speed = magnitude(vehicle.velocity.linear)
        vehicle.body.applyCentralForce(DOWN * downforceCoefficient * speed * speed)

        # Anti-tunnelling: the ray-cast vehicle itself cannot tunnel, but the chassis
        # body can. CCD is on (D06-S4.2) and the motion clamp below is a second net.
        if speed > MAX_VEHICLE_SPEED_MPS (40):
            vehicle.body.setLinearVelocity(normalize(v) * MAX_VEHICLE_SPEED_MPS)
```

<!-- D06-S5.6 -->### 5.6 Constraints and Breakable Attachments

**R20.** Attached parts are **not** separate bodies (D05-R3); they are children of the compound shape. Therefore, in the normal case, **no constraint exists** for an attached part. This is deliberate: constraints are the least stable part of any Bullet setup, and this design removes them from the common path entirely.

**R21.** Constraints are used in exactly two situations:

| Situation | Constraint | Behaviour |
|---|---|---|
| **Trailing / articulated parts** (authored `isArticulated = true`: a towed trailer, a hanging chain) | `btGeneric6DofSpring2Constraint` with a break impulse | Simulated as a separate body joined to the chassis |
| **Partially detached part** (`DESTROYED` but not yet detached, hanging by a thread for visual drama) | `btFixedConstraint` (short-lived, ≤ `HANGING_TICKS` = 60) with break impulse | Breaks under load, then becomes debris |

```pseudo
function attachBreakable(world, parentBody, childBody, localA, localB, breakImpulseN):
    c = new btFixedConstraint(parentBody, childBody, localA, localB)
    c.setBreakingImpulseThreshold(breakImpulseN)
    c.setOverrideNumSolverIterations(20)            # stiffer than the world default
    world.addConstraint(c, /*disableCollisionsBetweenLinkedBodies*/ true)
    return c                                        # OWNER: PhysicsWorld

function checkConstraintBreaks(world, tick):
    # Bullet sets isEnabled() = false when the accumulated impulse exceeds the
    # threshold. It does NOT remove or dispose the constraint — we must.
    for c in world.constraints.sortedBy(constraintId):        # deterministic (G3)
        if not c.isEnabled():
            emit ConstraintBrokeEvent(c.entityA, c.entityB, tick)
            world.removeConstraint(c); c.dispose()            # G19
            markDetached(c.childEntity)                        # D07-S5.7

# R22. The breaking threshold is an IMPULSE (N·s), not a force. Content authors think
#      in forces; the part definition's breakImpulseN is documented in N·s and the
#      asset pipeline validates the unit suffix. Confusing the two by a factor of
#      TICK_DT (60x) is the single most likely bug here (D14 PROG-013 catches it).
```

<!-- D06-S5.7 -->### 5.7 Mass Properties

```pseudo
# Mass comes from geometry and material, never from a magic number.
function computeMass(meshVolumeM3, materialId):
    density = MaterialTable.densityKgPerM3(materialId)         # D09-S6.3
    return meshVolumeM3 * density

function MassPropertySystem.recompute(vehicle):
    # Runs in POST_SIM slot 15 whenever structuralVersion changed (G10). Never during
    # the physics step.

    # 1. Total mass and COM in chassis-local space, weighted by part mass.
    totalMass = 0; weighted = ZERO_VEC
    for part in vehicle.liveParts.sortedBy(slotPath):          # deterministic sum (G3)
        localCom  = slotChainTransform(part).transform(part.type.comLocal)
        weighted += localCom * part.massKg
        totalMass += part.massKg
    assert totalMass > MIN_BODY_MASS_KG
    comLocal = weighted / totalMass

    # 2. Recentre the compound so the body's origin coincides with the COM.
    #    Bullet treats a compound's local origin as the COM. If we do not recentre,
    #    the vehicle rotates about its mesh origin instead of its true COM, which is
    #    the classic "car pivots around its nose" bug.
    delta = comLocal - vehicle.previousComLocal
    if magnitude(delta) > 1e-6:
        for i in 0 .. compound.getNumChildShapes()-1:
            t = compound.getChildTransform(i); t.translation -= delta
            compound.updateChildTransform(i, t, /*shouldRecalculateLocalAabb*/ false)
        compound.recalculateLocalAabb()
        # Keep the body in the same world place despite the origin shift.
        worldTransform = body.getWorldTransform()
        worldTransform.translate(rotate(delta, worldTransform.rotation))
        body.setWorldTransform(worldTransform)
        body.getMotionState().setWorldTransform(worldTransform)
        vehicle.previousComLocal = comLocal

    # 3. Inertia from the (recentred) compound at the new mass.
    inertia = ZERO_VEC
    compound.calculateLocalInertia(totalMass, inertia)
    body.setMassProps(totalMass, inertia)
    body.updateInertiaTensor()
    body.activate(true)                                        # never leave it asleep
                                                               # with stale mass props

    # 4. Vehicle controller must see the new mass too.
    vehicle.VehicleChassis.totalMassKg = totalMass
    vehicle.VehicleChassis.comLocal    = comLocal
    vehicle.VehicleStats.dirty         = true                  # derived stats change

# R23. Velocity is NOT touched here (D05-R23). Changing mass while preserving velocity
#      changes momentum — that is physically correct for mass that LEAVES the body,
#      because the leaving mass carries its momentum away with it as a debris body.
# R24. calculateLocalInertia on a btCompoundShape approximates using child AABBs.
#      That approximation is acceptable for vehicles (verified by D14 VEH-002/PHYS-004
#      tolerances) but NOT for single parts under verification, where the harness
#      computes the tensor from mesh geometry (D14-S5.4).
```

<!-- D06-S5.8 -->### 5.8 Determinism and Seeded Randomness

```pseudo
class RandomSource:
    long   matchSeed
    map<StreamId, Pcg32> streams        # one independent stream per subsystem

    function stream(id):
        if not streams.contains(id):
            streams[id] = new Pcg32(seed = mix(matchSeed, hash(id)), sequence = hash(id))
        return streams[id]

STREAMS (fixed list; adding one requires updating this table):
    DAMAGE_SPREAD      weapon spread cones
    FRACTURE_SCATTER   shard scatter jitter at fracture (authoritative, D07-S5.6)
    BOT_DECISION       bot reaction jitter and target choice
    SPAWN_SELECT       spawn point selection
    MATCH_MISC         everything else gameplay-relevant

# R25. A subsystem draws only from its own stream. Sharing a stream couples unrelated
#      systems: adding one bot decision would shift every weapon's spread, and a
#      determinism regression test would fail for reasons unrelated to the change.
# R26. Cosmetic randomness (particle jitter, debris sparks, audio variation) uses a
#      SEPARATE client-local unseeded RNG and MUST NOT write to any replicated field
#      (G4, G6). A CI check asserts that no cosmetic RNG call appears in game-core.
# R27. No system reads System.nanoTime()/currentTimeMillis() for simulation purposes
#      (G5). Profiling timers are exempt and are the only permitted callers.

DETERMINISM GUARANTEES (and non-guarantees):
    GUARANTEED: same build + same platform + same seed + same input sequence
                => identical simulation state at every tick, within DETERMINISM_POS_M
                   for Bullet-derived values and bit-identical for everything else.
    NOT GUARANTEED: cross-platform or cross-Bullet-version bit equality. Native Bullet
                uses SIMD paths and compiler-dependent floating point ordering (D02-R4).
    CONSEQUENCE: lockstep networking is not viable; the game is state-replicated
                (D10-S5.1). Regression tests assert tolerances and pin the tight tier
                to the CI platform (D12-S5.2).
```

<!-- D06-S5.9 -->### 5.9 Projectiles

```pseudo
# Two projectile implementations, chosen per weapon family (D01-S4.4).
#
# HITSCAN (SHOTGUN pellets, LASER): a ray test, resolved in the same tick as the shot.
function fireHitscan(world, origin, direction, maxRange, shooter):
    cb = new ClosestNotMeRayResultCallback(shooter.body)
    cb.setCollisionFilterGroup(LAYER_SENSOR_RAY)
    cb.setCollisionFilterMask(MASK_SENSOR_RAY)
    world.physics.rayTest(origin, origin + direction * maxRange, cb)
    if cb.hasHit(): emit HitEvent(cb.collisionObject, cb.hitPointWorld, cb.hitNormalWorld)

# BALLISTIC (AUTOCANNON, CANNON, ROCKET, MORTAR): integrated manually rather than as
# Bullet bodies. Rationale: hundreds of small fast bodies are expensive and need CCD;
# a swept ray per tick is cheaper, cannot tunnel, and is trivially lag-compensatable
# (D10-S5.6).
function ProjectileSystem.update(world, dt, tick):
    for p in world.family(BallisticMotion, Transform, ProjectileStats).iterate():
        previous = p.Transform.position
        p.BallisticMotion.velocity += WORLD_GRAVITY * p.gravityScale * dt
        p.BallisticMotion.velocity *= (1 - p.dragCoefficient * dt)
        next = previous + p.BallisticMotion.velocity * dt

        cb = new ClosestNotMeConvexResultCallback(p.Owner.entity)
        world.physics.rayTest(previous, next, cb)              # swept segment; no tunnel
        if cb.hasHit():
            emit HitEvent(...); world.destroyEntity(p.entity)
        else:
            p.Transform.position = next
            p.Lifetime.remainingS -= dt
            if p.Lifetime.remainingS <= 0: world.destroyEntity(p.entity)
```

<!-- D06-S5.10 -->### 5.10 Debris Physics and Budget

```pseudo
function spawnDebris(world, meshHull, massKg, transform, linearVel, angularVel, lifetimeS):
    if world.debrisCount >= MAX_DEBRIS_BODIES:
        recycleOldestDebris(world)                 # despawn the oldest, never refuse
    body = createBody(mass = massKg, shape = meshHull, transform = transform,
                      layer = LAYER_DEBRIS, mask = MASK_DEBRIS,
                      friction = 0.5, restitution = 0.15,
                      linearDamping = 0.10, angularDamping = 0.20,
                      ccd = false, sleepingEnabled = true)
    body.setLinearVelocity(clampMagnitude(linearVel, MAX_SCATTER_SPEED_MPS))
    body.setAngularVelocity(clampMagnitude(angularVel, MAX_ANGULAR_SPEED_RADPS = 30))
    entity = world.createEntity()
    add RigidBodyComponent(body); add DebrisTagComponent(spawnTick = world.tick)
    add LifetimeComponent(remainingS = lifetimeS, despawnPolicy = SLEEP_THEN_DESTROY)
    world.debrisCount += 1
    return entity

# R28. MAX_DEBRIS_BODIES (256) is a HARD cap. Exceeding it recycles the oldest rather
#      than refusing to spawn, so the newest, most gameplay-relevant destruction is
#      always shown.
# R29. SLEEP_THEN_DESTROY: a debris body that has slept for SLEEP_DESPAWN_S = 3 s is
#      destroyed early even if its lifetime remains. Piles of sleeping scrap cost
#      broadphase work and add nothing.
# R30. Debris velocity is clamped at spawn, not asserted. A shard that would exceed the
#      clamp is a symptom (usually overlapping spawn positions), and the harness's
#      PROG-009 check reports it; at runtime, clamping keeps the game playable.
```

---

<!-- D06-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D06-1.** `PhysicsSystem` receives exactly `TICK_DT` on every call; an assertion fires otherwise.
- [ ] **AC-D06-2.** `stepSimulation` is called with `maxSubSteps = 0` and `fixedTimeStep = TICK_DT` (grep + test).
- [ ] **AC-D06-3.** Every dynamic body has `mass > MIN_BODY_MASS_KG`; every static body has `mass == 0`.
- [ ] **AC-D06-4.** Every convex shape has margin 0.01.
- [ ] **AC-D06-5.** Collision layers and masks match D06-S4.4; a projectile never collides with debris; debris–vehicle contacts never produce damage.
- [ ] **AC-D06-6.** Vehicles use `btRaycastVehicle`; no `btHinge2Constraint` exists in vehicle construction.
- [ ] **AC-D06-7.** After any structural change, the compound child index map is rebuilt and matches the compound's actual children.
- [ ] **AC-D06-8.** After any structural change, mass, COM, and inertia are recomputed in the same tick, before the next step (G10).
- [ ] **AC-D06-9.** The compound is recentred on the COM; a vehicle rotates about its COM, not its mesh origin.
- [ ] **AC-D06-10.** Detachment does not modify the vehicle body's linear or angular velocity.
- [ ] **AC-D06-11.** Constraint break thresholds are interpreted as impulses (N·s); PROG-013/014 pass.
- [ ] **AC-D06-12.** Broken constraints are removed and disposed in the same tick they break.
- [ ] **AC-D06-13.** No gameplay code iterates an unsorted collection; all family iteration is by ascending `EntityId` and all cross-part sums are by `slotPath` order.
- [ ] **AC-D06-14.** Two runs of the same seeded scenario on the same build agree within `DETERMINISM_POS_M`.
- [ ] **AC-D06-15.** No simulation code reads wall-clock time (G5).
- [ ] **AC-D06-16.** Cosmetic RNG does not exist in `game-core` (CI check).
- [ ] **AC-D06-17.** Debris count never exceeds `MAX_DEBRIS_BODIES`; slept debris despawns after `SLEEP_DESPAWN_S`.
- [ ] **AC-D06-18.** Vehicle chassis bodies never sleep.
- [ ] **AC-D06-19.** Projectiles never tunnel through a 0.05 m plate at 600 m/s.
- [ ] **AC-D06-20.** `NativeResourceTracker.outstanding() == 0` after world disposal, including after 1000 spawn/destroy cycles.

---

<!-- D06-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | A shard's computed mass < `MIN_BODY_MASS_KG` | Fixed in tooling by merging (D09-S6.2). At runtime, refuse to spawn that shard and log at ERROR — never clamp mass, which would silently violate G7. |
| E2 | NaN appears in a body transform | Detected in the state pull-back (D06-S5.4). Response: log tick/entity, remove the body from the world, destroy the entity, increment `physics.nan` metric. Never let NaN propagate — one NaN body corrupts the whole solver within a few ticks. |
| E3 | Vehicle COM moves outside the compound's AABB | Legal (a vehicle can be lopsided) but flagged at WARN; usually indicates a mis-authored slot transform. |
| E4 | All parts removed except the chassis | Normal. Mass properties recompute from the chassis alone. |
| E5 | Compound child index used after a structural change | Prohibited (D06-R14). Debug builds store a `structuralVersion` alongside cached indices and assert it matches. |
| E6 | Constraint breaks in the same tick it is created | Legal; the break check runs after the step. |
| E7 | Two vehicles interpenetrate at spawn | Spawn points are separated by `MIN_SPAWN_SEPARATION_M = 8`; if a spawn is occupied, pick the next candidate deterministically from the `SPAWN_SELECT` stream. |
| E8 | Body tunnels despite CCD | CCD radius must be ≤ half the smallest extent; asserted at body creation. Log at WARN if the shape is too small for its speed. |
| E9 | `stepSimulation` throws from native code | Fatal: attempt clean shutdown, exit 70. A partially stepped world is not recoverable. |
| E10 | Debris budget exceeded during a mass destruction event | Oldest debris recycled; no allocation failure, no refusal. |
| E11 | Ray test with zero-length segment (projectile at rest) | Skip the test; treat as no hit. |
| E12 | Wheel ray finds no ground (airborne) | Suspension applies no force; `frictionSlip` contributes nothing. Vehicle is ballistic. Correct and intended. |
| E13 | `calculateLocalInertia` returns a zero component | Indicates a degenerate compound (all children coplanar). Substitute a small epsilon inertia and log at ERROR; a zero inertia component makes the body infinitely easy to spin. |
| E14 | Solver instability (jitter) with many stacked debris | Debris sleeps; `MAX_DEBRIS_BODIES` bounds the stack. If it persists, raise solver iterations for the debris island and record a `discoveries/` entry. |
| E15 | A part's collision hull is inverted (winding reversed) | Convex hull construction is winding-independent, so this cannot happen for hulls; the harness's ASSET-012 still checks enclosure. |
| E16 | Physics world disposed while a body reference is held | Prevented by the disposal order in D02-S5.7; debug tracker asserts. |
| E17 | Vehicle exceeds `MAX_VEHICLE_SPEED_MPS` | Velocity clamped in `VehicleControlSystem`; logged once per vehicle per match at DEBUG. |
| E18 | A body is added to the world twice | gdx-bullet does not guard this; debug builds keep a membership set and assert. |

---

<!-- D06-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D06-1 | Call `PhysicsSystem.update` with `dt = 0.02` | Assertion fires |
| T-D06-2 | Drop a 1 m³ steel cube from 2 m | Rests at y = 0.5 ± 0.005 m, penetration ≤ 0.01 m, jitter ≤ 0.01 m/s |
| T-D06-3 | Apply 100 N·s impulse to a 10 kg body | Δv = 10 m/s ± 5% |
| T-D06-4 | Apply 50 N·m torque for 0.5 s | ω = τt/I ± 8% |
| T-D06-5 | Run a 600-tick vehicle scenario twice with seed 1337 | Final transforms agree within 0.001 m |
| T-D06-6 | Detach a 340 kg plate from a 1600 kg vehicle | Mass 1260 kg, COM shift matches analytic, Δv ≤ 1 m/s |
| T-D06-7 | Detach every non-chassis part in random orders (50 permutations) | Compound child map correct each time; no crash; final mass equals chassis mass |
| T-D06-8 | Remove wheel index 1 of 4, then apply throttle | Remaining wheels driven; no index off-by-one |
| T-D06-9 | Fire a 600 m/s projectile at a 0.05 m plate | Hit registered; no tunnelling in 10,000 trials |
| T-D06-10 | Ramp force on a `btFixedConstraint` with 4000 N·s threshold | Breaks at 4000 ± 15%; removed and disposed in the same tick |
| T-D06-11 | Hold 3200 N·s for 2 s | No break |
| T-D06-12 | Spawn 300 debris bodies | Count capped at 256; oldest recycled; no allocation spike |
| T-D06-13 | Let debris settle | Sleeps within ~2 s; despawns 3 s later |
| T-D06-14 | Inject NaN into a body's velocity | Body removed, entity destroyed, metric incremented, world remains sane for 600 further ticks |
| T-D06-15 | Vehicle rotation test: apply yaw torque | Rotates about the COM marker, not the mesh origin |
| T-D06-16 | Compare `frictionSlip` on a 50%-health wheel | Equals the D05-S5.4 value |
| T-D06-17 | Create and dispose the physics world 1000 times | Zero outstanding natives, stable RSS |
| T-D06-18 | Set solver to parallel | Determinism test T-D06-5 fails — documents why the sequential solver is mandated |
| T-D06-19 | Vehicle at 40 m/s driving into a wall | No tunnelling; CCD engages |
| T-D06-20 | Compound with all children coplanar | Zero inertia component detected, epsilon substituted, ERROR logged |

---

<!-- D06-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Coordinate system and units | `docs/00_master_index.md#D00-S4.3`, `#D00-S4.4` |
| Physics-related constants | `docs/00_master_index.md#D00-S6.4` |
| Movement feel targets | `docs/01_product_game_design.md#D01-S5.2` |
| gdx-bullet choice and `Bullet.init` rules | `docs/02_technical_architecture.md#D02-S4.2` |
| Native ownership and disposal order | `docs/02_technical_architecture.md#D02-S5.7` |
| Runtime loop accumulator | `docs/03_runtime_modes.md#D03-S5.3`, `#D03-S5.4` |
| `PhysicsSystem` / `MassPropertySystem` scheduling | `docs/04_entity_component_model.md#D04-S4.4` |
| `RigidBodyComponent` fields | `docs/04_entity_component_model.md#D04-S4.3` |
| Compound shape membership rules | `docs/05_vehicle_part_system.md#D05-S4.1` |
| Wheel index remapping | `docs/05_vehicle_part_system.md#D05-S5.5` |
| Degraded wheel friction | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Fracture shard spawning | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Detachment sequence | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Debris lifetime policy | `docs/07_damage_destruction_model.md#D07-S5.8` |
| Material densities | `docs/09_blender_destruction_tool.md#D09-S6.3` |
| Hull generation in tooling | `docs/09_blender_destruction_tool.md#D09-S5.5` |
| State replication of physics | `docs/10_networking_multiplayer.md#D10-S4.3` |
| Lag compensation rewind | `docs/10_networking_multiplayer.md#D10-S5.6` |
| Deterministic physics regression tests | `docs/12_testing_validation_ci.md#D12-S5.2` |
| Harness physics checks | `docs/14_test_environment.md#D14-S5.5` |
| Tolerance rationale | `docs/14_test_environment.md#D14-S6.4` |

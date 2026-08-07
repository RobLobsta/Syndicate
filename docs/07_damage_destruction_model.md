<!-- D07-S0 --># 07 — Damage and Destruction Model

**Document ID:** D07
**Owns:** Authoritative/cosmetic classification, damage types and armour, hit resolution, propagation, damage state machine, shape key mapping, fracture, detachment, debris lifetime, destruction replication.

---

<!-- D07-S1 -->## 1. Purpose

This document specifies the complete damage pipeline: how a hit becomes damage to a specific part, how armour and damage type modify it, how damage spreads through the slot graph, how health drives both discrete damage states and continuous visual deformation, when and how a part fractures into shards, when a part detaches, how long debris lives, and precisely which of all of that is authoritative (replicated, gameplay-relevant) versus cosmetic (client-local).

The authoritative/cosmetic split (D07-S4.2) is the most important table in this document. Every ambiguity about "does the client decide this?" is answered there.

Requirements are numbered `R1..Rn`, cited as `D07-R18`.

---

<!-- D07-S2 -->## 2. Scope

<!-- D07-S2.1 -->### 2.1 In Scope

- The authoritative vs cosmetic classification of every destruction effect.
- Damage types, armour interaction, positional modifiers.
- Hit resolution: contact point → part.
- Damage propagation across the slot graph.
- Health → damage state machine.
- Health → shape key weight interpolation.
- Fracture triggering and shard body spawning with momentum inheritance.
- Detachment, joint breaking, scatter velocity.
- Debris lifetime and budget.
- What destruction state is replicated, how, and how often.

<!-- D07-S2.2 -->### 2.2 Non-Goals

- **NG1.** Degradation of stats from health — `docs/05_vehicle_part_system.md#D05-S5.4`.
- **NG2.** Bullet body/shape mechanics — `docs/06_physics_simulation.md#D06-S5`.
- **NG3.** Generation of shards and morph targets — `docs/09_blender_destruction_tool.md#D09-S5`.
- **NG4.** Wire encoding details — `docs/10_networking_multiplayer.md#D10-S4.4`; this document says *what* is replicated, D10 says *how*.
- **NG5.** Weapon balance numbers — content, per `docs/01_product_game_design.md#D01-S4.4`.
- **NG6.** Runtime mesh cutting or procedural fracture. All fracture is pre-authored (D09).

---

<!-- D07-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | G6 (auth/cosmetic split), G7 (mass conservation), G8/G9 (monotonic, one-way) |
| `docs/01_product_game_design.md#D01-S4.5` | Damage type identities |
| `docs/01_product_game_design.md#D01-S4.6` | Hit zone modifiers |
| `docs/04_entity_component_model.md#D04-S4.4` | System slots 11–14 |
| `docs/05_vehicle_part_system.md#D05-S4.3` | Slot graph, coverage |
| `docs/05_vehicle_part_system.md#D05-S5.5` | Detachment's physics effects |
| `docs/06_physics_simulation.md#D06-S5.10` | Debris body creation |
| `docs/09_blender_destruction_tool.md#D09-S4.4` | Fracture manifest contents |

---

<!-- D07-S4 -->## 4. Data Contracts

<!-- D07-S4.1 -->### 4.1 Health Model

**R1.** Every part has `maxHp` (from its part type, modified by utility multipliers at spawn) and `currentHp ∈ [0, maxHp]`. `healthFraction = currentHp / maxHp`.

**R2.** Health is a float in abstract HP. It is not a mass, a volume, or a material property; those influence *armour* and *fracture*, not the pool size.

**R3.** Health never increases during a life (G8). There is no repair in v1 (D01-R13). The only reset is respawn, which creates a fresh vehicle entity.

<!-- D07-S4.2 -->### 4.2 Authoritative vs Cosmetic Classification

**R4.** This table is normative. Every destruction-related piece of state is in exactly one column. Nothing is in both.

| State / effect | Class | Owner | Replicated | Notes |
|---|---|---|---|---|
| `currentHp`, `healthFraction` | **Authoritative** | Authority | Yes (quantised, D10-S4.4) | Drives everything else |
| `DamageState.state` | **Authoritative** | Authority | Yes | The discrete state (D07-S5.3) |
| `DamageState.stateVersion` | Authoritative | Authority | Yes | Delta trigger |
| Which part a hit resolved to | Authoritative | Authority | Yes (in the damage event) | G15: the client's belief is advisory |
| Applied damage amount and type | Authoritative | Authority | Yes (to the involved players) | Feeds the damage ledger |
| Armour mitigation result | Authoritative | Authority | No (derivable) | |
| Propagation results | Authoritative | Authority | Yes (as health changes) | |
| **Fracture occurred** (yes/no, at which tick) | **Authoritative** | Authority | Yes | The *decision* is authoritative |
| **Shard identities and masses** | Authoritative | Manifest (static) | No (both sides have the asset) | Same asset ⇒ same shards |
| **Shard spawn transforms and velocities** | **Cosmetic** | Each client | **No** | See R5 |
| Detachment occurred (which slot path, which tick) | **Authoritative** | Authority | Yes | Changes vehicle physics |
| Detached part's subsequent trajectory | **Cosmetic** | Each client | No | It is debris; it affects nothing |
| Vehicle total mass, COM, inertia | Authoritative | Authority | Derived on both sides from the same structural state | Clients recompute; they do not receive the numbers |
| Shape key / morph weights | **Cosmetic** | Each client | **No** | Derived from replicated `healthFraction` (D07-S5.5) |
| Scorch, decals, char level | Cosmetic | Each client | No | |
| Sparks, smoke, fire particles | Cosmetic | Each client | No | |
| Debris–world collision sounds | Cosmetic | Each client | No | |
| Screen shake, hit markers | Cosmetic | Each client | No | Triggered by replicated events |
| Damage numbers on screen | Cosmetic presentation of authoritative data | Client | No | |
| Score and kill attribution | Authoritative | Authority | Yes | |

**R5. Why shard trajectories are cosmetic.** Debris deals no damage (D06-R10) and blocks no projectile (D06-R11), so its exact motion cannot change any outcome. Replicating 12–64 shard bodies per destroyed part, at 20 Hz, for every destruction in a 12-player match, would dominate bandwidth for zero gameplay value. Instead the authority replicates *"part X fractured at tick T with parent velocity V"*, and each client spawns the same shard set locally, seeded from `(matchSeed, entityId, tick)` so clients that care will look very similar without any guarantee that they are identical.

**R6. The rule that follows from R5:** a cosmetic value may be *derived from* authoritative state, but no authoritative value may ever be *derived from* a cosmetic one. Debris positions never feed back into damage, scoring, physics of vehicles, or bot decisions. A CI check asserts that no field classified cosmetic in this table is read by any authority-only system.

**R7.** Two clients may legitimately show shards in different places. They may never show a different *damage state*, a different part missing, or a different vehicle mass.

<!-- D07-S4.3 -->### 4.3 Damage Types and Modifiers

**R8.** Five `DamageType` values (D01-R9). Their numeric behaviour:

| Type | Armour formula | Propagation factor | Max hops | Special |
|---|---|---|---|---|
| `KINETIC` | `dmg − armor` (floored at `0.10 × dmg`) | 0.5 | 1 | Baseline |
| `EXPLOSIVE` | `dmg − 0.4 × armor`, then × falloff | 1.5 | 2 | Radial falloff `(1 − d/R)²`; hits all parts in radius |
| `INCENDIARY` | `dmg` (armour ignored) | 0.5 | 1 | Applies `BurnStack`; ticks 4 HP/s for 5 s; stacks to 5 |
| `ENERGY` | `dmg − 0.5 × armor` | 0.0 | 0 | Single part only; ramps up 1.0→1.4× over 1.5 s of continuous beam |
| `COLLISION` | `dmg − armor` (floored at `0.05 × dmg`) | 1.0 | 1 | `dmg` derived from relative momentum (D07-S5.2) |

**R9.** Positional modifiers (D01-R11) multiply the post-armour result: `REAR` ×1.35, `TOP` ×1.20, `EXPOSED` ×1.50. They are multiplicative with each other; a rear top hit on an exposed part is ×1.35 × 1.20 × 1.50 = ×2.43.

**R10.** Material modifiers: each material declares a per-damage-type multiplier (`materialResistance[damageType]`), applied before armour. Example: `steel_hardened` has 0.85 for `KINETIC` and 1.15 for `INCENDIARY`.

<!-- D07-S4.4 -->### 4.4 Damage Event Structure

```pseudo
record DamageEvent:                # authoritative, produced in POST_SIM slot 11 or by WeaponSystem
    EntityId    targetPart         # resolved by hit resolution (D07-S5.1)
    EntityId    attackerVehicle    # 0 for world damage
    EntityId    attackerPlayer     # 0 for world damage
    DamageType  type
    float       baseAmount         # HP before any modifier
    Vector3     hitPointWorld
    Vector3     hitNormalWorld
    TickNumber  tick
    int         sourceWeaponGroup  # for the ledger; -1 for collision
    boolean     isPropagated       # true for secondary damage; propagation does not re-propagate
    int         hopCount           # 0 for the direct hit
```

---

<!-- D07-S5 -->## 5. Logic & Algorithms

<!-- D07-S5.1 -->### 5.1 Hit Resolution (Contact → Part)

```pseudo
function resolveHitToPart(world, collisionObject, hitPointWorld, hitNormalWorld):
    # Vehicles are ONE body with a compound shape (D05-R3), so the contact carries the
    # compound CHILD INDEX, which maps to a slot path (D06-S5.3).
    vehicle = entityOf(collisionObject)
    if vehicle has no VehicleChassis: return entityOf(collisionObject)     # prop or debris

    childIndex = contact.childIndexOnThisObject          # Bullet provides this for compounds
    slotPath   = vehicle.slotPathByChildIndex[childIndex]
    if slotPath == null:
        # Defensive: index map stale (should be impossible, D06-R14). Fall back to the
        # nearest part centroid so a hit is never silently dropped.
        slotPath = nearestPartByCentroid(vehicle, hitPointWorld).slotPath
        log.error("stale compound child index; used centroid fallback")
    struckPart = vehicle.partAt(slotPath)

    # Coverage: armour intercepts hits aimed at what it covers (D05-R13).
    covering = vehicle.coverageMap[slotPath]
    if covering != null and covering.state not in {DESTROYED, DETACHED}:
        return (target = covering, exposed = false)
    return (target = struckPart, exposed = isExposed(vehicle, struckPart))

function positionalModifiers(vehicle, hitNormalWorld, target, exposed):
    m = 1.0
    forward = vehicle.transform.forwardAxis()            # -Z local (D00-R15)
    if angleBetween(hitNormalWorld, forward) < 60_deg: m *= 1.35     # REAR
    if angleBetween(hitNormalWorld, WORLD_UP)  < 45_deg: m *= 1.20   # TOP
    if exposed:                                          m *= 1.50   # EXPOSED
    return m
```

**R11.** Wheels are not compound children (D06-R6), so hits on wheels are resolved by a separate short ray/sphere test against wheel positions when the contact point is within `WHEEL_HIT_RADIUS = wheelRadius + 0.1 m` of a wheel's contact position. This is the one geometric special case; it is explicit rather than emergent.

<!-- D07-S5.2 -->### 5.2 Damage Application

```pseudo
function applyDamage(world, event):
    part = world.get(event.targetPart)
    if part == null or part.DamageState.state in {DESTROYED, DETACHED}:
        return 0                                    # damage to a dead part is wasted, by design

    # 0. Source filtering.
    if event.source.layer == LAYER_DEBRIS: return 0                   # D06-R10
    if not world.rules.friendlyFire and sameTeam(attacker, part.vehicle): return 0

    # 1. Material resistance.
    amount = event.baseAmount * MaterialTable.resistance(part.materialId, event.type)

    # 2. Damage-type armour interaction (D07-S4.3).
    armor = part.Health.armorValue * degradationMultiplier(...)      # already in effectiveStats
    switch event.type:
        case KINETIC:     amount = max(amount - armor,       0.10 * amount)
        case COLLISION:   amount = max(amount - armor,       0.05 * amount)
        case EXPLOSIVE:   amount = max(amount - 0.4 * armor, 0.10 * amount)
        case ENERGY:      amount = max(amount - 0.5 * armor, 0.15 * amount)
        case INCENDIARY:  pass                                        # ignores armour

    # 3. Positional modifiers (direct hits only; propagated damage has no geometry).
    if not event.isPropagated:
        amount *= positionalModifiers(part.vehicle, event.hitNormalWorld, part, exposed)

    # 4. Apply.
    before = part.Health.currentHp
    part.Health.currentHp        = max(0, before - amount)
    part.Health.healthFraction   = part.Health.currentHp / part.Health.maxHp
    part.Health.lastDamageTick   = event.tick
    part.Health.lastAttacker     = event.attackerPlayer
    part.PartStats.dirty         = true                # triggers degradation recompute (D05-S5.4)
    part.vehicle.VehicleStats.dirty = true

    ledger.record(event.attackerPlayer, part.vehicle, before - part.Health.currentHp, event.tick)

    # 5. State transition and propagation.
    updateDamageState(part, world.tick)                # D07-S5.3
    if not event.isPropagated and event.hopCount < PROPAGATION_MAX_DEPTH:
        propagate(world, part, amount, event)          # D07-S5.4
    return before - part.Health.currentHp

# Collision damage magnitude, from momentum rather than speed, so mass matters (P3).
function collisionDamage(vehicleA, vehicleB, contactImpulse):
    # contactImpulse is the solver's applied impulse magnitude (N·s) for this manifold.
    if contactImpulse < COLLISION_DAMAGE_THRESHOLD (1500 N·s): return 0
    return COLLISION_DAMAGE_SCALE (0.02) * (contactImpulse - COLLISION_DAMAGE_THRESHOLD)
    # A 1500 kg vehicle at 20 m/s stopping dead is 30,000 N·s -> 570 HP spread over the
    # struck parts. Threshold prevents scraping a wall from grinding a vehicle down.
```

**R12.** Damage to a `DESTROYED` or `DETACHED` part is discarded, not redirected. Redirecting would let a player keep hitting the same visual spot and kill a vehicle through an already-dead plate, which is unreadable (P1).

<!-- D07-S5.3 -->### 5.3 Damage State Machine

```pseudo
# States are ordered by severity; transitions are monotonic (G8).
SEVERITY: INTACT(0) < DAMAGED(1) < CRITICAL(2) < DESTROYED(3) < DETACHED(4)

function updateDamageState(part, tick):
    h   = part.Health.healthFraction
    old = part.DamageState.state

    new = old
    if      h <= 0.0:  new = DESTROYED
    else if h <= 0.33: new = CRITICAL
    else if h <= 0.66: new = DAMAGED
    else:              new = INTACT

    # Monotonic guard: never step back toward health, even if a bug raises HP (G8).
    if severity(new) < severity(old): new = old
    if old in {DESTROYED, DETACHED}:  new = old        # terminal (G9)

    if new != old:
        part.DamageState.state           = new
        part.DamageState.stateEnteredTick = tick
        part.DamageState.stateVersion    += 1          # drives delta replication (D10-S5.4)
        emit DamageStateChanged(part, old, new, tick)  # authoritative event

        if new == DESTROYED:
            emit PartDestroyed(part, tick)             # -> FractureSystem (slot 13),
                                                       #    ScoreSystem (slot 17),
                                                       #    DetachSystem (slot 14)
```

**R13.** State depends only on `healthFraction` and on terminal-state stickiness. It never depends on the damage type, the attacker, or elapsed time. This is what makes it verifiable in one place (D14 PROG-011).

<!-- D07-S5.4 -->### 5.4 Damage Propagation

```pseudo
# Damage transfers to slot-graph neighbours: a hit that shatters a mount stresses what
# is bolted to it. Propagation is a bounded breadth-first walk, not a recursion.

function propagate(world, sourcePart, appliedAmount, originalEvent):
    factor  = PROPAGATION_FRACTION * typeFactor(originalEvent.type)   # 0.20 * {0.5,1.5,...}
    if factor <= 0: return                                            # ENERGY does not propagate

    visited = {sourcePart}
    frontier = [(sourcePart, 0)]
    while frontier not empty:
        (current, hop) = frontier.removeFirst()
        if hop >= PROPAGATION_MAX_DEPTH: continue

        neighbours = slotGraphNeighbours(current)      # parent + direct children
                     .filter(n -> n not in visited
                                  and n.state not in {DESTROYED, DETACHED})
                     .sortedBy(slotPath)               # deterministic order (G3)

        for n in neighbours:
            visited.add(n)
            # Attenuate by hop: 20% at hop 1, 4% at hop 2 for KINETIC.
            transferred = appliedAmount * pow(factor, hop + 1)
            if transferred < MIN_PROPAGATED_DAMAGE (0.5 HP): continue   # cheap cut-off

            applyDamage(world, DamageEvent {
                targetPart    = n,
                attackerPlayer= originalEvent.attackerPlayer,
                type          = originalEvent.type,
                baseAmount    = transferred,
                isPropagated  = true,          # no positional modifiers, no re-propagation
                hopCount      = hop + 1,
                tick          = originalEvent.tick })
            frontier.addLast((n, hop + 1))

# R14. Propagated damage does NOT propagate again from its own application (isPropagated
#      guard in applyDamage). The BFS here is the single, bounded source of secondary
#      damage. Without this, a chain of 40 parts would produce an exponential cascade.
# R15. The visited set prevents a part being damaged twice by one event via two paths.
# R16. Propagation crosses the chassis. Chassis health is therefore chipped by every
#      part destruction near it, which is what makes a vehicle eventually die from
#      attrition rather than only from direct chassis fire.
```

<!-- D07-S5.5 -->### 5.5 Shape Key Integration

```pseudo
# Morph targets authored by the Blender tool (D09-S5.3): dmg_25, dmg_50, dmg_75, dmg_100,
# representing progressively worse deformation. At runtime we blend BETWEEN adjacent
# morphs so deformation is continuous while the authored states stay discrete.
#
# COSMETIC (D07-S4.2). Computed on clients from replicated healthFraction. The server
# does not compute or store morph weights at all (D03-R13 does not even load the geometry).

MORPH_HEALTH_POINTS = [1.00, 0.75, 0.50, 0.25, 0.00]      # health at which each level is pure
MORPH_NAMES         = [ none, "dmg_25", "dmg_50", "dmg_75", "dmg_100" ]

function morphWeightsForHealth(h):
    h = clamp(h, 0.0, 1.0)
    w = [0, 0, 0, 0]                                       # weights for dmg_25..dmg_100

    # Find the bracketing pair. Health descends across the array.
    for i in 0 .. 3:
        hi = MORPH_HEALTH_POINTS[i]                        # e.g. 1.00
        lo = MORPH_HEALTH_POINTS[i+1]                      # e.g. 0.75
        if h <= hi and h >= lo:
            t = (hi - h) / (hi - lo)                       # 0 at hi, 1 at lo
            if i > 0: w[i-1] = 1.0 - t                     # previous level fades out
            w[i] = t                                       # this level fades in
            break
    if h <= 0.0: w = [0, 0, 0, 1.0]                        # fully deformed at destruction
    return w

# Worked values (asserted by D14 PROG-002):
#   h = 1.00 -> [0.00, 0, 0, 0]
#   h = 0.875-> [0.50, 0, 0, 0]
#   h = 0.75 -> [1.00, 0, 0, 0]
#   h = 0.50 -> [0, 1.00, 0, 0]
#   h = 0.25 -> [0, 0, 1.00, 0]
#   h = 0.00 -> [0, 0, 0, 1.00]

function DamageVisualSystem.update(world, dt):             # client only, PRESENT phase
    for part in world.family(Health, DamageState, DamageVisual).iterate():
        target = morphWeightsForHealth(part.Health.healthFraction)
        # Smooth toward the target so a burst of damage deforms visibly rather than
        # snapping. Purely presentational; the target is the authoritative-derived value.
        for i in 0..3:
            part.DamageVisual.morphWeights[i] =
                moveToward(part.DamageVisual.morphWeights[i], target[i],
                           MORPH_LERP_RATE (4.0) * dt)
        applyMorphWeightsToModelInstance(part)              # gdx-gltf morph target weights

# R17. If a part has fewer than four morphs, missing levels are skipped and the weights
#      renormalise over the morphs that exist. A part with no morphs simply never
#      deforms; it still fractures. Asset validation warns but does not fail (D08-S5.4).
# R18. Morph weights are NEVER read by any gameplay system. A CI check enforces that
#      DamageVisualComponent is not referenced outside game-client (G6).
```

<!-- D07-S5.6 -->### 5.6 Fracture

```pseudo
function FractureSystem.update(world, dt, tick):           # slot 13, authority
    for part in world.family(DamageState, FractureData).iterate():
        if part.DamageState.state != DESTROYED: continue
        if part.FractureData.hasFractured: continue        # one-way (G9)
        fracturePart(world, part, tick)

function fracturePart(world, part, tick):
    manifest = world.assets.fractureManifest(part.FractureData.manifestRef)
    vehicle  = part.vehicle

    # 1. Capture the parent's motion AT THE PART'S POSITION before anything changes.
    #    v_point = v_body + ω × (r_point − r_com). Using the body's COM velocity alone
    #    would give every shard the same velocity and lose the rotational component,
    #    which is what makes a spinning vehicle's debris look and behave right.
    partWorld  = part.worldTransform()
    vBody      = vehicle.velocity.linear
    omega      = vehicle.velocity.angular
    comWorld   = vehicle.worldCom()

    # 2. Remove the part's contribution from the vehicle (structural change).
    detachPart(world, vehicle, part, reason = FRACTURED)   # D05-S5.5 / D07-S5.7
                                                           # -> mass, COM, compound updated

    # 3. Spawn one debris body per shard.
    spawned = []
    rng = world.random.stream(FRACTURE_SCATTER)            # authoritative stream (G4)
    for shard in manifest.shards sortedBy shard.id:        # deterministic order (G3)
        worldT   = partWorld * shard.localTransform
        rPoint   = worldT.translation - comWorld
        vInherit = vBody + cross(omega, rPoint)            # momentum inheritance

        # Small outward scatter so shards separate instead of resolving as one
        # interpenetrating cluster. Magnitude is deliberately small: it must not
        # dominate the inherited momentum, or PROG-004 fails.
        outward  = normalize(shard.centroidLocal)          # from the part's centre
        jitter   = rng.nextUnitVector() * SCATTER_JITTER_MPS (0.4)
        vShard   = vInherit + outward * SCATTER_SPEED_MPS (1.2) + jitter

        body = spawnDebris(world, hull = shardHull(shard), massKg = shard.massKg,
                           transform = worldT, linearVel = vShard,
                           angularVel = omega + rng.nextVector(±2 rad/s),
                           lifetimeS = DEBRIS_LIFETIME_S)                # D06-S5.10
        spawned.append(body)

    part.FractureData.hasFractured = true
    emit PartFractured(part, tick, vBody, omega)           # replicated event (D07-S5.9)
    world.destroyEntity(part.entity)                       # deferred to CLEANUP (D04-S5.5)

# MOMENTUM ACCOUNTING (verified by D14 PROG-004):
#   Σ(m_i · v_i) = Σ(m_i · (v_body + ω×r_i)) + Σ(m_i · scatter_i)
#   The first term is exactly the part's momentum. The second is bounded by
#   SCATTER_SPEED_MPS + SCATTER_JITTER_MPS = 1.6 m/s per shard, and outward directions
#   largely cancel, keeping the total within VELOCITY_REL (5%).
#
# R19. Shard masses come from the manifest, never recomputed at runtime. The manifest is
#      validated at load (D08-S5.4) and by the harness (D14 ASSET-004/006), so runtime
#      trusts it. G7 is therefore an asset-time guarantee, not a runtime computation.
# R20. If MAX_DEBRIS_BODIES would be exceeded, spawnDebris recycles the oldest debris
#      (D06-R28). Shards are never dropped — a partial shard set would visibly
#      contradict the destroyed part.
```

<!-- D07-S5.7 -->### 5.7 Detachment and Joint Breaking

```pseudo
function DetachSystem.update(world, dt, tick):             # slot 14, authority
    # Three independent triggers.
    for part in world.family(DamageState, SlotAttachment).iterate():

        # T1: destroyed parts detach (immediately, or after a hanging delay if authored).
        if part.DamageState.state == DESTROYED and not part.isDetached:
            if part.type.hangsBeforeFalling and ticksSince(part.stateEnteredTick) < HANGING_TICKS:
                ensureHangingConstraint(part)              # btFixedConstraint, D06-S5.6
            else:
                detachPart(world, part.vehicle, part, reason = DESTROYED)

        # T2: constraint broke under load (Bullet disabled it, D06-S5.6).
        if part.SlotAttachment.constraintHandle != null
           and not part.SlotAttachment.constraintHandle.isEnabled():
            detachPart(world, part.vehicle, part, reason = JOINT_BROKE)

        # T3: parent gone (its subtree leaves with it — handled inside detachPart).

    # T4: chassis destroyed -> the vehicle is destroyed; everything detaches.
    for vehicle in world.family(VehicleChassis).iterate():
        if vehicle.chassisPart.DamageState.state == DESTROYED and not vehicle.isWrecked:
            wreckVehicle(world, vehicle, tick)

function wreckVehicle(world, vehicle, tick):
    emit VehicleDestroyed(vehicle, lastAttacker, tick)      # scoring (D01-S5.4)
    for part in vehicle.liveParts.sortedBy(slotPath):       # deterministic (G3)
        detachPart(world, vehicle, part, reason = VEHICLE_WRECKED)
    # The chassis itself becomes a debris body (or fractures if it has manifest data).
    if vehicle.chassisPart.hasFractureData: fracturePart(world, vehicle.chassisPart, tick)
    else: spawnDebris(world, chassisHull, chassisMass, ..., lifetimeS = WRECK_LIFETIME_S (30))
    vehicle.isWrecked = true
    world.destroyEntity(vehicle.entity)                     # deferred

# SCATTER VELOCITY for a detached (not fractured) part:
function detachVelocity(vehicle, part):
    r = part.worldPosition() - vehicle.worldCom()
    return vehicle.velocity.linear + cross(vehicle.velocity.angular, r)
    # Plus, when detachment was caused by a hit, a small impulse along the hit normal,
    # capped at DETACH_KICK_MPS = 3.0, so parts fly off in the direction they were struck.

# R21. Detachment is one-way (G9). A DETACHED part never rejoins the graph.
# R22. Joint break thresholds are impulses in N·s (D06-R22).
# R23. detachPart is defined once, in D05-S5.5. This document specifies WHEN it is
#      called; that document specifies WHAT it does to the physics profile.
```

<!-- D07-S5.8 -->### 5.8 Debris Lifetime and Budget

```pseudo
LIFETIME RULES:
  Shards from a fractured part      DEBRIS_LIFETIME_S = 12.0 s
  Detached non-fractured parts      DEBRIS_LIFETIME_S = 12.0 s
  Wrecked chassis                   WRECK_LIFETIME_S  = 30.0 s   (it is a landmark)
  Global cap                        MAX_DEBRIS_BODIES = 256

function LifetimeSystem.update(world, dt):                 # slot 16
    for e in world.family(Lifetime).iterate():
        e.Lifetime.remainingS -= dt
        expired = e.Lifetime.remainingS <= 0
        slept   = e.hasRigidBody and e.body.isDeactivated()
                  and ticksSince(e.body.deactivationTick) > SLEEP_DESPAWN_S (3.0) * TICK_RATE_HZ
        if expired or (e.Lifetime.despawnPolicy == SLEEP_THEN_DESTROY and slept):
            world.destroyEntity(e.id)                      # deferred to CLEANUP

# R24. Despawn is instantaneous on the authority. Clients may fade the mesh out over
#      DESPAWN_FADE_S = 0.5 s purely visually (cosmetic), continuing to render a body
#      that no longer exists in the authority's world. This is safe precisely because
#      debris is cosmetic (D07-R5).
# R25. Debris despawn is NOT replicated per-body. Clients run the same lifetime logic on
#      their locally spawned shards. Divergence in despawn timing is invisible and
#      harmless.
```

<!-- D07-S5.9 -->### 5.9 Replication of Destruction State

```pseudo
# WHAT IS SENT (authoritative, D07-S4.2):
#   1. Per-part health, quantised to 8 bits of healthFraction (256 levels ≈ 0.4% steps).
#      Rationale: morph blending and degradation are both smooth functions; 0.4%
#      resolution is far below perceptual threshold, and 8 bits × 64 parts × 12 vehicles
#      is trivial bandwidth.
#   2. DamageState.state (3 bits) + stateVersion (used only to detect change).
#   3. Structural events, RELIABLE and ORDERED, never delta-compressed away:
#        PartDestroyed  { networkId, slotPath, tick }
#        PartFractured  { networkId, slotPath, tick, parentLinearVel, parentAngularVel }
#        PartDetached   { networkId, slotPath, tick, reason }
#        VehicleDestroyed { networkId, killerPlayerId, tick }
#   4. Damage events addressed to the involved players only (attacker + victim), for
#      hit markers and the damage ledger.
#
# WHAT IS NOT SENT:
#   - Morph weights, scorch, particles (cosmetic).
#   - Individual shard transforms or velocities (D07-R5).
#   - Vehicle total mass / COM / inertia: every peer recomputes these from the
#     replicated structural state, which is cheaper AND guarantees agreement,
#     since disagreement would mean the structural state itself disagreed.

function NetworkSendSystem.collectDestructionState(vehicle, baseline):
    delta = []
    if vehicle.slotGraph.structuralVersion != baseline.structuralVersion:
        delta += fullStructuralState(vehicle)      # part presence bitmap + states
    else:
        for part in vehicle.parts.sortedBy(slotPath):
            if quantise8(part.healthFraction) != baseline.health[part.slotPath]:
                delta += (slotPathIndex, quantise8(part.healthFraction))
            if part.DamageState.stateVersion != baseline.stateVersion[part.slotPath]:
                delta += (slotPathIndex, part.DamageState.state)
    return delta

function applyDestructionState(client, vehicle, delta):
    # Idempotent (G16): applying the same delta twice yields the same state.
    for (slotIndex, value) in delta:
        part = vehicle.partBySlotIndex(slotIndex)
        part.Health.healthFraction = dequantise8(value)
        part.Health.currentHp      = part.Health.healthFraction * part.Health.maxHp
        updateDamageState(part, client.tick)       # same function, same result (D07-S5.3)
    if structuralChanged:
        applyStructuralState(vehicle, delta)       # detach locally, recompute mass (G10)
        # The CLIENT performs the same detachPart work, so its physics profile matches.

# R26. Clients run the same detach/fracture code as the authority, driven by replicated
#      events rather than by locally computed damage. This is what keeps the predicted
#      physics profile correct without replicating mass numbers.
# R27. Structural events are RELIABLE ORDERED. Losing a PartDetached message would leave
#      a client with a heavier vehicle than the server forever — an unrecoverable
#      divergence in prediction. Health updates, by contrast, are fine to lose because
#      the next snapshot carries the current value (D10-S5.3).
```

---

<!-- D07-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D07-1.** Every entry in D07-S4.2 is classified exactly once; a CI check verifies no cosmetic field is read by an authority-only system.
- [ ] **AC-D07-2.** A hit always resolves to a specific part via compound child index (or the wheel special case).
- [ ] **AC-D07-3.** Armour formulas match D07-S4.3 for all five damage types, including the floors.
- [ ] **AC-D07-4.** Positional modifiers multiply as specified; a rear-top-exposed hit is ×2.43.
- [ ] **AC-D07-5.** Coverage intercepts: while a covering armour part is alive, hits aimed at what it covers damage the armour instead.
- [ ] **AC-D07-6.** Damage state transitions occur exactly at 0.66 / 0.33 / 0.0 and are monotonic (G8).
- [ ] **AC-D07-7.** `DESTROYED` and `DETACHED` are terminal; raising health does not reverse them (G9).
- [ ] **AC-D07-8.** Propagation reaches at most `PROPAGATION_MAX_DEPTH` hops, never re-propagates, never damages a part twice for one event.
- [ ] **AC-D07-9.** `morphWeightsForHealth` returns exactly the worked values in D07-S5.5.
- [ ] **AC-D07-10.** No gameplay system reads `DamageVisualComponent` (CI check).
- [ ] **AC-D07-11.** Fracture occurs exactly once per part; `hasFractured` is one-way.
- [ ] **AC-D07-12.** Shard momentum sums to the parent part's momentum within `VELOCITY_REL` (D14 PROG-004).
- [ ] **AC-D07-13.** Shard masses come from the manifest; total equals part mass within `MASS_TOLERANCE_FRAC` (G7).
- [ ] **AC-D07-14.** Fracture removes the part's mass from the vehicle and recomputes COM in the same tick (G10).
- [ ] **AC-D07-15.** All four detach triggers (T1–T4) work and produce a `PartDetached`/`VehicleDestroyed` event.
- [ ] **AC-D07-16.** Debris deals no damage and blocks no projectile.
- [ ] **AC-D07-17.** Debris count never exceeds `MAX_DEBRIS_BODIES`; a fracture never spawns a partial shard set.
- [ ] **AC-D07-18.** Structural events are sent reliably and ordered; health updates are unreliable-but-current.
- [ ] **AC-D07-19.** Applying the same destruction delta twice yields identical state (G16).
- [ ] **AC-D07-20.** A client that receives only structural events and quantised health arrives at the same vehicle mass and COM as the authority, within `MASS_DELTA_FRAC` / `COM_OFFSET_M`.

---

<!-- D07-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Damage to an already-destroyed part | Discarded, returns 0 (D07-R12). No redirect, no overkill carry-over. |
| E2 | Overkill damage (500 HP to a 20 HP part) | Health clamps at 0. The excess is **not** transferred to neighbours beyond normal propagation, which uses the *applied* amount (20), not the raw amount. |
| E3 | Explosive hit centred between two vehicles | Each part within radius gets its own `DamageEvent` with its own falloff. No double-counting; each part is hit once per explosion. |
| E4 | Propagation reaches a part that is destroyed mid-walk | The `visited`/state filter excludes it. |
| E5 | A part with no fracture manifest is destroyed | It detaches as a single debris body rather than fracturing. Legal; asset validation warns only. |
| E6 | A part with no morph targets | Never deforms visually; still transitions states and fractures (D07-R17). |
| E7 | Fracture while the vehicle is spinning at 30 rad/s | Shards inherit `ω × r`, which can be large; `spawnDebris` clamps to `MAX_SCATTER_SPEED_MPS`. Clamping here is acceptable because debris is cosmetic. |
| E8 | Chassis destroyed while 20 parts remain | `wreckVehicle` detaches all 20 in slot-path order, potentially exceeding the debris budget; oldest debris is recycled. |
| E9 | Two damage events destroy the same part in one tick | The first sets state to `DESTROYED`; the second is discarded (E1). Only one `PartDestroyed` event fires. |
| E10 | A hanging (constrained) destroyed part is hit again | Damage discarded; the impulse may still break the constraint, which is the intended drama. |
| E11 | Client receives `PartFractured` for a part it already fractured | Idempotent: `hasFractured` guard makes it a no-op (G16). |
| E12 | Client misses `PartDetached` (impossible for reliable channel, but) | Detected at the next full structural snapshot; the client force-resyncs the vehicle's structure. |
| E13 | Compound child index map is stale at hit resolution | Centroid fallback with an ERROR log (D07-S5.1). Never drop the hit. |
| E14 | Collision impulse below threshold | No damage. Scraping walls is free. |
| E15 | Friendly fire disabled and a teammate rams | All damage suppressed including `COLLISION` (D01-E9). |
| E16 | Burn stacks on a part that then detaches | Stacks stop applying: detached parts are debris and take no damage. |
| E17 | Explosive damage on a detached subtree | Discarded (debris takes no damage). |
| E18 | A part's manifest declares 0 shards | Asset validation error (D08-S5.4); at runtime such a part detaches as one body. |
| E19 | `healthFraction` quantisation makes a client cross a state threshold before the server | Possible by ≤0.4%; harmless because the client also receives the authoritative `DamageState`, which wins on conflict. |
| E20 | Vehicle destroyed on the same tick a shot is in flight | The shot resolves against the wreck's debris (no damage) or misses. No credit is awarded after `VehicleDestroyed`. |

---

<!-- D07-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D07-1 | 100 HP `KINETIC` on a 30-armour plate | 70 HP applied |
| T-D07-2 | 100 HP `KINETIC` on a 200-armour plate | 10 HP applied (floor) |
| T-D07-3 | 100 HP `EXPLOSIVE` on a 100-armour plate | 60 HP applied |
| T-D07-4 | 100 HP `INCENDIARY` on a 200-armour plate | 100 HP applied plus burn stacks |
| T-D07-5 | Rear + top hit on an exposed part, 100 base | 243 HP before armour modifiers |
| T-D07-6 | Hit a slot covered by live armour | Armour takes the damage; covered part untouched |
| T-D07-7 | Destroy that armour, hit again | Covered part takes damage at ×1.5 |
| T-D07-8 | Reduce health across 1.0 → 0.0 in 0.01 steps | State transitions exactly at 0.66, 0.33, 0.0; never reverses |
| T-D07-9 | Set a destroyed part's HP to full | State remains `DESTROYED`; stats remain zero |
| T-D07-10 | 100 HP `KINETIC` on a part with 3 neighbours | Each neighbour takes 10 HP (0.20 × 0.5 × 100); hop-2 parts take 1 HP; nothing at hop 3 |
| T-D07-11 | `ENERGY` damage on a connected part | Zero propagation |
| T-D07-12 | Diamond-shaped slot graph (two paths to one part) | That part damaged once, not twice |
| T-D07-13 | `morphWeightsForHealth` at 1.0/0.875/0.75/0.5/0.25/0.0 | Exactly the D07-S5.5 table |
| T-D07-14 | Grep `game-core` for `DamageVisualComponent` | No matches |
| T-D07-15 | Fracture a 15.4 kg part with 12 shards while moving at 8 m/s and spinning at 2 rad/s | 12 debris bodies; Σm = 15.4 ± 2%; Σmv within 5% of parent momentum; no speed > 50 m/s |
| T-D07-16 | Fracture the same part twice | Second call is a no-op |
| T-D07-17 | Destroy a part, verify vehicle mass in the same tick | Mass reduced, COM recomputed before the next physics step |
| T-D07-18 | Ramp force until a hanging constraint breaks | Break within 15% of threshold; part becomes debris; constraint disposed |
| T-D07-19 | Destroy the chassis with 20 parts attached | All 20 detach; `VehicleDestroyed` emitted once; debris budget respected |
| T-D07-20 | Shoot debris | No damage to anything; projectile passes through |
| T-D07-21 | Apply the same destruction delta twice on a client | Identical state |
| T-D07-22 | Run a 3-minute headless match, compare client vs authority vehicle mass at every tick | Agrees within `MASS_DELTA_FRAC` at all times |
| T-D07-23 | Drop 30% of health-update packets | Client converges to correct health at the next snapshot; no structural divergence |
| T-D07-24 | Collide two 1500 kg vehicles head-on at 20 m/s each | Both take collision damage proportional to impulse above threshold; distributed to contacting parts |

---

<!-- D07-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| G6 auth/cosmetic, G7 mass conservation, G8/G9 monotonic and one-way | `docs/00_master_index.md#D00-S5.2` |
| Damage-related constants | `docs/00_master_index.md#D00-S6.4` |
| Damage type identities | `docs/01_product_game_design.md#D01-S4.5` |
| Hit zone modifiers | `docs/01_product_game_design.md#D01-S4.6` |
| Scoring from destruction events | `docs/01_product_game_design.md#D01-S5.4` |
| System slots 11–14 | `docs/04_entity_component_model.md#D04-S4.4` |
| Health/DamageState/DamageVisual components | `docs/04_entity_component_model.md#D04-S4.3` |
| Slot graph and coverage | `docs/05_vehicle_part_system.md#D05-S4.3`, `#D05-S5.8` |
| Degradation from health | `docs/05_vehicle_part_system.md#D05-S5.4` |
| `detachPart` physics effects | `docs/05_vehicle_part_system.md#D05-S5.5` |
| Compound child index → slot path | `docs/06_physics_simulation.md#D06-S5.3` |
| Breakable constraints | `docs/06_physics_simulation.md#D06-S5.6` |
| Mass property recomputation | `docs/06_physics_simulation.md#D06-S5.7` |
| Debris body creation and budget | `docs/06_physics_simulation.md#D06-S5.10` |
| Seeded `FRACTURE_SCATTER` stream | `docs/06_physics_simulation.md#D06-S5.8` |
| Asset validation of fracture data | `docs/08_asset_pipeline.md#D08-S5.4` |
| Fracture manifest contents | `docs/09_blender_destruction_tool.md#D09-S4.4` |
| Morph target generation | `docs/09_blender_destruction_tool.md#D09-S5.3` |
| Replication classes and rates | `docs/10_networking_multiplayer.md#D10-S5.3` |
| Delta compression | `docs/10_networking_multiplayer.md#D10-S5.4` |
| Reliable channel semantics | `docs/10_networking_multiplayer.md#D10-S4.2` |
| Destruction progression verification | `docs/14_test_environment.md#D14-S5.6` |

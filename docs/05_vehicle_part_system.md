<!-- D05-S0 --># 05 — Vehicle and Part System

**Document ID:** D05
**Owns:** Part catalogue and categories, slot system and slot graph, per-part properties, degradation curves, damage-state visuals, detachment effects on physics, vehicle stat aggregation.

---

<!-- D05-S1 -->## 1. Purpose

This document specifies what a vehicle is made of and how it behaves as its parts degrade. It defines the part catalogue, the slot system that connects parts into a tree, the properties every part carries, the exact function mapping part health to performance loss, the five visual damage states, what happens physically when a part detaches, and how part stats aggregate into the vehicle stats the player feels.

It is the bridge between design intent (D01) and simulation (D06/D07).

Requirements are numbered `R1..Rn`, cited as `D05-R11`.

---

<!-- D05-S2 -->## 2. Scope

<!-- D05-S2.1 -->### 2.1 In Scope

- Vehicle definition as a prebuilt assembly.
- Part catalogue: categories, canonical part types, their roles.
- Slot system: slot types, compatibility, the slot graph, slot paths.
- Per-part properties and the `StatBlock`.
- The degradation model: exact curves per category.
- The five visual damage states and their triggers.
- Detachment: what changes in the physics body, mass, and COM.
- Vehicle stat aggregation order and formulas.
- The power budget used for balance.

<!-- D05-S2.2 -->### 2.2 Non-Goals

- **NG1.** Damage application, propagation, and the fracture trigger — `docs/07_damage_destruction_model.md#D05` is not the owner; see `docs/07_damage_destruction_model.md#D07-S5`.
- **NG2.** Bullet-specific body/constraint construction — `docs/06_physics_simulation.md#D06-S5`.
- **NG3.** Asset file formats — `docs/08_asset_pipeline.md#D08-S4`.
- **NG4.** A garage/build-your-own-vehicle editor (D01-NG1). The data model supports arbitrary assemblies; the product ships prebuilt ones.
- **NG5.** Balance values for specific shipped vehicles. Those are content in `assets/`, governed by the power budget rule here.

---

<!-- D05-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S6` | Glossary: part, slot, chassis, damage state, degradation |
| `docs/00_master_index.md#D00-S6.4` | `MAX_PARTS_PER_VEHICLE`, `DAMAGE_STATE_THRESHOLDS` |
| `docs/01_product_game_design.md#D01-S5.3` | Degradation feel targets this document must realise |
| `docs/04_entity_component_model.md#D04-S4.3` | `PartStatsComponent`, `SlotGraphComponent`, `VehicleStatsComponent` |
| `docs/06_physics_simulation.md#D06-S5.7` | Mass property recomputation triggered by detachment |
| `docs/07_damage_destruction_model.md#D07-S5.3` | The damage state machine this document's visuals track |
| `docs/08_asset_pipeline.md#D08-S4.2` | Part definition file schema carrying these properties |

---

<!-- D05-S4 -->## 4. Data Contracts

<!-- D05-S4.1 -->### 4.1 Vehicle Definition

**R1.** A **vehicle** is an instantiated **assembly**: exactly one `chassis` part at the root, plus 0–63 further parts attached through slots. `MAX_PARTS_PER_VEHICLE = 64` including the chassis.

**R2.** An assembly is authored data (`assembly manifest`, D08-S4.4), not code. Instantiating an assembly produces one `VEHICLE` entity plus one `PART` entity per part (D04-S4.2).

**R3.** A vehicle has exactly **one primary rigid body** — the chassis body, whose collision shape is a `btCompoundShape` containing the chassis hull plus the hull of every attached non-wheel part (D06-S5.3). Wheels are not collision children; they are ray-cast vehicle wheels (D06-S5.5).

**R4.** Rationale for one body rather than one body per part: a vehicle of 30 constrained bodies is both slower and less stable in Bullet than one compound body, and the visual/gameplay requirement (parts degrade and detach) is satisfied by *removing children from the compound* rather than by simulating each part separately. Parts become independent bodies only at the moment they detach (D05-S5.5).

<!-- D05-S4.2 -->### 4.2 Part Categories and Catalogue

**R5.** Exactly six `PartCategory` values exist.

| Category | Role | Contributes | May be destroyed | Detaches on destroy | In compound shape |
|---|---|---|---|---|---|
| `chassis` | Root; owns the vehicle body, engine, and base slots | mass, engine force, base armour, all slots | Yes → vehicle destroyed | No (vehicle ends) | Yes (root shape) |
| `panel` | Bodywork; absorbs damage before what it covers | mass, armour value, `covers` relationship | Yes | Yes | Yes |
| `wheel` | Ground contact, drive, steering | mass, traction, steering, suspension | Yes | Yes | No (ray-cast wheel) |
| `weapon` | Damage output | mass, fire rate, damage, spread, ammo | Yes | Yes | Yes |
| `utility` | Support effects (ammo feed, radar, cooler, plating booster, cloak) | mass, its specific stat modifiers | Yes | Yes | Yes |
| `decorative` | Cosmetic only | mass **only** | Yes | Yes | Yes (small hull) |

**R6.** `decorative` parts carry mass and collide, but contribute no stat modifiers and no armour. They exist so that visual identity has physical consequence (P3) — a big spoiler is real weight — while never affecting balance beyond that mass. Cosmetic *skins* (D01-R28) are different: they change nothing at all.

**R7.** Canonical part types shipped in v1 (content, extensible):

| Part type | Category | Typical mass | Notes |
|---|---|---|---|
| `chassis_light_01` / `_medium_01` / `_heavy_01` | chassis | 600 / 900 / 1400 kg | Differ in slot count, engine force, base armour |
| `panel_plate_light_01` / `_medium_01` / `_heavy_01` | panel | 60 / 160 / 340 kg | Differ in armour value and health |
| `panel_bumper_01` | panel | 120 kg | Front-only slot type; high `COLLISION` resistance |
| `wheel_road_01` | wheel | 45 kg | High grip on hard surfaces |
| `wheel_offroad_01` | wheel | 60 kg | Better on loose surfaces, lower top speed |
| `wheel_reinforced_01` | wheel | 95 kg | Double health, heavy |
| `weapon_autocannon_01` | weapon | 180 kg | D01-S4.4 family `AUTOCANNON` |
| `weapon_cannon_01` | weapon | 320 kg | `CANNON` |
| `weapon_shotgun_01` | weapon | 140 kg | `SHOTGUN` |
| `weapon_rocket_pod_01` | weapon | 200 kg | `ROCKET` |
| `weapon_flamer_01` | weapon | 130 kg | `FLAMER` |
| `weapon_laser_01` | weapon | 210 kg | `LASER` |
| `utility_ammo_feed_01` | utility | 70 kg | +15% fire rate to all weapons |
| `utility_radiator_01` | utility | 85 kg | −30% heat accumulation |
| `utility_reinforcer_01` | utility | 150 kg | +10% health to all `panel` parts |
| `deco_spoiler_01`, `deco_exhaust_01` | decorative | 25 / 12 kg | Mass only |

<!-- D05-S4.3 -->### 4.3 Slot System and Slot Graph

**R8.** A **slot** is an attachment point declared by a part type. Slot definition fields:

| Field | Type | Constraint | Meaning |
|---|---|---|---|
| `slotId` | string | `^[a-z][a-z0-9_]{1,31}$`, unique within its part | Name, e.g. `hardpoint_left` |
| `slotType` | `SlotType` | enum | What may attach |
| `localPosition` | Vector3 | metres, part-local | Attachment origin |
| `localRotation` | Quaternion | unit | Attachment orientation |
| `maxMassKg` | float | > 0 | Refuses parts heavier than this |
| `covers` | string[] | slot ids on the same part | Armour semantics (D05-R13) |
| `isDetachable` | bool | — | False only for the chassis root |

**R9.** `SlotType` values and compatibility:

| `SlotType` | Accepts categories | Typical count on a chassis |
|---|---|---|
| `ROOT` | `chassis` | 1 (implicit, the vehicle root) |
| `WHEEL` | `wheel` | 4–6 |
| `HARDPOINT` | `weapon`, `utility` | 2–4 |
| `PANEL` | `panel` | 4–10 |
| `TURRET_MOUNT` | `weapon` | 0–1 |
| `ACCESSORY` | `decorative` | 2–6 |
| `SUBSLOT` | `weapon`, `utility`, `decorative` | on parts, e.g. a turret's barrel slot |

**R10.** The **slot graph** is a tree: the chassis is the root; every other part occupies exactly one slot on exactly one parent part. Cycles are impossible by construction; asset validation rejects any assembly that would create one (D08-S5.4).

**R11.** A part's **slot path** is the `/`-joined chain of slot ids from the root, prefixed `root`: `root/hardpoint_left/subslot_barrel`. Slot paths are the stable identity of a part *position* within an assembly and are used by replication (D10), test assertions (D14), and the damage ledger (D01-S5.4).

```
                            ┌──────────────────────┐
                            │  chassis_medium_01   │  root
                            └───┬───┬───┬───┬───┬──┘
        wheel_fl ◄──WHEEL───────┘   │   │   │   └────PANEL───────► panel_plate_front
        wheel_fr ◄──WHEEL───────────┘   │   └────────HARDPOINT────► weapon_autocannon_01
        wheel_rl ◄──WHEEL───────────────┘                              │
                                                                       └──SUBSLOT──► deco_muzzle
```

**R12.** Structural changes (attach/detach) increment `SlotGraphComponent.structuralVersion`. Every cache derived from the graph — compound shape, aggregated stats, mass properties, coverage map — is invalidated by a version change and recomputed in the same tick (G10).

**R13. Coverage.** A `panel` part *covers* the slots named in its slot definition's `covers` list. While a covering panel is present and not `DESTROYED`, hits that resolve to a covered part are first absorbed by the panel (D07-S5.1). When the panel is destroyed or detached, covered parts gain the `EXPOSED` modifier (D01-R11). Coverage is a *slot* relationship, not a geometric one, so it is deterministic and cheap.

<!-- D05-S4.4 -->### 4.4 Part Properties

**R14.** Every part type declares:

| Field | Type | Unit | Constraint | Notes |
|---|---|---|---|---|
| `partTypeId` | AssetId | — | D00-R19 | Unique |
| `category` | `PartCategory` | — | enum | D05-S4.2 |
| `massKg` | float | kg | > `MIN_BODY_MASS_KG` | Authoritative mass; must match the fracture manifest's `partMassKg` within `MASS_DELTA_FRAC` |
| `maxHp` | float | HP | > 0 | |
| `armorValue` | float | — | ≥ 0 | Flat mitigation (D07-S5.2) |
| `materialId` | AssetId | — | must resolve | Density + damage-type modifiers |
| `slotTypeRequired` | `SlotType` | — | enum | What slot it can occupy |
| `slots` | Slot[] | — | ≤ 8 per part | Slots it offers |
| `stats` | `StatBlock` | — | — | Its contribution (D05-S4.5) |
| `degradationProfile` | `DegradationProfile` | — | enum, default by category | D05-S5.4 |
| `fractureManifestRef` | AssetId | — | optional | Absent ⇒ the part vanishes on destroy instead of fracturing |
| `breakImpulseN` | float | N·s | > 0 | Joint break threshold when detached-but-jointed (D07-S5.7) |
| `visualStates` | morph names | — | 4 expected | `dmg_25`, `dmg_50`, `dmg_75`, `dmg_100` |
| `powerCost` | float | — | ≥ 0 | Balance budget contribution (D05-S5.7) |

<!-- D05-S4.5 -->### 4.5 StatBlock

**R15.** A `StatBlock` is a fixed set of named scalars. Every stat has an `add` term and a `mul` factor; a part may set either or both. Unset means identity (`add = 0`, `mul = 1`).

| Stat | Unit | Scope | Typical contributor |
|---|---|---|---|
| `engineForceN` | N | vehicle | chassis |
| `enginePowerW` | W | vehicle | chassis |
| `brakeForceN` | N | vehicle | chassis, wheels |
| `maxSteerRad` | rad | vehicle | wheels (steering ones) |
| `steerRateRadPerSec` | rad/s | vehicle | wheels |
| `frictionSlip` | — | per-wheel | wheel |
| `suspensionStiffness` | — | per-wheel | wheel |
| `armorValue` | — | per-part | armor |
| `maxHpMul` | — | per-part | utility (`reinforcer`) |
| `fireIntervalS` | s | per-weapon | weapon, utility (`ammo_feed`) |
| `damagePerShot` | HP | per-weapon | weapon |
| `spreadRad` | rad | per-weapon | weapon |
| `heatPerShot` | — | per-weapon | weapon, utility (`radiator`) |
| `projectileSpeedMps` | m/s | per-weapon | weapon |
| `sensorRangeM` | m | vehicle | utility (`radar`) |
| `moduleDurationS` | s | per-module | utility (an **active** module: how long one activation lasts) |
| `moduleCooldownS` | s | per-module | utility (an **active** module: how long before it may fire again) |

**R16.** `maxSpeedMps` and `accelerationMps2` are **derived**, never authored. They come from engine force, engine power, mass, and drag (D05-S5.6). Authoring a top speed directly would let content contradict physics (P3).

`enginePowerW` is what makes that derivation honest. Tractive force is `min(engineForceN, enginePowerW / v)`: a vehicle is traction-limited at a standstill and power-limited once it is moving. Without the power term the force is constant at every speed, and a chassis whose `engineForceN` was calibrated to a real standing-start acceleration reports a top speed several times what the vehicle has. A chassis that declares no power is unlimited, which is the behaviour of content authored before the stat existed.

---

<!-- D05-S5 -->## 5. Logic & Algorithms

<!-- D05-S5.1 -->### 5.1 Assembly Validation

```pseudo
function validateAssembly(assembly, partCatalog):
    errors = []
    roots = assembly.parts.filter(p -> p.parentSlotPath == null)
    if roots.size != 1:               errors += "assembly must have exactly one root"
    if roots[0].category != chassis:  errors += "root must be a chassis part"
    if assembly.parts.size > MAX_PARTS_PER_VEHICLE: errors += "too many parts"

    seenSlotPaths = {}
    for part in assembly.parts in topologicalOrder():
        parent = resolve(part.parentSlotPath)
        if parent == null:            errors += "unknown parent for " + part.slotPath; continue
        slot = parent.type.slots[part.parentSlotId]
        if slot == null:              errors += "no slot " + part.parentSlotId + " on " + parent.typeId
        else:
            if part.category not in ACCEPTS[slot.slotType]:
                errors += "category " + part.category + " cannot occupy " + slot.slotType
            if part.type.massKg > slot.maxMassKg:
                errors += "part exceeds slot mass limit"
            if slot is already occupied:
                errors += "slot " + part.slotPath + " occupied twice"
        if part.slotPath in seenSlotPaths: errors += "duplicate slot path"
        seenSlotPaths.add(part.slotPath)

    assertAcyclic(assembly)                                  # D05-R10
    if countCategory(assembly, wheel) < 3:
        errors += "vehicle needs at least 3 wheels to be drivable"
    for coverRef in allCoversReferences(assembly):
        if not resolves(coverRef): errors += "covers references unknown slot " + coverRef
    return errors
```

<!-- D05-S5.2 -->### 5.2 Vehicle Instantiation

```pseudo
function spawnVehicle(world, assembly, spawnTransform, ownerPlayer):
    vehicleEntity = world.createEntity()
    add TransformComponent(spawnTransform)
    add VehicleChassisComponent(assemblyId = assembly.id)
    add SlotGraphComponent(nodes = [], structuralVersion = 0)
    add VehicleStatsComponent(dirty = true)
    add TeamComponent(ownerPlayer.team); add NetworkReplicatedComponent(...)

    # 1. Create part entities in topological order so parents exist before children.
    for partSpec in assembly.parts in topologicalOrder():
        partEntity = world.createEntity()
        type = catalog.get(partSpec.partTypeId)
        add PartRefComponent(partTypeId, vehicleEntity, slotPath = partSpec.slotPath)
        add PartStatsComponent(baseStats = type.stats, effectiveStats = type.stats.copy(),
                               category = type.category, materialId = type.materialId)
        add HealthComponent(maxHp = type.maxHp * utilityHpMultiplier(assembly),
                            currentHp = same, armorValue = type.armorValue)
        add DamageStateComponent(state = INTACT, stateEnteredTick = world.tick)
        add SlotAttachmentComponent(parentEntity, slotId, localTransform,
                                    breakImpulseN = type.breakImpulseN)
        if type.category == weapon: add WeaponControllerComponent(from type.stats)
        if type.category == wheel:  add WheelControllerComponent(from type.stats)
        if type.fractureManifestRef: add FractureDataComponent(manifestRef, shardCount)
        vehicle.slotGraph.attach(parentEntity, slotId, partEntity)

    # 2. Build the single compound collision shape (D06-S5.3).
    compound = ShapeCache.buildCompound(assembly)          # chassis + non-wheel parts
    totalMass = sum(part.massKg for all parts)             # includes wheels: they are
                                                           # unsprung mass carried by the body
    add RigidBodyComponent(shape = compound, massKg = totalMass, layer = LAYER_VEHICLE)

    # 3. Ray-cast vehicle wheels (D06-S5.5), ordered by slot id for determinism (G3).
    for wheelPart in partsOfCategory(wheel) sortedBy slotPath:
        index = vehicleController.addWheel(from wheelPart's slot transform and stats)
        wheelPart.WheelController.wheelIndex = index
        vehicle.wheelEntities.append(wheelPart.entity)

    # 4. First aggregation + mass properties.
    VehicleStatsSystem.recompute(vehicleEntity)            # D05-S5.6
    MassPropertySystem.recompute(vehicleEntity)            # D06-S5.7
    return vehicleEntity
```

<!-- D05-S5.3 -->### 5.3 Visual Damage States

**R17.** Five states, driven by `healthFraction` and by structural events. The thresholds are `DAMAGE_STATE_THRESHOLDS` (D00-S6.4).

| State | Entered when | Visual | Functional |
|---|---|---|---|
| `INTACT` | `healthFraction > 0.66` | Base mesh; morph weights blend `dmg_25` up to 1.0 across the band | Full stats down to the curve value at 0.66 |
| `DAMAGED` | `healthFraction ≤ 0.66` | `dmg_25`→`dmg_50` blend; light scorch | Degraded per curve |
| `CRITICAL` | `healthFraction ≤ 0.33` | `dmg_75`→`dmg_100` blend; heavy scorch, sparks (cosmetic) | Degraded per curve; may emit warning feedback |
| `DESTROYED` | `healthFraction == 0` | Mesh replaced by shards (fracture, D07-S5.6) or hidden if no fracture data | Contributes nothing; still counted in the graph until detached |
| `DETACHED` | Detach event (D07-S5.7) | Shards/part exist as debris | Removed from the graph entirely |

**R18.** The morph weights are a *continuous* function of `healthFraction` (D07-S5.5); the state is a *discrete* classification of the same number. They never disagree because they share the input (P1, F3).

<!-- D05-S5.4 -->### 5.4 Degradation Model

**R19.** Degradation maps `healthFraction h ∈ [0,1]` to a multiplier `m ∈ [floor, 1]` applied to a part's stat contributions. The mapping depends on the part's `degradationProfile`.

```pseudo
# Four profiles. Each is a piecewise/parametric curve chosen for the FEEL its
# category needs (D01-S5.3), not for mathematical elegance.

enum DegradationProfile: LINEAR, THRESHOLD, EXPONENTIAL, NONE

function degradationMultiplier(profile, h, floor):
    h = clamp(h, 0.0, 1.0)
    switch profile:

        case NONE:                                  # armour value handled separately;
            return 1.0                              # decorative parts; mass never degrades

        case LINEAR:                                # smooth, predictable
            return floor + (1.0 - floor) * h

        case THRESHOLD:                             # full performance until it starts to
            # Nothing happens above 0.66, then it falls fast. Used for WEAPONS so a
            # lightly scratched gun still works, and a battered one is obviously failing.
            if h > 0.66: return 1.0
            t = h / 0.66                            # remap [0,0.66] -> [0,1]
            return floor + (1.0 - floor) * t

        case EXPONENTIAL:                           # early loss, long tail
            # Used for WHEELS: grip falls off immediately and noticeably, but a
            # near-dead wheel still provides *something* so the vehicle stays drivable.
            return floor + (1.0 - floor) * (h * h)

# Per-category assignment and floors. `floor` is what remains at h = 0+ (just before
# destruction); at h == 0 the part is DESTROYED and contributes nothing at all.
DEGRADATION_TABLE:
  category    stat(s) affected                     profile       floor   rationale
  ---------------------------------------------------------------------------------------
  wheel       frictionSlip                         EXPONENTIAL   0.35    grip loss felt at once (D01)
  wheel       maxSteerRad, steerRateRadPerSec      LINEAR        0.50    steering degrades gently
  wheel       suspensionStiffness                  LINEAR        0.70    ride gets soft, not broken
  weapon      fireIntervalS  (INVERTED: interval   THRESHOLD     0.40    "sputtering" feel
              grows as multiplier falls)
  weapon      spreadRad      (INVERTED)            LINEAR        0.35    accuracy fades
  weapon      damagePerShot                        NONE          1.00    a hit is a hit (readability)
  armor       armorValue                           LINEAR        0.10    protection scales with plate
  panel       massKg                               NONE          1.00    R20 — mass never degrades
  chassis     engineForceN                         LINEAR        0.45    vehicle gets sluggish
  chassis     brakeForceN                          LINEAR        0.60
  utility     its declared stats                   LINEAR        0.25
  decorative  —                                    NONE          1.00

# INVERTED stats (fireIntervalS, spreadRad) get WORSE as health falls, so the
# multiplier is applied reciprocally:
function applyDegradation(statName, baseValue, profile, h, floor):
    m = degradationMultiplier(profile, h, floor)
    if statName in INVERTED_STATS: return baseValue / max(m, 0.001)     # interval/spread grow
    else:                          return baseValue * m                 # everything else shrinks

function recomputePartEffectiveStats(part):
    h = part.health.healthFraction
    if part.damageState.state in {DESTROYED, DETACHED}:
        part.effectiveStats = ZERO_STATS                   # contributes nothing
        return
    for statName in part.baseStats.names():
        (profile, floor) = DEGRADATION_TABLE[part.category][statName]
        part.effectiveStats[statName] =
            applyDegradation(statName, part.baseStats[statName], profile, h, floor)
```

**R20.** **Mass never degrades** (D01-R17). `massKg` is not in any degradation profile other than `NONE`. Mass changes only on detachment. Violating this makes handling changes unattributable.

**R21.** `damagePerShot` never degrades. A damaged gun fires *less often* and *less accurately*, but each hit lands for full value. This keeps damage numbers readable and prevents a death spiral where a damaged player cannot fight back at all.

**R22.** Degradation is recomputed only when `healthFraction` changes or `structuralVersion` changes — not every tick. `VehicleStatsSystem` (D04-S4.4 slot 6) checks the dirty flags.

<!-- D05-S5.5 -->### 5.5 Detachment and Physics Profile Change

```pseudo
function detachPart(world, vehicle, partEntity, impulseAtDetach):
    # Called by DetachSystem (D04-S4.4 slot 14) in POST_SIM, never during the physics step.

    # 1. Detach the whole subtree: a part carries its children with it.
    subtree = vehicle.slotGraph.subtreeOf(partEntity)      # includes partEntity
    for p in subtree in reverse topological order:
        vehicle.slotGraph.detach(p)
        p.PartRef.vehicleEntity = NULL_ENTITY
        p.DamageState.state = DETACHED
        p.DamageState.stateVersion += 1

    # 2. Remove their hulls from the vehicle's compound shape.
    for p in subtree where p.category != wheel:
        compound.removeChildShape(p.compoundChildIndex)
    for p in subtree where p.category == wheel:
        vehicleController.removeWheel(p.WheelController.wheelIndex)
        reindexRemainingWheels(vehicle)        # wheel indices are dense; remap and
                                               # update every WheelController (D05-R24)

    # 3. Recompute the vehicle's mass properties BEFORE the next physics step (G10).
    removedMass = sum(p.massKg for p in subtree)
    vehicle.totalMassKg -= removedMass
    assert vehicle.totalMassKg > MIN_BODY_MASS_KG
    MassPropertySystem.recompute(vehicle)      # D06-S5.7: new COM, new inertia,
                                               # compound recentred, body mass set

    # 4. Turn the subtree into world objects.
    for p in subtree:
        if p.DamageState.wasDestroyed and p.hasFractureData:
            FractureSystem.spawnShards(p, inheritVelocity = velocityAt(p.worldPosition))
        else:
            spawnDebrisBody(p, shape = p.hull, mass = p.massKg,
                            velocity = velocityAt(p.worldPosition),   # v + ω × r
                            angularVelocity = vehicle.angularVelocity,
                            layer = LAYER_DEBRIS, lifetime = DEBRIS_LIFETIME_S)

    # 5. Structural bookkeeping.
    vehicle.slotGraph.structuralVersion += 1
    vehicle.VehicleStats.dirty = true          # forces re-aggregation this tick
    rebuildCoverageMap(vehicle)                # exposed slots gain the EXPOSED modifier
    world.events.emit(PartDetached(vehicle, partEntity, slotPath))   # replicated (D10)

# R23. MOMENTUM IS CONSERVED. The detached mass leaves with the velocity it had at
#      its own position (v_body + ω × r), and the vehicle body's linear and angular
#      velocity are NOT modified. Bullet recomputes the body's response from the new
#      mass and inertia on the next step; artificially adjusting velocity here would
#      create or destroy momentum and shows up as VEH-009 failing (D14-S4.5).
# R24. Wheel indices in btRaycastVehicle are dense and positional. Removing a wheel
#      shifts every higher index. Every WheelController.wheelIndex MUST be remapped in
#      the same operation, or the vehicle will drive the wrong wheels — a classic,
#      silent, hard-to-diagnose bug.
# R25. A vehicle that loses all wheels is immobile but alive (D01-E4). Do not destroy it.
# R26. Detaching the chassis is impossible; the chassis reaching 0 HP destroys the
#      vehicle, which detaches every remaining part as debris (D07-S5.7).
```

<!-- D05-S5.6 -->### 5.6 Vehicle Stat Aggregation

```pseudo
function recomputeVehicleStats(vehicle):
    # Runs in VehicleStatsSystem (slot 6) when VehicleStats.dirty or structuralVersion
    # changed. Deterministic order: parts iterated by slotPath, ascending (G3).

    # ---- Phase 1: per-part effective stats (degradation) ----------------------
    for part in vehicle.parts sortedBy slotPath:
        recomputePartEffectiveStats(part)                       # D05-S5.4

    # ---- Phase 2: utility multipliers (they modify OTHER parts) ---------------
    # Applied before aggregation so their effect is included, and applied only from
    # non-destroyed utilities. Utility effects are multiplicative and commutative,
    # so ordering among them does not matter — but we still iterate in slotPath
    # order so floating-point summation is reproducible.
    utilMul = identityStatBlock()
    for u in vehicle.partsOfCategory(utility) sortedBy slotPath:
        if u.state in {DESTROYED, DETACHED}: continue
        utilMul.combineMultiplicative(u.effectiveStats.multipliers)
    for part in vehicle.parts sortedBy slotPath:
        part.effectiveStats.applyExternalMultipliers(utilMul)

    # ---- Phase 3: vehicle-scope aggregation ----------------------------------
    # Additive terms sum; multiplicative factors multiply. Additives are applied first.
    v = new VehicleStats()
    v.engineForceN = sum(p.effectiveStats.engineForceN for p in liveParts)
    v.brakeForceN  = sum(p.effectiveStats.brakeForceN  for p in liveParts)

    # Steering comes only from wheels that both steer and are alive.
    steerWheels = liveParts.filter(p -> p.category == wheel and p.WheelController.isSteering)
    v.maxSteerRad        = steerWheels.isEmpty() ? 0.0 : mean(w.effectiveStats.maxSteerRad)
    v.steerRateRadPerSec = steerWheels.isEmpty() ? 0.0 : mean(w.effectiveStats.steerRateRadPerSec)

    v.armorRatingAvg = liveParts.filter(panel).isEmpty() ? 0.0
                       : mean(p.effectiveStats.armorValue for p in panelParts)

    # ---- Phase 4: derived stats (never authored, D05-R16) --------------------
    m = vehicle.totalMassKg
    v.accelerationMps2 = v.engineForceN / m
    # Top speed where AVAILABLE tractive force equals aerodynamic + rolling resistance:
    #     min(F_engine, P_engine / v) = k_drag * v^2 + k_roll * m * g
    # Monotonic in v, so solved by bisection. k_drag and k_roll come from the chassis
    # part's `handling` block (D08-R5); a chassis authoring none uses the reference.
    kDrag = chassis.handling.dragCoefficient; kRoll = chassis.handling.rollingResistance
    v.maxSpeedMps = min(solveTopSpeed(v.engineForceN, v.enginePowerW, m, kDrag, kRoll),
                        MAX_VEHICLE_SPEED_MPS)          # the arena's own limit, D06-S5.5

    v.powerBudget = computePowerBudget(vehicle)                # D05-S5.7
    v.dirty = false
    vehicle.VehicleStats = v

# R27. Aggregation is a PURE function of (part effective stats, slot graph, total mass).
#      It reads no previous VehicleStats value, so it cannot drift or accumulate error
#      across ticks — a recompute from scratch always yields the same answer.
# R28. Destroyed and detached parts contribute exactly zero, never a residual.
# R29. Division by zero is impossible: totalMassKg > MIN_BODY_MASS_KG is asserted at
#      detach (D05-S5.5) and at spawn.
```

<!-- D05-S5.7 -->### 5.7 Power Budget (Balance Invariant)

```pseudo
# The power budget makes D01-R27 ("unlocks are sidegrades") mechanically checkable
# rather than a matter of opinion.

function computePowerBudget(assembly):
    budget = 0
    for part in assembly.parts:
        budget += part.type.powerCost
    return budget

function partPowerCost(type):
    # Authored per part type, but sanity-checked by the asset pipeline against this
    # reference formula. A part whose authored cost deviates by more than 15% from
    # the formula is flagged (advisory) so outliers are deliberate, not accidental.
    return   0.010 * type.maxHp
           + 0.050 * type.armorValue
           + 0.400 * dpsOf(type)                       # weapons
           + 0.300 * type.stats.engineForceN / 1000
           + 0.200 * type.stats.frictionSlip * 100
           - 0.004 * type.massKg                       # mass is a COST, so it refunds budget

RULES:
  R30. All vehicles of the same class (light / medium / heavy) must have power budgets
       within POWER_BUDGET_TOLERANCE = 0.03 (3%) of the class target.
  R31. The asset pipeline computes budgets at build time and fails the build (strict
       mode) if R30 is violated (D08-S5.4).
  R32. Class targets are content constants in assets/balance/classes.json, not code.
```

<!-- D05-S5.8 -->### 5.8 Coverage Map

```pseudo
function rebuildCoverageMap(vehicle):
    # Which live panel protects which slot. Rebuilt on structuralVersion change.
    map = {}
    for panelPart in vehicle.partsOfCategory(panel) sortedBy slotPath:
        if panelPart.state in {DESTROYED, DETACHED}: continue
        for coveredSlotId in panelPart.slotDef.covers:
            coveredPath = parentPathOf(panelPart) + "/" + coveredSlotId
            map[coveredPath] = panelPart.entity          # last writer wins; assets are
                                                          # validated to avoid double cover
    vehicle.coverageMap = map

function isExposed(vehicle, part):
    return vehicle.coverageMap[part.slotPath] == null
       and partTypeDeclaresCoverable(part)               # only parts authored as coverable
```

---

<!-- D05-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D05-1.** An assembly with zero or two chassis parts is rejected by validation.
- [ ] **AC-D05-2.** A part in an incompatible slot type is rejected; a part exceeding `slot.maxMassKg` is rejected.
- [ ] **AC-D05-3.** Assemblies exceeding `MAX_PARTS_PER_VEHICLE` are rejected.
- [ ] **AC-D05-4.** The slot graph is always a tree; a cyclic assembly is rejected.
- [ ] **AC-D05-5.** Slot paths are unique within an assembly and stable across a match.
- [ ] **AC-D05-6.** Degradation multipliers match D05-S5.4 exactly for every category, profile, and health value (asserted at 100/75/66/50/33/25/1% health).
- [ ] **AC-D05-7.** `massKg` is unchanged at every health value above 0 (D05-R20).
- [ ] **AC-D05-8.** `damagePerShot` is unchanged at every health value above 0 (D05-R21).
- [ ] **AC-D05-9.** Inverted stats (`fireIntervalS`, `spreadRad`) increase as health falls.
- [ ] **AC-D05-10.** Detaching a part removes exactly its mass from the vehicle within the same tick.
- [ ] **AC-D05-11.** Detaching a part recomputes COM and inertia before the next physics step (G10).
- [ ] **AC-D05-12.** Detaching a wheel remaps all remaining wheel indices correctly (D05-R24), verified by driving after each of several detach orders.
- [ ] **AC-D05-13.** Vehicle velocity is not modified by a detach event (D05-R23); Δv ≤ `DETACH_VELOCITY_STEP_MPS`.
- [ ] **AC-D05-14.** Detaching a part detaches its whole subtree.
- [ ] **AC-D05-15.** Aggregation is pure: recomputing twice from the same state yields identical `VehicleStats`.
- [ ] **AC-D05-16.** Destroyed and detached parts contribute exactly zero to aggregation.
- [ ] **AC-D05-17.** `maxSpeedMps` and `accelerationMps2` are derived, never read from authored data (grep + schema check).
- [ ] **AC-D05-18.** All vehicles of a class have power budgets within 3%.
- [ ] **AC-D05-19.** Destroying a covering armour part sets `EXPOSED` on the parts it covered.
- [ ] **AC-D05-20.** A vehicle with zero wheels remains alive and can still fire.

---

<!-- D05-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | All wheels destroyed | Vehicle immobile, alive, weapons functional. `maxSteerRad` and traction are 0; no division by zero anywhere. |
| E2 | All armour destroyed | Every coverable part becomes `EXPOSED`; `armorRatingAvg = 0`, not NaN. |
| E3 | Utility part destroyed | Its multipliers stop applying at the next aggregation; other parts' effective stats increase back accordingly. This is correct: the buff is gone, not the damage. |
| E4 | Two armour parts cover the same slot | Asset validation warns; at runtime, the last in slot-path order wins deterministically. |
| E5 | Detaching a part whose child is already detached | Subtree walk finds only live children; no double-detach, no error. |
| E6 | Detach reduces mass below `MIN_BODY_MASS_KG` | Impossible while the chassis is present (the chassis alone exceeds it); asserted anyway. |
| E7 | Weapon at 1 HP | Fires at `1/floor` = 2.5× base interval, wide spread, full damage per hit (R21). |
| E8 | A part's `massKg` disagrees with its fracture manifest | Asset validation error (D08-S5.4); strict mode fails the build. |
| E9 | Health restored above 0 after `DESTROYED` | Prohibited (G8/G9). `recomputePartEffectiveStats` still returns ZERO_STATS for a destroyed part regardless of health. |
| E10 | Assembly with 3 wheels | Legal and drivable (validation minimum). Handling is intentionally poor. |
| E11 | Slot declares `covers` for a slot on a different part | Validation error: `covers` is same-part-scoped. |
| E12 | Zero live armour parts when computing `armorRatingAvg` | Return 0.0 explicitly; never `sum/0`. |
| E13 | `powerCost` deviates >15% from the reference formula | Advisory warning at build time; still builds. Deliberate outliers are allowed but visible. |
| E14 | Detach during the physics step | Structurally impossible: `DetachSystem` is POST_SIM (D04-E11). |
| E15 | A decorative part is authored with stat modifiers | Validation error: `decorative` parts may declare only mass (D05-R6). |
| E16 | Chassis reaches 0 HP | Vehicle destroyed; all remaining parts detach as debris (D05-R26); no attempt to keep a chassis-less vehicle. |

---

<!-- D05-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D05-1 | Validate an assembly with two chassis | Rejected with "exactly one root" |
| T-D05-2 | Attach a weapon to a `PANEL` slot | Rejected with a category/slot-type message |
| T-D05-3 | Attach a 400 kg part to a `maxMassKg = 200` slot | Rejected |
| T-D05-4 | Build a 65-part assembly | Rejected |
| T-D05-5 | Evaluate `degradationMultiplier` for every profile at h ∈ {1, .75, .66, .5, .33, .25, .01} | Matches D05-S5.4 to 1e-6 |
| T-D05-6 | Damage an armour plate to 1% health | Mass unchanged; `armorValue` = 10% of base |
| T-D05-7 | Damage a weapon to 50% health | `fireIntervalS` = base / (0.4 + 0.6·(0.5/0.66)) ; `damagePerShot` unchanged |
| T-D05-8 | Damage a wheel to 50% | `frictionSlip` = base·(0.35 + 0.65·0.25) |
| T-D05-9 | Detach a 160 kg plate from a 1500 kg vehicle | Vehicle mass 1340 kg within the same tick |
| T-D05-10 | Measure COM before/after that detach | Matches the analytic weighted COM within `COM_OFFSET_M` |
| T-D05-11 | Detach wheel index 1 of 4, then drive | Remaining wheels drive/steer correctly; no wheel is skipped |
| T-D05-12 | Detach every wheel in each of 24 orders | No crash, correct indices each time |
| T-D05-13 | Detach a turret with a barrel subpart | Both leave; both become debris |
| T-D05-14 | Measure vehicle Δv at the detach tick | ≤ 1.0 m/s |
| T-D05-15 | Recompute aggregation twice | Bit-identical `VehicleStats` |
| T-D05-16 | Destroy a utility ammo feed | Weapon fire intervals return to un-buffed values next tick |
| T-D05-17 | Destroy an armour plate covering a weapon slot | Weapon takes 1.5× damage on the next hit |
| T-D05-18 | Compute budgets for all shipped medium vehicles | All within 3% of the class target |
| T-D05-19 | Destroy all wheels, then fire | Vehicle stationary, weapons work, no NaN in stats |
| T-D05-20 | Set a destroyed part's health to 0.5 directly | `effectiveStats` remain zero (G8/G9 respected) |

---

<!-- D05-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Glossary: part, slot, chassis, degradation | `docs/00_master_index.md#D00-S6.1` |
| `MAX_PARTS_PER_VEHICLE`, thresholds | `docs/00_master_index.md#D00-S6.4` |
| Degradation feel targets | `docs/01_product_game_design.md#D01-S5.3` |
| `EXPOSED` / hit-zone modifiers | `docs/01_product_game_design.md#D01-S4.6` |
| Power-budget sidegrade rule | `docs/01_product_game_design.md#D01-S5.7` |
| Components carrying these fields | `docs/04_entity_component_model.md#D04-S4.3` |
| `VehicleStatsSystem` / `MassPropertySystem` slots | `docs/04_entity_component_model.md#D04-S4.4` |
| Slot paths as identity | `docs/04_entity_component_model.md#D04-S6.4` |
| Compound shape construction | `docs/06_physics_simulation.md#D06-S5.3` |
| Ray-cast vehicle and wheel indices | `docs/06_physics_simulation.md#D06-S5.5` |
| Mass property recomputation | `docs/06_physics_simulation.md#D06-S5.7` |
| Hit resolution and coverage | `docs/07_damage_destruction_model.md#D07-S5.1` |
| Damage state machine | `docs/07_damage_destruction_model.md#D07-S5.3` |
| Morph weight mapping | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Fracture and shard spawning | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Detachment sequencing | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Part definition schema | `docs/08_asset_pipeline.md#D08-S4.2` |
| Assembly manifest schema | `docs/08_asset_pipeline.md#D08-S4.4` |
| Assembly/part validation rules | `docs/08_asset_pipeline.md#D08-S5.4` |
| Degradation verified by the harness | `docs/14_test_environment.md#D14-S5.6` |
| Vehicle integration checks | `docs/14_test_environment.md#D14-S5.7` |

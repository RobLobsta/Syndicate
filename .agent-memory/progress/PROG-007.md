# PROG-007: vehicles are built from assemblies, and what is in the world expires

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/05_vehicle_part_system.md#D05-S5.2, docs/07_damage_destruction_model.md#D07-S5.8

**Status:** superseded (by PROG-010)

## Summary
`SpawnSystem` (5) and `LifetimeSystem` (16) exist, and `VehicleFactory` implements D05-S5.2: one
entity per part, the slot graph, the recentred compound, the chassis body with its mass properties
already correct, and a `btRaycastVehicle` with one ray per wheel. Seven of D04-S4.4's 27 systems now
exist. Nothing drives a vehicle yet — slots 6 and 7 are unwritten — and no real content loads,
because collision meshes need a glTF reader `game-core` does not have (DEV-010).

## Details

**Scope:** `game-core` — the ECS engine, physics, vehicle, damage, and the D04-S4.4 system
catalogue. Per DEC-023 this is the simulation subsystem's entry; the toolchain is PROG-008 and
content and the runtime shells are PROG-009.

**Status of Work:** (supersedes PROG-006 for the simulation subsystem)

System catalogue (D04-S4.4), 7 of 27:

| Area | State | Notes |
|---|---|---|
| 5 `SpawnSystem` | done | Drains `SpawnQueue` in ascending sequence; refuses an unloaded assembly with a log line rather than a throw (G16) |
| 10 `PhysicsSystem` | done | PROG-003 |
| 13 `FractureSystem` | done | PROG-005 |
| 14 `DetachSystem` | done | PROG-006; the detach kick still has no hit normal to read |
| 15 `MassPropertySystem` | done | PROG-005, plus wheel connection points now re-placed when the COM moves |
| 16 `LifetimeSystem` | done | Expiry and `SLEEP_THEN_DESTROY`; the sleep clock is Bullet's own, kept running past `ISLAND_SLEEPING` (DISC-010) |
| 27 `EntityDestroySystem` | done | PROG-004, plus ray-cast controller release ahead of the chassis body |
| 1–4, 6–9, 11, 12, 17–26 | not_started | `VehicleStatsSystem` (6) and `VehicleControlSystem` (7) are what turn a spawned vehicle into a driveable one; `CollisionEventSystem` (11) and `DamageSystem` (12) are what make damage happen at all rather than being set by a test |

Other work in this subsystem:

| Area | State | Notes |
|---|---|---|
| Vehicle instantiation (D05-S5.2) | done | `VehicleFactory`. Mass properties are established at spawn rather than deferred to slot 15 (DEC-021) |
| Ray-cast vehicle (D06-S5.5) | in_progress | `PhysicsWorld` owns the controller, raycaster and tuning; wheels are added in ascending slot path order. Nothing applies engine force or steering — that is slot 7. Wheel geometry constants are chosen, not authored (DEC-022) |
| `game-core` asset model (D08-S4.2/S4.3/S4.4) | done | `PartType` with the D08-R5 fields the spawn path reads, `SlotDefinition`, `AssemblyDef` with typed overrides, `MaterialDef`, `AssemblyLayout` |
| Assembly validation (D05-S5.1, A3xx) | done | A107, A301–A311, A313. A312 waits on `assets/balance/classes.json` (D05-R32); A314 and the A2xx rules belong to the part loader |
| Asset loading (D08-S5.3) | in_progress | `AssetLoader` reads materials, parts and assemblies with Jackson and reports every problem as a `ValidationIssue`. Collision meshes come through a seam (DEV-010); shard hulls and `asset-index.json` are not read |
| `VehicleStatsComponent` | not_started | Spawns `dirty` and stays that way. Nothing reads it, so a spawned vehicle is a correct physical object no input can drive |
| Detach kick (`DETACH_KICK_MPS`) | not_started | as PROG-006 — belongs to the session that gives a part a recorded last hit |
| Coverage map (D05-R13, D01-R11) | not_started | as PROG-005 |
| Wheel detach, native half (D05-S5.5 step 2) | blocked | as PROG-005 (DEV-008) |
| Hanging constraint for compound parts | blocked | as PROG-006 (DEV-009) |
| Constraints (D06-S5.6) | in_progress | `attachBreakable` exists and T2 consumes it; no spawn path builds an articulated part, so `btGeneric6DofSpring2Constraint` is still unused |
| net, ai, match | not_started | Component and DTO scaffolding only |

**History (append-only):**
- 2026-08-09: `LifetimeSystem`, `SpawnSystem`, `VehicleFactory`, `SpawnQueue`/`SpawnRequest`, the
  asset model and `AssetLoader`, `AssemblyValidator`, `AssemblyLayout`, ray-cast vehicle ownership
  in `PhysicsWorld`, wheel-point maintenance in `MassPropertySystem`. `DestructionTestScene` now
  registers content and calls the real spawn path instead of assembling vehicles itself. 144
  `game-core` tests green (31 new) under the JDK 21 toolchain override (DISC-007); `check`,
  `validateDocs`, `lintMemory` and the physics regression tag all pass.

**Acceptance criteria now covered:** AC-D05-1 (structurally, per DEC-019), AC-D05-4, AC-D05-5,
AC-D06-17's despawn half, AC-D07-17's despawn half, and AC-D04-3 for slots 5 and 16. T-D05-1 and
T-D05-4 have direct tests in `AssemblyValidatorTest`.

**What the next session should pick up:** `VehicleStatsSystem` (6) and `VehicleControlSystem` (7),
in that order. Everything they need now exists — a spawned vehicle has parts with stats, a slot
graph, wheels on a controller, and a mass that stays true as parts leave — and together they are the
difference between a vehicle that exists and a vehicle that moves. After that, the pair that makes
damage real: `CollisionEventSystem` (11) and `DamageSystem` (12), which also hand `DetachSystem` the
hit normal its kick has been waiting for.

## Rationale / Context
PROG-006 named `LifetimeSystem` and then `SpawnSystem` plus the assembly loader as the next work;
all three are done, and this records what each deliberately stopped short of — the loader's mesh
seam, the unauthored wheel constants, the stats system a spawned vehicle is still missing — so the
next session reads them as scoped boundaries rather than as oversights. It is also the first entry
written under DEC-023's one-entry-per-subsystem rule.

## Impact
`game-core`. Supersedes PROG-006 for the simulation subsystem only; the toolchain and content state
PROG-006's table carried are now PROG-008 and PROG-009.

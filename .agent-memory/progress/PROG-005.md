# PROG-005: Vehicles have collision shapes, correct mass properties, and parts that fracture

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/06_physics_simulation.md#D06-S5.2, docs/06_physics_simulation.md#D06-S5.7, docs/07_damage_destruction_model.md#D07-S5.6
**Status:** active

## Summary
`ShapeCache` (D06-S5.2) owns every collision shape, `MassPropertySystem` (slot 15) keeps mass, COM and inertia true to a vehicle's live parts, and `FractureSystem` (slot 13) turns a destroyed part into its manifest's shards with inherited momentum. Four of D04-S4.4's 27 systems now exist. `DetachSystem` (14) and everything that spawns a vehicle in the game rather than in a test are still unwritten.

## Details

**Status of Work:** (supersedes the corresponding rows of PROG-003 and PROG-004)

| Area | State | Notes |
|---|---|---|
| `ShapeCache` (D06-S5.2, R8, R13, R13a) | done | Keyed by `ShapeCacheKey`; margin zeroed before `btShapeHull` and set on the result; degenerate meshes rejected; sole owner of every shape (D02-S5.7 rule 2). Shard budget is not runtime-enforceable (DISC-009) |
| `VehicleCompound` (D06-S5.3, R14) | done | Children in slot-path order; `removeChild` mirrors Bullet's swap-last-into-index so the map is never observably stale; `recentre` for D06-S5.7 step 2 |
| Compound ownership | done | Instance-scoped, registered in `ShapeCache` by entity, released by slot 27 after the body is disposed (DEC-015) |
| `MassPropertySystem` (slot 15, D06-S5.7) | done | Sorted sum, COM recentring with the body's world transform kept in place, inertia with the D06-E13 floor, D06-E3 warning, velocity untouched (AC-D06-10) |
| `PartDetachment` (D05-S5.5 steps 1, 2, 5) | done | Subtree in reverse topological order, `DamageState → DETACHED`, compound child removal, wheel re-densification, version bump, `PartDetachedEvent` |
| `FractureSystem` (slot 13, D07-S5.6) | done | Manifest-driven shards, momentum inherited at each shard's own position, `FRACTURE_SCATTER` draws in fixed order, one-way `hasFractured` |
| `DebrisFactory` (D06-S5.10) | done | Hard `MAX_DEBRIS_BODIES` cap recycling oldest-first, velocity clamps, `SLEEP_THEN_DESTROY` lifetimes |
| `game-core` asset layer (D08-S5.3) | in_progress | `AssetIndex` declares one lookup — `fractureManifest` — plus `MeshData`, `FractureManifest`, `ShardDefinition`, `InMemoryAssetIndex`. Part types, materials, assemblies and arenas are not modelled; no loader exists |
| Wheel detach, native half (D05-S5.5 step 2) | blocked | gdx-bullet's `btRaycastVehicle` has no `removeWheel`; ECS indices are maintained, the controller rebuild belongs to slot 7 (DEV-008) |
| `DetachSystem` (slot 14) | not_started | All four triggers, hanging constraints, `wreckVehicle`, and debris bodies for non-fractured parts. Parts detached *below* a fractured one are currently left bodyless and inert |
| Coverage map (D05-R13, D01-R11) | not_started | `rebuildCoverageMap` in D05-S5.5 step 5 has no component to write to yet |
| Contact collection (D06-S5.1), constraints (D06-S5.6) | not_started | as PROG-003 |
| `game-core` system catalogue (D04-S4.4) | in_progress | 4 of 27: 10, 13, 15, 27. `SystemSetFactory.forMode` not started |
| vehicle spawn, damage, net/ai/match, `game-client`, `game-server-headless`, `asset-pipeline` | not_started | as PROG-002/PROG-003 |

**History (append-only):**
- 2026-08-08 (j): `ShapeCache`, `VehicleCompound`, `DebrisFactory`, `PartDetachment`, `SlotChain`, `MassPropertySystem`, `FractureSystem`, the `asset` package, `RandomVectors`, and the `damage` event types. `DestructionTestScene` stands in for `SpawnSystem`. 101 `game-core` tests green including T-D06-6 (340 kg plate off a 1600 kg vehicle), T-D06-7 (50 removal permutations), AC-D06-9/10, AC-D07-11/12/13/14/17. Verified under a JDK 21 toolchain override (DISC-007).

**Acceptance criteria now covered:** AC-D06-4 (every convex shape leaves the cache at 0.01 m), AC-D06-7, AC-D06-8, AC-D06-9, AC-D06-10, AC-D06-17 (the cap; the sleep-despawn half waits for `LifetimeSystem`), AC-D06-20 for these paths, AC-D07-11, AC-D07-12, AC-D07-13, AC-D07-14, AC-D07-17. AC-D06-3 now holds at every point a body is created in `game-core`.

**What the next session should pick up:** `DetachSystem` (slot 14). It has `PartDetachment.detach` to call (DEC-016) and needs the part-hull half of the asset index so a non-fractured part can become a debris body — which is also what closes the gap this session left, where a subtree detached below a fractured part is bodyless. `wreckVehicle` and the hanging-constraint path of D06-S5.6 come with it.

## Rationale / Context
PROG-003 named `MassPropertySystem`, the shape cache and `FractureSystem` as the next work and recorded them as `not_started`; that is now false. This entry also records what those three deliberately do *not* do — no debris body for a non-fractured part, no coverage map, no native wheel removal — so the next session reads them as scoped boundaries rather than as oversights, and knows which of them is blocked by a library and which by an unwritten system.

## Impact
`game-core`. Supersedes PROG-003's rows for the shape cache, compound shapes and `MassPropertySystem`, and PROG-004's row for the system catalogue.

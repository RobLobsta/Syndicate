# SESS-010: DetachSystem — parts leave, and what leaves lands

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/07_damage_destruction_model.md#D07-S5.7, docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.6

**Status:** active

## Summary
Implemented `DetachSystem` (slot 14): all four detachment triggers, `wreckVehicle`, and a debris body
for every part that leaves a vehicle in one piece. The subtree that used to be stranded bodyless
below a fractured part now lands with the rest of it.

## Details
Written this session:

- **`DetachSystem`** (`game-core` `system`, slot 14, POST_SIM). T1 destroyed parts with the
  `HANGING_TICKS` delay, T2 constraints Bullet disabled under load, T3 by way of `PartDetachment`,
  T4 `wreckVehicle`. Then one state-driven pass that gives every detached, vehicle-less, bodyless
  part a debris body and retires the part entity.
- **`PartPlacement`** (`vehicle`). The chassis-local-to-world transform and `v + ω × r`, in one
  place, because the recentring term in the first is the piece that is invisible when omitted.
  `FractureSystem` now uses it too, so the math exists once.
- **`PartDetachment`** records each leaving part's world transform and departure velocity onto the
  part, which is what lets slot 14 find `FractureSystem`'s orphans without being told about them
  (DEC-018).
- **`PhysicsWorld`** gained the constraint half of D06-S5.6: `attachBreakable` (N·s threshold, 20
  solver iterations, linked-body collisions off), a tracked constraint list, `removeConstraint`, and
  straggler teardown before bodies. `EntityDestroySystem` now routes through it rather than reaching
  for `dynamicsWorld()`.
- **`PartType`** and `AssetIndex.partType`, carrying the collision mesh a detached part's body is
  built from — the only source of a wheel's hull, since a wheel contributes no compound geometry —
  and `hangsBeforeFalling`.
- **`VehicleDestroyedEvent`**, emitted once per wreck, ahead of the part detachments it explains.

Two blueprint conflicts came up and were recorded rather than guessed at. `wreckVehicle` calls
`fracturePart`, which is another system's function (D04-R13); slot 13 running first in the same tick
produces the identical outcome, and `FractureSystem` grew a chassis guard that also fixed a latent
throw (DEC-017). And D06-R21's hanging `btFixedConstraint` needs a second rigid body that D06-R20
says an attached part does not have; the delay holds the part instead, with the three rejected
alternatives written down (DEV-009).

## Rationale / Context
Also worth knowing next time: the wreck's one-way guard is entity liveness, not a field — D07-S5.7's
`vehicle.isWrecked` has no home in the D04-S4.3.2 catalogue, and `destroyEntity` already makes a
vehicle unreachable in the same call.

## Impact
`game-core`: `system` (`DetachSystem`, `FractureSystem`, `EntityDestroySystem`), `vehicle`
(`PartDetachment`, `PartPlacement`), `physics` (`PhysicsWorld`), `asset` (`AssetIndex`, `PartType`,
`InMemoryAssetIndex`), `damage` (`VehicleDestroyedEvent`). 125 tests green; `check`, `validateDocs`
and `lintMemory` pass. Recorded as PROG-006, DEC-017, DEC-018, DEV-009.

# SESS-009: Shape cache, mass properties, and parts that break apart

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.2, docs/06_physics_simulation.md#D06-S5.7, docs/07_damage_destruction_model.md#D07-S5.6
**Status:** active

## Summary
Implemented `ShapeCache` (D06-S5.2), `MassPropertySystem` (slot 15) and `FractureSystem` (slot 13), plus the pieces they could not exist without. A destroyed armour plate now leaves its vehicle, breaks into its manifest's shards with the momentum it had at its own position, and the vehicle's mass, centre of mass and inertia are correct in the same tick.

## Details
Four systems of D04-S4.4 now exist: 10, 13, 15 and 27.

New in `game-core`:

- `physics.ShapeCache` — sole owner of every collision shape (D06-R8), margins per R13/R13a, degenerate meshes rejected, instance-scoped vehicle compounds.
- `physics.VehicleCompound` — D06-S5.3, with `removeChild` mirroring Bullet's swap-last-into-index so the map cannot be stale (D06-R14, AC-D06-7), and `recentre` for the COM shift.
- `physics.DebrisFactory` — D06-S5.10: hard `MAX_DEBRIS_BODIES` cap recycling oldest-first, velocity clamps, `SLEEP_THEN_DESTROY` lifetimes.
- `system.MassPropertySystem` — D06-S5.7 end to end, including E13's inertia floor and E3's warning.
- `system.FractureSystem` — D07-S5.6, momentum inherited per shard position, `FRACTURE_SCATTER` draws in a fixed order.
- `vehicle.PartDetachment` and `vehicle.SlotChain` — D05-S5.5 steps 1, 2 and 5, and the accumulated slot transforms both the compound and the mass sum need.
- `asset` — `MeshData`, `ShardDefinition`, `FractureManifest`, `AssetIndex`, `InMemoryAssetIndex`. Deliberately minimal: one lookup, the one an implemented system performs.
- `damage` — `DetachReason`, `PartDetachedEvent`, `PartFracturedEvent`.
- `util.RandomVectors` — uniform sphere draws and clamping, always against an explicit seeded stream.

Three questions the blueprints left open are recorded as DEC-014, DEC-015 and DEC-016: where an attached part's mass lives given a closed component catalogue, where a per-vehicle mutable compound lives in a cache of immutable shared shapes, and how slot 15 learns a structural change happened without keeping cross-tick state.

One library gap and one library surprise. `btRaycastVehicle` has no `removeWheel`, so the native half of wheel detachment is deferred to `VehicleControlSystem` (DEV-008). `btShapeHull` takes no vertex budget and reduces to 42 directions regardless, so the 32-vertex shard budget is an asset-time property rather than a runtime one (DISC-009) — found by a test asserting the budget and failing at 42.

101 `game-core` tests green; `check`, `validateDocs` and `lintMemory` pass under the JDK 21 toolchain override of DISC-007. `NativeResourceTracker.outstanding()` is zero after every test in the new suites.

## Rationale / Context
The three subsystems were assigned together for a reason that only becomes obvious while writing them: none is testable alone. A mass recompute needs a compound to recentre, a compound needs a cache to own it, and a fracture needs all three plus a detach path. Most of the session's judgement went into pieces the assignment did not name — where the compound lives, who owns `detachPart` — and those are what the next session will trip over first.

## Impact
`game-core` `asset`, `damage`, `physics`, `system`, `util`, `vehicle`. `EntityDestroySystem` gained an optional `ShapeCache` argument so it can release a dead vehicle's compound. No blueprint was amended; DEV-008 records one that should be.

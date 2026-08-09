# SESS-011: assemblies become vehicles, and progress entries get one home each

**Date:** 2026-08-09
**Category:** session_summaries
**Related Docs:** docs/05_vehicle_part_system.md#D05-S5.2, docs/07_damage_destruction_model.md#D07-S5.8, docs/13_persistent_memory_system.md#D13-S4.1

**Status:** active

## Summary
Implemented the three things PROG-006 named — `LifetimeSystem` (16), the assembly loader, and
`SpawnSystem` (5) with D05-S5.2's vehicle instantiation — then restructured `progress/` after finding
that a working subsystem had been buried by a global supersession chain, and added `ROADMAP.md`.

## Details
Written this session:

- **`LifetimeSystem`** (slot 16, all modes): expiry, plus early retirement once a
  `SLEEP_THEN_DESTROY` body has slept. Bullet freezes a body's deactivation clock the instant it
  reaches `ISLAND_SLEEPING`, at about 2 s, so the 3 s test could never have fired and the whole path
  would have been dead code (DISC-010).
- **The asset model**: `SlotDefinition`, a `PartType` carrying the D08-R5 fields the spawn path
  reads, `AssemblyDef` with typed overrides, `MaterialDef`, and `AssemblyLayout` — which resolves an
  assembly into placed parts plus the mass, COM and power totals validation and spawning both need.
- **`AssemblyValidator`** (D05-S5.1, A107/A301–A311/A313) and **`AssetLoader`**, which reads the
  three JSON kinds with Jackson and reports every problem as a `ValidationIssue` rather than throwing.
- **`VehicleFactory`** (D05-S5.2): one entity per part, the slot graph as a real tree, the compound
  recentred on the COM, the chassis body built with correct mass properties, and a
  `btRaycastVehicle` with one ray per wheel in ascending slot path order.
- **`SpawnSystem`** (5) plus `SpawnQueue`/`SpawnRequest`, so a vehicle is created at one point in the
  tick whoever asked for it.
- **`PhysicsWorld`** now owns ray-cast controllers, raycasters and tunings; `EntityDestroySystem`
  releases the controller ahead of the chassis body. **`MassPropertySystem`** re-places wheel
  connection points when the COM moves — without it a vehicle that loses its rear armour finds its
  wheels have crept backwards under it.
- **`DestructionTestScene`** stopped assembling vehicles itself and now calls the real spawn path.

Four blueprint gaps were recorded rather than guessed at: who owns the chassis's place in an assembly
(DEC-019), two tolerance constants D08 names and D00 never defines (DEC-020), where spawn-time mass
properties belong given D04-R13 (DEC-021), and wheel geometry no schema authors (DEC-022). One
deviation: the loader cannot read collision meshes (DEV-010).

## Rationale / Context
The audit found that `progress/` had been a single global chain, each entry superseding the last
whatever it was about. PROG-002 recorded a working destruction toolchain; PROG-003 superseded it
while being about `PhysicsSystem`, so by today the only active entry said nothing about the tool at
all. A session following the read protocol would have concluded the pipeline did not exist.

## Impact
`game-core`: `asset`, `vehicle`, `system`, `physics`. 144 tests green; `check`, `validateDocs`,
`lintMemory` and the physics tag pass. Recorded as PROG-007/008/009, DEC-019 through DEC-023,
DISC-010, DEV-010. Added `ROADMAP.md` and the CLAUDE.md step that keeps it current.

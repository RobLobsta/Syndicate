# PROG-003: The first simulation system runs; the Bullet step is wired into the schedule

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/06_physics_simulation.md#D06-S5.4, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

## Summary
`game-core` has its first system: `PhysicsSystem` in schedule slot 10, stepping a real `PhysicsWorld` at a fixed `TICK_DT` and mirroring Bullet's state into `Transform` and `Velocity`. The other 26 systems of D04-S4.4 remain unwritten, and nothing yet spawns a vehicle.

## Details

**Status of Work:** (supersedes the corresponding rows of PROG-002)

| Area | State | Notes |
|---|---|---|
| `game-core` physics world (D06-S4.1, S5.1) | done | `PhysicsWorld.create()`: gravity, sequential solver, 10 iterations, split impulse, ERP 0.2/0.8, warm starting + SIMD. Owns exactly its five natives and registers them with `NativeResourceTracker` |
| `game-core` `PhysicsSystem` (D04-S4.4 slot 10) | done | dt guard, impulse queue drain, one `TICK_DT` step at `maxSubSteps = 0`, sorted pull-back, non-finite eviction (D06-E2) |
| Impulse queue (D06-S5.4 step 1) | done | `PhysicsWorld.queueImpulse/queueImpulseAt/queueTorqueImpulse`, drained in `(entityId, queue order)` (DEC-012) |
| Contact collection (`setInternalTickCallback`, D06-S5.1) | not_started | Deliberately not wired: the callback exists to feed `CollisionEventSystem` (slot 11), which sorts the manifolds before gameplay reads them. A collector with no sorter is the G3 violation the sort prevents |
| Constraints (D06-S5.6) | not_started | No `attachBreakable`, no break check. Nothing creates a constraint yet |
| Compound shapes, shape cache (D06-S5.2, S5.3) | not_started | `ShapeCacheKey` exists; the cache does not. Test bodies build their own primitives |
| `MassPropertySystem` (slot 15, D06-S5.7) | not_started | The G10 half of the contract is unimplemented; nothing changes mass yet either |
| `game-core` system catalogue (D04-S4.4) | in_progress | 1 of 27. `SystemSetFactory.forMode` not started |
| `game-core` vehicle/damage (D05, D07) | not_started | unchanged from PROG-002 |
| `game-core` net/ai/match | not_started | unchanged from PROG-002 |
| `blender-tool`, fixtures, `test-environment`, visual mode | in_progress | unchanged from PROG-002 |
| `game-client`, `game-server-headless` | not_started | unchanged from PROG-002 |
| `asset-pipeline` + `schemas/` (D08) | not_started | unchanged from PROG-002 |
| Golden manifests (D14-S7.2) | not_started | unchanged from PROG-002 |

**History (append-only):**
- 2026-08-08 (f): `PhysicsWorld`, `PendingImpulse`, `PhysicsSystem` and 18 tests. 70 JVM tests green in `game-core`, including the first `@Tag("physics")` regression pair: a steel cube rests at 0.5 m ± 0.005 m (T-D06-2) and a 6-body scatter scenario reruns to within `0.001 m` of itself (T-D06-5, D12-R9). Verified with a JDK 21 toolchain override — the sandbox has no JDK 17 and cannot fetch one (DISC-007). `:game-core:check`, `validateDocs` and `:memory-system:lintMemory` are green; `:test-environment:*` was not run (JitPack blocked, DEV-001).

**Acceptance criteria now covered:** AC-D06-1 (dt guard, T-D06-1), AC-D06-2 (`maxSubSteps = 0`), AC-D06-13 for this system (family iteration is by ascending `EntityId`), AC-D06-15 (no wall-clock read), AC-D06-18 in the sense that nothing here re-enables sleeping. AC-D06-3/4/5 hold for the bodies the tests build but have no enforcement point until the spawn path exists.

**What the next session should pick up:** `EntityDestroySystem` (slot 27) is the natural next one — `PhysicsSystem` already destroys entities on a non-finite body, and until slot 27 exists their bodies and motion states are freed by test scaffolding rather than by the engine. After that, `MassPropertySystem` (slot 15) with the compound shape and shape cache of D06-S5.2/S5.3, then `FractureSystem` (slot 13), whose behaviour the harness's `DestructionScene` already encodes. Smaller and self-contained: the `TestWorld` refit of DEV-007, and re-running `:test-environment:verifyFixtures` (both need JitPack reachable).

## Rationale / Context
PROG-002 recorded the game half as "an ECS engine and a component catalogue with no systems on top of it" and named `PhysicsSystem` as the place to start. That row is now false, and a session that trusted it would rewrite work that exists. This entry also records what `PhysicsSystem` deliberately does *not* do — contacts, constraints, mass properties — so the next session does not read those omissions as oversights and re-derive the reasons.

## Impact
`game-core`. Supersedes PROG-002's rows for the system catalogue and for `game-core` physics; every other row of PROG-002 is carried forward unchanged.

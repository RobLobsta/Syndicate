# PROG-003: The first simulation system runs; the Bullet step is wired into the schedule

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/06_physics_simulation.md#D06-S5.4, docs/04_entity_component_model.md#D04-S4.4

**Status:** superseded (by PROG-005)

## Summary
`game-core` has its first system: `PhysicsSystem` in schedule slot 10, stepping a real `PhysicsWorld` at a fixed `TICK_DT` and mirroring Bullet's state into `Transform` and `Velocity`. The harness now runs on that same world, and all five fixtures verify 31/31 against it. The other 26 systems of D04-S4.4 remain unwritten, and nothing yet spawns a vehicle.

## Details

**Status of Work:** (supersedes the corresponding rows of PROG-002)

| Area | State | Notes |
|---|---|---|
| `game-core` physics world (D06-S4.1, S5.1) | done | `PhysicsWorld.create()`: gravity, sequential solver, 10 iterations, split impulse, ERP 0.2/0.8, warm starting + SIMD; owns and tracks its five natives |
| `game-core` `PhysicsSystem` (slot 10) | done | dt guard, impulse drain, one `TICK_DT` step at `maxSubSteps = 0`, sorted pull-back, non-finite eviction (D06-E2) |
| Impulse queue (D06-S5.4 step 1) | done | `queueImpulse/queueImpulseAt/queueTorqueImpulse`, drained in `(entityId, queue order)` (DEC-012) |
| `test-environment` world config (D14-R10) | done | `TestWorld` delegates to `PhysicsWorld.create()`; one margin, solver and step for both (DEV-007) |
| Hull margin (D06-R13a) | done | Source margin zeroed before `btShapeHull`; a simplified hull sits one margin outside its mesh, not two (DISC-008) |
| Fixture verification (D14-S7.3) | done | 5/5 fixtures at **31/31** at the game's 0.01 m margin, Blender 4.2 via the `bpy` module |
| Contact collection (D06-S5.1) | not_started | Deliberately unwired: the callback feeds `CollisionEventSystem` (slot 11), which sorts manifolds before gameplay reads them. A collector with no sorter is the G3 violation the sort prevents |
| Constraints (D06-S5.6) | not_started | Nothing creates one yet |
| Shape cache, compound shapes (D06-S5.2, S5.3) | not_started | `ShapeCacheKey` exists; the cache does not. Must follow R13a when written |
| `MassPropertySystem` (slot 15) | not_started | The G10 half of the contract; nothing changes mass yet either |
| `game-core` system catalogue (D04-S4.4) | in_progress | 1 of 27. `SystemSetFactory.forMode` not started |
| vehicle/damage, net/ai/match, `game-client`, `game-server-headless`, `asset-pipeline`, golden manifests | not_started | as PROG-002 |
| visual mode, `blender-tool` | in_progress | as PROG-002 |

**History (append-only):**
- 2026-08-08 (f): `PhysicsWorld`, `PendingImpulse`, `PhysicsSystem`, 18 tests. First `@Tag("physics")` pair: steel cube rests at 0.5 m ± 0.005 (T-D06-2); 6-body scatter reruns within 0.001 m (T-D06-5). Verified under a JDK 21 toolchain override (DISC-007).
- 2026-08-08 (g): `TestWorld` refitted onto `PhysicsWorld` (DEV-007); a 1 m cube's rest moved 0.505000 → 0.510000 m. `gdx-gltf` removed from `test-environment` (DEC-013).
- 2026-08-08 (h): Blender 4.2 installed (`bpy`), fixtures processed and verified end to end. Two failed PHYS-008 at the new margin and exposed DISC-008 — `btShapeHull` bakes the source shape's margin into its points, so every simplified hull sat two margins out. Fixed in `TestWorld.buildHull` and in D06-S5.2 (new R13a) before `ShapeCache` could inherit it. 5/5 fixtures 31/31; 70 `game-core` and 17 `test-environment` tests green.

**Acceptance criteria now covered:** AC-D06-1, AC-D06-2, AC-D06-13 for this system, AC-D06-15, AC-D06-4 (R13a, asserted on hull geometry) and AC-D14-10. AC-D06-3/5 hold for the bodies the tests build but have no enforcement point until the spawn path exists.

**What the next session should pick up:** `EntityDestroySystem` (slot 27) — `PhysicsSystem` already destroys entities on a non-finite body, and until slot 27 exists their natives are freed by test scaffolding. Then `MassPropertySystem` (slot 15) with the shape cache of D06-S5.2 (follow R13a), and `FractureSystem` (slot 13), whose behaviour `DestructionScene` encodes.

## Rationale / Context
PROG-002 called the game half "an ECS engine and a component catalogue with no systems on top of it" and named `PhysicsSystem` as the place to start; that is now false. This entry also records what `PhysicsSystem` deliberately does *not* do — contacts, constraints, mass properties — so the next session does not read those omissions as oversights.

## Impact
`game-core`, `test-environment`. Supersedes PROG-002's rows for the system catalogue, `game-core` physics, and fixture verification.

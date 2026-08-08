# SESS-006: PhysicsSystem — the Bullet step joins the schedule

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.4, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

## Summary
Implemented the first of D04-S4.4's 27 systems: `PhysicsSystem` in slot 10, on a new `PhysicsWorld` built to D06-S4.1. The simulation now advances — a body falls, lands, and its `Transform` and `Velocity` components say so.

## Details

**Written:**
- `dev.syndicate.core.physics.PhysicsWorld` — the one `btDiscreteDynamicsWorld` per match (D06-R1), with the gravity, sequential solver, iteration count, split impulse, ERP pair and solver mode of D06-S4.1. Owns exactly the five natives it allocates, registers them with `NativeResourceTracker`, guards the double-add of D06-E18, and holds the pending-impulse queue.
- `dev.syndicate.core.physics.PendingImpulse` — a queued central, off-centre or angular impulse. Copies the caller's vectors and rejects non-finite ones (D00-R13).
- `dev.syndicate.core.system.PhysicsSystem` — slot 10, `SIM`: rejects any dt but `TICK_DT`, drains the impulse queue in ascending entity id, steps once at `maxSubSteps = 0`, then pulls transform and velocity back for every body in ascending entity id order. A body whose state has gone non-finite is evicted and its entity destroyed rather than allowed to poison the solver (D06-E2).
- 18 tests: `PhysicsWorldTest` and `PhysicsSystemTest` at L2, `PhysicsRegressionTest` at L3 — the first `@Tag("physics")` tests in the project — plus `PhysicsTestScene`, the scaffold that stands in for the spawn path until `SpawnSystem` exists.

**Verified:** 70 `game-core` tests green, `:game-core:check` (headless safety, layering, package roots, spotless) green, `validateDocs` and `:memory-system:lintMemory` green. `-Ptags=physics` selects the two regression tests and runs them in 0.25 s against a 120 s budget.

**Two numbers worth keeping:** a 1 m³ steel cube dropped from 2 m settles at y = 0.5 m within the 5 mm of T-D06-2 — which is really a measurement of the 0.01 m margin of D06-R13 — and a six-body scatter scenario rerun in a fresh world lands within 0.001 m of its first run (T-D06-5, D12-R9).

**Recorded:** DEC-012 (physics world injected into the system; impulses queue on it), DISC-007 (the sandbox cannot provision JDK 17; foojay is blocked, and the error blames the configuration cache), DEV-007 (the harness's `TestWorld` builds its own world at a 0.005 m margin instead of `PhysicsWorld.create()` — now a real deviation from D14-R10, since `PhysicsWorld` exists), PROG-003.

**Not done, deliberately:** the `setInternalTickCallback` of D06-S5.1, because its consumer `CollisionEventSystem` (slot 11) does not exist and an unsorted manifold list reaching gameplay is the G3 violation the sort exists to prevent. Constraints (D06-S5.6), the shape cache and compound shapes (D06-S5.2/S5.3), and `MassPropertySystem` (slot 15) are likewise untouched.

**Not run:** anything in `test-environment` — it compiles only with gdx-gltf, which resolves from JitPack alone, and JitPack is blocked here.

## Rationale / Context
The session's decisions are in DEC-012 and its environment findings in DISC-007; this entry is the index into them.

## Impact
`game-core`. Unblocks every system that needs a stepped world: `CollisionEventSystem`, `MassPropertySystem`, `FractureSystem`, `DetachSystem`, and the vehicle path.

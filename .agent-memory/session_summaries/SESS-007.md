# SESS-007: the harness runs on the game's physics world

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/14_test_environment.md#D14-S5.5, docs/06_physics_simulation.md#D06-S4.1, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
Resolved DEV-007: `TestWorld` now delegates to `PhysicsWorld.create()` instead of building its own Bullet world at a different collision margin. The blocker turned out to be a single unused dependency, and the fix needed no fixture expectations updated — which is itself the finding.

## Details

**Changed:**
- `TestWorld` holds a `PhysicsWorld` and delegates construction, gravity, solver configuration, the step and body membership to it. Its `COLLISION_MARGIN_M` is an alias of `PhysicsWorld.COLLISION_MARGIN_M`, so the harness and the game cannot hold two different numbers again.
- `gdx-gltf` removed from `test-environment` and from D02-S4.5's dependency column, with a note under the table on why the harness must not have it (DEC-013). Nothing in the repository ever imported it; DEC-008 made it dead when the harness got its own GLB reader, and because Gradle resolves the whole compile classpath regardless, that one line made the module unbuildable wherever JitPack is blocked.
- `TestWorldTest` (L2, 3 tests): the margin constants are the same value and equal 0.01 m; gravity, solver iterations, split impulse and ERP2 read back off the live world prove it came from `PhysicsWorld.create()` (AC-D14-10); a synthetic 1 m cube rests at half-extent + one margin. No Blender needed, so CI can catch a margin regression anywhere.

**Measured, not assumed:** the same cube rested at **0.505000 m** under the harness's old 0.005 m margin and rests at **0.510000 m** under the game's 0.010 m — the +0.005 m the difference predicts, to six decimal places.

**Nothing needed re-recording.** The expected failure did not happen, for a reason worth keeping: PHYS-008 computes its expected resting height as `-min.y + TestWorld.COLLISION_MARGIN_M`, so it had always measured the harness against the harness's own margin and always agreed. `fixtures/golden/` is empty (D14-S7.2 not started), so no golden pinned a height either. A wrong constant that every check derives from is invisible to all of them — the new assertions compare the two constants directly rather than deriving from one.

**Verified:** `:shared-models:check`, `:game-core:check`, `:test-environment:check`, `:memory-system:check`, `:asset-pipeline:check` and `validateDocs` all green — 70 tests in `game-core`, 16 in `test-environment`.

**Not run:** `:test-environment:verifyFixtures`, which needs Blender to produce the fixtures, and Blender is not installed here. `:game-client:*` still cannot resolve, because the client legitimately keeps gdx-gltf and JitPack is blocked.

## Rationale / Context
The measured before/after and the "no expectation needed updating" finding are the parts a future session cannot re-derive cheaply; both are in DEV-007 alongside the resolution.

## Impact
`test-environment`, `docs/02_technical_architecture.md#D02-S4.5`. DEV-007 resolved; AC-D14-10 satisfied and now asserted.

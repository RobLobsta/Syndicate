# SESS-007: the harness runs on the game's physics world

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/14_test_environment.md#D14-S5.5, docs/06_physics_simulation.md#D06-S4.1, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
Resolved DEV-007: `TestWorld` delegates to `PhysicsWorld.create()` instead of building its own Bullet world at a different collision margin. Installing Blender then let the fixtures actually run, and the margin change exposed a real hull-construction bug (DISC-008) that would have shipped into the game.

## Details

**Changed:**
- `TestWorld` holds a `PhysicsWorld` and delegates construction, gravity, solver, step and body membership; its `COLLISION_MARGIN_M` is an alias of the game's, so the two cannot diverge again.
- `gdx-gltf` removed from `test-environment` and from D02-S4.5, with a note on why the harness must not carry it (DEC-013). Nothing imported it; it was the only thing making the module unbuildable without JitPack.
- `TestWorld.buildHull` zeroes the source shape's margin before `btShapeHull` (DISC-008), and D06-S5.2's pseudocode gained R13a saying so.
- `:test-environment:verifyFixtures` now depends on `:test-environment:classes`.
- `TestWorldTest` (L2, 4 tests): the two margin constants are equal and 0.01 m; the solver settings read back off the live native world (AC-D14-10); a cube rests at half-extent + one margin; a simplified hull's own geometry carries no baked-in margin.

**Measured, not assumed:** a 1 m cube rested at **0.505000 m** at the old margin and rests at **0.510000 m** at the game's.

**The expected fixture failure did happen — but only with Blender present.** `pip install bpy==4.2.0` gives Blender 4.2 as a Python module (PyPI is reachable where JitPack is not), so `processFixtures` and `verifyFixtures` could run for real. Two of five failed PHYS-008, and not because an expectation needed re-recording: `btShapeHull` bakes the source shape's margin into its points, so every *simplified* hull sat two margins outside its mesh. The error was exactly one margin, so at the old 0.005 m it equalled the tolerance and passed on the boundary. All five now verify **31/31**.

**Corrects an earlier claim in this entry's first version:** that no fixture expectation needed updating. The derived-expectation reasoning was right — PHYS-008 computes `-min.y + margin`, and no golden pins a height — but the conclusion "nothing will fail" was drawn without Blender, and was wrong for curved sources.

**Verified:** `:shared-models:check`, `:game-core:check`, `:test-environment:check`, `:memory-system:check`, `:asset-pipeline:check`, `validateDocs`, and `:test-environment:verifyFixtures` — 70 tests in `game-core`, 17 in `test-environment`, 5/5 fixtures at 31/31.

**Still not run:** `:game-client:*`, which legitimately keeps gdx-gltf while JitPack is blocked.

## Rationale / Context
The hull mechanism and its measurements are in DISC-008; the margin resolution is in DEV-007. This entry records that the two are connected — the margin fix is what made the hull bug visible.

## Impact
`test-environment`, `docs/02_technical_architecture.md#D02-S4.5`, `docs/06_physics_simulation.md#D06-S5.2`. DEV-007 resolved, DISC-004 superseded, AC-D14-10 asserted.

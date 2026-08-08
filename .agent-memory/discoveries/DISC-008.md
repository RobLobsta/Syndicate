# DISC-008: btShapeHull bakes the source shape's margin into its points

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.2, docs/14_test_environment.md#D14-S5.5

**Status:** active

## Summary
`btShapeHull` samples support points from the shape it is handed, and those include that shape's collision margin. Its `buildHull(margin)` argument is ignored. So a source shape carrying a margin yields hull points already pushed one margin outward, and the finished shape adds its own on top — a simplified hull sits two margins outside its mesh, an unsimplified one exactly one. Supersedes the remedy recorded in DISC-004.

## Details

DISC-004 found the double margin and prescribed `buildHull(0)`. That is not the fix: the argument does nothing (Bullet 2.8x marks the parameter unused). The margin comes in through the *source* shape, which `TestWorld.buildHull` was setting before simplifying.

**Measured**, on the processed fixtures, reading each finished hull's `localGetSupportingVertexWithoutMargin` along −Y against the mesh's own lowest vertex (`meshMinY = 0` for all three):

| Fixture | Vertices | Hull bottom, before | Hull bottom, after |
|---|---|---|---|
| `test_cube_1m` | 24 → 24 (not simplified) | 0.000000 | 0.000000 |
| `test_sphere_r0.5` | 1488 → 42 | **−0.010000** | 0.000000 |
| `test_cylinder_r0.5_h1` | 290 → 42 | **−0.010000** | 0.000000 |

The fix is one line: `raw.setMargin(0)` before constructing `btShapeHull`, with the real margin set on the result.

**Why it surfaced only now.** The error is exactly one margin, so at the harness's old 0.005 m it equalled `RESTING_POSITION_M` (0.005) and PHYS-008 passed on the boundary. Doubling the margin to the game's 0.01 m doubled the error and the sphere and cylinder failed at 30/31. The bug is older than the margin change; the margin change is what made it visible.

**Two secondary findings from the same probe.** `btShapeHull` returns 42 points whatever `maxVertices` says — it samples a fixed direction set, so that parameter is only a threshold for whether to simplify, not a budget (the game's shard hulls come from the Blender tool, D09-S5.5, and do respect D06-R6's 32). And `:test-environment:verifyFixtures` had no dependency on `:test-environment:classes`, so the first run of the fix was verified against the previous build's classes and reported failing; the dependency is now declared.

## Rationale / Context
Anyone re-deriving this from DISC-004 will pass 0 to `buildHull`, watch nothing change, and conclude the double margin has some other cause. The distinguishing measurement is the finished hull's support point without margin: it must land on the mesh surface, and if it is one margin below, the source shape carried a margin into the simplifier.

## Impact
`test-environment`: `TestWorld.buildHull`. `docs/06_physics_simulation.md#D06-S5.2`, whose pseudocode prescribed the broken sequence and now sets the source margin to 0 (R13a) — this would have shipped into `ShapeCache` when D06-S5.2 is implemented, floating every simplified part hull two margins above the ground.

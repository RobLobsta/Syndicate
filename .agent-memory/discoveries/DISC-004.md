# DISC-004: btShapeHull.buildHull(margin) double-counts the collision margin

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S4.3, docs/14_test_environment.md#D14-S4.5.2

**Status:** superseded (by DISC-008)

## Summary
Passing a shape's collision margin to `btShapeHull.buildHull(margin)` offsets the generated hull points outward by that margin, and the `btConvexHullShape` built from the result then adds its own margin on top. A simplified hull therefore has its collision surface two margins outside its mesh while an unsimplified one has exactly one. Pass `0`.

## Details

**Symptom:** PHYS-008 ("drop and rest") could not be satisfied for both the cube and the sphere fixtures at once. Measured resting heights, mesh bottom at `y = 0` in both cases, Bullet's default 0.04 m margin:

| Fixture | Hull points | Simplified? | Rest height |
|---|---|---|---|
| `test_cube_1m` | 24 | no (24 <= 64) | 0.040 m |
| `test_sphere_r0.5` | 42 | yes (362 -> 42) | 0.080 m |

Every attempt to express the expected height in terms of the *mesh* was right for one fixture and 0.04 m wrong for the other. `btCollisionShape.getAabb` was no better — it reports `localAabb ± margin`, so it inherits the same double count.

**Fix:** `TestWorld.buildHull` calls `simplifier.buildHull(0f)` and sets the margin explicitly on the resulting shape. Both fixtures then rest at exactly `meshBottom + margin`, and PHYS-008 became expressible without a per-shape special case.

**Second change, separate reason:** the margin is set to 0.005 m rather than Bullet's 0.04 m default. Four centimetres on a 1 m part is a visible float, and the harness's captures are meant to be evidence about the simulation rather than an approximation of it.

## Rationale / Context
This cost most of an afternoon and the fix is a single `0f` argument, which the diff makes look arbitrary. The wrong version is also not obviously wrong in play — a shard hovering 8 cm above the ground reads as "physics feels floaty" rather than as a shape-construction bug — so without this entry the same argument gets re-added by someone who reasonably thinks the margin belongs there.

## Impact
`test-environment/.../physics/TestWorld.java`, `check/CheckRunner.java` (PHYS-008/009). The game's own `ShapeCache` will build hulls the same way and needs the same argument.

# DISC-006: the point-in-mesh ray test double-counts a ray grazing a shared edge

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2

**Status:** active

## Summary
`fracture._inside_predicate` counts a `+X` ray crossing by triangle parity, and a ray passing exactly along the edge two triangles share is counted **twice** — so the point reads as outside when it is inside. Its docstring claims a tie-break resolves this; there is none.

## Reproduction
The hollow box of `test_complex_hollow`, sampled on a regular 13³ grid inside its AABB:

```
point (-0.46153846, -0.30769231, 0.19230769)   # solidly inside the -x wall
_inside_predicate(...) -> False
crossings at x = [-0.45, -0.45, 0.45, 0.5]     # -0.45 counted twice
```

`-0.45` is the cavity's `-x` face. That face is a quad split into two triangles, and the sample point lies exactly on the split diagonal `z = 0.05 + (y + 0.45)`. `_ray_x_hits_triangle` accepts a projected point on an edge for *both* adjacent triangles, because its inclusion test is `all >= 0 or all <= 0` and a point on the shared edge has a zero determinant against both.

## Details
The condition for it to bite is a sample whose `(y, z)` lands exactly on a triangulation edge, which is vanishingly unlikely for the PCG32 site samples the predicate was written for and routine for any regular grid — which is how it surfaced, in a test that used the predicate as ground truth for a convex decomposition and blamed the decomposition.

**Impact on the tool is benign, which is why it survived.** The predicate is used only to accept or reject a candidate fracture site (`sites.uniform_sites`). A false "outside" rejects a valid site, rejection sampling immediately draws another, and the fracture is unaffected. Nothing else calls it. It was left as it is rather than fixed, because the fix is a fill-rule tie-break on the projected edge test and the change belongs with a reason to touch site placement.

**The real hazard is as an oracle.** Anything that uses it to decide "is this point in the solid?" — a test, a coverage metric, a future interior-cell check — inherits a wrong answer on a set of points that a regular sampling pattern hits systematically rather than rarely.

## Rationale / Context
This cost a debugging cycle: the decomposition was correct, the assertion that failed was correct in form, and the ground truth was wrong. Without this entry the next session that samples the interior of a mesh on a grid re-derives the same false failure and, worse, may "fix" working geometry code to satisfy it. `test_decompose.py` now decides membership analytically and says why.

## Impact
`blender-tool/syndicate_fracture/fracture.py` (`_inside_predicate`, `_ray_x_hits_triangle` — its docstring overstates the tie-break), `blender-tool/tests/unit/test_decompose.py`. No effect on shipped assets.

# DISC-039: the fracture tool cannot cut a windscreen, and says so seven different ways

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.7

**Status:** active

## Summary
Every `glass` part on both shipped cars failed to fracture. The D09 tool is sound and its
self-verification is what caught each failure, but a solidified 5 mm curved windscreen is not
the kind of solid its Voronoi path was built and fixture-tested against, and it fails on three
different guards depending on the pane.

## Details
Stage 7 solidifies a `glass` part and hands it to the D09 tool (D15-S5.7). On fourteen glass
parts across the two cars, the failures were:

- `convex decomposition unavailable (decomposition exceeded a depth of 96): cutting the mesh
  instead` — logged for every pane. DEC-011's solid BSP splits a non-convex source on its own
  face planes; a windscreen is hundreds of nearly-parallel faces, so the partition goes deep
  and gives up, falling back to a cutting path that does not conserve volume.
- `shard mass sum 40.8 kg deviates 78% from part mass 22.9 kg` — the consequence of that
  fallback. G7's mass conservation is checked and correctly refuses the result.
- `shard mass 0.0029 kg is below the minimum after merging` — a thin shell cut into 24 cells
  produces slivers that the merge step cannot rescue.
- `hull for 'shard_010' does not enclose its source: worst vertex is 0.018 m outside` — a
  shard so thin that its convex hull is numerically hopeless.

Nothing here is a defect in the fracture tool; every one of these is the tool declining to
publish something wrong. What it means is that **pre-authored glass shattering is not available
yet**, and the pipeline's own handling of that is the part worth keeping: each pane ships with
no `shardMesh` and no `fractureManifest`, which is D15-R46 working exactly as intended — the
manifest promises only what exists, the asset is valid, and the pane detaches whole (D07-E5).

## Rationale / Context
Recorded so the next session does not read D15-S5.7's "cell-fracture into shards at authoring
time" and assume it happens. It does not, on real glass, today.

The direction if somebody takes it on: the geometry being fractured is a *shell*, and a shell
is the one case where the general solid path is the wrong tool. A pane cut in 2D across its
own surface and then solidified per shard would sidestep the decomposition entirely — cells
first, thickness second, rather than thickness first and cells into the result.

## Impact
- Both prepared cars ship with unfractured glass and a note per pane saying why.
- D15-S5.7's glass row is aspirational until this is fixed; T-D15-38 records the expectation.
- The Eclipse's chassis *does* carry damage morphs, so deformation is not blocked by this —
  only shattering is.

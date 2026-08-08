# DISC-009: btShapeHull has no target vertex count; it reduces to 42 and no further

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.2, docs/06_physics_simulation.md#D06-S4.3, docs/09_blender_destruction_tool.md#D09-S5.5
**Status:** active

## Summary
`btShapeHull` cannot be asked for a vertex budget. It samples support points along a fixed set of 42 unit-sphere directions and keeps the unique ones, so its output is bounded at 42 whatever the caller wants. `MAX_HULL_VERTICES` (64) is therefore always satisfied and `MAX_SHARD_HULL_VERTICES` (32) cannot be enforced at runtime at all.

## Details
D06-S5.2's pseudocode reads as though the simplifier takes the budget:

```pseudo
if meshData.vertexCount > maxVertices:
    hullUtil = new btShapeHull(raw)
    hullUtil.buildHull(0)
```

It does not. `buildHull`'s only parameter is the margin, which Bullet ignores (D06-R13a, DISC-008), and there is no second parameter. Internally `btShapeHull::buildHull` walks `NUM_UNITSPHERE_POINTS = 42` fixed directions, takes the support point in each, and hulls the deduplicated set.

Measured: a 500-point golden-spiral sphere mesh simplifies to **42** vertices, both when asked for 64 and when asked for 32.

Consequences:

| Budget | Value | Enforced at runtime? |
|---|---|---|
| `MAX_HULL_VERTICES` (part) | 64 | Yes, trivially — 42 < 64 |
| `MAX_SHARD_HULL_VERTICES` (shard) | 32 | **No.** 42 > 32 and there is no further reduction available |

This is not a hole in the design, because meeting the shard budget was always the asset pipeline's job: D06-R6 says shard hulls come from the Blender tool "decimated further than parts", D09-S5.5 does the decimation, and D14 ASSET-011 is a *blocking* check on the result. The runtime simplification is a safety net for a mesh that arrives over budget, not the mechanism that meets it. `ShapeCache` logs at WARN naming the asset when a simplified hull is still over its budget, so a pipeline regression is visible at load rather than as unexplained frame-time drift.

## Rationale / Context
The pseudocode invites the reading that the runtime enforces both budgets, and a session that believes it will write an assertion on 32 vertices, watch it fail on real content, and go looking for a bug in its own hull construction — or worse, "fix" it by raising `MAX_SHARD_HULL_VERTICES` to 42 and silently retiring a budget the pipeline was meeting.

## Impact
`game-core` `physics` (`ShapeCache`). No change to the Blender tool or the harness: both already treat the shard budget as an asset-time property. `ShapeCache.BT_SHAPE_HULL_VERTICES` records the ceiling as a named constant.

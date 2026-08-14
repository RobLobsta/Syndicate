# DISC-040: our convex hull is not convex when the points are a slab of glass

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.5, docs/09_blender_destruction_tool.md#D09-S5.2.1

**Status:** active

## Summary
`convex_hull` returned face sets that were not polyhedra at all on glass shards — 30 vertices
carrying 71 triangles where Euler allows 56, with four of the hull's **own vertices** lying
22 mm outside it. The pipeline saw this only as a pane that would not fracture (exit 71,
"hull does not enclose its source"), three stages away from the cause.

## Details
Incremental hull insertion rests on one invariant: the faces a new point can see form a
topological disc, so replacing them with a fan to that point leaves a closed surface. Two
independent things break it when the points are a 5 mm slab whose vertices sit on each
other's face planes, and both broke silently.

**The visible set stops being a disc.** A face in the middle of the visible cap comes out at
−1e−12 instead of +1e−12 and reads as hidden, punching a hole in it; the horizon then has two
cycles rather than one and the fan built across it is self-intersecting. Instrumented on
`shard_001` of `glass_eclipse_side_window_l_01`, this happened at 16 of 44 insertions.

**A dropped fan triangle leaves a hole.** `_make_face` returns `None` when the new point is
collinear with a horizon edge — routine on a slab — and the loop appended the rest of the fan
anyway. From then on the surface is non-manifold and every later visibility test reads through
the hole.

This is not an epsilon that needs tuning. The distances genuinely *are* at the noise floor,
because the points genuinely *are* on each other's planes. The fixes are structural: force the
classification into two connected regions before reading the horizon, and make each insertion
transactional, adopting the new face set only if it is still closed. All 24 shards then hull
correctly, the worst enclosure error being 1 micron on the one point that gets rolled back.

The same measurement session corrected a second thing. The shell fracture's coverage check
recovered each patch's area from its *solidified* volume, which carries `solidify`'s curvature
inflation — 0.1% on a windscreen, 6% on a tightly curved quarter-light — and then compared it
against a 1% bar. Four of nine panes failed a check about a quantity the cut is not
responsible for. Measuring the patches **as cut** instead brings every pane to within 0.03%.

## Rationale / Context
Recorded because the failure is invisible at every layer above it and because both fixes look
like paranoia without the measurement. A future session reading `_repair_visible_cap` will see
a reclassification pass that never fires on any of the test suite's clean geometry, and a
transactional insertion that rolls back once in 24 shards, and will be tempted to simplify
both away.

The regression fixture is `tests/unit/glass_shard_fixture.py` and it is **measured, at full
precision, on purpose**: constructed slabs — thin, curved, densified along the rim — all hull
correctly under the pre-fix algorithm, and rounding the real points to a micrometre makes them
hull correctly too. A tidier fixture would guard nothing.

## Impact
- All nine glass panes on the two shipped cars now fracture; before this, four did not.
- `build_hull` inflates for enclosure whether or not it simplified, since a rolled-back
  insertion leaves a shortfall of its own.
- Anything thin benefits, not only glass: this hull is used for every shard and every part.

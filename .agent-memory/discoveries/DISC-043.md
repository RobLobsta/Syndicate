# DISC-043: thickening a curved patch inflates it, so area is not recoverable from volume

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2.1

**Status:** active

## Summary
The shell fracture's coverage check recovered each patch's area from its *solidified* volume
and held the result to 1%. Four of nine panes on the shipped cars failed it — not because the
cut was wrong, but because `solidify` offsets along vertex normals and a patch of convex
curvature therefore encloses more than `its own area × thickness`.

## Details
Measured deviation, patch volume against `area × thickness`:

| Pane | Source area | Recovered area | Deviation |
|---|---|---|---|
| Eclipse windscreen | 1.8330 m² | 1.8440 m² | 0.60% |
| Stampede windscreen | 1.6592 m² | 1.6613 m² | 0.13% |
| Eclipse rear window | 1.0273 m² | 1.0772 m² | 4.86% |
| Eclipse side window | 0.6121 m² | 0.6288 m² | 2.74% |
| Stampede quarter-light | 0.2890 m² | 0.3070 m² | 6.23% |

The excess tracks curvature rather than area: the flattest pane on either car is out by a
tenth of a percent and the tightest by six.

The fix is to measure the patches **as cut**, before thickening, which is the quantity the cut
is actually responsible for. Every pane on both cars then covers its source to within 0.03%.

The inflation itself is not an error and is not removed. It is real glass — an offset surface
on the convex side genuinely is larger than the one it came from — so it belongs in the shard's
mass, and D09-R11c defines a shell part's volume as the sum of its shards' for that reason.
G7's conservation is then exact rather than true to a tolerance.

## Rationale / Context
Two quantities looked interchangeable and were not, and the code had *already recorded* the
difference: `part_volume_m3`'s docstring named the 3–5% inflation as real material while the
check twenty lines above treated the same 3–5% as an error. Writing both without noticing they
contradicted each other is the failure worth remembering.

The general form: when a check re-derives its input through a transformation, it is testing the
transformation too. Here the transformation was solidify, whose whole job is to change the
quantity being recovered.

## Impact
- All nine panes on the two shipped cars fracture; before this, four failed the coverage guard.
- D09-R11b states the rule and the measurements, so the next reader does not re-derive them.
- The check is now tighter as well as correct: 0.03% observed against a 1% bar, where the old
  form used most of its tolerance on curvature.

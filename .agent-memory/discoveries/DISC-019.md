# DISC-019: one shipped car names its materials, the other does not

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S4.2, docs/15_vehicle_preparation_pipeline.md#D15-S4.3

**Status:** active

## Summary
Labelling shells by material name plus a physical glass test covers **99.4% of the Stampede's
triangles and 35.9% of the Eclipse's**. The Stampede's materials are called `…Window_Material1` and
`…CallipersCalliperA_Zone…`; the Eclipse's are called `bw00.001`, `bwfmp`, `bwfgd` and `oyctp`. Any
segmentation design that relies on names works on one of the two cars this project ships.

## Details

**The measurement.** Whole-token matching over a vocabulary of eleven labels, plus `alphaMode` /
`KHR_materials_transmission` / roughness for glass:

| | shells | labelled triangles | unlabelled |
|---|---|---|---|
| Stampede | 6,078 | 99.4% | 12 shells, 1,386 t |
| Eclipse | 6,830 | 35.9% | 5,178 shells, 181,399 t |

**Why the escape hatch is affordable.** The Eclipse's 64% of unlabelled geometry is covered by
**six material names**. Overrides keyed by *material* rather than by shell turn "label 5,178 things"
into "label 6 things", which is why D15-R9 fixes that key.

**Bounding-box clustering does not rescue it.** Union-find over AABB overlap, with any padding
sufficient to join a door skin to its inner card, collapses **both** cars to a single cluster —
every panel's box overlaps its neighbour's. Grouping must be by `(label, side, index)` after
labelling, not by geometry before it (D15-R18).

**Substring matching is a trap.** `rim` occurs inside `p·rim·ary`, so a naive token test labelled
`vehicle_generic_smallspecmap_PRIMARY` — the Eclipse's paint — a wheel, and inflated the wheel share
from 13.5% to 20.8%. D15-R5 requires whole-token matching for this reason.

**What survives on both.** Geometry and material *physics*. Transmission and blended-with-low-
roughness find glass on both cars regardless of naming, and rotational symmetry finds what turns.
Those are the cues D15-R4 marks "always".

## Rationale / Context
The obvious plan — read the material names, they are right there — is 99% correct on the model you
happen to test with and 36% correct on the other one. Recorded with both numbers so the next session
does not rediscover it by shipping a labeller that works on the Mustang.

## Impact
`docs/15_vehicle_preparation_pipeline.md` §4.2–4.3, `blender-tool/syndicate_dissect`, and the
`parts.json` override the Eclipse will need and the Stampede will not.

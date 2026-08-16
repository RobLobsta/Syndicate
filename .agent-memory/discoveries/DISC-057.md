# DISC-057: a downloaded model's scale is unknown, so every metre threshold measures nothing

**Date:** 2026-08-16
**Category:** discoveries
**Related Docs:** docs/17_weapon_system.md#D17-S5.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.5

**Status:** active

## Summary
Both shipped weapon models import into Blender at **100×**. Every absolute threshold applied to them
before that is corrected therefore measures the wrong thing — and the failure is silent, because each
stage produces a plausible-looking result on the way to a wrong one. The cannon separated into **203
shells instead of 22**, its bore axis was fitted to rivets, and it classified as a `LASER` weighing
631 kg.

## Details
The chain is worth writing down because no single link looks like a bug.

`syndicate_prepare.clean_topology` welds at `WELD_DISTANCE_M` = 0.1 mm and dissolves slivers at a
fixed edge length (D15-S5.5). On a model at 100× that is a **one-micron** weld: nothing welds. Two
triangles sharing an edge in appearance but not in topology stay two connected components, so
`separate(type='LOOSE')` returns 203 shells where the file has 22 real pieces.

The bore-axis fit then samples up to 96 vertices from every barrel-like shell and weights them
equally, so 203 shells of rivets and hinge pins outvoted the one barrel. The fitted axis came out
`(-0.03, -0.38, -0.92)` on a model whose long axis is X. With the axis wrong, the axial cue's whole
premise — position along the gun — is meaningless, no shell was labelled `barrel`, and `derive_family`
correctly concluded there was nothing barrel-shaped to measure.

The fix is two normalisations rather than one, in this order:

1. **A unit pre-scale at load**, before style, repair or separation: scale so the largest extent is
   1.0. This is what makes the repair stage's absolute constants mean what they say. 22 shells.
2. **A bore-aligned normalisation before labelling**: rotate the bore onto +Z, scale to unit bore
   length, put the mount face on the origin. This turns every *ensemble* threshold into a proportion
   of the gun, which is what each of them always meant.

A third scale, to the size class's target length, happens after classification.

## Rationale / Context
The tempting reading is "the tolerances need tuning for weapons". They did not. Every threshold was
a reasonable number being applied in units nobody had established, and tuning them would have
produced constants that worked on these two models and on nothing else — the exact failure D15-R7
names.

## Impact
- `syndicate_weapon.labels` states in its own text that its distance thresholds are **fractions of
  bore length**, so the next person cannot reintroduce a metre.
- The bore fit additionally drops shells under `BORE_FIT_MIN_EXTENT_FRAC` (0.35) of the longest
  barrel-like one, because "technically slender" catches a lot of rivets on a detailed model.
- Anything else reusing D15's repair stage on downloaded art has the same trap waiting.

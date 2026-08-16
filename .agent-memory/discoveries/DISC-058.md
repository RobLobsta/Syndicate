# DISC-058: a seam budget tests the artist; what the pipeline owes is contact

**Date:** 2026-08-16
**Category:** discoveries
**Related Docs:** docs/17_weapon_system.md#D17-S5.8

**Status:** active

## Summary
D17's seam rule was first written as a **gap budget**: after export, the distance between parent and
child must be under 2 mm or the tool fails. It failed every weapon, and the reason is that the
assertion was about the wrong party. Real art models clearances — a barrel inside its shroud, a sight
above its rail — and a gap is the artist's decision, not the pipeline's defect.

## Details
The first run reported "6 of 6 seams open; worst mount→receiver at 150.6 mm". Two of those numbers
were the scale bug (DISC-057) and the rest were real modelled clearances of 15–20 mm on a 1.4 m gun.
Tightening or loosening the budget just moves which models pass.

What the pipeline is actually responsible for is **never inventing a join**. A slot placed between
two parts that do not meet is a guess, and it is the thing that makes a fitted weapon look wrong.
So `WEAP-004` now asserts that every join was found from parent and child geometry within reach of
each other, records how many contact points formed it, and reports the reach it was found at — a join
found only at the widest pass reads as the weak join it is. Zero contact points is the failure.

The same reframing fixed a second problem for free. D17-R43 says parenting follows **support**, and
the tree in D17-R42 is a proposal: where the taxonomy's parent is absent or not touching, the child
is re-parented onto the nearest sub-part it does touch. The shipped cannon has no separate barrel
group, so its muzzle hangs off the receiver — and the report says why, rather than the tool refusing
a model for having geometry the taxonomy did not predict.

## Rationale / Context
The instinct behind the budget was right — "no sloppy seams" was the ask — but a tolerance is the
wrong instrument for it. The join *position* is what the eye reads, and that is what the contact
centroid fixes: two parts meeting along a ring join at the ring's centre, rather than at whichever
centroid the geometry happened to weight.

## Impact
- Both shipped weapons pass with every join on real contact; three of nine needed a widened reach,
  which is visible in the report rather than hidden by a looser constant.
- `MOUNT_SEAM_TOL_M` is gone, replaced by `SEAM_CONTACT_REACH` (0.03 of bore length) and three
  widening passes. D17-R44 and D17-R63 were amended in the same commit.

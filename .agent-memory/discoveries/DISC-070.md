# DISC-070: a three-blade rotor's box is not centred on its mast

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.4, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
The Kestrel's main rotor boxes on `x = -0.760`; its mast stands at `x = 0.006`. Three blades 120°
apart do not box symmetrically about the hub — only an even number of evenly spaced blades does. Four
pieces of the pipeline read that centre as the hub, and each failed differently and silently.

## Details
The disc is 7.825 m in x by 8.201 m in z. It looks symmetric and is not: with blades at 0°, 120° and
240°, the x extremes come from two blades and the z extremes from one, so the box is offset by about
a fifth of the radius. The **area centroid** is the hub exactly, because the blades are equal —
`(0.006, 2.972, 1.339)`, matching an independent minimum-enclosing-circle fit to three decimals.

What read the box centre, and what each did with it:

1. **The symmetry cue.** From the box centre the blades fall in fifteen of twenty-four sectors with
   **symmetry order 1** — none at all. From the centroid, nine sectors and **order 3**. This is why
   the first rotor cue found nothing, and why it looked like a threshold problem.
2. **The exported radius.** Half the larger in-plane extent is 4.10 m; the true swept radius is
   4.724 m. 13% low — and thrust goes as the square of the radius, so it is a third of the
   aircraft's lift.
3. **The part origin.** `_origin_for` returns the bounds centre, whose own docstring warns that a
   part offset from its origin *orbits* rather than turns. A wheel is exempt because a wheel's box
   centre genuinely is its axle; a three-blade rotor is not, and would have been drawn sweeping a
   0.77 m circle around its own mast.
4. **The vehicle's footprint.** Not the offset but the *span*: 7.83 m across because that is the
   rotor, against a 3.10 m airframe, which made the kerb-mass estimate a **15.7-tonne helicopter**.

## Rationale / Context
Worth an entry because the failure mode is invisible and the reflex is wrong. Every symptom pointed
at a threshold — "the disc test is too strict", "the symmetry bar is too high" — and each of those
fixes would have loosened a cue until something else matched it. The actual cause is one point being
0.77 m from where four callers assumed it was, and nothing printed a hub position.

The rule to carry forward: **for anything that rotates, the axis is the area centroid and the extent
is the furthest vertex from it.** A bounding box answers neither for an odd blade count, and "it
looks symmetric" is not evidence — this disc looks perfectly symmetric in every render.

## Impact
`cues._rotor_axle`, `manifest._disc_radius_m`, `manifest._origin_for` and `manifest.body_width_m` all
take the centroid or exclude the rotor. DEC-093 records the decisions; this records why the box was
wrong, which is the part that gets re-derived otherwise.

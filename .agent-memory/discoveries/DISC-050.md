# DISC-050: a wheel measured from the shells that voted for it is not where the wheel is

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.4, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
A wheel part's origin came from the corner model's `axle` — the bounding box of whichever shells
voted "wheel". That set spans brake furniture as well. On the Stampede it sat 3.6 cm above and 3 cm
outboard of the wheel's actual centre, so every wheel spun about a point off its own middle and the
car's track came out 6 cm wide of the real one.

## Details
A wheel group is a disc and is symmetric about its axle in every direction, so its **own** bounding
box centre *is* the axle, exactly. The corner model still decides which shells are a wheel; it no
longer decides where the wheel is.

Two further faults surfaced in the same measurement and are fixed with it:

- **A fifth wheel.** A 0.8 kg, 104-triangle fragment half a metre up the Stampede's driver's door
  was labelled `wheel` by its material name, survived the small-part floor, took a `WHEEL` slot, and
  would have been given a ray cast — the car would have ridden on it. A wheel is a *position*, not a
  label: a `wheel` or `hub` group belonging to no corner is now folded into the chassis.
- **An asymmetric axle.** The two front corners are measured independently (DEC-066), which on real
  art differs by millimetres and is worth keeping. On the Stampede they differed by 6 cm, and a car
  whose left wheel is 6 cm further out than its right sits crooked and drives on two wheels. Beyond a
  2 cm tolerance the pair is now mirrored.

## Rationale / Context
All three were invisible while the five-part hand-cut content shipped and the preparation pipeline's
output was only ever inspected in reports. Shipping that output is what made them measurable — and
it is worth remembering that the pipeline had been "done" for a session before any of this was true.

## Impact
- D15-R45c and R45d record the placement rule and the symmetry rule.
- The Stampede's wheelbase moves to within 9 mm of the published figure; its track stays 2.9 cm wide
  per side, which is the art, and `ART_TRACK_TOLERANCE_M` widens to 8 cm to say so honestly.

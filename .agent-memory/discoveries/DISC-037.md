# DISC-037: a material name defined an axle, and took a third of the car with it

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.4, docs/15_vehicle_preparation_pipeline.md#D15-R23a

**Status:** active

## Summary
The Eclipse carries a flat bracket, 0.35 × 0.10 m and round to 0.29, whose material is called
`vehicle_generic_smallspecmap_WHEEL`. C3 matches the whole token `wheel` and labels it one. As a
**seed** for a wheel corner it dragged the front axle 0.37 m rearward, inflated the wheel to
1.44 m across, captured 891 shells — 37% of the car — and exported every one of them as brake
furniture.

## Details
The first run of the finished pipeline on real art produced six corners on the Eclipse and eight
on the Stampede, where both have four wheels. Two of the Eclipse's were 7.7 mm across.

The cause is a conflation the design did not name: **seeding a corner and belonging to one are
different questions.** Everything downstream of a corner is measured from its seeds — the axle
position, the tyre diameter, the capture radius, and therefore which shells are judged for
rotational symmetry at all. A shell that reaches the `wheel` label on a *name* is a perfectly
good member of a wheel assembly (a hub cap is one) and a catastrophic definition of where the
axle is.

D15-R6 already says C2 outranks C3 because "a file's declared transparency is what it will
render as; its material *name* is a comment". The same argument applies with more force here,
and it had not been carried into D15-S5.4: a comment cannot place an axle.

The fix is a geometric gate on seeds only (D15-R23a) — round in side view, 0.45–1.20 m across,
under 0.75 m wide, axled below 0.65 m, at least 0.45 m outboard — plus a rule that a corner in
which nothing turns is dissolved (D15-R23b). Both cars then produce exactly four corners, and
the axles land where the hand-authored slots are: the Eclipse's front at
`x = ±0.8563, z = 1.4565` against the shipped chassis's `±0.8563, 1.4565`, to four decimals.

## Rationale / Context
What makes this worth recording is that **nothing failed**. The run exited 0, the report was
well-formed, every part validated, and the vehicle would have loaded — as a car whose front
suspension is a metre and a half across and whose brake hubs weigh more than its engine. The
only visible symptom in the report was a diameter, and only if somebody knew what a wheel
should measure.

The general form: **a cue that is good enough to label is not automatically good enough to
seed.** Where one shell's classification defines a frame that other shells are then measured
against, that shell has to earn it geometrically.

## Impact
- `roles.is_wheel_seed` and `roles.dissolve_empty_corners`; six new unit tests.
- D15-R23a and R23b; T-D15-36 and T-D15-37.
- Both shipped cars: 4 corners, correct axles, and the 37% of the Eclipse that was `hub`
  returns to `chassis` where it belongs.

# DISC-049: a car does not stand on fully extended springs

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.8, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
`syndicate_prepare` placed each wheel slot at `axle + suspensionRestLengthM`, which is where the
suspension is fully **extended**. No vehicle stands there. Both prepared cars settled 8.2 cm into
the road — exactly their static sag — and the model's own ground plane stopped meaning anything.

## Details
The sag is derivable and, usefully, does not depend on the vehicle's mass. Bullet's suspension force
is `stiffness · compression · chassisMass`, so equilibrium at `stiffness · sag · m = m · g / n` gives

    sag = g / (stiffness · n)

which is 9.81 / (30 × 4) = 8.17 cm at the reference stiffness on four wheels, against a measured
8.25 cm. The Stampede's stiffer springs (×1.5) sag 5.4 cm.

The retired five-part content passed the same ride-height test with a slot 8 cm lower, because the
old dissection measured the axle 8 cm below where it really is. Two errors of the same size in
opposite directions, and the test that would have caught either one was green.

## Rationale / Context
Worth recording because the arithmetic is not obvious and the failure is not visible in Blender: the
car is correct in the source, correct in the export, and 8 cm wrong the moment physics runs. It also
explains why a "correct" axle measurement made ride height *worse* — which is the kind of result
that gets a good fix reverted.

## Impact
- `manifest.wheel_slot_lift(stiffness, wheelCount)`; D15-R45b records the derivation.
- `RideHeightTest`'s art-axle table is the prepared pipeline's measurements now, not the dissection's.

# DISC-014: a published braking ordering does not survive a change of mass

**Date:** 2026-08-09
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/05_vehicle_part_system.md#D05-S5.6

**Status:** active

## Summary
The Stampede's published 100-0 is 30 m against the Eclipse's 33 m, and in simulation it stops
**longer** — 34.9 m against 33.7 m. A ray-cast wheel's brake is clipped to its own friction circle,
so 31% more mass beats 25% more grip and the ordering inverts.

## Details

**Measured**, on the shipped content through `ShippedContentScene`, 100 km/h to rest:

| Vehicle | Kerb mass | Grip (`frictionSlip`) | Published | Measured | Over |
|---|---|---|---|---|---|
| Eclipse | 1500 kg | 2.00 | 33.0 m | 33.65 m | +2% |
| Stampede | 1969 kg | 2.50 | 30.0 m | 34.90 m | +16% |

Both are inside the harness's 30% braking tolerance, and the *absolute* figures are as good as this
model gets — the Eclipse's is within a metre. What does not survive is the comparison.

**Why.** `btRaycastVehicle::updateFriction` clips each wheel's brake impulse to
`m_wheelsSuspensionForce · dt · frictionSlip`. Deceleration is therefore bounded by grip, not by
brake force, and the bound scales with mass on both sides — so what decides the outcome is the ratio
of grip to mass. The Stampede has 1.25× the grip and 1.31× the mass, so it is 5% worse off, and
downforce does not rescue it: at 100 km/h it contributes 1.30 · 27.78² ≈ 1.0 kN, about 5% of weight,
and falls away as the car slows.

Real cars do not work this way, which is why the published figures disagree: tyre grip is
load-sensitive and a wider tyre gains more than proportionally, and carbon-ceramic brakes and
aerodynamic balance change what the tyre can be asked for. None of that is in a ray-cast vehicle.

**The fix that is not a fix.** Raising the Stampede's `frictionSlip` until it out-brakes the Eclipse
needs about 2.9 — GT3-slick territory for a road car on Cup 2 R tyres. That would make one test pass
and every cornering behaviour wrong.

## Rationale / Context
`VehicleProfileCalibrationTest` used to assert that the second vehicle stopped shorter, and that
assertion held for two sessions because the second vehicle was lighter. When the reference car
changed (DEC-037) the assertion failed, and the tempting reading is "the calibration is wrong" — it
is not. The next session to add a heavy vehicle will hit exactly this, and should know that the
model's braking is grip-limited before it starts tuning brake force, which does nothing.

## Impact
`game-core` `system` (`VehicleControlSystem`), `VehicleProfileCalibrationTest`, and any future
vehicle whose reference car is materially heavier than another's. Closing the gap needs a
load-sensitive tyre model, not a constant. Related to DEC-034, which closed the part of the gap that
*was* a distribution problem.

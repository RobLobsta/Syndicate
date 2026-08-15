# DISC-054: the garage drew every wheel a rest length up inside its own arch

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
`GaragePreview` places each part at its slot's `localTransform`. For a wheel that transform is the
**suspension connection point**, not the axle — so every wheel was drawn 22 cm high (30 cm before
DISC-049), tucked into the fender, and the car looked like it was sitting on its floor pan.

## Details
The garage has no Bullet world by design: it assembles the chassis' slot table directly, which is
what makes it cheap and what makes it wrong here. In a match the same slot is handed to
`addWheel`, Bullet hangs the wheel a rest length below it, and the vehicle settles one static sag
back up — net, the wheel ends at the axle. With no physics, none of that happens.

The fix is the arithmetic rather than a simulation: drop a wheel by
`suspensionRestLengthM - VehicleFactory.staticSagM(stiffness, wheelCount)`, both read from the same
places the physics reads them. `staticSagM` is now public on `VehicleFactory` because three things
need it and only one of them can run a simulation to find it out — the physics, the preparation
pipeline, and this screen.

The user spotted it in a screenshot. It had been true of every garage capture in the project's
history, including the ones used to claim the garage worked.

## Rationale / Context
Worth recording as a *class* of bug rather than an incident: any second renderer that places parts
from the same authored data will reproduce every piece of physics the authored data assumes, or
draw something the game never shows. A wheel slot is the first place that assumption bites and it
will not be the last — a hinged door's slot rotation is the next one.

## Impact
- `VehicleFactory.staticSagM`, used by the garage and mirrored by the Python pipeline.
- `VehicleProfileContentTest.everyWheelSlotStandsItsTyreOnTheGround` asserts the whole chain from
  content: `slotY - (restLength - sag) - tyreRadius` is the ride height, and it is zero.

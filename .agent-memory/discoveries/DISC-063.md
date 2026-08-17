# DISC-063: driven at speed, the desert launches a car and the clamp holds it there

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/16_procedural_arena_generation.md#D16-S5.3

**Status:** active

## Summary
The first scripted drive of the desert — full throttle, the Eclipse — reached 145 km/h, launched off a
dune, and was still airborne against an empty sky **130 frames later**, reading exactly 145 km/h in
every capture. 145 km/h is 40 m/s, which is `MAX_VEHICLE_SPEED_MPS` to the digit: the anti-tunnelling
clamp of D06-S5.5 was holding the car at terminal speed through the whole flight.

## Details
Three captures at frames 150, 210 and 270 of one run show the same thing: the car tumbling, no ground
in frame, the speed readout pinned at 145. Between frames 40 and 90 its part count fell from 37/39 to
8/12 — most of the car came off — and the score stayed at zero while the bots climbed.

Two things are worth separating, because they are not the same defect:

1. **A dune at 40 m/s is a ramp.** D16-S5.3's dunes stand at the angle of repose on their slip face
   and rise gently on the windward side, which is a jump taken from the windward direction. Nothing is
   wrong with the terrain; nothing has ever driven over it at this speed.
2. **The clamp does not distinguish airborne from grounded.** A ballistic car has no business being
   held at a speed chosen to stop a *chassis* tunnelling through *geometry*, and holding it there is
   what turned a long jump into a flight that outlasted the capture window.

The desert's whole relief spans −5 m to +49 m, so there is a great deal of dune to launch from.

## Rationale / Context
This was found in the first minute of the first scripted drive, by a run that existed to photograph a
fight. It had never been found before because nothing had ever driven the arena at full throttle — the
headless match runner drives bots, and bots do not hold the throttle down across a dune field. It is
the DISC-051 lesson exactly: eleven passing tests said nothing about what happens when someone drives.

## Impact
`game-core` `vehicle` (`VehicleControl`), and the desert theme's dune shape. Neither is fixed here.
The cheapest first move is to exempt an airborne vehicle from the speed clamp — the clamp exists for
contact tunnelling, which a car with no wheel in contact cannot do — and then to drive it again and
see whether the dunes are still a problem once landings are landings.

# SESS-013: two vehicles derived from real cars, and the roster that tracks them

**Date:** 2026-08-09
**Category:** session_summaries
**Related Docs:** docs/05_vehicle_part_system.md#D05-S5.6, docs/08_asset_pipeline.md#D08-S4.2, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
Added vehicle profiles and shipped two: a road supercar derived from a Maserati MC20 and a GT racer
derived from a Ford Mustang GT3, each calibrated from published figures and held to them by tests.
Extended the part schema with a `handling` block, added an engine-power stat so a vehicle has a real
top speed, and found two bugs in the brake path.

## Details
Continuation of SESS-012's Phase 4 work rather than a new phase.

**Profiles.** `VehicleProfile` is a research record: a real car's published mass, power, torque,
0-100, top speed, braking distance, drag coefficient and tyre sizes, plus the arithmetic that turns
them into simulation parameters. `VehicleProfiles` holds the two shipped ones. The name is separate
from the reference car, deliberately (DEC-033).

**The `handling` block (DEC-031).** D06-S4.5's reference chassis table names nine parameters that
D05-S4.5's stat list cannot carry, because they are properties of a body rather than contributions to
a sum — drag does not add up across two chassis. They are now authored on the part, and
`HandlingBlock.REFERENCE` is the single home for that table in code.

**Engine power (DEC-032).** Calibrating the first vehicle exposed a modelling hole: with one constant
force at every speed, a chassis calibrated to a real 0-100 reports a 637 km/h top speed. Adding
`enginePowerW` and capping force at `P/v` makes both figures come out right from published data —
341 km/h derived against "over 325" published for the road car, 267 km/h for the race car.

**Two brake bugs.** Bullet reads `setBrake` as an impulse and `applyEngineForce` as a force, so the
blueprint's own control loop brakes sixty times too hard (DISC-012). And splitting brake force
equally across wheels throws away the rears' share as they unload, which had the race car barely
out-braking the road car; splitting by live suspension load fixes it (DEC-034). The same
investigation turned up that a wheel can report contact while carrying no load at all, which is why a
test fixture had been driving and braking on two wheels while every contact check said four.

**The roster.** `VEHICLES.md` is generated from the profiles by a test that rewrites it and fails
until the new copy is committed. `assets/README.md` documents the content layout and exactly where a
`.glb` goes.

Amendments in the same commit: `enginePowerW` added to D05-S4.5 and the power limit to D05-S5.6 phase
4; the `handling` block added to D08-R5; two more fields on D04-S4.3.2's `VehicleStatsComponent`.

285 tests green across the JVM modules; `check`, `validateDocs` and `lintMemory` all pass.

## Rationale / Context
Session record (D13-R14).

## Impact
`game-core` (`asset`, `vehicle`, `system`, `component`), `assets/`, `VEHICLES.md`, `README.md`,
`docs/04`, `docs/05`, `docs/08`, and `ROADMAP.md`.

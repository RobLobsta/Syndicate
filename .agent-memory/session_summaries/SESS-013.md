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

**The `handling` block (DEC-031).** D06-S4.5's reference chassis names nine parameters D05-S4.5's
stat list cannot carry, because they are properties of a body rather than contributions to a sum —
drag does not add up across two chassis. They are now authored on the part.

**Engine power (DEC-032).** Calibrating the first vehicle exposed a modelling hole: with one constant
force at every speed, a chassis calibrated to a real 0-100 reports a 637 km/h top speed. Adding
`enginePowerW` and capping force at `P/v` makes both figures come out right from published data —
341 km/h derived against "over 325" published for the road car, 267 km/h for the race car.

**Two brake bugs.** Bullet reads `setBrake` as an impulse and `applyEngineForce` as a force, so the
blueprint's own control loop brakes sixty times too hard (DISC-012). And splitting brake force
equally across wheels throws away the rears' share as they unload, which had the race car barely
out-braking the road car; splitting by live suspension load fixes it (DEC-034).

**The roster.** `VEHICLES.md` is generated from the profiles by a test that rewrites it and fails
until the new copy is committed. `assets/README.md` says where a `.glb` goes.

Amendments: `enginePowerW` in D05-S4.5 and the power limit in D05-S5.6 phase 4; the `handling` block
in D08-R5; two fields on D04-S4.3.2's `VehicleStatsComponent`.

CI then failed two `ServerMainTest` cases that pass locally: the job exports
`SYNDICATE_STRICT_ASSETS=1`, and `ServerMain` resolved its configuration from `System.getenv()`, so
the two tests asserting the non-strict asset path were asserting the runner's environment instead
(DISC-013). `run` now takes an explicit environment and every test passes an empty one.

285 tests green across the JVM modules; `check`, `validateDocs` and `lintMemory` all pass, and all
three CI stages pass locally with the CI environment set.

## Rationale / Context
Session record (D13-R14).

## Impact
`game-core` (`asset`, `vehicle`, `system`, `component`), `assets/`, `VEHICLES.md`, `README.md`,
`docs/04`, `docs/05`, `docs/08`, and `ROADMAP.md`.

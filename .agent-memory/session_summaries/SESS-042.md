# SESS-042: the Kestrel — the first thing in Syndicate that flies

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/05_vehicle_part_system.md#D05-S4.2, docs/15_vehicle_preparation_pipeline.md#D15-S4.2, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
A cartoon helicopter arrived in `art-source/` and left as the **Kestrel**: a flying, shootable,
part-based rotorcraft on the shipped roster. That took a new part category, a new flight model, a new
geometric cue in the preparation pipeline, and four fixes to things that had assumed every vehicle
was a car.

## Details
**Taxonomy and physics (DEC-090).** `PartCategory.ROTOR`, `SlotType.ROTOR_MOUNT`, a `rotor` block,
and `RotorControl` beside `VehicleControl`'s wheel path. Lift acts along the vehicle's *own* up axis,
which is the flight model in one line: pitching the nose down converts lift into forward flight, with
nothing written to say so. The main rotor's torque is always applied and the tail rotor's cancels it,
so losing the tail rotor spins the aircraft — again with no code saying it should.

**The pipeline (DEC-093).** A rotor is found by a disc test — flat, square normal to its thin axis,
and either rotationally symmetric or covering the circle — reusing D15-S5.4's machinery generalised
off the x axis. Roles `main` and `tail` keep the discs apart, and every number is derived from the
geometry.

**Four car-shaped assumptions, each found by something failing.** `MODEL-004` told an operator to
scale a correctly sized helicopter by 0.396 (DEC-091); `A309` rejected an aircraft for having no
wheels (DEC-092); the kerb-mass footprint measured the rotor span and gave a 15.7-tonne helicopter;
and the rotorcraft branch returned before the speed clamp.

**What cost the most time** was none of those: a three-blade rotor's bounding box is not centred on
its mast, and four pieces of the pipeline read that centre as the hub (DISC-070). Every symptom
looked like a threshold needing loosening, and each of those fixes would have been wrong.

**It was flown, not just tested.** `--script` gained a `collective` key; the Kestrel lifts off at 3/3
parts and cruises at 191 km/h.

## Rationale / Context
Three things were recorded rather than fixed, all deliberately.

**DISC-071: a parked helicopter on a slope destroys its own rotor**, because neutral collective trims
to a full hover and a tilted aircraft with no wheels slides and rocks. Flown it is fine. The fix is a
feel question, so it is the user's call and it is in ROADMAP §5.

**DEV-020: the engine's forward axis is +Z and D00-R15 says −Z.** Not created here, but relied on:
the Kestrel's `yawDeg` is 0 on the implementation's convention.

**61% of the model is `unclassified`** and merges into the chassis (D15-R2's first-class outcome).
Cabin, fin, stabilisers and skids have no label in a taxonomy written for cars; inventing four
speculatively is how a closed taxonomy stops being one.

## Impact
`shared-models`, `game-core`, `game-client`, `asset-pipeline`, `test-environment`, and the whole of
`blender-tool`'s prepare package. New content: `art-source/vehicles/kestrel/` and
`assets/vehicles/vehicle_kestrel_01/`. New tests: `RotorFlightTest` (6) and `KestrelFlightTest` (3);
23 physics tests pass, the strict asset gate reports 0 errors, and `--model` passes 10/10.

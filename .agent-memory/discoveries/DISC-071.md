# DISC-071: a parked helicopter on a slope destroys its own rotor

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/16_procedural_arena_generation.md#D16-S5.6, docs/05_vehicle_part_system.md#D05-S5.2

**Status:** active

**Diagnosed, not fixed** — the fix is a design choice, recorded in ROADMAP §5.

## Summary
Given **no input at all**, the Kestrel loses its main rotor within seconds: three captures at frame
90 show 2 of 3 parts, a full health bar, and the blades lying detached beside an empty mast. Given
collective from the first frame it takes off and cruises at 191 km/h intact. **It survives if it
flies immediately and breaks if it sits.**

## Details
**Two causes ruled out.** Not `breakImpulseN`: that governs only a part already detached and joined
by a constraint, and nothing detaches an *attached* part by impulse. The rotor is **destroyed** by
collision damage and then detaches because `ROTOR.detachesOnDestroy()` is true — the intended chain.
And not simply spawning inside terrain: `aHelicopterAtRestOnTheGroundKeepsItsRotor` holds neutral on
flat ground for five seconds and passes.

**What it is.** Neutral collective trims to a hover — thrust equal to weight (DEC-090) — along the
**vehicle's own up axis**, at the hub, above the centre of mass. On flat ground that is stable. On a
**slope** the aircraft rests tilted, so the thrust tilts with it and gains a horizontal component of
about `weight × sin(θ)` with nothing to oppose it: no wheels, no suspension, no rolling resistance.
It slides, the offset thrust rocks it, and the 4.72 m disc reaches the ground.

## Rationale / Context
The design error underneath is that **"neutral collective hovers" is right in the air and wrong on
the ground.** A real helicopter on a pad has its collective fully down and its rotor at flat pitch.
The model gives a parked aircraft 100% of the thrust it needs to fly, permanently, which makes it a
puck on any gradient.

The flat-ground test cannot catch it, because the mechanism *is* the tilt: that test passing was not
evidence the behaviour was fine, only that it was on the wrong surface.

Three candidate fixes, none written. **Trim only when airborne** is the right answer and needs a
ground-contact test a rotorcraft does not have. **Spawn aircraft already flying** is the cheap one
and sidesteps the case rather than solving it. **Let the collective rest down** is truest to a real
machine and the biggest change to how it feels. It is a feel question, so it is the user's.

## Impact
`VehicleFactory.liftOntoTerrain` was added while chasing this and is **kept**: a vehicle should not be
created inside the ground, and it samples terrain across the hull's footprint rather than under its
centre, which matters for a 4.72 m overhang as it never did for a bumper. It is **not** a fix for
this defect — captures before and after are identical. All 23 physics tests pass with it in.

A rough edge rather than a blocker: fly it and it is fine.

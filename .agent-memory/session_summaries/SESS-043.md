# SESS-043: four defects, three of them invisible to the test suite

**Date:** 2026-08-18
**Category:** session_summaries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/16_procedural_arena_generation.md#D16-S5.4, docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/03_runtime_modes.md#D03-S4.1

**Status:** active

## Summary
A stabilisation pass: four defects fixed and one withdrawn feature shipped. The repository was green
at the start and green at the end, which is the point — only one of the four was findable from the
test suite; the rest came from running the game and reading what it printed.

## Details
**The parked helicopter destroys its own rotor (DISC-071 → DEC-096).** ROADMAP's one rough edge at
"you are here". Hover trim now engages only off the ground: on a 12° gradient 43.9 m of slide becomes
none, and it still takes off. Both new tests are on a slope, since the flat-ground one passes with
the defect present.

**The engine audio bus died mid-match (DISC-073).** `Index 28249 out of bounds for length 28249`, on
the render thread, caught by the bus's catch-all: one WARN, then every engine silent for the rest of
the match. The delay line's read position is wrapped by adding `DELAY_FRAMES` while negative; at
28,249 a float's spacing is 0.002, so a position in the last sliver below zero rounds to the length
itself.

**The gamepad could not fly.** `GamepadSource` wrote three of four axes and never `collective`, so a
pad-flown helicopter held whatever the keyboard or the previous match left there. Now the left
stick's vertical axis. `InputRouter`'s idle path had the same hole, in the method whose own docstring
names that failure.

**The scrapyard's haul road ships (DEC-097).** DISC-062 asked for a guard on a road reaching the
border rise. `validateExtent` measures the rim height under the centreline, which *is* the cut depth,
so it is seed-independent. The first attempt rejected the shipped desert highway — a corridor may
clip the rim harmlessly; the mechanism is the centreline. The road landed: 235 m, cutting 3.6 m.

**And the test scaffold (DISC-074).** `DestructionTestScene` built its control system with no
`PhysicsWorld` while handing one to eleven other systems, so D16's per-surface grip had never run
under a physics test.

## Rationale / Context
Three of the four came from `--capture-frames` runs and their logs, not from `./gradlew check`, which
was green throughout. The audio one is sharpest: eleven passing audio tests, and a WARN line in a
capture log was the whole evidence trail.

**One decision ROADMAP §5 had left to the user was taken.** DEC-096 picks "trim only when airborne"
of its three candidates, reading a helicopter that destroys itself parked as a defect rather than a
matter of feel. It is one expression in one method, and DEC-096 says how to reverse it. The other §5
questions are untouched.

## Impact
`game-core` (`RotorControl`, `PhysicsWorld`, `RoadCarver`, `TerrainGenerator`), `game-client`
(`EngineMixer`, `GamepadSource`, `LibGdxDevices`, `InputRouter`), and the scrapyard's `arena.json`.
Six new tests; 25 physics and 508 core tests pass, as do `check`, `validateDocs` and `lintMemory`.
The strict asset gate reports 0 errors on 74 parts, 3 vehicles and 2 arenas.

# SESS-020: a window, and the first time anybody can watch it

**Date:** 2026-08-11
**Category:** session_summaries
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.5

**Status:** active

## Summary
Implemented Phase 7: the five presentation slots (22–26), a chase camera, a HUD, the client startup sequence and the fixed-timestep client loop. The client draws eight cars from their own art in a real arena with a live scoreboard, and a capture mode proves it on a machine with no display.

## Details
Twenty-three of D04-S4.4's 27 systems now exist; the four left are networking. Beyond the slots themselves:

- **`World.present` takes two numbers** (DEC-049). Alpha and frame delta are different quantities and were the same argument, which made the morph-crumple rate depend on where in a tick the frame landed.
- **Slot 22 interpolates local ticks, not snapshots** (DEC-050). `InterpolationComponent`'s ring buffer stays empty until slot 19 exists.
- **The client attaches its own cosmetic components** (DEC-051), because `VehicleFactory` also runs on a server that must not import a renderer.
- **`checkCosmeticIsolation`** turns AC-D07-10 into a build gate, and was verified by breaking the rule on purpose.

Two bugs, both found by running the thing rather than by reading it:

- **DISC-022** — `DamageEvent` was published same-tick only, so presentation never saw a hit. A full match took a car to 78% health and drew zero particles, with green tests for both systems that would have used them.
- **DISC-021** — a guardrail task read `project.rootDir` at execution time, which the configuration cache forbids. It only runs on the reporting path, so it would have failed for the first time on the commit that broke the rule it guards.

## Rationale / Context
The pattern from the last four sessions held again: the tests verify that components are correct and almost never that they are correct together, and both bugs above were obvious within a second of the real client printing what it did. The counter-measure that keeps working is the same one — make the real thing run, and make it say what it did. `--capture` and `ParticleRenderer.peakQuadCount()` exist for that reason.

## Impact
- `game-client`: 18 new classes across `render`, `present`, `effect`, `audio`, plus the runtime shell.
- `game-core`: `World.present` signature, cosmetic-event publication in slots 11 and 9's impact path.
- `build-logic`: one new check task, one latent bug fixed in an existing one.
- `docs/02_technical_architecture.md#D02-S4.5` amended for the client's dependency list.

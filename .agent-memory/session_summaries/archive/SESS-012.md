# SESS-012: a vehicle you can drive, and a server that runs it

**Date:** 2026-08-09
**Category:** session_summaries
**Related Docs:** docs/05_vehicle_part_system.md#D05-S5.6, docs/06_physics_simulation.md#D06-S5.5, docs/03_runtime_modes.md#D03-S5.4

**Status:** active

## Summary
Implemented Phase 4: the degradation model, `VehicleStatsSystem` (6) and `VehicleControlSystem` (7),
the D03-S5.2 schedule factory, and a real tick loop in `game-server-headless`. A vehicle now drives.
Two bugs came out of it — a collision filter that made every suspension ray miss the ground, and
wheel tuning constants that had been wrong against D06-S4.5 since they were written.

## Details
Work, in the order it landed:

1. **Degradation (D05-S5.4).** The four curves, the per-category table, the inverted stats, and
   `degradationOverrides` read from `part.json` onto `PartType`. Two gaps the table leaves were
   decided and recorded (DEC-024).
2. **`VehicleStatsSystem` (slot 6).** All four phases of D05-S5.6 — per-part effective stats, the
   utility multipliers that modify other parts, the vehicle-scope sums, and the derived acceleration
   and top speed D05-R16 forbids content from authoring. It recomputes unconditionally rather than
   trusting a `dirty` flag nothing currently sets (DEC-025).
3. **`VehicleControlSystem` (slot 7).** D06-S5.5's loop. A destroyed wheel is commanded to zero
   rather than skipped, because Bullet's per-wheel setters persist (DEC-028).
4. **`SystemSetFactory.forMode` and `WorldFactory`.** The D04-S4.4 catalogue as data, filtered per
   mode, with client systems arriving through a provider so the layering holds (DEC-030).
5. **`HeadlessLoop` and `ServerRuntime`.** D03-S5.4's pacing and overload resync, and D03-S5.1's
   startup as far as the implemented subsystems reach. No libGDX Application is created (DEV-011).

Then the two findings. The first drive test failed with the vehicle resting on its own chassis hull:
`btDefaultVehicleRaycaster` issues its ray on `DefaultFilter` and cannot be told otherwise, and
D06-S4.4's `STATIC` mask does not contain that bit, so no wheel ever found the ground (DISC-011,
DEV-012). The second surfaced while tuning around the first — D06-S4.5 authors the whole per-wheel
tuning table, and DEC-022 had recorded that it authored none of it, so `frictionSlip` had been 10.5
against a specified 2.0 (DEC-029).

Three blueprint amendments in the same commit: two component fields in D04-S4.3.2 that D05-S5.6 and
D06-S5.5 require, and a note in D06-S4.4 about ray filtering.

209 `game-core` tests green (65 new), 11 in `game-server-headless`, and `check`, `validateDocs`,
`lintMemory` and the physics regression tag all pass.

## Rationale / Context
Session record (D13-R14).

## Impact
`game-core` (`vehicle`, `system`, `ecs`, `asset`, `physics`, `component`), `game-server-headless`,
`docs/04_entity_component_model.md`, `docs/06_physics_simulation.md`, and `ROADMAP.md`.

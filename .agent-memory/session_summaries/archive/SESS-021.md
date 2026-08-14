# SESS-021: the cars finally sound like the cars they came from

**Date:** 2026-08-12
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
Rebuilt the engine synthesiser around exhaust pulse trains and formants, added forced induction as a second voice, added start / stop / overrun and a fire loop, and wired the three families that shipped with correct files and no triggers. The bank goes 52 → 74. Found and fixed a loop-seam bug that had been silently breaking half the engine loops.

## Details
Three questions opened it: what is left on the Blender tool, can the missing sounds be generated, and do the engine sounds replicate the reference cars. The first was answered by reading — D15-S5.1 stages 6–8 unimplemented, the fracture tool never run on the six shipped parts — and the user chose to spend the session on audio alone.

The third was the interesting one. Measured rather than guessed: the firing maths and rev ranges were right and almost nothing else was. The loops were sawtooths with no exhaust resonance, no way to express a cross-plane V8's uneven bank firing, and no forced induction despite both reference cars having it.

Seven things landed; PROG-021 has the table. Two are worth restating.

**Bank divergence took three attempts** (DEC-054); the wrong answers bracket the right one. A cross-plane V8's bank patterns are time-reverses, so summed coherently their odd orders cancel to zero — the burble is uneven firing *failing to cancel*. A large uniform divergence made even-firing V6s and V10s lumpier than the V8; a small plausible one cancelled the V8's burble entirely.

**The loop test could never have failed** (DISC-023). It measured the sample-value step at the join, which an equal-power crossfade removes by construction; phase it does not fix, and three of six loops restarted a third of a cycle out of step every pass. Third session running where the bug was invisible to a green suite and obvious the moment the artefact was measured.

## Rationale / Context
`game-client` could not be built (DISC-024): jitpack.io is denied by the sandbox proxy. Its audio package was type-checked with a hand-built `javac` classpath, catching three real defects — a `Gdx.graphics` call where the frame delta was already a parameter, an `AssetId.of("")` that throws, and a sound id built with a key for the one sound that has none. Its tests did not run.

One self-inflicted bug the suite did catch: stamping a material onto debris assumed `PartType.materialId()` is non-null, which the destruction fixtures violate. Both call sites now guard.

## Impact
- 74 audio files and a regenerated manifest; every engine loop's bytes changed.
- `shared-models`: new `Induction`; `EngineConfiguration` and `AudioEvent` extended.
- `game-core`: three new events; slots 7, 8, 9, 13, 14, 16 touched; `EngineVoice` gains its induction half.
- `game-client`: `AudioSystem` rewritten around a per-vehicle `VehicleVoices`.
- D15-S8 amended: R36a–c, R37a1, R37a2, R38a1.
- New memory: DEC-052, DEC-053, DEC-054, DISC-023, DISC-024, PROG-021.
- 349 tests green; `validateDocs` and `lintMemory` green.

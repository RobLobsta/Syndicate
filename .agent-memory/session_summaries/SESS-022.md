# SESS-022: the engine stops being a file

**Date:** 2026-08-12
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
The user did not believe the engine loops. They were right: the V8's loudest component was 100 Hz against a firing frequency of 266.7 Hz, and four of six loops sounded at a pitch unrelated to their engine. Engine audio is now synthesised at runtime, placed by our own mixer, and tuned against real recordings including a Mustang GT the user supplied. The bank goes 74 files to 47.

## Details
Five findings, each with its own entry.

**The loops were filtered, not synthesised** (DISC-025). `resonate` summed three band-passes and emitted only their output, which gates everything between them — 25 dB down at a V8's own firing frequency. The excitation was never wrong.

**Two tests could not fail, and neither could their first replacements** (DISC-025, DISC-028). Counting sub-orders against a threshold relative to the firing order is worthless when the firing order is what the defect suppresses. Asserting the firing order is the *loudest* order is false of real engines. Both had to be restated as contrasts.

**Generic recordings fixed the texture** (DISC-026). Harmonics fall at 7 dB/oct where this fell at 22, and stand 9-17 dB above the floor between them where this stood 37: too dark, and far too clean.

**Free constants read as faults** (DISC-027). The ignition modulated at a hardcoded 6 Hz and whined at a fixed pitch while the crank speed swung 26% underneath. The ear finds an unconnected constant long before it finds a wrong value.

**A real V8 was worth more than every generic clip together** (DISC-028, DISC-029). Its comb reads at the cycle rate, which is itself the cross-plane signature. Against it our even sub-orders were 14 dB light, because two banks that are time-reverses cancel them and nothing broke that symmetry; fixed per-cylinder trims do, and make two of the same car differ. Its ignition envelope showed the catch is a step and the flare holds 0.2 s.

## Rationale / Context
`game-client` cannot be built by Gradle here, and it is not the dependency's age: CI builds it every run, jitpack is simply proxy-denied (DISC-024). A JDK 17 toolchain installs from apt, which is what made any Gradle task work. The three DSP classes import no libGDX, so their 17 tests run standalone; `AudioSystem` and `EngineAudioOutput` are type-checked only.

Two thresholds were **weakened** and are recorded as such: burble contrast 12 to 5 dB, dead-cylinder margin 5x to 1.25x. Engines whose cylinders differ have no exact nulls left to destroy, which is true of real engines and costs real contrast.

## Impact
- 27 audio files deleted; `AudioEvent` 15 to 9; `SoundSynth` loses ~370 lines.
- New in `game-client` `audio`: `EngineSynth`, `EngineRunState`, `EngineMixer`, `EngineAudioOutput`.
- D15-S8: R37a3, R37a4, R38a2-a9 added; T-D15-11..20 added.
- New memory: DEC-055, DEC-056, DISC-025 to DISC-029, PROG-022.
- 17 audio tests pass standalone; core, models and pipeline suites green.

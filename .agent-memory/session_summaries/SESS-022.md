# SESS-022: the engine stops being a file

**Date:** 2026-08-12
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
The user did not believe the engine loops. They were right: the V8's loudest component was 100 Hz against a firing frequency of 266.7 Hz, and four of six loops sounded at a pitch unrelated to their engine. The cause was a filter topology. Engine audio is now synthesised at runtime, placed by our own mixer, and tuned against real recordings; the bank goes 74 files to 47.

## Details
Three questions, each making the next one bigger.

**Is it wrong?** Yes, and the excitation never was. `resonate` summed three band-passes and emitted only their output — a gate: −25.3 dB at 267 Hz against −1.8 dB at 100 Hz.

**Can it be real-time?** 147 ns/sample, 142× real time for one voice — and if it is live, condition and transients stop needing files. That is 27 of the 74.

**Do they sound like the real cars?** The Predator is cross-plane with a Roots blower and a 7,650 limit; the Nettuno a 90° V6 firing 1-6-3-4-2-5, which puts its banks on alternating events — `010101`, what `EngineConfiguration.V6` already returned. Output matches both cars' derived firing frequencies to 0.005%.

**The tests could not fail** (DISC-025). One counted sub-firing-order lines and wanted three or more; a gate creates them. The other wanted two spectral peaks; there were three band-passes. Third session running with a green suite and a wrong artefact.

## Rationale / Context
`game-client` still cannot be built by Gradle here, and it is not the dependency's age: CI builds it fine, and jitpack is simply proxy-denied (DISC-024). A JDK 17 toolchain does install from apt, which is what made any Gradle task work at all. The three DSP classes import no libGDX, so their 14 tests ran standalone and all pass; `AudioSystem` and `EngineAudioOutput` are type-checked only.

Then real recordings were found and the voicing tuned against them (DISC-026). Almost every audio host is proxy-blocked but `raw.githubusercontent.com` is not, and ESC-50 lives on GitHub. Real exhaust harmonics fall at 7 dB per octave where this fell at 22, and stand 9-17 dB above the floor between them where this stood 37: too dark and far too clean, and the second is what makes a synthesised engine sound synthesised. Brightening it then unmasked a comb notch from a bank delay chosen against the dark exhaust, and invalidated the burble test the same way — by measuring sub-orders against a firing order the defect suppresses.

One gap is deliberate: the deleted overrun files carried crackle, and dropping the load reproduces the overrun but not the pops.

## Impact
- 27 audio files deleted; `AudioEvent` 15 → 9 members; `SoundSynth` loses ~370 lines.
- New in `game-client` `audio`: `EngineSynth`, `EngineRunState`, `EngineMixer`, `EngineAudioOutput`.
- D15-S8: R37a3, R37a4, R38a2, R38a3, R38a4 added; T-D15-11..16 added.
- New memory: DEC-055, DEC-056, DISC-025, DISC-026, PROG-022.
- 14 audio tests pass standalone; core, models and pipeline suites green.


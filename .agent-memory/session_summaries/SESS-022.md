# SESS-022: the engine stops being a file

**Date:** 2026-08-12
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
The user did not believe the engine loops. They were right: measured on the committed bytes, the V8's loudest component was 100 Hz against a firing frequency of 266.7 Hz, and four of six loops sounded at a pitch unrelated to their engine. The cause was a filter topology, not a number. Engine audio is now synthesised at runtime and placed in the world by our own mixer; the bank goes 74 files to 47.

## Details
Three questions in sequence, and each answer made the next one bigger.

**Is it wrong?** Yes, and the excitation never was. A single V8 bank's pulse train peaks at order 8 exactly as physics says. `resonate` then summed three band-passes and emitted only their output — a gate: −25.3 dB at 267 Hz against −1.8 dB at 100 Hz. DISC-025 has the table.

**Can it be real-time?** 186 ns/sample, 112× real time for one voice — and if the synthesiser is live, condition and transients stop needing files. That is 27 of the 74.

**Do they sound like the real cars?** Checked against published specifications. The Predator is cross-plane with a Roots blower and a 7,650 limit; the Nettuno is a 90° V6 firing 1-6-3-4-2-5, which puts its banks on alternating events — `010101`, what `EngineConfiguration.V6` already returned. Output matches the derived firing frequencies to 0.005%.

**The tests could not fail** (DISC-025). One counted sub-firing-order lines and wanted three or more; a gate creates them. The other wanted two spectral peaks; there were three band-passes. Third session running where the suite was green and the artefact was wrong.

**A strict replacement would also have been wrong.** The first asserted the firing order is the loudest order, and failed honestly: at 1,200 rpm an I4's fundamental sits below every exhaust resonance and its second harmonic carries. It now asserts the firing order is never *buried* — never more than 12 dB down, against the old bank's 25.

## Rationale / Context
`game-client` still cannot be built by Gradle here (DISC-024), but two things changed. A JDK 17 toolchain installs from apt, which is what made any Gradle task work at all — foojay provisioning is proxy-blocked. And the three DSP classes import no libGDX, so their 14 tests ran standalone. All pass; `AudioSystem` and `EngineAudioOutput` are type-checked only.

One gap is deliberate: the deleted `engine_overrun_*` files carried crackle, and dropping the load reproduces the overrun but not the pops.

## Impact
- 27 audio files deleted, manifest regenerated; `AudioEvent` 15 → 9 members.
- New in `game-client` `audio`: `EngineSynth`, `EngineRunState`, `EngineMixer`, `EngineAudioOutput`.
- `EngineVoice` reduced to a description of an engine; `SoundSynth` loses ~370 lines.
- D15-S8: R37a3, R37a4, R38a2, R38a3 added; R36 shortened; T-D15-11..15 added.
- New memory: DEC-055, DEC-056, DISC-025, PROG-022.
- 14 new audio tests pass standalone; core, models and pipeline suites green.

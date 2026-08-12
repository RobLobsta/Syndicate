# PROG-022: engines are synthesised, placed, and sound like the cars they came from

**Date:** 2026-08-12
**Category:** progress
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
Engine audio moves out of the bank and into the runtime. `EngineSynth` renders an engine from its live state a block at a time; `EngineMixer` places up to 24 of them in the world with distance, panning, air absorption and propagation delay; `EngineRunState` maps ignition, shutdown and damage onto synthesiser parameters. The bank goes 74 files to 47, and the two tests that could not fail are replaced by five that can.

## Details

**Scope:** `game-client` `audio`, `game-core` `vehicle`, `shared-models`, `asset-pipeline` `audio`, `assets/audio/`, D15-S8.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Exhaust topology (D15-R38a2) | done | Dry path plus resonant boosts. Firing order goes from −25 dB to within 9.6 dB of the peak, worst case over 6 configurations × 5 speeds (DISC-025) |
| Streaming synthesis (D15-R37a3) | done | Causal, block-based, allocation-free. 147 ns/sample, 142× real time for one voice |
| Condition | done | Misfire, dead cylinder and exhaust breach, derived from chassis health in `EngineRunState` |
| Ignition / shutdown | done | Phases of the run state at the car's own idle, not twelve files at a nominal 800 rpm |
| Induction | done | Blower and turbo curves moved into the synth; blow-off is a triggered envelope |
| Spatialisation (D15-R37a4) | done | 24 slots, stereo, distance + pan + air absorption + propagation delay; doppler falls out of the delay line. All 24 at 18% of one core |
| Threading | done | Lock-free, allocation-free audio thread over `AtomicReferenceArray` snapshots (DEC-055) |
| Bank | done | 27 files deleted, manifest regenerated, `AudioEvent` 15 → 9 |
| Voicing vs real engines (D15-R38a4) | done | Tuned against 39 CC-licensed recordings: tilt −22 → −7 dB/oct, harmonic-to-floor +37 → +19 dB (DISC-026) |
| Overrun crackle (D15-R38a7) | done | Armed by the throttle transition, not the state; bangs peak 2.3x a coast at the same rpm |
| Low end on throttle (D15-R38a6) | done | 75 Hz shelf scaled by power and load: ~5 dB swing on the Stampede, ~1 dB on a 110 kW four |
| Ignition (D15-R38a5) | done | Crank labour at the true compression rate, whine geared to the crank, cranking engine now pumps (DISC-027) |

**Measured against the reference cars.** Checked against published specifications, not taste. The Predator 5.2 is cross-plane with a 2.65 L Roots blower and a 7,650 rpm limit (profile: 7,600); the Nettuno is a 90° V6 firing 1-6-3-4-2-5, redline 8,000. That order puts cylinders 1-3 and 4-6 on alternating events — `010101`, exactly what `EngineConfiguration.V6.bankOf` returns, so the even-firing V6 is right rather than lucky. Output matches the derived firing frequencies to 0.005% at idle, mid-range and redline for both cars.

**Tuned against recordings, not taste.** `game-client/tools/engine_reference.py` fetches ESC-50's engine class and measures harmonic tilt and harmonic-to-floor ratio; the same script measures a rendered synth WAV, so the comparison is repeatable rather than a number in a commit message. The clips are CC BY-NC, are fetched outside the repository, and nothing derived from them ships.

**Not verified here.** `game-client` still cannot be built by Gradle in this sandbox (DISC-024). The audio package was type-checked with `javac` against the cached gdx jars, and the three classes that import no libGDX had their 14 tests run standalone; all pass. `AudioSystem` and `EngineAudioOutput` are type-checked but not exercised. A JDK 17 toolchain installs from apt, which is what unblocked Gradle at all — foojay provisioning is proxy-blocked.

## Rationale / Context
PROG-021 recorded the bank as done at 74 files and the engines as replicating the reference cars. The first half stands for everything still a file; the second was wrong, and DISC-025 has the measurement. This supersedes PROG-021's engine inventory and nothing else.

## Impact
- `assets/audio/`: 47 files; every engine and induction asset removed.
- `game-client`: four new classes in `audio`, `AudioSystem` rewritten around the mixer.
- `game-core`: `EngineVoice` reduced to a description of the engine.
- `shared-models`: `AudioEvent` loses six members.
- `asset-pipeline`: `SoundSynth` loses ~370 lines, `SoundBankBuilder` three builders.
- D15-S8: R36 table shortened; R37a3, R37a4, R38a2, R38a3 added; R37b, R37c amended; T-D15-11..15 added.

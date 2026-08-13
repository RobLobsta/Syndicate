# DISC-025: a parallel band-pass bank with no dry path is a 25 dB gate, not an exhaust

**Date:** 2026-08-12
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/12_testing_validation_ci.md#D12-S4.1

**Status:** active

## Summary
`SoundSynth.resonate` summed three band-pass filters and emitted only their output. Everything between the formants was attenuated by the sum of their skirts *and* by phase cancellation between them. For the 105 / 560 / 1750 Hz set that put a 25 dB hole at 267 Hz — which is a V8's firing frequency at the 4,000 rpm reference. The loudest component of the shipped `engine_loop_v8.wav` was 100 Hz.

## Details
Measured on the committed bytes, not on the source:

| | firing Hz | loudest Hz | ratio |
|---|---|---|---|
| I4 | 133.3 | 133.3 | 1.00 |
| I6 | 200.0 | 600.0 | 3.00 |
| V6 | 200.0 | 600.0 | 3.00 |
| V8 | 266.7 | 100.0 | **0.375** |
| V10 | 333.3 | 666.7 | 2.00 |
| V12 | 400.0 | 400.0 | 1.00 |

Four of six sounded at a pitch unrelated to their engine. I4 and V12 passed by coincidence — their scaled formant happened to land on a firing order.

**The excitation was never wrong.** In isolation a single V8 bank's pulse train peaks at order 8 with the burble half-orders at −3 to −11 dB. DEC-052 and DEC-054's firing geometry was correct; only the filter stage was wrong.

**The 25 dB is not roll-off.** Two second-order band-passes an octave apart should leave ~8 dB in the middle. The composite response measured −33.4 dB at 200 Hz, deeper than at 33 Hz, because a 2nd-order band-pass swings ±90° either side of centre and three of them summed phase-unaware comb against each other. The gap between formants is a cancellation notch, not a valley.

**Two tests covered this and neither could fail.** `aCrossPlaneV8BurblesAndAnEvenFiringVDoesNot` counted sub-firing-order lines and wanted ≥3; a gate *creates* sub-order lines, so the test rewarded the defect. `engineLoopsHaveExhaustFormants` wanted ≥2 spectral peaks; there were three band-passes, so it could only fail if somebody deleted them. Nothing asserted that the firing order was present.

## Rationale / Context
Third session running where a defect was invisible to a green suite and obvious the moment the artefact was measured (DISC-023 was the second). The pattern is specific enough to name: **a test written from the implementation's vocabulary tests that the implementation ran, not that its output is right.** Both were written by asking "what does this code produce?" rather than "what is an engine?"

The test that would have caught it is one line of physics — the loudest thing an engine produces is related to the rate at which it fires. It is now D15-R38a3.

## Impact
- `SoundSynth`'s engine synthesis deleted; `EngineSynth` in `game-client` adds the resonances over a dry path (DEC-056).
- D15-R38a2 and D15-R38a3 added: the exhaust colours, it does not replace; and the verifying test is that the firing order survives at more than one engine speed.
- T-D15-11 and T-D15-12 replace the two tests that could not fail.
- Measured on the replacement: the firing order is never more than 9.6 dB below the loudest order, across six configurations at five speeds.

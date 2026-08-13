# DISC-029: the ignition timing was wrong in every dimension, against a real start

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
The same Mustang GT recording carries a complete ignition, and the model's timing was wrong in all three of its parts: the crank was too long, the catch was a ramp where the real one is a step, and the flare collapsed in a single frame instead of holding and decaying.

## Details
Measured off a 20 ms RMS envelope of the reference:

| | real | model before | after |
|---|---|---|---|
| catch | one 40 ms step from ambience to full | 0.30 s ramp | 0.12 s |
| cranking | brief, near the noise floor | 0.62 s | 0.40–0.68 s, per arrangement |
| flare above midpoint | 0.19 s | collapsed immediately | 0.19 s |
| flare / idle level | 1.4 | 3.5–4.6 | 2.6–3.1 |

**The flare collapse** was structural: `CATCHING` handed straight to `RUNNING`, which slews to the demanded rpm at 9,000 rpm/s — effectively instant. A `SETTLING` phase now holds the flare and eases it down.

**The flare level** turned out not to be a flare problem at all. It would not move however the settling load was tuned, because the ratio was being set at the other end: `IDLE_LOAD` was 0.12, so a car at idle was modelled as barely fuelling. Raised to 0.22 and the ratio fell into range without touching the flare.

**Crank duration and speed now scale with cylinder count** — more pistons is more inertia and more compressions to drag over. A four cranks 0.40 s at 7.5 Hz; a twelve labours 0.68 s at 19 Hz. That is where most of the audible variety between arrangements at startup comes from.

## Rationale / Context
Worth recording that the reference's crank is *almost inaudible* — the recording goes from ambience to full engine between two envelope samples. The model deliberately keeps a longer, audible crank than the reference shows, because a game wants the event to read, and because a car with a flat battery or a hurt engine should be able to crank longer. That is a deviation from the measurement made on purpose rather than by accident.

A measurement bug was also found and fixed in the test itself: `dominantModulationHz` reported an I4 cranking at 15 Hz when the truth was 7.5, because a 0.24 s window holds under two cycles at 7.5 Hz and the detector locked onto the second harmonic. It now prefers a subharmonic that reaches half the peak's magnitude.

## Impact
- `EngineRunState`: new `SETTLING` phase, `crankSeconds(cylinders)` and `crankRpm(cylinders)`, `CATCH_SECONDS` 0.30 → 0.12.
- `AudioSystem.IDLE_LOAD` 0.12 → 0.22.
- D15-R38a9 added; T-D15-17 now measures against each arrangement's own crank speed.

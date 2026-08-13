# DISC-027: an engine's periodic parts must all be tied to the crank, or it reads as a fault

**Date:** 2026-08-12
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
The ignition was reported as sounding like "an audio error". Three separate constants in it were free of the engine: the crank-speed modulation ran at a hardcoded 6 Hz, the starter's gear whine held a fixed pitch, and a cranking engine made almost no exhaust noise at all. Tied to the crank, the same code produces a growl that differs per arrangement.

## Details
**The 6 Hz.** `EngineRunState` modulated cranking speed by `sin(2π · 6 · t)` under a comment reading "the starter labours slightly as each compression comes round". It does not: a starter labours once per compression, which is `rpm / 120 × cylinders` — 8.7 Hz for a four at cranking speed, 17.3 for a V8, 26.0 for a V12. One rate for every engine, and that rate belonging to none of them.

**The fixed whine.** The gear whine sat at a constant ~1,160 Hz while the crank speed swung 26% underneath it. A pinion is geared to the ring gear, so its pitch must dip and recover with the labour. A pitch that holds still over a speed that does not is a combination no machine makes.

**The silence.** Cranking ran at `load = 0`, so `burn` was 0.18 and the exhaust barely spoke. A cranking engine pumps air through an open exhaust on every stroke, and that chuffing is most of what a start is. With nothing there the starter's whine was the whole sound.

**Measured after.** The envelope modulation during the crank window now sits within 1% of each arrangement's compression rate — V8 17.5 against 17.3, I4 8.8 against 8.7, V12 25.8 against 26.0 — at 35–43% depth.

## Rationale / Context
Two of these were introduced in the same session that fixed the *steady-state* voicing against real recordings (DISC-026), and neither was caught by it, because every measurement there was of a spectrum at a fixed rpm. A transient has no steady spectrum, so nothing in the suite looked at one.

The general form is worth keeping: **a synthesised machine is convincing when its parts move together, and the ear detects a free constant long before it detects a wrong value.** The starter's frequency was not badly chosen — 1,160 Hz is a plausible gear mesh — it was simply not connected to anything.

## Impact
- `EngineRunState`: crank modulation at the true compression rate, a spin-up ramp, `CRANK_PUMPING`, and a ragged catch fed through the existing misfire parameter.
- `EngineSynth`: starter whine and armature both derived from instantaneous rpm via `STARTER_RING_TEETH`; per-vehicle gear scale so several cranking cars do not phase-lock.
- D15-R38a5 added; T-D15-17 asserts the chuff rate per arrangement.

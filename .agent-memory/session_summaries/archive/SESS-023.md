# SESS-023: the engine stops pumping

**Date:** 2026-08-13
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
The user asked for the distinct rumble to be synthesised correctly. It now is, to within a factor
of two of a real cross-plane V8, and the reason it was missing turned out not to be the one two
sessions had assumed. The sub-orders were already right; the engine simply pumped twelve times too
hard once per cylinder.

## Details
**The diagnosis inverted the problem** (DISC-030). Per-order envelope modulation against a Mustang
GT: our sub-orders 28.1% against its 27.0%, our firing order 20.3% against its 1.6%. Every effort
to date had been aimed at raising the half that was already correct.

**Two mechanisms, both physical, both previously absent** (DISC-030, DISC-031). A cylinder's
exhaust event is a blowdown *and* a 180° sweep, and only the blowdown existed. Pulses are fused by
an eight-stage all-pass chain measured in firing intervals — never by a comb, which pins the
spectrum and re-creates DISC-025 by another route.

**A reference was not one engine** (DISC-032). The four-cylinder target of 8.4 came from a clip of
several cars. Its peaks form no order series. Hours were spent chasing it.

**The gain imbalance lost its floor** (DEC-057). Detune and delay decorrelate banks; a gain
difference between banks firing the same pattern creates nothing. The floor was inventing a lope
for even-firing Vs and capping the V8's.

## Rationale / Context
Two test thresholds hold the tuning where it is and were **not** weakened: the V6's −12 dB
sub-order bound and the crank-chuff rate. Both blocked settings that measured better against the
Mustang, and both are right to — one keeps an even-firing V from loping, the other keeps a start
chugging. The remaining gap to the reference is recorded as a gap rather than tuned away.

## Impact
- `EngineSynth`: scavenge sweep added; `TAILPIPE_*` replaced by the diffuser; ring 4 k → 16 k.
- V8 lope 1.3 → 7.6/8.1 (real 17.4/18.2); 9.9 through the mixer. Centroid tracking ×2.5 → ×3.7.
- All 17 audio tests pass; none relaxed.
- `engine_reference.py` gains `--rumble`, `--compare`, `--identify`, MP3 input and windowing.
- D15: R38a10–R38a14, T-D15-21, T-D15-22. New memory: DEC-057, DISC-030 to DISC-032.

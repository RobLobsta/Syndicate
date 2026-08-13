# DISC-028: a real V8 recording says the even orders were 14 dB light, and why

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
The user supplied a Ford Mustang GT startup recording. Measured against it at matched engine speed, this synthesiser's odd sub-orders were within 7 dB and its **even** sub-orders 14 dB light. The cause is that two banks which are exact time-reverses of each other cancel their even content, and nothing in the model broke that symmetry. Fixed per-cylinder trims do; random jitter cannot.

## Details
**Reading the reference.** Decoded with `miniaudio` (no ffmpeg in the sandbox). Fundamental tracking was useless — octave-confused on a reverberant phone recording — but the harmonic comb read straight off a high-resolution spectrum of a steady window: **6.6 Hz spacing, so 792 rpm, firing order at 52.7 Hz**. A comb at the *cycle* rate rather than the firing rate is itself the cross-plane signature, and confirms the arrangement model.

| relative to the firing order | Mustang | before | after |
|---|---|---|---|
| odd sub-orders 1,3,5,7 | −37.3 dB | −44.1 | −37.4 |
| even sub-orders 2,4,6 | −37.4 dB | −51.2 | −41.1 |

**Why random noise could not fix it.** Per-event jitter already existed and spreads energy across the spectrum as noise. Orders are a property of what repeats every cycle, so only a *fixed* difference between cylinders puts energy back into them. `CYLINDER_LEVEL_SPREAD` and `CYLINDER_TIMING_SPREAD_DEG` are that, drawn once per vehicle from its seed — which also makes two cars of the same model measurably different.

**Where it stopped, and why.** ±20% level and ±3.2° timing matched the reference exactly and collapsed the arrangement contrast: the even-firing V10 out-burbled the cross-plane V8 at 2,000 rpm. Settled at ±12% and ±2.0°, leaving the evens 3.7 dB light. Some of the reference's even-order energy is plausibly body and room resonance rather than exhaust, and this synthesiser models a pipe, not a car.

## Rationale / Context
The first attempt at breaking bank symmetry was a flat gain imbalance on every V, and it **reproduced DEC-054's original mistake exactly** — banks that fire identically must stay matched, so any asymmetry has to scale with `bankDivergence`. That is now true of the detune, the delay and the gain. Third time this specific error has been made in this subsystem; the rule belongs in D15-R38a8 rather than in three separate constants' comments.

Two test thresholds moved as a consequence and both are genuine weakenings, recorded here so nobody reads them as tightening: the burble contrast went 12 dB → 5 dB and the dead-cylinder margin 5× → 1.25×, because engines whose cylinders differ have no exact nulls left to destroy.

## Impact
- New `CYLINDER_LEVEL_SPREAD` 0.12, `CYLINDER_TIMING_SPREAD_DEG` 2.0, `BANK_GAIN_IMBALANCE_MAX` 0.22 scaled by divergence.
- D15-R38a8 added: every asymmetry between banks scales with how differently they fire.
- `MIN_BURBLE_CONTRAST_DB` 12 → 5; dead-cylinder margin 5× → 1.25×.

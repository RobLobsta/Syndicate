# DISC-026: measured against real engines, the synthesiser was too dark and far too clean

**Date:** 2026-08-12
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
Thirty-nine CC-licensed engine recordings give two numbers to tune against: real exhaust harmonics fall at about **−7 dB per octave** from 100 Hz to 4 kHz, and stand **+9 to +17 dB** above the floor between them. `EngineSynth` measured **−22 dB/oct** and **+37 dB** — muffled, and almost noiseless. The second is what makes a synthesised engine sound synthesised.

## Details
**Where they came from.** Almost every audio host is proxy-denied — archive.org, freesound, Wikimedia, Zenodo, HuggingFace. `raw.githubusercontent.com` is not, and ESC-50 is a GitHub-hosted dataset with 40 clips in an `engine` class: CC BY-NC, **analysis inputs only, never committed** (D15-R39). Generic engines rather than the reference cars, which is fine: both measures are properties of exhausts in general, and neither was estimated at all before.

**Method** (`game-client/tools/engine_reference.py`). Rank by harmonic-product score, take the strongest eight, estimate the firing frequency, then measure only *at* its harmonics — peak power against the median floor midway between them. The raw spectrum reports −4.1 dB/oct, but that is street background flattering the tilt.

| | tilt 100 Hz–4 kHz | harmonic-to-floor |
|---|---|---|
| Real, 8 best clips (median) | −7.3 dB/oct | +9.3 dB |
| `EngineSynth` before | −22.3 | +36.8 |
| `EngineSynth` after | −6.7 to −10.9 | +18.5 to +23.3 |

Left a little cleaner than the real median, because the measured floor includes street background and so understates the engine's own tonality — the two cleanest clips measure +15.4 and +16.6.

**Two changes.** The muffler cascade moves from 640 Hz ×2.2 to 2,600 Hz ×4.0, and a continuous **flow-noise** term joins the excitation before the exhaust, scaled by `sqrt(rpm) × load`. Per-pulse turbulence already existed and could not fix the floor: it is gated by the pulse envelope, so it decays with the pulse and leaves the gaps empty.

## Rationale / Context
The knock-on was the interesting part. Brightening the exhaust unmasked a **comb notch from the bank delay**: 1.4 ms nulls at 357 Hz, and a V8 at 4,500 rpm fires at 300 Hz, so its firing order sat 12 dB down. DEC-054 chose 1.4 ms because shorter killed the burble — but that was judged against the dark exhaust, and the burble it protected was partly the firing order being notched rather than the sub-orders being loud. At 0.7 ms the worst deficit goes from −12.5 dB to −4.4 dB and the V8's odd sub-orders still stand 15 dB clear of every even-firing arrangement's.

That invalidated a test the same way DISC-025's was invalidated: counting sub-orders against a threshold relative to the firing order, when the firing order is what the defect suppresses. It is now a contrast between arrangements.

## Impact
- Muffler 640 → 2600 Hz, breached 2600 → 6000, second stage ×2.2 → ×4.0; new `FLOW_NOISE = 0.90`.
- `BANK_DELAY_MAX_SECONDS` 0.0014 → 0.0007; supersedes DEC-054's value, not its reasoning.
- Burble test restated as a 12 dB contrast between arrangements; centroid rail widened to 6.5.
- D15-R38a4 and T-D15-16 added; harness at `game-client/tools/engine_reference.py`.
- Faster as well: 147 ns/sample, 24 voices at 18% of one core.

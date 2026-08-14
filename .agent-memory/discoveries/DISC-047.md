# DISC-047: fbm never reaches ±1, and the PBR environment brightens a flat colour fourfold

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.1, docs/16_procedural_arena_generation.md#D16-S6

**Status:** active

## Summary
Two measured constants, both found by looking at output rather than by reading code, and both of
which silently produced a plausible-looking wrong result. `TerrainNoise.fbm` is normalised to [-1, 1]
and in practice spans about ±0.44. `RenderEnvironment` renders a plain base colour roughly four times
brighter than the colour itself.

## Details

**The noise range.** `fbm` divides by the sum of its octave amplitudes, so it is bounded by ±1 — and
gradient noise summed over four octaves reaches ±0.44, measured over a 301 × 301 field. The rectified
field `0.5 + 0.5·n` therefore spans 0.28 to 0.72 and never approaches either end.

The scrapyard's heap layer thresholds that field and rescales what is above the threshold. Dividing
by `1 - HEAP_FLOOR` — the obvious thing — assumes the field reaches 1.0, so the tallest heap on the
map came out at a twelfth of its intended height: a 14 m spoil heap arrived as a 1.2 m bump, and the
arena measured 100% drivable with no visible error anywhere. Divide by `HEAP_PEAK - HEAP_FLOOR`
against a measured peak instead.

**The environment gain.** `RenderEnvironment` runs one sun at intensity 3.2, an ambient term of 0.55
and an outdoor image-based light. A material with base colour 0.19 and no texture renders at about
0.75. The shipped cars look correctly exposed through it only because their glTF materials were
authored from photographs and are dark to begin with.

The first terrain capture was a snowfield: every surface, sand through tarmac, rendered near-white.
Diagnosis was one capture with the palette set to saturated red and green, which showed the materials
were being applied correctly and the exposure was the problem — a distinction no amount of reading
the shader would have settled quickly.

## Rationale / Context
Both are the same shape of mistake: a normalised quantity assumed to use its full range. The heap one
is the more dangerous, because its symptom was a *reasonable* landscape rather than a broken one, and
every automated check passed. It was caught only because a test asserted the arena should **not** be
entirely drivable.

## Impact
- `HEAP_PEAK = 0.72` and `ENVIRONMENT_GAIN = 4.0` are both named constants with the measurement in
  their comment. Both must be re-measured if the noise or the lighting changes.
- Any future layer that thresholds and rescales `fbm` needs the same treatment.
- A test that asserts an upper bound as well as a lower one is what caught this; prefer them.

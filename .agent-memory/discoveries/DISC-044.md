# DISC-044: a fixed windward fraction makes the dune's angle an output, and it was a ramp

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.3

**Status:** active

## Summary
D16-S5.3 specified the dune profile with a fixed windward fraction of 0.72, which makes the slip
face's **angle** a consequence of dune height and wavelength rather than an input. At the parameters
the document shipped — 9 m over 90 m — that angle is **19.6°**, well under the 25° a vehicle can
climb. Every dune in the desert biome would have been a ramp on both sides.

## Details
The whole desert biome hangs on one gap (D16-R2): a slip face at the 33° angle of repose is above
the 25° drivable limit, so a dune is a ramp from one side and a wall from the other. A profile that
fixes the windward fraction cannot deliver that, because the angle falls out of

```
tan(slip) = height / ((1 - windwardFraction) × wavelength) = 9 / (0.28 × 90) → 19.6°
```

D16-R33's remedy made it worse rather than better: it scaled `duneHeightM` **down** until the mean
slope came back into tolerance. That only helps a face that is too *steep*. Against one that is too
shallow it is a correction pointing the wrong way, and there is no value of the height it converges
to.

Solving for the slip width instead makes the property hold by construction:

```
slipFraction = amplitude × phaseGradient / tan(SAND_REPOSE_DEG)
```

Two details are load-bearing, and each was found by measuring the result rather than by reading it:

- **The local crest height, not the nominal one.** Crest heights are modulated down to 70% of
  nominal, so solving once from `duneHeightM` leaves most dunes with a face too gentle. Real dunes
  stand at repose whatever their size.
- **The local phase gradient, not `1 / duneWavelengthM`.** The phase warp that stops dunes being
  corduroy also means a phase cycle is not the same number of metres everywhere. Solving against the
  mean produced a measured mean of **38.7°** — steeper than repose, because the faces are compressed
  wherever the warp crowds two crests together.

With both, the shipped arena measures a mean of 32.5° across 2,229 face cells, a median of 32.9°,
and a 90th percentile of 34.6°.

## Rationale / Context
The general shape of this is worth keeping even after dunes stop being interesting. A generator
parameter that is *physically meaningful* — an angle of repose — should be an input the generator
solves for, not an output it is checked against. The check-and-correct form looks equivalent and is
not: it needs a convergent correction, and a one-directional one is a correction that silently does
nothing half the time.

## Impact
- D16-S5.3 rewritten: R33 restated, R33a added, the pseudocode replaced.
- Costs two extra `fbm` evaluations per sample for the warp derivative. A 601² field generates in
  187 ms, which is a load-time cost and not a per-tick one.
- The test that measures this asserts the 90th percentile as well as the mean, because a mean can be
  right while the distribution is wrong (DISC-033).

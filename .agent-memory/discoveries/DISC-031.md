# DISC-031: fusing exhaust pulses with a comb re-creates the defect it is fixing

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a11, D15-R38a2

**Status:** active

## Summary
Successive blowdowns have to overlap for an engine to rumble rather than pump (DISC-030). The
obvious mechanism — delay lines with the reflections mixed back — pins the spectrum, which is
exactly the DISC-025 defect this whole rewrite exists to remove. An all-pass chain cannot.

## Details
**The comb fails.** Driven hard enough to fuse the pulses (mix 0.95) it does raise the rumble to
5.88, and at that setting the I4's spectral centroid stopped tracking engine speed — ×1.8 against
the ×2.0 bound in T-D15-12. Fixed delays have fixed resonances. This is DISC-025 arriving by a
different route, and it is a trap worth naming because the comb is the physically obvious model of
a tailpipe and it does the right thing to the envelope.

**An all-pass cannot fail that way.** Unity magnitude at every frequency by construction: it
disperses in time and colours nothing. Substituted for the comb, the centroid ratio went the other
way — worst case ×2.5 to ×3.7 — while the firing-order modulation fell from 29.6% to 4.0%.

**Two further findings, both measured:**

*Stage count matters.* With four stages the V8's firing-order modulation was 1.6% at 900 rpm and
16.8% at 1,100 — a tenfold swing across a fifth of the rev range. One stage's delay had come near
the firing period, so its echoes landed on the *next* cylinder instead of in the gap before it and
reinforced the pumping. Eight cannot all align.

*Delays belong in firing intervals, not milliseconds.* Fixed in time, the character changed with
engine speed. Expressed as fractions of the firing interval the whole model is scale-invariant, and
the engine sounds like itself at every speed. The pipe's genuinely fixed resonances are the
formants, and those must not move — that distinction is the whole justification.

**A bug this introduced.** Clamping over-long stages individually collapses the last four onto the
same delay at cranking speeds, turning eight diffusers into one hard echo. It measured as a false
4 Hz crank chuff. Scale the whole set together instead.

## Rationale / Context
The next person to hear a synthesised engine pumping will reach for a delay network, because that
is what an exhaust is. This entry is here so they reach for the all-pass form of one.

## Impact
- D15-R38a11 added; T-D15-22 added.
- `EngineSynth`: `TAILPIPE_*` replaced by `DIFFUSER_FRACTIONS`, `DIFFUSER_GAIN`, `DIFFUSER_MIX`.

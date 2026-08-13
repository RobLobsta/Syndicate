# DISC-034: the overrun crackle ran off a clock and sounded like popcorn

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a7a, D15-R38a5

**Status:** active

## Summary
`CRACKLE_RATE_HZ = 26.0` produced 11 to 14 evenly spaced bangs a second on every arrangement at
every engine speed. It is the same fault as the hardcoded 6 Hz crank in R38a5, introduced two
sessions after that one was recorded, by the same hand.

## Details
**The rate was the wrong shape.** Unburnt charge lights when an exhaust valve opens onto a hot pipe,
so a pop is an exhaust event that went wrong and its opportunities arrive at the firing rate. As a
per-event probability the rate rises with revs, differs between a four and a twelve, and dies with
them. As a constant in hertz it does none of those, and the ear identifies it immediately: a stream
of identical clicks that does not belong to the car.

**A tick is not a bang.** The crackle was one band-pass at 1.9 kHz — all crack, no body. A real
detonation shoves a slug of gas down a pipe and thumps before it cracks. Adding the thump exposed a
second bug: a band-pass passes white noise in proportion to its bandwidth, so the narrow low filter
emitted a third of what the broad high one did, and giving the bang body made it *quieter*.

**Independent coin flips are uniform by construction.** Real crackle arrives in bursts because one
detonation leaves the pipe hotter and the charge behind it richer. A per-event probability with no
memory produces a flat Poisson stream, which is the defect again in a subtler form.

Measured on a rendered lift: 11–14 bangs/s before, 3–7/s after, irregularly spaced.

**The test could not have caught any of it.** It asked for energy between 1.2 and 3 kHz after a lift
— the band the crackle happened to occupy when it was a hiss — so it passed the popcorn and then
*failed* the fixed version, whose energy had moved. Restated as crest factor (a peak standing clear
of its own surroundings, band-agnostic) plus a rate that must rise with engine speed.

## Rationale / Context
Recorded because DEC/DISC entries on R38a5 already existed and did not prevent the repeat. The rule
needs stating as a check rather than as a principle: **before adding any constant with `_HZ` or
`_RATE` in its name to this synthesiser, ask what it is a rate of.** If the answer is not an
engine quantity, it is wrong.

## Impact
- `CRACKLE_RATE_HZ` replaced by `CRACKLE_PER_EVENT`, plus a thump path and clustering.
- D15-R38a7a added; T-D15-24, T-D15-25 added; the old band-energy assertion replaced.

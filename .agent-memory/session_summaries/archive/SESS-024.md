# SESS-024: three complaints, three mechanisms that were not there

**Date:** 2026-08-13
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
The user reported no startup sound, no feelable rumble, and a popping "like popcorn being made" on
every engine. All three were real, all three were mechanisms the model did not have rather than
constants set wrong, and one of them was a fault this project had already recorded and repeated.

## Details
**The lope was in the wrong place** (DISC-033). SESS-023 fixed the sub-order *total* and the user
still could not feel the cycles. Correct: the total matched while orders 1 and 2 — 6 and 12 Hz, the
rate you feel — sat at 0.9% and 1.1% against a real V8's 4.8% and 8.9%, with everything piled into
order 3 at whatever pitch that vehicle's random trims happened to give it. Two mechanisms fixed it:
a **flywheel**, so the crank surges and drops within each cycle (a low-pass on torque impulses,
which lands on the low orders by construction), and the cross-plane V8's **rocking couple**, which
is its order 2 and which nothing else in the model could produce.

**The crackle ran off a clock** (DISC-034). `CRACKLE_RATE_HZ = 26.0` gave 11–14 evenly spaced bangs
a second on every engine at every speed. That is R38a5's fault — a periodic component untied to the
crank — reintroduced two sessions after it was recorded. Pops now fire on exhaust events, cluster,
and have a low thump instead of being a bare 1.9 kHz tick.

**The start was faithful and inaudible** (DEV-016). The crank matched the Mustang at 0.54 s; the
user heard nothing, which was the right report. Doubled, with a louder starter that audibly labours
on each compression. Recorded as a deviation from R38a9, not slipped in.

## Rationale / Context
Three tests had to be restated, and none was weakened. Each was measuring where a thing *happened
to be* rather than what it *is*: crackle energy in a fixed band that a better bang moved out of;
the loudest Fourier component of a chuff whose second harmonic exceeds its fundamental. Replaced by
crest factor, a pop rate that must rise with engine speed, and autocorrelation. All are
band-agnostic or shape-agnostic, and all would have caught the defects that were reported by ear.

## Impact
- V8 idle: order 1 0.9% → 4.8%, order 2 1.1% → 11.8%; bangs 11–14/s → 3–7/s and irregular.
- 18 audio tests pass; three restated, none relaxed.
- `ShowcaseRenderer` + `:game-client:showcaseAudio` render an audition take per car (D15-R38a15).
- D15: R38a7a, R38a15–R38a17, T-D15-23..25. New memory: DISC-033, DISC-034, DEV-016.

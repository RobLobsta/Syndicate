# DISC-032: a clip of several cars was used as a single-engine reference

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a14, D15-R38a4

**Status:** active

## Summary
A supplied recording of multiple cars starting was measured as though it were one four-cylinder at
1,450 rpm, giving a rumble ratio of 8.4. That number was then chased through a parameter search for
most of a session. Its envelope-modulation peaks form no coherent order series at all.

## Details
The cycle rate was derived from the recording, the peaks were read off at multiples of it, and the
orders duly appeared — because with eight harmonics to choose from and a ±0.7 Hz search window,
almost any dense modulation spectrum will produce them.

The check that was skipped: **do the strongest peaks lie on one series?** For the real Mustang GT
they do, and each order stands two to four times above the modulation halfway to the next one. For
this clip the strongest peaks are 33.2, 35.2, 11.4, 10.3, 24.7, 63.6, 96.4 and 103.5 Hz — no common
divisor, two near-duplicates a couple of Hz apart, which is two engines at slightly different
speeds. The user described the clip accurately as "different cars starting"; it was the analysis
that assumed one.

The cost was real: several hours of tuning aimed at a four-cylinder target that does not exist, and
an incorrect conclusion along the way that the I4 arrangement was badly wrong. The only defensible
engine reference this project has is the single-car Mustang GT recording.

## Rationale / Context
Recorded because the failure mode is invisible from inside — every intermediate number looked
plausible, and the search only stopped because the V8 and the four disagreed by a factor of twenty
and the V8's numbers were the ones that moved sensibly under changes.

## Impact
- D15-R38a14 added: a recording is not a reference until its orders are checked.
- `engine_reference.py --identify` implements the check and reports how many of the strongest peaks
  fall on one series.
- The four's rumble ratio is now an unconstrained output, not a target. Measured at 0.32; a four
  legitimately buzzes more than a V8, and there is no trustworthy recording to say how much.

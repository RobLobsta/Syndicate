# DISC-023: a crossfade removes a loop's value discontinuity and hides its phase one

**Date:** 2026-08-12
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/12_testing_validation_ci.md#D12-S4.1

**Status:** active

## Summary
`Waveform.loopable(seconds)` trimmed a crossfade measured in *seconds* off a buffer whose length had been chosen to hold a whole number of *cycles*, destroying exactly the property the caller established. Three of the six shipped engine loops — I4, V8 and V10 — held a fractional number of cycles and warbled once per pass. The existing loop test was green on all six, because a crossfade guarantees continuity in value and says nothing about phase.

## Details
`engineLoop` picked `length = round(cycles × SR / firingHz)`, and its docstring stated the consequence: "the sample after the last is exactly the first". Then `loopable(0.02)` removed 960 samples — a whole number of cycles only when the fundamental divides neatly. Measured on the old bank:

```
cfg    firingHz   kept  cycles in kept  seam
i4       133.33  23160          64.333  BROKEN
i6       200.00  23040          96.000  exact
v6       200.00  23040          96.000  exact
v8       266.67  22980         127.667  BROKEN
v10      333.33  23088         160.333  BROKEN
v12      400.00  23040         192.000  exact
```

The three exact ones were exact by luck: 200 and 400 Hz divide 48000/0.48. Spectrally the difference is unmistakable — the V6's harmonics are single lines at 200, 400, 600, 800 Hz; the V8's smear into sidebands about 2 Hz either side (263.2 / 265.3 / 267.4 / 269.5 / 271.5 Hz), the loop rate. Audibly: a warble on the Stampede and not the Eclipse, from identical code.

**Why the test did not catch it.** `everyLoopJoinsCleanly` measures `|samples[0] - samples[n-1]|` against the buffer's own 95th-percentile adjacent-sample step. An equal-power crossfade makes those two samples continuous *by construction*, so the assertion can never fail on a crossfaded buffer however badly the periodic content lines up. It was testing the crossfade, not the loop.

The fix is `loopable(seconds, fundamentalHz)`, rounding the crossfade to a whole number of cycles first. The fade becomes approximately rather than exactly `seconds` long, which nothing can hear.

**The fundamental to pass is not the obvious one.** For a pulse-train engine (DEC-052) it is the *engine cycle* rate, `rpm/120`, not the firing rate: an uneven bank pattern repeats once per 720°, so a buffer holding whole firing intervals but fractional cycles splices a bank's pulse train into its own middle. All six loops now hold exactly 15 engine cycles.

## Rationale / Context
The general lesson outlives the bug: **a smoothing operation applied to make a test pass makes that test unable to fail.** Same pattern as DISC-022. The counter-measure that worked was measuring the artefact — a DFT over the committed WAV files — rather than asserting on the code that wrote them.

Anyone adding a loop must choose an overload. Filtered noise with nothing periodic can use `loopable(seconds)`; anything with a fundamental must pass it, and passing the wrong one fails silently.

## Impact
- `Waveform`: `loopable(double, double)` added, `loopable(double)` narrowed to aperiodic content, `crossfade(int)` extracted.
- `SoundSynth`: `engineLoop` and `inductionLoop` pass their fundamentals; induction also takes a 50 ms crossfade, because broadband rush at 5 kHz leaves a join no smoother than its own texture at 20 ms.
- `SoundBankTest.everyEngineLoopHoldsAWholeNumberOfEngineCycles` is the assertion `everyLoopJoinsCleanly` could not be.

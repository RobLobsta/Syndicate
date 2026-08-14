# DISC-045: dunes that all stand at repose partition the arena into parallel corridors

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.3, docs/16_procedural_arena_generation.md#D16-S5.11

**Status:** active

## Summary
Making every dune slip face a wall (DISC-044) worked, and made the arena unplayable. Dunes run
transverse to the wind, so uniformly non-zero dune heights produce uniformly continuous **walls**:
the field measured 73% drivable and split into **42 disconnected regions**, the largest of them
23.8% of the arena. The connectivity check refused to load it, which is the only reason this was
found at build time rather than by driving.

## Details
It follows directly from the property that makes dunes good. A slip face past the drivable slope is a
wall; a dune field is a set of long parallel crests; so a dune field is a set of long parallel walls,
and a player travels along the troughs but never across them.

**The first fix connected the arena by deleting the dunes.** A crest field falling to zero below 0.42,
ramped to full height over the rest of its range, gave 80.5% drivable with the largest region at
76.4% — apparently a complete success. Measuring the dune layer alone showed what had happened: peak
height had fallen from 9 m to **3.55 m**, 62.7% of the field was dead flat, and 0.7% of it was
undrivable. The connectivity came from there being almost no dunes left.

The cause: a three-octave fBm is concentrated near its midpoint, so a threshold at 0.42 with a ramp
across the whole remaining range leaves typical values at a quarter of full height.

The working form is a **sharp gate** — zero below 0.40, 70% of nominal by 0.50, full height above —
a tenth of the crest field's range rather than three fifths. Measured: peak 7.2 m, 29.2% of the field
over 4 m, 35.5% flat, largest region 69.3% of the arena and 94% of all drivable ground.

## Rationale / Context
Two things will recur. First, a *local* property proved correct — every face at repose — can produce
a *global* one nobody checked: traversability is not implied by any measurement of one dune, and only
a connected-component pass over the whole grid sees it.

The second is the shape of the first fix: a metric moved decisively the right way while the mechanism
behind it was being removed. Same failure as a matching sub-order total hiding a lope in the wrong
place (DISC-033), and the same counter-measure worked — measure the mechanism in isolation, not only
the number it was meant to improve.

## Impact
- D16-R34a added; `CREST_GAP_LOW`, `CREST_GAP_HIGH` and `CREST_PASS_FLOOR` replace a single scale.
- A connected-component test asserts the largest drivable region holds at least 90% of drivable
  ground, naming the 42-region figure as the failure it guards.
- The passes are better level design than corduroy anyway: a dune field with gaps is routes with
  chokepoints, which is what a barchan field looks like.

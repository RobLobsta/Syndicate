# DISC-018: an object in a downloaded car is a material group, not a part

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.2, docs/08_asset_pipeline.md#D08-S4.1

**Status:** active

## Summary
`syndicate_dissect` treated each imported object as an island. On both shipped cars an object is
everything sharing one material, spanning the whole vehicle: one Eclipse object is the entire cabin,
another is both headlights and both tail lights at once; the Stampede's `Window_Material1` object is
all of its glass. True connected-component separation turns 171 objects into **6,830 shells** and
1,101 into **6,078**, in 16 seconds each.

## Details

**How it was hidden.** The wheels were found anyway, because both artists happened to author each
wheel as its own object. Every measurement the tool reports is a wheel measurement, so nothing
downstream ever asked whether the *chassis* was one thing or two hundred. `separate_loose()` existed
in the module and was never called from `classify()`.

**What the numbers are.** Eclipse 283,192 triangles → 6,830 shells; Stampede 234,057 → 6,078. Of
those, 66% and 75% respectively are under 20 triangles: bolts, screws, single grille strands. They
are noise and must be merged into a neighbour, not labelled (D15-R17).

**What separation buys.** Door-sized shells exist on both cars and come in exact mirrored pairs —
Eclipse at `(±0.83, 0.60, 0.31)` sized `0.24 × 0.58 × 1.42`, Stampede at `(±0.86, 0.59, −0.01)` sized
`0.13 × 0.73 × 1.39`. Without separation those are invisible, buried inside a body-wide object.

**What it does not buy.** A door is not one shell: the Eclipse's door region holds at least three,
with different materials — outer skin, inner card, window frame. Grouping them back together is the
next problem, and bounding-box clustering does not solve it (DISC-019).

**Cost.** 16 s per model, once, at asset time. Cheap enough that D15-R16 forbids skipping it as an
optimisation — the version that skipped it is the version that could only see wheels.

## Rationale / Context
Recorded because the tool's own docstring asserts the opposite ("Loose parts, but only for objects
that actually straddle a wheel boundary… on all 171 of them it is minutes for no benefit"). That
estimate was wrong by an order of magnitude and it justified the design that could not see a door.

## Impact
`blender-tool/syndicate_dissect` (`collect_islands`, `classify`), and every part label beyond
`wheel`, none of which is reachable without this.

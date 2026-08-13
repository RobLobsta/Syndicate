# DISC-036: a bearing of exactly zero lands in the last sector, not the first

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.4, D15-R24b

**Status:** active

## Summary
`angular_coverage_deg` bins a vertex by `degrees(atan2(dy, dz)) % 360`. A vertex sitting exactly on
the axle's `+z` bearing has `dy = 0` — except that the axle centre it is measured against is derived
from a bounding box, so `dy` comes out as `-2.8e-17`, the bearing as `-1.8e-14`, and the modulo
sends it to **359.99999…**, which is the twenty-fourth sector rather than the first.

## Details
One misplaced sector is not a rounding error in this measurement, it is a different answer. The set
`{0, 6, 12, 18}` — four lug nuts at ninety degrees — is invariant under a quarter turn and rotates
with the wheel (DEC-066). The set `{0, 6, 12, 18, 23}` is invariant under nothing, and the nuts go
to the hub.

It only bites where a group is *sparse*. A tyre puts vertices in all twenty-four sectors and does
not care which one a particular vertex lands in; a bolt pattern has exactly as many occupied sectors
as it has bolts, and one spurious member breaks the symmetry test outright. So the fault is
invisible on everything except the case the symmetry test exists for.

The fix is one line — snap a bearing within `BEARING_EPSILON_DEG` of 360 down to 0 — and the reason
to record it rather than just write it is that it is *not* visible in the diff: the code reads as a
perfectly ordinary modulo, and the only sign anything is wrong is a coverage of 75° where the
geometry says 60°.

## Rationale / Context
Found by a unit test whose synthetic wheel was built about `axle_y = 0.35` while the corner's axle
was measured as `0.35000000000000003`. That difference is not an artefact of the test: every axle in
this pipeline is `(lo + hi) * 0.5` over floating-point bounds, so an artist-authored wheel whose
valve stem sits on the vertical centreline hits it exactly the same way.

The general form is worth carrying: **any `% 360` that bins an angle has a boundary at zero, and
zero is where symmetric things put their first element.**

## Impact
- `grouping.occupied_sectors` snaps near-360 bearings to 0; `BEARING_EPSILON_DEG` is 1e-6.
- D15-R24b states the requirement so it survives a rewrite of the function.

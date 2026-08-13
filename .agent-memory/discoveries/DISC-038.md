# DISC-038: real art's topology decides what every later stage can do

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.5, docs/15_vehicle_preparation_pipeline.md#D15-S5.2

**Status:** active

## Summary
Welding doubled vertices more than **halves** the shell count on both shipped cars — the
Eclipse goes from 6,830 shells to 2,878 — and dissolving slivers is what makes damage morphs
possible at all: with 502 degenerate faces removed the chassis could not be dented, and with
11,988 removed it dents at the second amplitude tried.

## Details
Two numbers from the first runs on real models, both about the same thing.

**Separation.** D15-R16 recorded 6,830 and 6,078 shells as the cost of separating the two cars.
Those figures were measured *without* a cleanup stage. With welding at 0.1 mm the same models
separate into 2,878 and 2,466. Thirty-nine thousand of the Eclipse's 216,000 vertices were
duplicates, and every duplicate is a seam splitting one surface into two components that share
an edge in appearance and not in topology. Everything downstream inherits it: the merge floor,
the mirror pairing, the corner capture, the part count.

**Deformation.** The chassis initially got no damage morphs at all. D09's zero-area guard
rejected `dmg_25` because a 4 cm dent collapsed *one* face somewhere in 181,000 triangles —
correctly, and at the cost of the largest part on the car having no deformation. Deleting faces
below `MIN_FACE_AREA_M2` (1e-8 m²) does not prevent it: a sliver of 2e-8 survives the delete and
collapses the moment a vertex moves. `bmesh.ops.dissolve_degenerate` at 0.5 mm is the right
operator — it *collapses* a sliver into its neighbours rather than deleting it, so no hole is
left — and it took the Eclipse's degenerate count from 502 to 11,988 removed.

Retrying at a smaller amplitude is the other half, and the window is narrow in both directions:
4 cm collapses a face, and below 2 cm D09's *other* guard rejects `dmg_25` for displacing less
than the 5 mm minimum, because level one is a quarter of the amplitude. The ladder is
(0.04, 0.03, 0.02) and the Eclipse's chassis lands on 0.03.

## Rationale / Context
The lesson is not "weld your meshes". It is that **the cost and the capability of every stage
after separation are properties of the source file's topology, not of the algorithms** — and
that a measurement recorded before a cleanup stage existed silently stops being true when one
does. D15-R16's numbers were quoted in three places and were wrong by a factor of two the
moment welding was added; they are now recorded with the condition attached.

## Impact
- `cleanup.DEGENERATE_EDGE_M` and the `dissolve_degenerate` pass in `prepare.clean_topology`.
- `exporter.MORPH_AMPLITUDES_M` is a ladder rather than a constant.
- D15-R16 restated with both figures and the reason they differ; D15-R27a extended.
- The Eclipse's chassis ships with four damage morphs, which is the first content slot 23 has
  ever had to drive.

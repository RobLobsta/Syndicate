# SESS-027: the glass shatters, the doors are found, and the tank is answered

**Date:** 2026-08-14
**Category:** session_summaries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2.1, docs/15_vehicle_preparation_pipeline.md#D15-R6a, docs/15_vehicle_preparation_pipeline.md#D15-S5.4

**Status:** active

## Summary
Three asks: fix the glass shatter, decide whether the Stampede has doors and find them, and say
what happens to a tank. All nine glass panes on both cars now fracture into 24 shards with mass
conserved exactly; both cars now yield two hinged doors; and a tank was built and run rather
than reasoned about, which produced a clear answer and turned up a defect that had nothing to
do with tanks.

## Details
**Glass.** DISC-039 had diagnosed the cause — a solid partition cannot cut a thickened pane —
and proposed cutting the surface first and thickening the patches second. That path
(`syndicate_fracture/shell.py`, now D09-S5.2.1) was already written; finishing it took two more
fixes, both found by measuring rather than by reading:

- The coverage check recovered each patch's area from its *solidified* volume, which carries
  `solidify`'s curvature inflation, and then held it to 1%. Four of nine panes failed a check
  about a quantity the cut is not responsible for. Measuring the patches **as cut** takes every
  pane to within 0.03%.
- `convex_hull` was returning face sets that were not polyhedra on glass shards — 30 vertices
  carrying 71 triangles, four of them 22 mm outside the hull they belonged to. Two structural
  bugs, both silent (DISC-040).

**Doors.** The Stampede has doors, they are already clean mirrored shells, and two independent
things hid them: flatness 0.809 against a 0.86 bar, and a material called `paint` voting for the
chassis. Fixed as a geometric cue rather than a `parts.json` entry, because every car has doors
and fixing it per model means fixing it again per model (DEC-068, D15-R6a).

**Tank.** Built as a downloaded model would arrive — 31 root-level objects, materials named
after their look, no hints. The classifier labels all sixteen road wheels correctly and then the
four-corner wheel model captures eight a side into one corner, calls it a wheel 7.04 m across,
and correctly dissolves it; the turret and the gun are `chassis` because they span a third of the
vehicle, and the tracks are `panel`/`sill`. The result is one immobile 5.2 t chassis that passes
every check (DISC-042).

The tank also exposed DISC-041: a model with no common parent lost 30 of its 31 objects before
stage 1, silently, because the loader kept the root subtree with the most *objects* and every
root in a flat scene has one. The first fix — spatial clustering — regressed both cars, and the
Eclipse's stray icospheres are why: they sit concentric with the car.

## Rationale / Context
The tank question was answered empirically on purpose. The interesting part of the answer was
not the labelling, which mostly held up, but the structures built on it — and no amount of
reading the cue code would have shown that the corner model turns twelve correct wheels into
zero.

## Impact
- 231 unit tests pass, up from 225; `validateDocs` passes with 458 section ids.
- Both cars re-run byte-identical after DISC-041's fix, and `verify_prepared` reports 54 parts,
  2 vehicles, 0 findings.
- D09 gains S5.2.1 and R11a-c; D15 gains R6a. PROG-024 updated; DEC-068, DISC-040, DISC-041 and
  DISC-042 added.
- `:memory-system:lintMemory` still cannot run here — no JDK 17 (DISC-007) — so this entry and
  INDEX.md were written by hand against D13-S4.2.

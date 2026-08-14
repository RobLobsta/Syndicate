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
**Glass.** DISC-039 had diagnosed the cause and proposed the remedy: cut the surface first,
thicken the patches second. That path (`syndicate_fracture/shell.py`, now D09-S5.2.1) existed;
finishing it took two more fixes, both found by measuring rather than reading — a coverage check
that measured curvature instead of the cut (DISC-043), and a convex hull that was not convex on
a slab of glass (DISC-040).

**Doors.** The Stampede has doors, already clean mirrored shells, hidden by two independent
things: flatness 0.809 against a 0.86 bar, and a material called `paint` voting for the chassis.
Fixed as a geometric cue rather than a `parts.json` entry, because every car has doors and
fixing it per model means fixing it again per model (DEC-068, D15-R6a).

**Tank.** Built as a downloaded model arrives — 31 root-level objects, no hints. All sixteen
road wheels are labelled correctly and the four-corner model then captures eight a side into one
corner, calls it a wheel 7.04 m across, and correctly dissolves it; turret and gun are `chassis`,
tracks are `panel`/`sill`. One immobile 5.2 t chassis, passing every check (DISC-042).

It also exposed DISC-041: a model with no common parent lost 30 of its 31 objects before stage 1,
silently, because the loader kept the root with the most *objects* and every root in a flat scene
has one. The first fix — spatial clustering — regressed both cars, and the Eclipse's stray
icospheres are why: they sit concentric with the car.

## Rationale / Context
The tank question was answered empirically on purpose. The interesting part was not the
labelling, which mostly held up, but the structures built on it — and no amount of reading the
cue code would have shown that the corner model turns twelve correct wheels into zero.

## Impact
- 231 unit tests pass, up from 225; `validateDocs` passes with 458 section ids.
- Both cars re-run byte-identical after DISC-041's fix, and `verify_prepared` reports 54 parts,
  2 vehicles, 0 findings.
- D09 gains S5.2.1 and R11a-c; D15 gains R6a. PROG-024 updated; DEC-068 and DISC-040 to
  DISC-043 added.
- `lintMemory` caught seven entries over D13-R23's word limit — four of them left by the
  previous session — and PROG-024's tables. DISC-007's init-script workaround runs it here and
  was simply not used; that omission is what let a red gate ship twice.

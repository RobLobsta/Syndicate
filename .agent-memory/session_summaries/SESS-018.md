# SESS-018: the caliper stops spinning, and segmentation gets measured

**Date:** 2026-08-10
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S1, docs/15_vehicle_preparation_pipeline.md#D15-S5.4

**Status:** active

## Summary
Fixed the brake caliper rotating with the tyre, by a physical test rather than a naming one, and
measured what a general vehicle segmentation tool could and could not do on the two shipped cars.
The answers became `docs/15_vehicle_preparation_pipeline.md`, a new blueprint (D15).

## Details

**The caliper.** Everything inside a wheel's cylinder went into the wheel, so the caliper spun. The
test that works needs no names: a part bolted to a rotating wheel must be rotationally symmetric
about the axle. Every rotating piece on both cars covers 360°; every caliper covers 90–150°.
Coverage is measured over vertices, and over a **material group within a corner** rather than a
single shell — symmetry is a property of an assembly, and a lug nut alone occupies 15°. The first
attempt judged shells individually, exiled 177 of the Stampede's rim shells and failed six CI tests
(DEC-044).

**Why CI caught it and the local run did not.** `assets/` is on no source set, so regenerating the
meshes left every test task `UP-TO-DATE` and the suite passed against the meshes it had seen on an
earlier run. Test tasks now declare the asset tree as an input (DISC-020).

**Three findings about segmentation**, each measured:

- **The tool was not separating anything** (DISC-018). An imported object is a material group: one
  Eclipse object is the whole cabin, another is both headlights and both tail lights. True separation
  gives 6,830 and 6,078 shells in 16 s each, two-thirds of them under 20 triangles and needing merging
  rather than labelling.
- **Naming is worth 99% on one car and 36% on the other** (DISC-019). The Stampede's materials are
  `…Window_Material1`; the Eclipse's are `bw00.001` and `oyctp`. Physics — transmission, alpha,
  roughness — finds glass on both. Names find it on one.
- **Spatial clustering does not group shells into parts.** Union-find over bounding-box overlap
  collapses both cars to a single cluster at any padding useful enough to join a door skin to its
  card. Grouping is by `(label, side, index)` after labelling, using mirror pairing.

**Why the escape hatch is affordable.** The Eclipse's 64% of unlabelled geometry is six material
names, so D15 keys its `parts.json` override by material rather than by shell.

**D15 also specifies** geometry repair (symmetry reported, never auto-corrected — real cars are
asymmetric on purpose), hinge inference as part *data* rather than an armature, per-class destruction
treatments, and an audio inventory that is per class and per material rather than per vehicle.

## Rationale / Context
The method is worth keeping: every design claim was checked against both cars before being written
down, and two plausible plans died that way — name-driven labelling and spatial clustering. A
prototype answering "how far does this get on the hard case" is cheaper than a tool that answers it
after being built.

## Impact
`blender-tool/syndicate_dissect/dissect.py`, all six `assets/parts/*/mesh.glb`, new
`docs/15_vehicle_preparation_pipeline.md`, `docs/00_master_index.md`, `CLAUDE.md`, `ROADMAP.md`.

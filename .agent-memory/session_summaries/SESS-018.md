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

**The caliper.** Everything inside a wheel's cylinder was being exported into the wheel, so the
caliper spun. The test that works needs no names: a part bolted to a rotating wheel must be
rotationally symmetric about the axle. Every rotating piece on both cars covers 360°; every caliper
covers 90–150°. Coverage is measured over vertices — a five-spoke rim's bounding box has four
corners like anything else's — and the test had to be applied to seed islands too, because eight
caliper fragments of 1–40 triangles had seeded the Stampede's front-right corner (DEC-044). Every
wheel's diameter, width and axle are unchanged to four decimals.

**Three findings about segmentation**, each measured:

- **The tool was not separating anything** (DISC-018). An imported object is a material group, not a
  part: one Eclipse object is the whole cabin, another is both headlights and both tail lights.
  Connected-component separation gives 6,830 and 6,078 shells in 16 s each. Two-thirds to three
  quarters are under 20 triangles and must be merged, not labelled.
- **Naming is worth 99% on one car and 36% on the other** (DISC-019). The Stampede's materials are
  `…Window_Material1`; the Eclipse's are `bw00.001` and `oyctp`. Physics — transmission, alpha,
  roughness — finds glass on both. Names find it on one.
- **Spatial clustering does not group shells into parts.** Union-find over bounding-box overlap
  collapses both cars to a single cluster at any useful padding. Grouping has to be by
  `(label, side, index)` after labelling, using mirror pairing.

**Why the escape hatch is affordable.** The Eclipse's 64% of unlabelled geometry is six material
names. D15 keys its `parts.json` override by material rather than by shell for that reason: an
operator labels tens of things, never thousands.

**D15 also specifies** geometry repair (with symmetry reported and never auto-corrected — real cars
are asymmetric on purpose), hinge inference for doors and lids as part *data* rather than an
armature, per-class destruction treatments, and the audio inventory, which is per class and per
material rather than per vehicle.

## Rationale / Context
The session's method is worth keeping: every design claim here was checked against both shipped cars
before it was written down, and two plausible plans died that way — name-driven labelling and
spatial clustering. A prototype that answers "how far does this get on the hard case" is cheaper
than a tool that answers it after being built.

## Impact
`blender-tool/syndicate_dissect/dissect.py`, all six `assets/parts/*/mesh.glb`, new
`docs/15_vehicle_preparation_pipeline.md`, `docs/00_master_index.md`, `CLAUDE.md`, `ROADMAP.md`.

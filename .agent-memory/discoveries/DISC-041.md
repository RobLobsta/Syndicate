# DISC-041: a model with no common parent lost 30 of its 31 objects before stage 1

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.5

**Status:** active

## Summary
`drop_foreign_roots` kept the single root subtree holding the most **objects**. Both shipped
cars arrive as one parented hierarchy, so that kept the car. A scene of unparented meshes
gives every root a count of one, the tie went to iteration order, and the pipeline deleted all
but one object — then measured the survivor and reported a 2.9 m vehicle with a straight face.

## Details
Found by running the classifier against a tank exported as 31 root-level objects, which is an
ordinary way for a model to be built and is exactly the "drop in a 3d model" case the pipeline
exists to serve. Nothing failed: the run exited 0 and produced a report describing a
2.9 × 6.9 × 0.7 m object that was "lying on its side or on its nose".

Deleting geometry is the one operation in this pipeline with no downstream check. Every later
stage measures what it is given, so whatever survives becomes the truth about the vehicle.

The replacement decides foreignness per root on two conditions that must **both** hold: the
root carries under 0.1% of the scene's triangles, **and** it reaches outside the silhouette of
everything that is not negligible. Neither alone works, and the two failure directions are not
symmetric:

- Weight alone deletes the low-poly wheels of a high-poly body.
- Position alone keeps the Eclipse's stray icospheres, which are *concentric with the car* —
  their bounds overlap it on every axis, and it is that they stand 40 cm proud of the roof
  that gives them away. A first attempt at spatial clustering by bounding-box overlap kept
  them, cost the Stampede both its doors, and moved the Eclipse's mass by 160 kg.

Measured margins: the icospheres are 80 triangles in 283,352 (0.028%); the sparsest root of
the flat tank is 0.65%. The threshold sits more than an order of magnitude from each.

## Rationale / Context
Recorded because the bug is invisible — no error, no warning, a plausible-looking report — and
because the obvious fix is the one that regressed both cars. The next person to touch this
should know that spatial position alone has already been tried and that the icospheres are the
counterexample.

Both cars re-run byte-identical to before the change.

## Impact
- A model exported flat is now classified rather than deleted.
- The stage biases towards keeping: junk that survives widens a bounding box and is reported
  later as a stray shell, whereas geometry deleted here is gone with nothing left to notice.
- `test_dissect_classification.py` carries the Eclipse's real root layout as a fixture.

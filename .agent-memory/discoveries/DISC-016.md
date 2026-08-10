# DISC-016: a skinned mesh's node transform is not its placement

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.5, docs/14_test_environment.md#D14-S5.3, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
`GltfReader` composes each node's own transform and ignores joint weights (DEC-035). For ten of the
Eclipse's 171 meshes that is wrong by up to **2.65 m**: the whole rear-left corner, which the file
places through its joint matrices. Read the way the reader reads it, the car has three wheels and a
fourth floating over the front axle.

## Details

**What the file contains.** Two copies of the car under one scene root, each with its own 79-joint
armature: the author modelled the right-hand side and mirrored it, and the mirror's placement lives
in `skin[1]`'s inverse-bind matrices. Ten objects hang off that second armature rather than off a
joint, so their node transform is a bind pose and not a placement.

**The measurement.** Evaluating the armature moves `Object_287` from `(−0.838, 0.974, +1.244)` to
`(−0.838, 0.360, −1.244)` — the exact mirror of the rear-right wheel. Posed, all four wheels land
within a millimetre of what `SOURCE.md` records; unposed, one is on the wrong axle at the wrong
height.

**Why nothing caught it.** `SOURCE.md` claimed the joint matrices reproduce the node transforms,
"checked, not assumed". The check behind that was `MODEL-008`, which *counts* skinned nodes and never
compares a posed vertex with an unposed one; and the two `--model` captures are three-quarter views
in which the displaced corner is behind the body from one angle and inside it from the other.

One number did record it: `SOURCE.md` gives the height as 1.2365 m, and the AABB as the reader places
it is 1.3338 m. Nobody compared the two.

**What it affects.** `syndicate_dissect` bakes the armature before measuring, so the split assets are
correct. `GltfReader` is unchanged and still wrong for this class of file — but it reads collision
meshes, and the parts it now reads are the dissected ones, which carry no skins. Nothing shipped is
affected today; a future model with a live skin would be.

**The general rule.** A node transform is a placement only for an *unskinned* mesh. For a skinned one
it is `skin.inverseBindMatrices` composed with the joint hierarchy, and a reader wanting one
transform per mesh must evaluate that or refuse the file. Counting skins and concluding they are
inert holds only when every joint matrix is the identity — a thing to test, not to infer.

## Rationale / Context
The second time this pair of models has hidden a placement bug every internal check agreed on — the
first was DEC-036's unit and axis correction — and both times the tell was one document's number
disagreeing with another's rather than a failing test. Recorded because the next person to touch the
reader will read DEC-035's "composes node transforms" and reasonably assume that is the whole story.

## Impact
`game-core` `asset` (`GltfReader`, `GltfCollisionMeshSource`), `test-environment`'s `--model` mode
and its `MODEL-008` check, `art-source/vehicles/eclipse/SOURCE.md`, and
`blender-tool/syndicate_dissect`.

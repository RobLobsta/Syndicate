# SESS-016: Blender runs here after all, and the art is split

**Date:** 2026-08-10
**Category:** session_summaries
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.1, docs/02_technical_architecture.md#D02-S4.1, docs/14_test_environment.md#D14-S5.3

**Status:** active

## Summary
Built `syndicate_dissect`, a Blender tool that cuts a whole-vehicle model into a chassis and four
wheels by geometry rather than by name, and ran it on both shipped cars. The blocker three
consecutive progress entries called "a Blender job nobody has run" is closed, and finding it
uncovered a placement bug in the shipped glTF reader.

## Details

**Blender was available the whole time.** `pip install bpy==4.2.0` gives the exact version D02-R12
pins, headless — the path DEV-002 already established. The assumption that no Blender existed here
had been carried unexamined since SESS-007.

**The classifier.** Connected components plus spatial classification: outboard, low, and *round in
profile*, which is what separates a wheel from a sill, a wishbone and an exhaust. Names are ignored
on purpose (DEC-042) — the Eclipse's two nodes actually called `wheel_lf` are its front-left and
front-**right** wheels, because the author mirrored a subtree. Two thresholds are set by
counterexample: a wing mirror that passed both the old height limit and the old roundness tolerance.
A wheel is measured from its round seeds alone — a caliper sticks past the tyre, and measuring from
everything reported a 1.31 m front tyre instead of 0.71 m.

**The reader bug (DISC-016).** The Eclipse holds two copies of the car, the second supplying the
mirrored left-hand corners through its armature's inverse-bind matrices. `GltfReader` composes node
transforms and ignores joint weights, so it places the whole rear-left corner up to 2.65 m from where
it belongs. `SOURCE.md` claimed the opposite, "checked, not assumed" — the check counted skinned
nodes rather than comparing a posed vertex with an unposed one. Both files corrected.

**Verified by round trip.** `SplitVehiclePartsTest` reads the exported parts back through the game's
own `GltfCollisionMeshSource` and asserts each wheel is a disc of the diameter the art was
independently measured to two sessions ago, centred on its axle. Both cars match to within a tenth of
a millimetre, and the Stampede's wheelbase and track come out exactly right on a classifier written
against the Eclipse and never tuned to it. The pipeline's blocking findings fell from 18 to 6; the
rest are fracture manifests, now a matter of running a tool that already exists.

**What it did not do.** The chassis parts' authored wheel slots still disagree with the measured
axles by 10 cm longitudinally and 36 cm vertically. Moving them changes wheelbase, ride height and
therefore handling, so it needs the calibration test re-run alongside — the next session's first job.

## Rationale / Context
Three progress entries running named this the blocker, each assuming it needed a person in Blender.
That the *assumption* was the blocker, not the work, is the part worth keeping: the same reflex will
apply to the next tool the project decides it cannot run.

## Impact
`blender-tool/syndicate_dissect`, `assets/parts/` (six new `mesh.glb`), `art-source/vehicles/*`,
`game-core` asset tests, `:blender-tool:dissectVehicles`, `ROADMAP.md`.

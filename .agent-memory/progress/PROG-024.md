# PROG-024: a model goes in and a vehicle comes out; all nine preparation stages run

**Date:** 2026-08-13
**Category:** progress
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.8, docs/08_asset_pipeline.md#D08-S4.2

**Status:** active

## Summary
`syndicate_prepare` implements all nine stages of D15-S5.1. Given a directory holding a downloaded
model and nothing else, it corrects the model's frame, cleans its topology, separates and labels it,
refines those labels into roles a human would name, resolves which pieces turn with each wheel, rigs
the panels that open, authors deformation and fracture per destruction class, and writes
`assets/parts/<id>/{mesh.glb,part.json}` plus `assets/vehicles/vehicle_<name>_01/assembly.json`.

## Details

**Scope:** `blender-tool/syndicate_prepare`, and the two Gradle entry points over it. Supersedes PROG-019's rows for the preparation pipeline; its audio half is
unchanged and is tracked by PROG-021 and PROG-022.

**Status of Work:**

| Stage (D15-S5.1) | State | Notes |
|---|---|---|
| 1 Load, pose, correct | done | Unchanged; `syndicate_dissect.load_model` (DISC-016) |
| 2 Repair geometry | done | Now **applied**, not only reported: scale, yaw, placement and topology (DEC-065) |
| 3 Separate into shells | done | Unchanged; 6,830 and 6,078 shells on the shipped cars |
| 4 Label shells | done | Cue ensemble, plus roles (D15-R3a) and the wheel/hub symmetry pass (DEC-066) |
| 5 Group into parts | done | Keyed on `(label, role, side, corner)` rather than `(label, side)` |
| 6 Rig articulated parts | done | Doors, bonnet, boot; sign derived per part; D15-E9 swing check |
| 7 Author destruction | done | Subdivide + morphs for deforming classes; solidify + D09 fracture for glass |
| 8 Re-origin and export | done | One mesh and one manifest per part, plus the assembly |
| 9 Self-verify and report | done | The report says what was produced, not what was planned (D15-R46) |

**Two commands, two consequences.** `:blender-tool:classifyVehicles` runs stages 1 to 6 and writes
only a report — what to run when a threshold changed. `:blender-tool:prepareVehicles` runs all nine
and writes committed content, so running it is a decision to re-cut the art. Neither is in `check`.

**What a prepared vehicle looks like.** Measured on the synthetic pickup the tests carry: thirteen
part types — chassis, two wheel types, two hub types, two doors, a bonnet, a windscreen, two
headlamps, an interior and a badge — 2,106 kg total, a power budget of exactly 84.0 against the
`heavy` class target, a centre of mass on the centreline at 0.87 m, front wheels steering and rear
wheels driven. Doors and bonnet carry an `articulation` block; the windscreen carries shards and no
morph targets; the wheels carry neither.

**What is not done.** Nothing has been run through a Blender host in this environment — there is
none — so the geometry half of stages 7 and 8 is unexercised: joining, re-origining, solidifying,
subdividing, morph generation and the glTF export. Every *decision* those stages make is unit-tested
against synthetic geometry (201 tests, 60 of them new), and the Blender half is deliberately thin
and built from `syndicate_dissect.emit` and `syndicate_fracture.morphs`, both of which are exercised
by the fixture pipeline. The first run on a real car is still the first run.

**Known gaps, in the order they will matter:**

- A part's material is decided by its label alone. A carbon bonnet weighs what a steel one does.
  `parts.json` needs a `materialOverrides` block, and the report already says what each part got.
- `regionLabels` exists and is honoured, but nothing generates one. A model whose doors are welded
  into the body (D15-E3) still needs a human with the model open.
- The shipped cars have not been re-cut. `assets/parts` is still `syndicate_dissect`'s output —
  a chassis and two wheel types each — and re-cutting them is a content decision, not a tool one.

## Rationale / Context
PROG-019 recorded stages 1 to 6 with 7 and 8 as `not_started`, and the report named them as pending
so that a pipeline which quietly stopped early could not be mistaken for one that had finished. It
has now finished, and the difference is the whole point of the subsystem: before this, preparing a
new vehicle meant a human deciding every part, mass, slot and manifest by hand.

## Impact
`syndicate_prepare` gains `cleanup`, `roles`, `hinges`, `destruction`, `manifest` and `exporter`;
`grouping`, `labels`, `shell`, `prepare`, `report` and the CLI all change. `syndicate_dissect.emit`
gains `join_objects`. D15 gains S4.1.1, S5.8, R1a, R3a-c, R24a-b, R25a-d, R27a, R40-R46, four
acceptance criteria and ten test cases; D08-S4.2 gains `articulation` and `yieldImpulseN`.

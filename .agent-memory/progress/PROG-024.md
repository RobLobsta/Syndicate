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

**Run on both shipped cars, on Blender 4.2.** `bpy` is installed in this environment, so the
geometry half is no longer unexercised. Each car takes about 100 s and comes out as:

| | Eclipse | Stampede |
|---|---|---|
| Shells after cleanup | 2,877 | 2,466 |
| Labelled triangles | 88.4% | 99.4% |
| Corners | 4 | 4 |
| Front axle | `±0.8563, 1.4565` (shipped: `±0.8563, 1.4565`) | `±0.854, 1.354` |
| Wheel mass | 36.1 / 39.5 kg (shipped: 37.5) | 32.7 / 35.8 kg |
| Total mass | 1,619 kg (real: 1,500) | 1,784 kg (real: 1,969) |
| Class / budget | medium / 74.0 (shipped: medium / 74.0) | medium / 74.0 |
| Part types | 25 | 25 |
| Hinged panels | 2 doors | 0 — no panels found |
| Chassis damage morphs | 4, at 0.03 m | 4, at 0.03 m |

`tools/verify_prepared.py` opens every exported mesh and checks it against its own manifest:
**50 parts, 2 vehicles, 0 findings.** Every `.glb` carries the node and the morph targets its
`part.json` promises, every slot type accepts the part filling it, no slot is filled twice or
left empty, and the masses and power costs sum to what the assemblies declare.

Four defects surfaced on real art and were fixed: a material *name* seeding a wheel corner
(DISC-037), a mass rule that made a brake hub weigh 214 kg (DEC-067), sliver faces that made
damage morphs impossible (DISC-038), and a duplicate-geometry crash in the collision hull
builder that the dissector had never triggered.

**Known gaps, in the order they will matter:**

- A part's material is decided by its label alone. A carbon bonnet weighs what a steel one does.
  `parts.json` needs a `materialOverrides` block, and the report already says what each part got.
- `regionLabels` exists and is honoured, but nothing generates one. A model whose doors are welded
  into the body (D15-E3) still needs a human with the model open.
- The shipped cars have not been re-cut. `assets/parts` is still `syndicate_dissect`'s output —
  a chassis and two wheel types each — and re-cutting them is a content decision, not a tool one.
- **Glass does not fracture** (DISC-039). Every pane on both cars ships whole, with a note per
  pane saying which of D09's guards refused it.
- The Stampede finds **no panels**, so it has no doors to hinge: its bodywork is one material
  group and its `parts.json` maps that group to `chassis`. Splitting it needs `regionLabels`.
- The footprint estimate runs 8-9% under a real kerb mass, which put the Stampede in `medium`
  where the hand-authored one is `heavy`. `--mass` is the answer and the report says so.

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

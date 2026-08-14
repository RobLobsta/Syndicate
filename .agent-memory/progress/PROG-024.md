# PROG-024: a model goes in and a vehicle comes out; all nine preparation stages run

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.8, docs/09_blender_destruction_tool.md#D09-S5.2.1, docs/08_asset_pipeline.md#D08-S4.2

**Status:** superseded (by PROG-029)

## Summary
`syndicate_prepare` implements all nine stages of D15-S5.1. Given a directory holding a downloaded
model and nothing else, it corrects the model's frame, cleans its topology, separates and labels it,
refines those labels into roles, resolves which pieces turn with each wheel, rigs the panels that
open, authors deformation and fracture per class, and writes the parts and the assembly.

## Details

**Scope:** `blender-tool/syndicate_prepare` and the two Gradle entry points over it. Supersedes
PROG-019's preparation rows; its audio half is tracked by PROG-021 and PROG-022.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| 1 Load, pose, correct (D15-S5.1) | done | Roots by weight and silhouette, not by parent (DISC-041) |
| 2 Repair geometry | done | Applied, not only reported: scale, yaw, placement, topology (DEC-065) |
| 3 Separate into shells | done | 2,879 and 2,460 shells on the shipped cars, after welding |
| 4 Label shells | done | Cue ensemble, roles (D15-R3a), wheel/hub symmetry (DEC-066), doors (DEC-068) |
| 5 Group into parts | done | Keyed on `(label, role, side, corner)` |
| 6 Rig articulated parts | done | Doors, bonnet, boot; sign derived per part; D15-E9 swing check |
| 7 Author destruction | done | Morphs for deforming classes; shell fracture for glass (D09-S5.2.1) |
| 8 Re-origin and export | done | One mesh and one manifest per part, plus the assembly |
| 9 Self-verify and report | done | The report says what was produced, not what was planned (D15-R46) |
| Non-car vehicles | not_started | Prepares cars only; a tank yields one immobile chassis (DISC-042) |
| Re-cut the shipped assets | not_started | `assets/parts` is still the old dissector's output |

**History (append-only):**
- 2026-08-13: stages 1-9 recorded done; glass fracture and Stampede doors recorded as gaps
- 2026-08-14: glass fracture and doors done; DISC-042 and the re-cut added as open rows

**Two commands, two consequences.** `:blender-tool:classifyVehicles` runs stages 1 to 6 and writes
only a report. `:blender-tool:prepareVehicles` runs all nine and writes committed content, so
running it is a decision to re-cut the art. Neither is in `check`.

Both shipped cars run end to end on Blender 4.2 in about 100 s each; the measurements are under
Impact. `tools/verify_prepared.py` holds every exported mesh against its own manifest: **54 parts,
2 vehicles, 0 findings** — nodes, morph targets, slot types, and the mass and power sums.

Seven defects surfaced on real art and were fixed: a material *name* seeding a wheel corner
(DISC-037), a 214 kg brake hub (DEC-067), sliver faces that made morphs impossible (DISC-038), a
duplicate-geometry crash in the hull builder, glass that could not be fractured as a solid
(DISC-039), a coverage check that measured curvature (DISC-043), and a convex hull that was not
convex on a slab of glass (DISC-040).

**Known gaps, in the order they will matter:** a part's material is decided by its label alone, so
a carbon bonnet weighs what a steel one does; `regionLabels` is honoured but nothing generates one;
the footprint mass estimate runs 8-9% under a real kerb weight, which puts the Stampede a class
light unless `--mass` is given.

## Rationale / Context
PROG-019 recorded stages 1 to 6 with 7 and 8 as `not_started`, so a pipeline that quietly stopped
early could not be mistaken for one that had finished. It has now finished, and that is the point
of the subsystem: before it, preparing a vehicle meant a human deciding every part, mass, slot and
manifest by hand.

## Impact
**Run on both shipped cars, on Blender 4.2**, about 100 s each:

| Measure | Eclipse | Stampede |
|---|---|---|
| Shells after cleanup | 2,879 | 2,460 |
| Labelled triangles | 88.4% | 99.4% |
| Front axle | ±0.8563, 1.4565 (authored: identical) | ±0.854, 1.354 |
| Wheel mass | 36.1 / 39.5 kg (authored 37.5) | 32.7 / 35.8 kg |
| Total mass | 1,619 kg (real 1,500) | 1,784 kg (real 1,969) |
| Part types | 25 | 25 |
| Hinged panels | 2 doors | 2 doors |
| Glass panes fractured | 4 of 4, 24 shards each | 5 of 5, 24 shards each |
| Chassis damage morphs | 4, at 0.03 m | 4, at 0.03 m |

`syndicate_prepare` gains `cleanup`, `roles`, `hinges`, `destruction`, `manifest` and `exporter`;
`grouping`, `labels`, `shell`, `prepare`, `report` and the CLI all change. `syndicate_dissect`
gains `emit.join_objects` and a rewritten `drop_foreign_roots`. `syndicate_fracture` gains `shell`,
and `geometry.convex_hull` and `hulls.build_hull` both change. D15 gains S4.1.1, S5.8, R1a, R3a-c,
R6a, R24a-b, R25a-d, R27a, R40-R46; D09 gains S5.2.1 and R11a-c; D08-S4.2 gains `articulation`.

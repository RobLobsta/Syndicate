# PROG-019: a car is segmented by geometry, and the whole game makes a noise

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/08_asset_pipeline.md#D08-S4.3

**Status:** superseded (by PROG-024)

## Summary
`syndicate_prepare` implements D15-S5.1 stages 1 to 6 and reproduces D15-R16's measurements exactly on both shipped cars. `assets/audio/` holds 52 synthesised sounds covering every event family D15-S8 names. The material table gains an audio axis and the part schema gains a destruction class.

## Details

**Scope:** `blender-tool`, `asset-pipeline` `audio`, `assets/`, `shared-models`.

**Status of Work:** (the art path; PROG-008 keeps the fracture half)

| Area | State | Notes |
|---|---|---|
| Shell separation (D15-S5.2) | done | 6,830 and 6,078 shells, matching D15-R16 exactly |
| Cue ensemble (D15-S4.2) | done | Four families, weighted votes, unclassified as a first-class outcome |
| `parts.json` overrides (D15-S4.3) | done | Keyed by material. Nine names take the Eclipse from 25% to 82%; four take the Stampede to 99.4% |
| Geometry repair (D15-S5.5) | done | Reported with before and after, never applied. Symmetry reported and never repaired |
| Segmentation report (D15-S4.4) | done | Ranks the overrides worth writing, which is what makes the design practical |
| Articulation rigging (D15-S5.6) | not_started | Reported as a pending stage rather than omitted |
| Per-class destruction authoring (D15-S5.7) | not_started | The class is decided and carried on the part; nothing acts on it yet |
| Export (D15-S5.1 stage 8) | not_started | The tool reports; `syndicate_dissect` is still what writes part meshes |
| Sound bank (D15-S8) | done | 52 files, every event family, synthesised and reproducible |
| Runtime audio playback | not_started | Slot 25 is unwritten; the bank and its manifest exist for it |

**Preparation.** Load and correct, repair, separate, label, group, report — 6,830 shells from 283,192 triangles on the Eclipse and 6,078 from 234,057 on the Stampede, seventeen seconds each. Everything that decides is pure Python over a `Shell` record with no Blender import, so the cue ensemble is unit-tested on synthetic geometry.

D15-S1's claim is now demonstrated rather than asserted. The Eclipse labels 25% of its triangles unaided; the report ranks the materials responsible and nine names in `parts.json` take it to 82%. The Stampede reaches 99.4% from four — the same figure DISC-019 measured independently, arrived at from the other direction.

**Stages 6 to 8 are not implemented**: rigging, per-class destruction authoring, and export. The report names them as pending rather than omitting them.

**Audio.** 52 files, all synthesised, so the bank carries the repository's licence and regenerates byte-identically from a seed. Impacts are modal rather than filtered noise; measured, metal rings 1.2-4.3 s at 217-742 Hz and rubber thuds 0.08-0.29 s at 109-354. Engine loops are keyed on configuration rather than vehicle class (DEC-047), so the shipped V6 and V8 sound like different cars.

**Materials.** One `materialId` answers density, resistance and acoustic family; `destructionClass` lives on the part, because it follows from what a part is (DEC-045).

## Rationale / Context
PROG-008 recorded the destruction toolchain as working and untouched. It still is — this entry is the other half of the art path, the stage that produces the parts D09 then fractures, and the two are separate subsystems that happen to share a Blender host.

## Impact
`blender-tool`'s new `syndicate_prepare` package, `asset-pipeline`'s `audio` package, `assets/audio/`, `assets/materials/materials.json`, and a `parts.json` beside each shipped model.

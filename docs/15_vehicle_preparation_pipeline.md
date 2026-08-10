<!-- D15-S0 --># 15 — Vehicle Preparation Pipeline

**Document ID:** D15
**Owns:** The headless Blender pipeline that turns one downloaded vehicle model into game-ready parts: segmentation into labelled components, the label taxonomy, the cue ensemble and its confidence model, the per-model override file, geometry repair, articulation rigging, per-class destruction authoring, and the audio inventory a finished vehicle needs.

---

<!-- D15-S1 -->## 1. Purpose

`docs/09_blender_destruction_tool.md` specifies what to do with **one part** once you have it: fracture it, generate its damage shape keys, weigh its shards. This document specifies how you **get** the parts — how a single downloaded car mesh, authored by a stranger in an unknown package with unknown conventions, becomes a labelled set of components the rest of the pipeline can consume.

The distinction matters because the two problems have opposite characters. Fracturing a part is a well-posed geometry problem with a right answer. Segmenting a car is an **inference** problem: the information needed to name a door is not reliably present in the file, and how much of it *is* present varies enormously from model to model. A pipeline that pretends otherwise will confidently mislabel, and a mislabelled part is worse than an unlabelled one — it propagates into mass, handling and damage without ever failing a check.

The design principle that follows, and the one this whole document is arranged around:

> **Infer what geometry can prove. Read what the file happens to say. Ask a human for the rest, once per material rather than once per part.**

Requirements are numbered `R1..Rn`, cited as `D15-R14`.

---

<!-- D15-S2 -->## 2. Scope

<!-- D15-S2.1 -->### 2.1 In Scope

- Segmentation of a whole-vehicle mesh into connected shells and their grouping into parts.
- The part label taxonomy (D15-S4.1) and the slot roles each label maps to.
- The cue ensemble: geometric, material-physical, material-nominal, and structural cues, with confidence.
- The per-model override file, `parts.json`, and its precedence over inference.
- Geometry repair: orientation, scale, placement, symmetry, and degenerate topology.
- Articulation rigging for doors, bonnet, boot and other hinged parts.
- Per-class destruction authoring: what deformation and fracture treatment each label receives.
- The audio inventory a vehicle needs, and where those assets come from.

<!-- D15-S2.2 -->### 2.2 Non-Goals

- **NG1.** Fracturing, shape-key generation and mass assignment — `docs/09_blender_destruction_tool.md#D09-S1`. This document decides *which* treatment a part gets; that one performs it.
- **NG2.** Runtime deformation and destruction behaviour — `docs/07_damage_destruction_model.md#D07-S1`.
- **NG3.** The part and assembly manifest schemas — `docs/08_asset_pipeline.md#D08-S4.2`. This pipeline writes files in those formats; it does not define them.
- **NG4.** Authoring original art. Every input is a model somebody else made.
- **NG5.** Sound *design*. D15-S8 specifies which sounds a vehicle needs and where they come from; how they are mixed is a later concern.
- **NG6.** Machine-learned segmentation. Every cue here is hand-written and explainable, because a wrong label must be traceable to a rule somebody can read and change.

---

<!-- D15-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S6.4` | Units, axes, and the game coordinate frame every measurement here is expressed in |
| `docs/08_asset_pipeline.md#D08-S4.1` | Source art conventions and the `import.json` correction |
| `docs/08_asset_pipeline.md#D08-S4.2` | The `part.json` schema this pipeline emits |
| `docs/09_blender_destruction_tool.md#D09-S4.1` | The tool contract: headless, JSON on stdout, exit codes |
| `docs/05_vehicle_part_system.md#D05-S4.3` | Slot types, and what a part may hang from |
| `docs/07_damage_destruction_model.md#D07-S5.5` | Damage states and morph targets the authoring stage produces |
| `docs/14_test_environment.md#D14-S5.11` | Visual verification of a prepared vehicle |

---

<!-- D15-S4 -->## 4. Data Contracts

<!-- D15-S4.1 -->### 4.1 Part Label Taxonomy

**R1.** Every shell is assigned exactly one label from this closed set. The set is closed so that a new model cannot silently introduce a category nothing downstream handles.

| Label | What it is | Slot role | Detach | Destruction class |
|---|---|---|---|---|
| `chassis` | The structural body — everything not separately labelled | `ROOT` | never (D05-R26) | `structural` |
| `wheel` | Tyre, rim, hub and disc: what rotates about the axle | `WHEEL` | yes | `rigid` |
| `hub` | Caliper, upright, dust shield: unsprung, does **not** rotate | `HUB` | with its wheel | `rigid` |
| `panel` | Bonnet, boot lid, doors, wings, bumpers | `PANEL` | yes | `sheet_metal` |
| `glass` | Windscreen, side and rear glass, lamp lenses | `GLASS` | shatters | `glass` |
| `mirror` | Wing mirrors | `ACCESSORY` | yes | `rigid` |
| `light` | Lamp housings and reflectors | `ACCESSORY` | yes | `rigid` |
| `decal` | Badges, plates, scripts, liveries | `DECAL` | with its host | `none` |
| `grille` | Mesh inserts, vents, ducts | `ACCESSORY` | yes | `rigid` |
| `interior` | Seats, dash, trim — visible through glass, never hit | none | never | `none` |
| `drivetrain` | Engine, exhaust, radiator | `INTERNAL` | yes | `structural` |
| `unclassified` | Everything the ensemble could not decide | `chassis` | never | `sheet_metal` |

**R2.** `unclassified` is a **first-class outcome, not a failure**. It merges into the chassis, which is always a correct-if-coarse answer, and it is reported by count and by triangle share so the operator can see how much of the car the pipeline could not name.

**R3.** A label is not a part. Parts are formed by grouping shells that share `(label, side, index)` — see D15-S5.3.

<!-- D15-S4.2 -->### 4.2 The Cue Ensemble

**R4.** Four independent cue families, each producing `(label, confidence)` votes in `[0,1]`. They are combined by summing weighted votes per label; the winner must exceed `LABEL_MIN_CONFIDENCE` or the shell is `unclassified`.

| Cue | Basis | Generalises? | Example |
|---|---|---|---|
| **C1 Geometric** | Size, position, aspect, rotational symmetry, planarity | **Always** — it is measurement | A wheel is round in side view and outboard and low |
| **C2 Material-physical** | `alphaMode`, base-colour alpha, `KHR_materials_transmission`, roughness, emissive | **Always** — it is the file's own render intent | Transmissive or blended-and-glossy ⇒ `glass` |
| **C3 Material-nominal** | Tokens in material and node names | **Opportunistically** | `…Window_Material1` ⇒ `glass` |
| **C4 Structural** | Mirror pairing about `x = 0`, containment, adjacency | **Always** | Two identical shells at ±x are one part in two instances |

**R5.** C3 matches **whole tokens**, never substrings. Identifiers are split on non-alphabetic characters and at camel-case boundaries before matching. Substring matching is not a style preference: `rim` occurs inside `p·rim·ary`, which labels a car's paint a wheel.

**R6.** C2 outranks C3 when they disagree. A file's declared transparency is what it will actually render as; its material *name* is a comment.

**R7.** Cue weights and thresholds are constants in the tool, not per-model tuning. A threshold that has to move for a new model is a bug in the threshold.

<!-- D15-S4.3 -->### 4.3 Per-Model Override: `parts.json`

**R8.** `art-source/vehicles/<name>/parts.json` sits beside `import.json` and overrides inference. It is optional; a model that needs none is a model the ensemble handled.

```json
{
  "schemaVersion": "1.0.0",
  "materialLabels": {
    "bw00.001": "panel",
    "bwfmp": "chassis",
    "bwfgd": "chassis"
  },
  "regionLabels": [
    { "label": "panel", "part": "door_l",
      "boundsMin": { "x": -1.1, "y": 0.3, "z": -0.5 },
      "boundsMax": { "x": -0.6, "y": 1.2, "z": 0.9 } }
  ],
  "hinges": [
    { "part": "door_l", "axis": "y", "pivot": { "x": -0.78, "y": 0.75, "z": 0.95 },
      "openDeg": 62.0 }
  ]
}
```

**R9.** `materialLabels` is keyed by **material**, not by object or shell. This is the single decision that makes manual correction affordable: the Eclipse has 6,830 shells and 60 materials, and the 64% of its geometry the ensemble cannot name is covered by **six** material names. An operator labels tens of things, never thousands.

**R10.** `regionLabels` is the escape hatch for the case a material cannot express — one material covering both a door and the wing beside it. It is a box in game space, applied after material labels and before geometric cues.

**R11.** Precedence, highest first: `regionLabels`, `materialLabels`, C2, C1, C4, C3. An override is never outvoted.

<!-- D15-S4.4 -->### 4.4 Segmentation Report

**R12.** The tool emits one JSON document on stdout (D09-R2), including per-label shell counts and triangle shares, the unclassified share, every cue disagreement, and every repair applied. The report is the deliverable an operator reads to decide whether a model needs a `parts.json`.

**R13.** The report carries `confidence.labelledTriangleFraction`. Below `REPORT_MIN_LABELLED_FRACTION` the tool exits non-zero in strict mode: a car that is 64% unnamed has not been prepared, and saying so loudly is the difference between a pipeline and a plausible-looking one.

---

<!-- D15-S5 -->## 5. Logic & Algorithms

<!-- D15-S5.1 -->### 5.1 Stage Order

```
1. Load, pose, and correct        (D09-S5.1 conventions; DISC-016 for why posing is first)
2. Repair geometry                 (D15-S5.5)
3. Separate into connected shells  (D15-S5.2)
4. Label shells                    (D15-S4.2 ensemble, D15-S4.3 overrides)
5. Group shells into parts         (D15-S5.3)
6. Rig articulated parts           (D15-S5.6)
7. Author destruction per class    (D15-S5.7)
8. Re-origin, re-parent, export    (D08-S4.5)
9. Self-verify and report          (D15-S4.4)
```

**R14.** Repair precedes separation. A model whose scale or orientation is wrong produces measurements in the wrong units, and every geometric cue is a measurement.

<!-- D15-S5.2 -->### 5.2 Shell Separation

**R15.** Separation is by connected component (`separate(type='LOOSE')`) over **every** object, not only over objects suspected of straddling a boundary. An object in a downloaded file is a *material group*, not a part: on both shipped cars one object is the entire painted body, and on one of them a single object is both headlights and both tail lights.

**R16.** Cost is bounded and known: 6,830 shells from 283k triangles in 16 s, and 6,078 from 234k in 16 s, on both shipped cars. Separation is not the expensive stage and must not be skipped as an optimisation.

**R17.** Shells below `MIN_SHELL_TRIANGLES` are **merged into their nearest labelled neighbour** rather than labelled independently. Two-thirds to three-quarters of the shells on a real car are bolts, screws and single grille strands; treating each as a part produces thousands of meaningless parts and destroys the triangle-share statistics the report depends on.

<!-- D15-S5.3 -->### 5.3 Grouping Shells into Parts

**R18.** Shells are grouped by `(label, side, index)`, **not** by spatial clustering. Bounding-box clustering was measured and rejected: with any padding sufficient to join a door skin to its inner card, it joins the entire car into one cluster, because every panel's box overlaps every neighbour's.

**R19.** `side` is `l`, `r` or `c`, from the sign of the group's centroid x against `SIDE_DEADBAND_M`.

**R20.** Mirror pairing (C4) is authoritative for `side`. A shell whose reflection about `x = 0` matches another shell to within `MIRROR_TOLERANCE_M` is one instance of a two-instance part, and both take the same part type with opposite `side` — which is how the shipped assemblies already express two front wheels sharing one part type.

<!-- D15-S5.4 -->### 5.4 Rotational Symmetry: What Turns and What Does Not

**R21.** A part attached to a rotating wheel rotates **only if it is rotationally symmetric about the axle**. Angular coverage is measured over vertices, in sectors of `360 / ROTATION_SECTORS` degrees, in the plane normal to the axle. Coverage at or above `ROTATION_SYMMETRY_MIN_DEG` ⇒ `wheel`; below ⇒ `hub`.

**R22.** The test is applied to **seed** shells as well as captured ones. A caliper bolt is square in silhouette and therefore passes the roundness test that seeds a wheel; it fails this one.

**R23.** Measure coverage from vertices, never from a bounding box. A five-spoke rim's box corners land in four sectors, so a box-based measure cannot distinguish a spoked wheel from a caliper.

> Measured on both shipped cars: every rotating piece covers 360°; calipers cover 90–150°. The gap is wide enough that the threshold is not delicate.

<!-- D15-S5.5 -->### 5.5 Geometry Repair

**R24.** Repairs are applied in this order, each reported, none silent:

| Check | Detection | Repair |
|---|---|---|
| **Scale** | Wheelbase or overall length outside plausible vehicle bounds | Uniform scale to bring length into range; recorded in `import.json` |
| **Orientation** | Long axis not `z`; up axis not `y`; nose not `+z` | Rotate to the game frame (D00-R16) |
| **Nose direction** | Windscreen rake, cabin bias, and wheel-size asymmetry vote | 180° yaw if the vote is against `+z` |
| **Ground contact** | Lowest wheel contact not at `y = 0` | Translate so it is |
| **Lateral centring** | Body centroid off `x = 0` by more than `CENTRING_TOLERANCE_M` | Translate to centre |
| **Symmetry** | A shell with no mirror twin within `MIRROR_TOLERANCE_M` | **Report only** — never auto-mirror |
| **Degenerate topology** | Zero-area faces, doubled vertices, non-manifold edges | Merge by distance, delete degenerates |
| **Detached fragments** | A shell far outside the body's hull | Report; drop only beyond `STRAY_SHELL_M` |

**R25.** Broken symmetry is reported and never repaired automatically. Real cars are asymmetric on purpose — one exhaust, a fuel filler on one side, left-hand drive — and a pipeline that mirrors those away damages correct models to flatter incorrect ones.

**R26.** Every repair is recorded in the report as a before/after measurement. A repair nobody can see is indistinguishable from a bug.

<!-- D15-S5.6 -->### 5.6 Articulation Rigging

**R27.** An articulated part is a part with a hinge: an axis, a pivot in chassis-local space, and an open angle. Doors, bonnet and boot are the expected cases.

**R28.** Hinge inference, in order of reliability:

1. **Declared** — `parts.json.hinges`. Always wins.
2. **Panel-edge inference** — a door hinges about the vertical edge nearest the front of the car; a bonnet about its rear-most transverse edge; a boot about its forward-most transverse edge. The axis is the panel's longest edge in that direction, the pivot its midpoint.
3. **None** — the part is rigid and detaches without opening.

**R29.** A hinge is exported as **data on the part**, not as a Blender armature. The game already composes parts down a slot chain (D04-S4.3.1), so an opening door is a slot whose local rotation animates. An armature would add a second, redundant transform hierarchy that the runtime does not read.

**R30.** Articulation and detachment are independent. A door may open, then later break off; the hinge angle is cosmetic state and the attachment is authoritative (G6).

<!-- D15-S5.7 -->### 5.7 Destruction Authoring by Class

**R31.** Each label maps to a destruction class, and each class to a treatment. This is where the pipeline decides *how a part fails* before anything hits it.

| Class | Treatment | Rationale |
|---|---|---|
| `sheet_metal` | Subdivide to `SHEET_METAL_TARGET_EDGE_M`; fine lattice cage; damage shape keys at 25/50/75/100% | A panel crumples locally and keeps its area. Needs vertex density where the dent is, or the dent is a facet |
| `glass` | No shape keys. Cell-fracture into shards at authoring time; runtime state is intact or shattered | Glass does not dent. A deformed windscreen looks like a bug; a shattered one reads instantly |
| `structural` | Coarse lattice cage; high-stiffness treatment with a plasticity yield threshold; no fine subdivision | A chassis or engine block buckles and shears globally. Fine subdivision makes it squish like a sponge, which is the failure mode to avoid |
| `rigid` | No deformation. Detach whole, fracture only if authored | A caliper, a mirror, a lamp housing either survives or leaves |
| `none` | Untouched | Decals ride their host; interiors are never hit |

**R32.** A class's parameters are per-class constants, not per-part authoring. A part that needs different numbers is evidence the taxonomy is missing a class, not that the part needs hand-tuning.

**R33.** The `structural` yield threshold is expressed as an impulse in newton-seconds, comparable with `breakImpulseN` (D08-R5), so "the frame buckles before the mounts shear" is a statement about two numbers in the same unit.

---

<!-- D15-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D15-1.** Running the pipeline on a model with no `parts.json` produces a report, a labelled part set, and a non-zero unclassified share if the model warrants one — never a silent guess.
- [ ] **AC-D15-2.** No part labelled `wheel` contains geometry whose angular coverage about the axle is below `ROTATION_SYMMETRY_MIN_DEG`.
- [ ] **AC-D15-3.** A wheel's reported diameter, width and axle position are unchanged by the presence or absence of hub furniture.
- [ ] **AC-D15-4.** Every triangle of the source model appears in exactly one output part. Nothing is dropped and nothing is duplicated.
- [ ] **AC-D15-5.** Adding a `materialLabels` entry changes the labelling of every shell using that material and of no other shell.
- [ ] **AC-D15-6.** Every geometry repair is reported with a before and after measurement.
- [ ] **AC-D15-7.** Symmetry violations are reported and never repaired.
- [ ] **AC-D15-8.** Two runs on the same input produce byte-identical output (D09-R30).
- [ ] **AC-D15-9.** A part with an inferred hinge opens about that hinge without its geometry intersecting the chassis at the open angle.
- [ ] **AC-D15-10.** Each label's destruction class matches D15-S5.7, and no `glass` part carries damage shape keys.

---

<!-- D15-S7 -->## 7. Edge Cases & Failure Modes

| ID | Case | Behaviour |
|---|---|---|
| E1 | Model has meaningless material names (`bw00`, `oyctp`) | C3 contributes nothing; C1/C2/C4 carry the run; unclassified share rises and is reported |
| E2 | Model has semantic names for everything | C3 dominates; the run needs no `parts.json` |
| E3 | Doors are not separate shells — welded into the body | No `panel` found; reported as "no door candidates"; `regionLabels` is the remedy |
| E4 | Six-wheeled or three-wheeled vehicle | Corner assignment is by count of wheel-shaped groups, not by an assumption of four |
| E5 | Two cars in one file (a mirrored duplicate) | Largest root subtree wins, as today; the second is reported as dropped |
| E6 | A mirror or aerial passes the wheel shape test | Rejected on height and roundness; the thresholds sit where a measured wing mirror fails |
| E7 | Shell count exceeds `MAX_SHELLS` | Abort with a machine-readable error rather than run for an unbounded time |
| E8 | `parts.json` names a material the model does not have | Error, not warning: it means the override was written against a different file |
| E9 | Hinge inference gives an axis that intersects the body | Fall back to rigid, and report; a door that opens through its own sill is worse than one that does not open |
| E10 | Model is already in game units and axes | Repair stage is a no-op and reports zero corrections |

---

<!-- D15-S8 -->## 8. Audio Inventory

**R34.** A prepared vehicle is not finished when it looks right. Sound is the half of destruction feedback the visual pipeline cannot deliver, and it is cheap to specify now and expensive to retrofit once a hundred parts exist.

**R35.** The inventory a vehicle needs, keyed to events that already exist in the simulation:

| Event | Source | Notes |
|---|---|---|
| Engine loop | Per vehicle class, pitched by RPM | One loop per class, not per vehicle; `VehicleProfile` already carries the RPM band |
| Tyre roll / skid | Per surface, blended by slip | The ray-cast wheel already computes slip |
| Collision impact | Per material pair, by impulse | `CollisionEventSystem` (slot 11) already classifies both sides |
| Part detach | Per destruction class | `sheet_metal` tears, `glass` shatters, `rigid` clangs |
| Glass shatter | One-shot per `glass` part | The one sound a player will notice missing |
| Debris settle | Per material, by mass | Driven by the existing debris lifetime |
| Weapon fire / impact | Per weapon family (D01-R8) | Eight families, eight pairs |

**R36.** Sounds are **per class and per material**, never per vehicle. Seven events over five destruction classes is a set of tens; per-vehicle sound would be a set of hundreds and would gate every new car on an audio pass.

**R37.** Sourcing, in order of preference: (a) permissively licensed libraries with attribution recorded beside the asset exactly as `license.txt` records model provenance today; (b) procedural synthesis for engine and tyre loops, which are parametric by nature and where a synthesiser removes a per-class asset; (c) commissioned or recorded audio. Generative audio models are viable for one-shots and are **not** for loops, where seam artefacts are audible.

**R38.** Every audio asset records its licence beside it, under the same rule as art. The two shipped models are CC-BY-NC-SA and that constraint is already live; audio must not add a second, differently-encumbered set of terms nobody tracked.

---

<!-- D15-S9 -->## 9. Test Cases

| ID | Test | Expected |
|---|---|---|
| T-D15-1 | Run on both shipped cars | Four wheels each, axles within 1 mm of the recorded art |
| T-D15-2 | Assert no caliper material in any exported wheel | Passes on both cars |
| T-D15-3 | Sum triangles across all output parts | Equals the source model's triangle count |
| T-D15-4 | Add a `materialLabels` entry for one material | Exactly the shells using it change label |
| T-D15-5 | Feed a model scaled ×100 | Repair reports the scale correction; measurements match the corrected run |
| T-D15-6 | Feed a model yawed 180° | Nose-direction repair fires; wheelbase and track unchanged |
| T-D15-7 | Angular coverage of a synthetic annulus vs a synthetic wedge | 360° and ≤ 90° respectively |
| T-D15-8 | Two runs, same seed | Byte-identical `.glb` output |
| T-D15-9 | A model with one deliberately asymmetric part | Reported, not repaired |
| T-D15-10 | Open every inferred hinge to its authored angle | No part intersects the chassis |

---

<!-- D15-S10 -->## 10. Cross-References

| Topic | Document |
|---|---|
| Fracturing, shape keys, mass, hulls | `docs/09_blender_destruction_tool.md#D09-S5.1` |
| Part and assembly manifests | `docs/08_asset_pipeline.md#D08-S4.2` |
| Slot graph and part attachment | `docs/05_vehicle_part_system.md#D05-S4.3` |
| Damage states and morph targets | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Units, axes, global invariants | `docs/00_master_index.md#D00-S6.4` |
| Visual verification of a prepared vehicle | `docs/14_test_environment.md#D14-S5.11` |

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

| Label | What it is | Category (D05-S4.2) | Slot type (D05-S4.3) | Detach | Destruction class |
|---|---|---|---|---|---|
| `chassis` | The structural body — everything not separately labelled | `CHASSIS` | `ROOT` | never (D05-R26) | `structural` |
| `wheel` | Tyre, rim, hub and disc: what rotates about the axle | `WHEEL` | `WHEEL` | yes | `rigid` |
| `hub` | Caliper, upright, dust shield: unsprung, does **not** rotate | `UTILITY` | `HARDPOINT` | with its wheel | `rigid` |
| `panel` | Bonnet, boot lid, doors, wings, bumpers | `PANEL` | `PANEL` | yes | `sheet_metal` |
| `glass` | Windscreen, side and rear glass, lamp lenses | `DECORATIVE` | `ACCESSORY` | shatters | `glass` |
| `mirror` | Wing mirrors | `DECORATIVE` | `ACCESSORY` | yes | `rigid` |
| `light` | Lamp housings and reflectors | `DECORATIVE` | `ACCESSORY` | yes | `rigid` |
| `decal` | Badges, plates, scripts, liveries | `DECORATIVE` | `ACCESSORY` | with its host | `none` |
| `grille` | Mesh inserts, vents, ducts | `DECORATIVE` | `ACCESSORY` | yes | `rigid` |
| `interior` | Seats, dash, trim — visible through glass, never hit | `DECORATIVE` | `ACCESSORY` | never | `none` |
| `drivetrain` | Engine, exhaust, radiator | `UTILITY` | `HARDPOINT` | yes | `structural` |
| `weapon` | A gun the model came with: a tank's barrel, a mounted cannon | `WEAPON` | `TURRET_MOUNT` | yes | `rigid` |
| `unclassified` | Everything the ensemble could not decide | `CHASSIS` | `ROOT` | never | `sheet_metal` |

**R1a.** The two middle columns are the **actual** members of `PartCategory` (D05-S4.2) and `SlotType` (D05-S4.3), and a part.json carrying anything else does not load. An earlier version of this table named `HUB`, `PANEL`, `GLASS`, `DECAL` and `INTERNAL` as slot roles; none of those is a `SlotType`, and the pipeline that emits assets had to invent the mapping or emit files the loader rejects. The pairing is constrained: `SlotType.acceptsCategory` must accept each row's category in each row's slot type, which is why a door is a `PANEL` part in a `PANEL` slot. The category was called `ARMOR` when this table was written; it is called `PANEL` now precisely because a door, a bonnet and a bumper are bodywork rather than fitted plating, and the two should not share a word (DEC-073).

**R2.** `unclassified` is a **first-class outcome, not a failure**. It merges into the chassis, which is always a correct-if-coarse answer, and it is reported by count and by triangle share so the operator can see how much of the car the pipeline could not name.

**R3.** A label is not a part. Parts are formed by grouping shells that share `(label, role, side, index)` — see D15-S5.3.

**R3b.** The `weapon` label is for a **built-in** weapon: geometry the source model came with, which is the only kind this pipeline can produce. A *modular* weapon — one a player fits to any vehicle — is authored content in the shared library (`D08-R14b`) and never comes out of a car's art. The two differ in exactly one further way: a built-in weapon's `weapon` block is **derived** from its own geometry (R48), where a modular one's is authored.

<!-- D15-S4.1.1 -->#### 4.1.1 Roles

**R3a.** A label may carry a **role**, which is the refinement that separates two things the taxonomy calls by one name. A bonnet and a door are both `panel` and they are not interchangeable: one hinges at its rear edge and one at its front, and the slot each hangs from has to say which it is. Roles are a closed set per label, and a shell whose role cannot be decided keeps its label and takes none — the same first-class "could not tell" R2 makes of `unclassified`.

| Label | Roles |
|---|---|
| `panel` | `bonnet`, `boot`, `door`, `fender`, `quarter`, `bumper`, `sill`, `roof` |
| `glass` | `windscreen`, `rear_window`, `side_window`, `lens` |
| `light` | `head`, `tail` |
| `grille` | `head`, `tail` |

**R3b.** A role is decided by the **plane a panel lies in** — its thinnest axis — and then by where on the body it sits. That is the one measurement that separates the three families of panel on every vehicle ever built: a bonnet is thin vertically, a door is thin laterally, a bumper is thin longitudinally. Position then chooses the member of the family, in fractions of the vehicle's own dimensions so that a hatchback and a pickup need the same numbers (R7).

<!-- D15-R3d -->**R3d.** Some roles are **singular**: `bonnet`, `boot`, `roof`, `windscreen` and `rear_window` exist once per vehicle and span the centreline, so their shells take side `c` whatever their centroid says. Without this a windscreen is exported three times — a left one, a right one and a middle one — because any shell more than `SIDE_DEADBAND_M` off centre takes a side. Both shipped cars did exactly that.

**R3c.** A role is a refinement, never a reclassification. It cannot turn a `panel` into a `glass`. Roles feed three things and nothing else: the part's exported name, the slot id it hangs from, and whether stage 6 looks for a hinge on it.

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

<!-- D15-R6a -->**R6a.** **C1 votes `panel` for a door-shaped shell, and outweighs the paint.** A door is the one panel a vehicle has that a purely positional cue cannot see: it is painted the same colour as the wing beside it, named after that paint, and sits in the middle of a body whose middle is otherwise chassis. The Stampede's doors are clean mirrored shells and were labelled `chassis`, because C3 read `paint` and voted for the body at 0.595 while nothing voted for a door at all.

The shape is specific enough to name. A door is thin in **x** and in x alone — no more than 0.22 of the body's width — is not round, is at least 0.18 of the body long, sits outboard of 0.72 of the half-width, spans the middle of the length (0.20 to 0.78 from the nose), and its centroid is between 0.25 and 0.85 of the body's height, which is above the sill and below the roof. C1 votes `panel` at 0.75 on that, which beats a nominal paint vote and loses to any override (R8).

This is the geometric cue doing what R7 requires of it: the numbers are fractions of the vehicle's own dimensions, taken from measurements of both shipped cars, and the cases that must *not* match — an interior door card, a wheel, a sill — are each held out by a different one of the six conditions rather than by the margin on one.

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

<!-- D15-S4.5 -->### 4.5 The House Style Table: `style.json`

**R47.** Every vehicle arrives as art somebody else made, and no two artists agree about what a car looks like. `assets/materials/style.json` is the single table that makes the roster read as one artist's work: for each **surface role** — body paint, trim, chrome, glazing, rubber, lamps, grilles, interior, underbody — it fixes a colour band (a hue shift, a saturation ceiling, a value floor and ceiling), a metallic and roughness target, and how far the result is then dragged toward grime.

**R47a.** The pass is applied **per material** and it **must not touch labelling evidence**. A material's name, its alpha mode, its base alpha, its transmission, its backface culling and its emission *strength* are all read by the cue ensemble (D15-S4.2 C2 and C3), they outrank every geometric measurement, and they are the only reason glass is found at all on a model whose materials are called `bw00.001` (DISC-019). The style pass writes base colour, metallic, roughness and emission *colour*, and nothing else.

**R47b.** Colour is **clamped, not assigned**. A red car stays a red car: the hue is gameplay — a faction colour, a livery — and the saturation is what makes it a toy. Only a surface whose real-world hue is not a free choice, such as rust or a sodium lamp, carries a `hueShiftDeg`.

**R47c.** How hard the pass pushes is **measured**, not declared. Two numbers over the scene's materials, each weighted by the triangles it covers: mean colour saturation, and the fraction of geometry whose material carries a base-colour texture. A source that is saturated *and* textureless is **stylised** and is reskinned — its base-colour textures are disconnected and the house colour replaces them outright, because those textures are flat colour and are what makes the model read as a cartoon. Anything else is **realistic** and is only pulled part of the way toward the palette, keeping every texture it came with. Weighting by triangles is what stops one bright badge on a photoreal car from costing it its textures.

**R47d.** Behind a texture, a base-colour socket is a **factor** and not a colour, and the two must not be confused. A surface band's value ceiling applied as a multiplier renders the whole vehicle at a fraction of its brightness — trim's ceiling is 0.20, and a fifth of a diffuse map is a silhouette. A textured material therefore receives a *tint*: what the style does to a mid-grey, rescaled so it never darkens past a floor.

**R47e.** Everything the pass decides is a function of the table, the source material and the run's seed (G3). The per-material grime jitter is **hashed from the material's name** rather than drawn from a stream, so adding a badge to a model cannot change the colour of its doors.

<!-- D15-S4.6 -->### 4.6 The Researched Profile: `profile.json`

**R50.** A model may carry a `profile.json` beside it holding the figures nobody can measure off a mesh: the published kerb mass, the chassis's engine force, power and brake force, its aerodynamics, and its wheels' grip, springs and steering. When present, those figures replace the ones the pipeline derives; when absent, the derivation stands and "drop in a model" remains true for the next car nobody has looked up.

**R50a.** The profile is a **copy**, and `dev.syndicate.core.vehicle.VehicleProfiles` is the authority. The Java record carries the derivations and the sources; the JSON exists because the Blender tool cannot read it. `VehicleProfileContentTest` asserts the shipped content against that record field by field, so a figure that drifts fails the build naming the figure and both values.

**R50b.** Steering is authored on the **front** wheel type only, because D05-S5.6 phase 3 filters on `isSteering` and a lock on all four would be counted twice on a vehicle whose rear wheels ever steered. Grip and springs go on every wheel, because every wheel has them.

<!-- D15-S4.4 -->### 4.4 Segmentation Report

**R12.** The tool emits one JSON document on stdout (D09-R2), including per-label shell counts and triangle shares, the unclassified share, every cue disagreement, and every repair applied. The report is the deliverable an operator reads to decide whether a model needs a `parts.json`.

**R13.** The report carries `confidence.labelledTriangleFraction`. Below `REPORT_MIN_LABELLED_FRACTION` the tool exits non-zero in strict mode: a car that is 64% unnamed has not been prepared, and saying so loudly is the difference between a pipeline and a plausible-looking one.

---

<!-- D15-S5 -->## 5. Logic & Algorithms

<!-- D15-S5.1 -->### 5.1 Stage Order

```
1. Load, pose, and correct        (D09-S5.1 conventions; DISC-016 for why posing is first)
1b. Normalise the materials        (D15-S5.9)
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

**R14a.** Style normalisation precedes **everything**, and the ordering is the point rather than an accident. Restyling reads and writes materials only, so it is indifferent to scale and axes — but every later stage destroys what it needs. Separation shatters a material group into thousands of shells (DISC-018), grouping joins shells from different materials into one part, and the export writes one mesh per part. By the time a part exists there is no material left to normalise, only a mesh already carrying the wrong colours.

<!-- D15-S5.2 -->### 5.2 Shell Separation

**R15.** Separation is by connected component (`separate(type='LOOSE')`) over **every** object, not only over objects suspected of straddling a boundary. An object in a downloaded file is a *material group*, not a part: on both shipped cars one object is the entire painted body, and on one of them a single object is both headlights and both tail lights.

**R16.** Cost is bounded and known: on the two shipped cars, 2,878 shells from 283k triangles and 2,466 from 234k, in about 11 s each. Separation is not the expensive stage and must not be skipped as an optimisation.

Those counts are **after** the topology cleanup of R27a and they are less than half what they were before it: 6,830 and 6,078. The difference is not noise, it is the point of welding — 39,000 of the Eclipse's 216,000 vertices were duplicates, and every one of them was a seam splitting one surface into two "connected" components that share an edge in appearance and not in topology.

**R17.** Shells below `MIN_SHELL_TRIANGLES` are **merged into their nearest labelled neighbour** rather than labelled independently. Two-thirds to three-quarters of the shells on a real car are bolts, screws and single grille strands; treating each as a part produces thousands of meaningless parts and destroys the triangle-share statistics the report depends on.

<!-- D15-S5.3 -->### 5.3 Grouping Shells into Parts

**R18.** Shells are grouped by `(label, side, index)`, **not** by spatial clustering. Bounding-box clustering was measured and rejected: with any padding sufficient to join a door skin to its inner card, it joins the entire car into one cluster, because every panel's box overlaps every neighbour's.

**R19.** `side` is `l`, `r` or `c`, from the sign of the group's centroid x against `SIDE_DEADBAND_M`.

**R20.** Mirror pairing (C4) is authoritative for `side`. A shell whose reflection about `x = 0` matches another shell to within `MIRROR_TOLERANCE_M` is one instance of a two-instance part, and both take the same part type with opposite `side` — which is how the shipped assemblies already express two front wheels sharing one part type.

<!-- D15-S5.4 -->### 5.4 Rotational Symmetry: What Turns and What Does Not

**R21.** A part attached to a rotating wheel rotates **only if it is rotationally symmetric about the axle**. Angular coverage is measured over vertices, in sectors of `360 / ROTATION_SECTORS` degrees, in the plane normal to the axle. Coverage at or above `ROTATION_SYMMETRY_MIN_DEG` ⇒ `wheel`; below ⇒ `hub`.

**R22.** The unit of judgement is a **material group within one corner**, never a single shell. Rotational symmetry is a property of an assembly: a wheel is symmetric under rotation by 360°/n and every piece maps onto another piece of the same kind. A lug nut occupies 15° and plainly rotates — what it lacks is not size but a partner to be rotated onto, and the material a piece was authored with is the best available proxy for "the same kind of part". Judged shell by shell, a rim loses its lug nuts, spoke details and valve stem to the chassis.

**R23.** The test is applied to **seed** shells as well as captured ones. A caliper bolt is square in silhouette and therefore passes the roundness test that seeds a wheel; it fails this one.

<!-- D15-R23a -->**R23a.** **Seeding a corner and belonging to one are different questions.** A shell may define an axle only if it is itself a disc of a road wheel's size in a road wheel's place — round in side view, 0.45–1.20 m across, no wider than 0.75 m, axled below 0.65 m and at least 0.45 m off the centreline. Everything else that belongs to the wheel arrives by capture and is judged by D15-S5.4.

The gate is on the *geometry*, not on the label, and that is the point: a shell can reach the `wheel` label on a material **name** alone (C3), and a name cannot define an axle. Measured on the Eclipse: a flat bracket 0.35 × 0.10 m, round to 0.29, whose material is called `vehicle_generic_smallspecmap_WHEEL`. As a seed it dragged the front axle 0.37 m rearward, inflated the wheel to 1.44 m across, captured 891 shells — 37% of the car — and reported all of them as brake furniture. With the gate, both shipped cars produce exactly four corners whose axles match the hand-authored slot positions to the millimetre.

<!-- D15-R23b -->**R23b.** A corner in which **nothing rotates** is not a wheel. If no material group in it passes D15-R21 or R24a after capture, the corner is dissolved and its shells return to `chassis`. Both shipped cars produced two to four such corners — a 7.7 mm "wheel", a 0.37 m one — before this existed, each of which took a slot on the vehicle and a directory in `assets/parts`.

**R24.** Measure coverage from vertices, never from a bounding box. A five-spoke rim's box corners land in four sectors, so a box-based measure cannot distinguish a spoked wheel from a caliper.

> Measured on both shipped cars: every rotating piece covers 360°; calipers cover 90–150°. The gap is wide enough that the threshold is not delicate.

<!-- D15-R24a -->**R24a.** Coverage is **not the only** sufficient test. A group also rotates if it is invariant under a turn of `360/n` for some `n ≥ MIN_SYMMETRY_ORDER`, measured over the same sectors. The two tests catch the two shapes a rotating piece can have — a *solid of revolution* covers the circle, a *bolt pattern* repeats around it — and R22's own example is the second: five lug nuts occupy five sectors of twenty-four, so coverage alone calls them 75° and sends them to the hub, while the set is plainly invariant under a fifth of a turn. A caliper satisfies neither: 90–150° of arc, once. `MIN_SYMMETRY_ORDER` is 3, because two pieces opposite each other are symmetric under half a turn and are just as likely to be the two ends of one bracket.

<!-- D15-R24b -->**R24b.** A vertex lying exactly on the axle's `+z` bearing must land in the **first** sector. Its bearing is zero, but computed against an axle centre that is a rounding error away it comes out fractionally negative and wraps to 359.99…, landing in the last sector instead — which is one misplaced sector, and enough to make a four-fold bolt pattern read as not symmetric at all.

<!-- D15-S5.5 -->### 5.5 Geometry Repair

**R25.** Repairs are applied in this order, each reported, none silent:

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

**R25a.** The scale, orientation and placement rows are one **similarity transform** — a uniform scale, a yaw about `+y`, and a translation — and the pipeline **derives** it and records it in the model's `import.json` (DEC-036). That file remains the single recorded correction and the thing the harness verifies; what changes is that an operator no longer has to write it before the pipeline will run. A model that already carries a correct one yields an identity residual and is rewritten unchanged, which is what makes re-running the pipeline safe.

**R25b.** What is written back is the **composition** of the existing correction with the residual, never the residual alone. Stage 1 has already applied the old file by the time stage 2 measures anything, so writing the residual would discard whatever that file was doing and the next run would arrive at a different vehicle. With the composition, a second run's residual is the identity — which is the property that makes the stage idempotent and is worth a test of its own.

**R25c.** A **unit** correction is chosen from a closed list of factors — metric decades, inches, feet — and never fitted continuously. A wrong unit is always one of those; a continuous fit would happily rescale a genuinely 24 m vehicle to 16 m and report success. If no candidate lands the model in the plausible range, the tool reports that and scales nothing.

**R25d.** A misorientation a yaw cannot express — a model lying on its side or standing on its nose, detectable because `y` is not its shortest extent — is **reported and not repaired**, under the same argument as R26. Guessing which way up a model on its side should go turns one visible fault into an invisible one.

**R26.** Broken symmetry is reported and never repaired automatically. Real cars are asymmetric on purpose — one exhaust, a fuel filler on one side, left-hand drive — and a pipeline that mirrors those away damages correct models to flatter incorrect ones.

**R27.** Every repair is recorded in the report as a before/after measurement. A repair nobody can see is indistinguishable from a bug.

**R27a.** Topology cleanup — welding doubled vertices, deleting zero-area faces and loose vertices — is bounded to `WELD_DISTANCE_M`, an order of magnitude below the smallest feature any threshold in this document measures, so no weld can move a shell far enough to change its label. It is not cosmetic: doubled vertices are most of why a downloaded car separates into thousands of shells that ought to be one, because two triangles that share an edge in appearance but not in topology are two connected components, and every stage after separation inherits that.

<!-- D15-S5.6 -->### 5.6 Articulation Rigging

**R28.** An articulated part is a part with a hinge: an axis, a pivot in chassis-local space, and an open angle. Doors, bonnet and boot are the expected cases.

**R29.** Hinge inference, in order of reliability:

1. **Declared** — `parts.json.hinges`. Always wins.
2. **Panel-edge inference** — a door hinges about the vertical edge nearest the front of the car; a bonnet about its rear-most transverse edge; a boot about its forward-most transverse edge. The axis is the panel's longest edge in that direction, the pivot its midpoint.
3. **None** — the part is rigid and detaches without opening.

**R30.** A hinge is exported as **data on the part**, not as a Blender armature. The game already composes parts down a slot chain (D04-S4.3.1), so an opening door is a slot whose local rotation animates. An armature would add a second, redundant transform hierarchy that the runtime does not read.

**R31.** Articulation and detachment are independent. A door may open, then later break off; the hinge angle is cosmetic state and the attachment is authoritative (G6).

<!-- D15-S5.7 -->### 5.7 Destruction Authoring by Class

**R32.** Each label maps to a destruction class, and each class to a treatment. This is where the pipeline decides *how a part fails* before anything hits it.

| Class | Treatment | Rationale |
|---|---|---|
| `sheet_metal` | Subdivide to `SHEET_METAL_TARGET_EDGE_M`; fine lattice cage; damage shape keys at 25/50/75/100% | A panel crumples locally and keeps its area. Needs vertex density where the dent is, or the dent is a facet |
| `glass` | No shape keys. Cell-fracture into shards at authoring time; runtime state is intact or shattered | Glass does not dent. A deformed windscreen looks like a bug; a shattered one reads instantly |
| `structural` | Coarse lattice cage; high-stiffness treatment with a plasticity yield threshold; no fine subdivision | A chassis or engine block buckles and shears globally. Fine subdivision makes it squish like a sponge, which is the failure mode to avoid |
| `rigid` | No deformation. Detach whole, fracture only if authored | A caliper, a mirror, a lamp housing either survives or leaves |
| `none` | Untouched | Decals ride their host; interiors are never hit |

**R33.** A class's parameters are per-class constants, not per-part authoring. A part that needs different numbers is evidence the taxonomy is missing a class, not that the part needs hand-tuning.

**R34.** The `structural` yield threshold is expressed as an impulse in newton-seconds, comparable with `breakImpulseN` (D08-R5), so "the frame buckles before the mounts shear" is a statement about two numbers in the same unit.

<!-- D15-S5.8 -->### 5.8 Naming, Mass and Balance

Stage 8 turns labelled geometry into the two documents D08 specifies. Four of its decisions are load-bearing, and none of them is derivable from the geometry alone.

**R40.** A part's identity is `<label>_<vehicle>[_<role>][_<side>][_<index>]_01` and its slot is `<label>[_<role>][_<side>][_<index>]`, both matching D08-R6's patterns. The label leads so a listing of `assets/parts` groups by kind, the vehicle follows so two cars never collide, and the role and side are what a human uses to find the one they mean — `panel_pickup_door_l_01`. A slot at a wheel corner is named for the corner instead (`wheel_fl`), which is what the shipped assemblies already call them.

**R41.** A part's mass is `area × areal density`, capped by `enclosed volume × density`, and is **not** `volume × density`. D09-R16's volume rule is right for a fractured solid, where the geometry is the object; it is wrong for vehicle art, where the panels are shells. A door modelled as a closed skin encloses about 0.1 m³ of air and `volume × density` calls it 785 kg of steel.

The per-class constant is a **mass per square metre**, not a thickness times a density, and the distinction is load-bearing. 20 mm is the right wall for a rubber tyre and yields 22 kg/m²; the same 20 mm of *steel* yields 157, so a brake hub with 1.37 m² of folded surface was weighed at 214 kg — ten times a real one — and inflated a 1,500 kg car to 1,977. One `rigid` class covers both a tyre and a steel casting, so its constant must be the quantity that is stable across the two. It is: a designer picks whatever thickness gets the required stiffness out of the material to hand, which puts nearly everything on a vehicle between 17 and 20 kg/m². Glass is genuinely lighter and a chassis genuinely heavier, being box sections rather than a skin.

The cap exists because a part cannot contain more material than fits inside it. An open surface encloses nothing and keeps the surface reading; a hollow box encloses far more than its walls hold, so the surface reading is already the smaller of the two; a small solid lump encloses less than its own folded surface implies, and the lump wins.

<!-- D15-R41a -->**R41a.** The target mass a vehicle is scaled to is measured across its **track**, not across its bounding box, whenever the pipeline found wheels. A bounding box includes the wing mirrors: on the Eclipse that is 2.18 m against a real 1.97, which is 11% on the number every part's mass is derived from. Track plus a wheel's width is a road vehicle's width to within a few centimetres, because that is what a track is.

**R42.** The **chassis takes the balance**: every other part is weighed from its own geometry and the chassis is whatever is left of the vehicle's target mass. The target is authored (`--mass`) or, failing that, derived from the footprint at a fixed areal density, and the report says which. If the measured parts would leave the chassis below `CHASSIS_MIN_FRACTION` of the vehicle, the target is raised to keep it there and that is reported too: a car whose doors outweigh its structure is a measurement fault, not a design.

**R43.** `powerCost` is **distributed**, not summed. D05-S5.7's reference formula fixes the ratios between parts; `assets/balance/classes.json` fixes the total for the vehicle's class, and A312 makes that total an error rather than a warning. Each part therefore takes its share of the class target, which satisfies A312 by construction, and A210's advisory comparison against the raw formula is left to say how far the two disagree.

**R44.** Wheels and hubs on one axle **share** a part type across the two sides, as D15-R20 describes and the shipped assemblies already express. Nothing else shares: a left door is not a right door reflected, and a pipeline that pretended otherwise would put the handle on the inside. A shared type is exported once and placed at each corner's **own** measured axle rather than at the reflection of the other side's, and the shells of every instance stay attributable to it, so AC-D15-4's accounting is exact.

<!-- D15-R45a -->**R45a.** A **wheel's slot is not its axle.** The runtime hands the slot position to Bullet's `addWheel` as the suspension's connection point, and Bullet hangs the wheel `suspensionRestLengthM` below it. Emitting the axle buries every wheel 30 cm into the ground and leaves the vehicle sitting on its floor pan. The *mesh* origin stays at the axle, because that is what the wheel spins about — the two are different points and they are supposed to be.

**R45b.** The slot is the axle raised by one rest length **less the car's static sag**, not by a whole rest length. No vehicle stands on fully extended suspension; it stands at its ride height, one sag below, and the artist modelled it there. Raising by the full rest length settles the body that far into the road and makes the model's own ground plane meaningless. The sag is derivable and does not depend on the vehicle's mass: Bullet's suspension force is `stiffness · compression · chassisMass`, so equilibrium at `stiffness · sag · m = m · g / n` gives **`sag = g / (stiffness · n)`** — 8.2 cm on four wheels at the reference stiffness of 30.

**R45c.** A wheel's placement is the centre of the **wheel part**, not of the shells that voted for it. A wheel group is a disc and is symmetric about its axle in every direction, so its bounding box centre *is* the axle; the corner's own measurement spans brake furniture as well and sat 3.6 cm high and 3 cm wide of it on one shipped car. The corner model still decides *which* shells are a wheel; it no longer decides where the wheel is.

**R45d.** Two wheels on one axle are placed **symmetrically** about the centreline beyond a small tolerance. Each corner is measured independently (DEC-066) and on real art the two answers differ by millimetres, which is worth keeping; a disagreement of centimetres is a corner whose shells were cut differently, and a car whose left wheel is further out than its right sits crooked and drives on two of them.

**R45.** A part's mesh origin is the point its slot is measured from, and the choice is per label: a wheel's is its **axle**, because Bullet spins a wheel about its part origin and a wheel offset from it orbits the vehicle; an articulated part's is its **hinge pivot**, because R30 makes an opening door a slot whose local rotation animates and a rotation animates about the origin; everything else takes its own bounds centre, which is where its mass acts (DEC-043).

**R46.** **What is produced is what is written.** Each authoring step reports whether it actually succeeded and the manifest is built from that report rather than from the plan. A part whose morph generation tripped a D09 guard ships with no `morphTargets` array rather than a `part.json` promising four shape keys the mesh does not carry — which would pass this tool and fail in the asset gate, one layer further from its cause. The same applies to a `glass` part that could not be fractured: it ships unfractured and detaches whole, which D07-E5 already handles.

---

<!-- D15-S5.9 -->### 5.9 Style Normalisation

**R47f.** Stage 1b classifies every material in the loaded scene into one of R47's surface roles, then moves it into that role's band. Classification follows the same precedence the labelling ensemble does (D15-R6): **physical evidence first, name second**. Transmission or a blended low alpha is glazing; emission is a lamp; a near-black rough surface is rubber; a metallic smooth one is plating. Only then are name tokens consulted, matched as whole words — `wheelarch` must not match `wheel`, which is the mistake DISC-037 records one stage later.

**R47g.** A material with no evidence at all takes the neutral answer, **trim**, with one exception: the material covering the most triangles on the vehicle is its **paint**. That is true of every published car model, and without it an unnamed, untextured supercar body renders as matte grey.

**R47h.** A failure here is content, not a crash. A missing or malformed style table is reported and the run continues unstyled: the vehicle that comes out is still a correct vehicle, it merely does not match the roster, and refusing to prepare a car because a palette file moved is the wrong trade.

<!-- D15-S5.10 -->### 5.10 Hardpoints

**R49.** Every prepared vehicle offers **five mounting points that its own art did not supply**: one `TURRET_MOUNT` on the roof and four `HARDPOINT`s — bonnet, tail, and each flank. Four is exactly what D05-S4.3 allows a vehicle; a turret ring is a slot of a different type and a vehicle has at most one roof.

**R49a.** Their positions are derived from the body's own box as fractions of its half width, its height above the ground plane, and its length **measured from the rear** — so that a hardpoint transfers from a hatchback to a tank without a number moving. Their capacity is a fraction of the vehicle's kerb mass, with a floor, so a light buggy still mounts something real.

**R49b.** They are declared on the chassis and **filled by nobody**. That is not an omission: a weapon or a module is shared content in `assets/parts/` (D08-R14b), a vehicle drives perfectly well with all five empty, and every check that reports an unfilled slot must exempt these by **id** rather than by slot type — a brake hub also occupies a `HARDPOINT`-typed slot (DEC-063), and a missing one is a real finding.

**R48.** A `weapon` part's block is **derived from its own geometry**. Two things, and only two, because they are the two the geometry actually determines: the **family**, from the aspect ratio — a part at least `BARREL_ASPECT_MIN` times as long as it is wide is a barrel and therefore a cannon, anything shorter is a mount or a pod and is an autocannon — and the **muzzle**, at the part's forward extent on its own centreline. Choosing between the remaining six families would be inventing content rather than deriving it.

---

<!-- D15-S5.11 -->### 5.11 Lamps

**R51.** A part labelled `light` is authored with a `light` block (D08-R6). Only a **head** lamp casts: a tail light, an indicator and a reflector glow and illuminate nothing, which is true of the real ones and is what keeps eight vehicles to sixteen casting lights rather than fifty.

**R51a.** Two of its fields are derived from the part rather than from the per-role table. The **direction** is the vehicle's front tilted down by the role's cut-off — and mirrored for a lamp at the rear, so a tail light glows backwards. The **origin** is the lamp's own outward face along that direction, because a beam starting at the middle of the lens starts inside the bodywork.

**R51b.** Lighting is **cosmetic** in the sense of G6. A lamp is extinguished by *reading* authoritative state — its part being destroyed or detached — and never by writing any. Shooting a headlight out is therefore already implemented by the damage model, and the renderer contributes nothing to it but the observation.

**R51c.** A visible beam and a lit surface are two different things and both are needed. A spot light makes a headlight *light something*; a translucent cone drawn from the lens makes it *visible as a beam*, which no amount of surface lighting achieves — a real beam is seen because the air between the lamp and the ground scatters it, and a renderer with no participating medium has to draw that shaft or the light appears from nowhere. The visible shaft is drawn at the **inner** cone angle and well short of the lamp's range: the illumination reaches further than the scattering does, which is what a real beam looks like.

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
- [ ] **AC-D15-11.** A model dropped in with no `import.json` and no `parts.json` produces a loadable vehicle: a directory of parts and an assembly the game's own loader and asset gate accept, with no hand-authoring between the two.
- [ ] **AC-D15-12.** Every emitted `partTypeId` and `slotId` matches D08-R6's patterns, and every emitted `(category, slotTypeRequired)` pair is one `SlotType.acceptsCategory` accepts.
- [ ] **AC-D15-13.** The assembly's power budget equals its class's target within A312's tolerance, and its total mass is within the plausible range for a vehicle of its footprint.
- [ ] **AC-D15-14.** Running the pipeline twice on the same model leaves the second run with an identity correction, and the `import.json` it writes unchanged.

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

**R35.** A prepared vehicle is not finished when it looks right. Sound is the half of destruction feedback the visual pipeline cannot deliver, and it is cheap to specify now and expensive to retrofit once a hundred parts exist.

**R36.** The inventory a vehicle needs, keyed to events that already exist in the simulation:

| Event | Source | Notes |
|---|---|---|
| Tyre roll / skid | Per surface, blended by slip | The ray-cast wheel already computes slip |
| Collision impact | Per material pair, by impulse | `CollisionEventSystem` (slot 11) already classifies both sides |
| Part detach | Per destruction class | `sheet_metal` tears, `glass` shatters, `rigid` clangs |
| Glass shatter | One-shot per `glass` part | The one sound a player will notice missing |
| Debris settle | Per material, by mass | Driven by the existing debris lifetime |
| Weapon fire / impact | Per weapon family (D01-R8) | Eight families, eight pairs |
| Fire loop | One | A burning vehicle. `DamageSystem` (12) already runs the burn timer it rides on |

Everything an engine makes — the exhaust note, ignition, shutdown, overrun, induction and its release
— is **not in this table and is not an asset**. It is synthesised in the client from the engine's
live state (R37a3). Those six families were assets for two sessions and the reasons they stopped
being so are in R37a3 and R38a2.

**R36a.** **Induction is a second voice, and it is the axis on which a forced engine is recognised.**
An exhaust note says how many cylinders are firing and how evenly; it says nothing about how the
engine is breathing. Both of the vehicles this project ships are forced-induction — the Eclipse's
reference is a twin-turbo V6 and the Stampede's a supercharged V8 — and a blower whine is what a
listener identifies the latter by before its exhaust registers at all. Keyed on `Induction`
(`NATURALLY_ASPIRATED`, `TURBO`, `SUPERCHARGED`), which is a closed set of three obeying R37 exactly
as `EngineConfiguration` does: one asset per member, nothing per vehicle.

**R36b.** The two forced types must differ **structurally**, not by tuning. A supercharger is geared
to the crank, so it is a hard tone at a fixed order of engine speed and it is present whenever the
engine turns. A turbocharger is spun by exhaust flow, so it is broadband rush that needs both revs
and load, and it lets go audibly when the driver lifts — which is why only `TURBO` has a release.
Rendering both as one loop at two gains reproduces the fault this rule exists to prevent.

**R36c.** **Every family in this table needs a trigger that exists**, and a family whose sound ships
without one is not delivered. Three of them shipped that way for a session: tyre roll and skid had
correct files and no exposed wheel slip, weapon fire and impact had fourteen files and no events from
slots 8 and 9, debris settle had five files and no came-to-rest signal. The files were never the hard
half. An inventory is complete when a sound plays, not when a file exists.

**R37.** Sounds are **per class and per material**, never per vehicle. Seven events over five destruction classes is a set of tens; per-vehicle sound would be a set of hundreds and would gate every new car on an audio pass.

**R37a.** For an engine, the class in R37 is the **engine configuration**, not the vehicle class. `light`/`medium`/`heavy` describe how much a car weighs; what an engine sounds like is decided by how often it fires and how evenly, which is `cylinders × rpm / 120` and the arrangement's firing evenness. Keying engine loops on weight makes two different cars of the same weight sound identical, which is the specific complaint this rule exists to prevent. Six configurations cover every vehicle worth modelling and the set stays closed.

**R37a1.** An engine is a **train of exhaust pulses at crank angles**, not a harmonic stack at the
firing frequency. The two are not equivalent, and the difference is the whole character of a
cross-plane V8: its banks fire at `90-180-180-270` while the engine as a whole fires evenly every
90°, and what a listener calls the burble is the odd-order content that unevenness produces. A
harmonic stack at `firingHz` is by construction perfectly even and cannot express it; approximating
it with a noise gain makes an engine hissier rather than lumpier, because the ear locates unevenness
in time and a noise gain puts it in the spectrum.

**R37a2.** The burble is produced by that unevenness **failing to cancel**. A cross-plane V8's two
bank patterns are time-reverses of each other, so summed perfectly coherently their odd orders cancel
to zero and the engine is even at order 8 — which is why cross-connecting the banks audibly flattens
a real one. Bank divergence is therefore a synthesis parameter with a physical meaning, and it must
scale with how differently the banks fire: applied uniformly it makes even-firing arrangements lumpier
than the one arrangement that should be lumpy.

**R37a3.** **An engine is synthesised as it runs, not played back.** The crank angle integrates
forward at whatever rpm the simulation reports and a cylinder fires when its angle comes round, a
block at a time, from the audio callback. Three things follow that a sampled bank cannot deliver at
any file count:

- **Condition.** A damaged engine misfires, loses a cylinder, and loses its exhaust. All three are
  changes to *when* the cylinders fire and *what happens to the pulse afterwards*, and neither
  survives resampling. A dropped cylinder is not a filter setting — it is one pulse not laid down,
  and the even-order nulls of the bank's own geometry filling in is the lope a listener hears.
- **Transients stop being assets.** Ignition and shutdown were twelve files only because a loop
  cannot change speed. Cranking is 260 rpm with nothing catching; shutting down is the same engine
  running out of rotation. Both are phases of the one voice, at the car's own idle rather than at a
  nominal one no car on the roster has.
- **No reference rpm.** There is no loop to pitch away from, so the pitch ratio, the reference rpm,
  and the clamp that stopped it running away all cease to exist. Below 1,000 rpm the clamped ratio
  had stopped tracking revs at all.

**R37a4.** Synthesised engines are **mono and positionless**, so the runtime must place them: a
fixed set of voices mixed to stereo with distance attenuation, panning against the listener's own
axes, air absorption, and propagation delay. The delay is not decoration — reading a voice out of a
delay line `distance / 343 m·s⁻¹` late produces doppler at exactly the right ratio for free, and a
doppler modelled as a pitch multiplier instead would have to be applied to the rpm, which changes the
engine's speed rather than its pitch and leaves the exhaust resonances behind.

**R37b.** Two vehicles sharing a configuration must still differ, and do so without a second asset. A vehicle carries an **engine voice** — configuration, idle and redline rpm, peak power, induction — and the runtime synthesises from it. Peak power is the same number the physics accelerates the car with, so a car that gets faster gets louder in the same commit and the two cannot drift apart.

**R37c.** A more powerful engine must be *audibly* more powerful. Gain rises with power on a saturating curve rather than a linear one, and a larger engine's exhaust resonances sit lower — because a big engine is not a loud small engine, and the spectral difference survives a small speaker where the volume difference does not.

**R38.** Sourcing, in order of preference: (a) permissively licensed libraries with attribution recorded beside the asset exactly as `license.txt` records model provenance today; (b) procedural synthesis for engine and tyre loops, which are parametric by nature and where a synthesiser removes a per-class asset; (c) commissioned or recorded audio. Generative audio models are viable for one-shots and are **not** for loops, where seam artefacts are audible.

**R38a.** The shipped bank takes (b) for **every** family, not only the loops, and the deciding argument is R39's rather than R38's: the two shipped vehicle models are CC-BY-NC-SA, that constraint is already live, and a sampled bank would add a second differently-encumbered set of terms for somebody to discover later. A synthesised bank carries the repository's own licence, regenerates byte-identically from a seed, and — for the loops — has no seam to hide, because the buffer is constructed to contain a whole number of cycles rather than being cut from a recording.

**R38a1.** A synthesised loop is seamless only if the buffer holds a **whole number of periods of
everything periodic in it**, and a crossfade does not establish that — it hides the failure. A
crossfade removes the discontinuity in sample *value*, which is what a click is; it does nothing
about the discontinuity in *phase*, which is what a warble is. A buffer built to hold a whole number
of cycles and then trimmed by a crossfade measured in seconds no longer holds a whole number of
cycles, and its harmonics restart out of step on every pass. Any trim must be rounded to a cycle
boundary of the lowest periodic component — for a pulse-train engine that is the **engine cycle**
rate (`rpm/120`), not the firing rate, because an uneven bank pattern repeats once per 720°.

**R38a2.** **The exhaust colours the pulse train; it must not replace it.** A bank of band-pass
resonators summed alone is not a filter, it is a gate: everything between the formants is attenuated
by the sum of their skirts *and* by the phase cancellation between them, which for the shipped
105/560/1750 Hz set put a 25 dB hole at 267 Hz — a V8's firing frequency at 4,000 rpm. Measured, four
of six loops peaked at a frequency unrelated to their engine, the V8 at 0.375× its firing frequency
and the V6 at 3×. The dry signal must reach the output, with the resonances added over it.

**R38a3.** The verifying test is that **the firing order survives**, at more than one engine speed.
"Has resonant peaks" and "carries sub-firing orders" are both satisfied by the defect R38a2
describes — a gate creates peaks and creates sub-orders; what it destroys is the firing frequency,
and neither test asked about it. The firing order need not be the single loudest component, because
at low rpm a four-cylinder's fundamental legitimately sits below every exhaust resonance and its
second harmonic carries; it must not be buried.

**R38a4.** A synthesised engine is tuned against **measured recordings of real engines**, not
against taste, and two numbers carry most of the character. Its harmonics must fall at roughly
**7 dB per octave** between 100 Hz and 4 kHz, and must stand roughly **15 dB** above the
inter-harmonic floor. Both were far out before anybody measured them — 22 dB per octave and 37 dB —
and the second matters more than it looks: an engine with no broadband content between its harmonics
reads as synthetic however correct its firing geometry is. Real gas keeps moving through a pipe
between blowdowns, so the flow noise is continuous and not gated by the pulse envelope.

Reference recordings are **analysis inputs and never ship**. They inform constants in the
synthesiser; no sample derived from them enters `assets/`, so R39's licence rule is satisfied by the
bank carrying no third-party audio at all.

**R38a5.** **Every periodic component of an engine is tied to the crank, and none of them may be a
free constant.** Three were, and all three were audible as faults rather than as machinery:

- The starter's crank-speed modulation ran at a fixed 6 Hz. A starter labours once per *compression*,
  which is `rpm / 120 × cylinders` — 8.7 Hz for a four and 26 Hz for a twelve at cranking speed. A
  rate unrelated to the engine is what makes a start sound like a machine fault.
- The starter's gear whine held a fixed pitch while the crank speed swung 26% underneath it. The
  pinion is geared to the ring gear, so the whine must dip and recover with the labour; the two
  moving together is what a listener recognises as an engine being turned over.
- A cranking engine was near silent apart from that whine. It is pumping air through an open exhaust
  on every stroke, and that chuffing is most of what a start actually is.

**R38a6.** Power must be audible as **weight on the throttle**, not only as volume. A low shelf below
the exhaust's own resonances, scaled by peak power *and* by load together, so it swells when the
driver is on it and falls away on a lift. Volume alone does not survive a small speaker; the low
shelf does.

**R38a7.** Lifting off at speed **pops**. Unburnt charge lights in a hot exhaust, so the crackle is
armed by the throttle *transition* rather than by the off-throttle state — a car coasting for ten
seconds pops for the first second and then just coasts — and scales with revs and with how open the
exhaust is.

**R38a8.** **Every asymmetry between a V's two banks scales with how differently they fire.** Banks
that fire identically must stay nearly matched, or the even-firing arrangements come out lumpier than
the one arrangement that should be lumpy. This has now been got wrong three times — once each for the
manifold delay, the formant detune and the bank gain imbalance — so it is a rule and not a comment on
a constant. It is also why a V's even orders cannot be fixed by bank asymmetry alone: **cylinder-to-
cylinder scatter** is what fills them, because orders are what repeats every cycle and only a *fixed*
difference between cylinders lands there. Random per-event jitter spreads energy as noise instead.

**R38a9.** Ignition timing is **measured, not invented**, and its parts scale with the arrangement.
Against a real V8 startup the catch is a single step from ambience to full voice, the flare holds
about 0.2 s before it decays, and the flare sits under 3 dB above idle — not the half-second ramp,
instant collapse and 12 dB step a first guess produces. Crank duration and crank speed both follow
cylinder count, because a bigger engine is more inertia and more compressions for a starter to drag
over, and that is most of what makes two arrangements start differently.

<!-- D15-R38a10 -->**R38a10.** **The rumble is an envelope property, and a spectrum cannot see it.** What a listener
calls an engine's lope is the ratio between how much its loudness varies at rates *below* its firing
order and how much it varies *at* the firing order. Measured on two steady windows of a real
cross-plane V8 idling, the sub-orders modulate by 27% and 79% while the firing order modulates by
1.6% and 4.4% — ratios of 17.4 and 18.2. A synthesiser built from a correct firing geometry does the
opposite by default: this one measured 28.1% of sub-order modulation, which was already right, and
20.3% at the firing order, which was twelve times too much. It read as a buzz because it pumped once
per cylinder, and no spectral measurement in this document would have found it. Any change to the
exhaust path is checked against this ratio, at more than one engine speed.

<!-- D15-R38a11 -->**R38a11.** **Pulses are fused by an all-pass chain, never by a comb.** Successive blowdowns have to
overlap until the ripple between them fills in, and the obvious way to do it — feeding the exhaust
into delay lines and mixing the reflections back — reintroduces R38a2's defect by a different route:
fixed delays have fixed resonances, and driven hard enough to fuse the pulses they pin the spectrum
(the I4's centroid stopped tracking engine speed at ×1.8 against a ×2.0 bound). An all-pass has unity
magnitude at every frequency by construction, so it disperses in time and *cannot* colour. Two
further constraints follow from measurement: the chain needs enough stages that no single one can
align with the firing period — with four, the V8's firing-order modulation swung from 1.6% at 900 rpm
to 16.8% at 1,100 — and its delays are expressed **in firing intervals rather than in milliseconds**,
so the engine has the same character at every speed. The pipe's own fixed resonances are the
formants, and those are the part that must not move.

<!-- D15-R38a12 -->**R38a12.** **A cylinder's exhaust event is a blowdown and then a 180° sweep.** Modelling only the
blowdown — a third of a firing interval, violent, then nothing — leaves the gaps between firings
empty and is most of why R38a10's ratio inverts. Once cylinder pressure has equalised the piston
still displaces the remainder over its whole exhaust stroke: four times longer, lower, smooth, and
carrying no transient. Because 180° is a quarter of the cycle, a quarter of any engine's cylinders
are always sweeping, and their overlap is what leaves the differences *between* cylinders as the only
thing still modulating — which repeats once per cycle, and is therefore the rumble.

<!-- D15-R38a13 -->**R38a13.** **A cranking engine is not heard through its exhaust**, and the exhaust model is backed
off while the starter is engaged. There is no combustion and gas velocity is a fraction of an idle's;
measured on a recording of an engine failing to catch, 72% of the energy sits above 1.3 kHz, which is
not a tailpipe. Left at full strength R38a11's smear runs to half a compression stroke and washes the
chuff out — the four's crank measured 4.0 Hz against its true 7.5 — and a start that does not chug is
the fault sound R38a5 exists to prevent.

<!-- D15-R38a14 -->**R38a14.** **A recording is not a reference until its orders have been checked.** Before any number
is taken from a clip, its envelope-modulation peaks must be shown to lie on one series: a single
engine puts most of them on multiples of one cycle rate, and several engines put them nowhere. A clip
of multiple cars starting was used as a four-cylinder reference for most of a session, and the ratio
taken from it was chased through a parameter search before the check showed its peaks form no series
at all. `game-client/tools/engine_reference.py --identify` performs this check and is the first thing
run on an unfamiliar recording.

<!-- D15-R38a15 -->**R38a15.** **An engine is auditioned as a take, not as a steady tone**, and the renderer that
produces one is part of the deliverable. Every defect this synthesiser has had was reported by ear
and only then measured — the filter gate, the beep at the head of a start, the missing lope, the
overrun crackle that sounded like popcorn — and each needed a *new* measurement afterwards, because
none of the existing ones asked about it. A test suite checks the questions somebody already thought
to ask; a rendered take is what can be judged against the one nobody has. The take is a performance:
start, settle, several seconds of idle, two blips with lifts between them, then a pull to the
limiter, rendered through the mixer so what is heard is the path the game uses. Faults hide in
steady state and surface in transitions.

<!-- D15-R38a16 -->**R38a16.** **A crank does not turn at a constant speed, and the flywheel is what puts a lope where
the ear feels it.** Each power stroke is an impulse and the flywheel only partly smooths it, so an
idling engine surges and drops within every cycle — measured on this model, 28% peak to peak for an
eight at idle, falling to 8% by 3,000 rpm. Because a flywheel is a low-pass on the torque impulses,
that ripple lands on the lowest orders, which is exactly where a real engine's lope is: the reference
Mustang puts 4.8–8.1% at order 1 and 8.9–16.3% at order 2, at 6 and 12 Hz. **The sub-order total is
not sufficient** — this synthesiser once matched the total to within a percentage point while having
0.9% and 1.1% at orders 1 and 2 and dumping everything into order 3, and was reported as having no
feelable rumble at all, correctly. Where the lope sits is checked, not just how much of it there is.

<!-- D15-R38a17 -->**R38a17.** **A cross-plane V8 rocks on its mounts once per crank revolution, and that is its order
2.** Its crank throws sit at 90° and the reciprocating masses do not balance end to end; the engine
rocks about its centre every revolution, which is why these engines carry heavy counterweights and
why one at idle visibly shakes. A moving engine radiates differently as it moves. Nothing else in
the model produces order 2 — the bank imbalance lands on odd orders and the flywheel on order 1 —
so without it a V8 is a drone with no thump. Scaled by bank unevenness, so an even-firing V does not
inherit a shake its crank does not have, and faded with engine speed, because a V8 shakes at idle
and settles as the revs rise.

<!-- D15-R38a7a -->**R38a7a.** **Overrun bangs are exhaust events that lit off, so they are scheduled on exhaust events**
— never on a clock. A free-running rate in hertz produced 11 to 14 evenly spaced pops a second on
every arrangement at every engine speed, which a listener described as popcorn: a stream of identical
clicks with no relationship to the car making them. This is R38a5's fault in a second costume, and it
is caught by the same kind of assertion — the pop rate must rise with engine speed, which no constant
in hertz can do. Two further properties are required and were both absent: a bang has **body** as
well as a crack, because a detonation shoves a slug of gas down a pipe before it cracks at the tip;
and bangs **cluster**, because one detonation leaves the pipe hotter and the charge behind it richer,
where independent per-event coin flips give a flat Poisson stream that is uniform by construction.

**R38b.** Impact and detachment one-shots are **modal**: a broadband strike transient over a handful of exponentially damped, inharmonic sinusoids whose frequencies and decay times are the material's. Filtering noise is cheaper to write and does not work, because the ear identifies material from decay time and partial spacing far more than from spectral tilt.

**R38c.** Severity is authored, never pitched. Three impact variants per material (`light`, `medium`, `heavy`) differ in modal frequency, decay and brightness, because a hard hit excites a larger area and therefore rings *lower* and longer — pitching one recording up and down produces the single most recognisable sign of a cheap bank.

**R39.** Every audio asset records its licence beside it, under the same rule as art. The two shipped models are CC-BY-NC-SA and that constraint is already live; audio must not add a second, differently-encumbered set of terms nobody tracked.

---

<!-- D15-S9 -->## 9. Test Cases

| ID | Test | Expected |
|---|---|---|
| T-D15-1 | Run on both shipped cars | Four wheels each, axles within 1 mm of the recorded art |
| T-D15-2 | Assert no caliper material in any exported wheel | Passes on both cars |
| T-D15-2b | Assert a wheel keeps its lug nuts and valve stem | Triangle count unchanged by the symmetry pass |
| T-D15-3 | Sum triangles across all output parts | Equals the source model's triangle count |
| T-D15-4 | Add a `materialLabels` entry for one material | Exactly the shells using it change label |
| T-D15-5 | Feed a model scaled ×100 | Repair reports the scale correction; measurements match the corrected run |
| T-D15-6 | Feed a model yawed 180° | Nose-direction repair fires; wheelbase and track unchanged |
| T-D15-7 | Angular coverage of a synthetic annulus vs a synthetic wedge | 360° and ≤ 90° respectively |
| T-D15-8 | Two runs, same seed | Byte-identical `.glb` output |
| T-D15-9 | A model with one deliberately asymmetric part | Reported, not repaired |
| T-D15-10 | Open every inferred hinge to its authored angle | No part intersects the chassis |
| T-D15-11 | Firing-order magnitude vs the loudest order, six configurations × five speeds | Never more than 12 dB below (R38a3) |
| T-D15-12 | Spectral centroid at 1,500 rpm vs 6,000 rpm | Rises by 2×–4×; a fixed formant scores 1× (R38a2) |
| T-D15-13 | Order spectrum of a V8 with and without a dead cylinder | Even-order nulls fill in by more than 5× (R37a3) |
| T-D15-14 | A voice moving at ±60 m/s past the listener | Received frequency shifts by `c/(c∓v)` (R37a4) |
| T-D15-15 | 24 voices at distinct positions, rendered together | Panned to their side, bus never clips (R37a4) |
| T-D15-16 | Harmonic tilt and harmonic-to-floor ratio vs measured real engines | Within the measured range (R38a4) |
| T-D15-17 | Envelope modulation rate while cranking, per arrangement | Equals that engine's compression rate (R38a5) |
| T-D15-18 | Low-band energy on throttle vs on a lift, powerful engine vs weak | Swings more on the powerful one (R38a6) |
| T-D15-19 | High-band energy after a lift vs a steady coast at the same rpm | Raised, and decaying (R38a7) |
| T-D15-20 | Odd and even sub-order energy vs a measured real V8 at matched rpm | Both within a few dB (R38a8) |
| T-D15-21 | Sub-order over firing-order envelope modulation, V8 at two matched idle speeds | Within an order of magnitude of the real V8's 17.4–18.2, and consistent between the two (R38a10) |
| T-D15-22 | Firing-order envelope modulation across 700–3,000 rpm | No speed more than a few times any other; a tenfold swing is one diffuser stage aligning (R38a11) |
| T-D15-23 | Envelope modulation at orders 1 and 2 of a V8 at idle | Both within the real Mustang's 4.8–8.1% and 8.9–16.3%; a matching *total* is not sufficient (R38a16, R38a17) |
| T-D15-24 | Overrun bang count at 6,000 rpm vs 3,000 rpm, summed over the arrangements | Rises by at least half again; a rate in hertz cannot (R38a7a) |
| T-D15-25 | Crest factor after a lift vs a coast at the same speed | Peaks stand at least 1.35× further above the level; measured band-agnostically (R38a7a) |
| T-D15-26 | A model in centimetres, in millimetres, and already in metres | The first two are scaled by a unit factor; the third is not touched (R25c) |
| T-D15-27 | Derive a correction, apply it, derive again | The second is the identity (R25b) |
| T-D15-28 | A model whose shortest extent is not `y` | Reported, and no yaw applied (R25d) |
| T-D15-29 | Panel roles on a synthetic body: flank, horizontal and end panels | door / bonnet / boot / bumper / sill / fender, per R3b |
| T-D15-30 | Angular coverage and symmetry order of four lug nuts at 90° | Coverage 60°, symmetry order 4, and the group rotates (R24a) |
| T-D15-31 | The two doors of one vehicle, opened to their authored angles | Opposite signs, and both finish outside the body (R29, E9) |
| T-D15-32 | Every emitted identifier and category/slot-type pair | Matches D08-R6 and is accepted by `SlotType` (AC-D15-12) |
| T-D15-33 | The assembly's power budget against its class target | Equal within A312's tolerance (R43) |
| T-D15-34 | Shells accounted for across every part and every instance | Each appears exactly once (AC-D15-4, R44) |
| T-D15-35 | A part whose morph generation failed | No `morphTargets` in its `part.json` (R46) |
| T-D15-36 | A flat bracket whose material names it a wheel | Rejected as a seed; the corner keeps its real axle (R23a) |
| T-D15-37 | A corner in which no material group rotates | Dissolved, its shells returned to `chassis` (R23b) |
| T-D15-38 | Glass parts on both shipped cars | Fractured, with the shard masses summing to the pane's (S5.7) |
| T-D15-39 | Every mesh named by a `part.json` | Contains the node and the morph targets the manifest promises (R46) |
| T-D15-40 | Every slot a prepared chassis offers | Filled exactly once by the assembly, by a part its slot type accepts |

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

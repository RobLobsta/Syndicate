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

**R22.** The unit of judgement is a **material group within one corner**, never a single shell. Rotational symmetry is a property of an assembly: a wheel is symmetric under rotation by 360°/n and every piece maps onto another piece of the same kind. A lug nut occupies 15° and plainly rotates — what it lacks is not size but a partner to be rotated onto, and the material a piece was authored with is the best available proxy for "the same kind of part". Judged shell by shell, a rim loses its lug nuts, spoke details and valve stem to the chassis.

**R23.** The test is applied to **seed** shells as well as captured ones. A caliper bolt is square in silhouette and therefore passes the roundness test that seeds a wheel; it fails this one.

**R24.** Measure coverage from vertices, never from a bounding box. A five-spoke rim's box corners land in four sectors, so a box-based measure cannot distinguish a spoked wheel from a caliper.

> Measured on both shipped cars: every rotating piece covers 360°; calipers cover 90–150°. The gap is wide enough that the threshold is not delicate.

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

**R26.** Broken symmetry is reported and never repaired automatically. Real cars are asymmetric on purpose — one exhaust, a fuel filler on one side, left-hand drive — and a pipeline that mirrors those away damages correct models to flatter incorrect ones.

**R27.** Every repair is recorded in the report as a before/after measurement. A repair nobody can see is indistinguishable from a bug.

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

<!-- D17-S0 --># 17 — Weapon Preparation and the Weapon System

**Status:** contract
**Owns:** the weapon preparation pipeline, weapon sub-part structure, weapon articulation, size-class
slot gating, and the firing impulses.
**Companion tools:** `blender-tool/syndicate_weapon`, `test-environment`.

---

<!-- D17-S1 -->## 1. Purpose

D15 turns a downloaded *vehicle* into parts. This document does the same job for a downloaded
*weapon*, and then specifies the four runtime behaviours that a weapon built this way needs and that
no existing document owns:

1. **A weapon is an assembly, not a part.** A gun that can only be destroyed as a unit cannot be
   played against. A barrel that can be shot off, a breech that can be cracked, and a mount that
   still holds a ruined gun are what make a weapon a target worth aiming at rather than a health
   pool with a muzzle flash.
2. **A weapon moves.** A gun whose geometry is rigid while it fires reads as a prop. Barrels recoil,
   feed drums turn, elevation gears drive, cylinders index. That motion is **cosmetic** (G6) and this
   document is where the line is drawn, because the temptation to let a recoiling barrel push the
   physics is exactly the kind of feedback G6 exists to prevent.
3. **Not every weapon fits every mount.** A slot type (D05-S4.3) says a hardpoint takes weapons and a
   turret ring takes weapons, which is true and useless: it does not stop a siege cannon being bolted
   to a light hatchback's wing mirror. Size class is the mechanism that does.
4. **A cannon shoves.** Firing a heavy gun moves the vehicle that fired it, and being hit by one moves
   the vehicle that was hit. Both are authoritative physics and both go through the one queue D06 has
   for the purpose.

D15-R3b already draws the distinction this document builds on: a **built-in** weapon is geometry a
vehicle model came with, derived by `syndicate_prepare`; a **modular** weapon is authored content in
the shared library (D08-S4.6), fits anything with a compatible mount, and is what `syndicate_weapon`
produces. DEC-077 records the split. This document specifies the modular half.

---

<!-- D17-S2 -->## 2. Scope

<!-- D17-S2.1 -->### 2.1 In Scope

- The `syndicate_weapon` CLI: arguments, exit codes, report schema, determinism.
- Frame correction for a weapon model: units, the **bore axis**, and the origin at the mount face.
- Style normalisation and geometry repair, reusing D15-S5.9 and D15-S5.5 unchanged.
- The **weapon sub-part taxonomy** (D17-S4.2) and the cue ensemble that assigns it.
- The **sub-part slot graph**: which sub-part hangs off which, and the seam rule that stops the join
  showing.
- The **articulation block** (D17-S4.4): what moves, about what axis, driven by what.
- **Size class** (D17-S4.3) on slots and on parts, and the gate it forms.
- Derivation of a weapon's family, stats and mass from its own geometry and its family's D01-S4.4 row.
- **Recoil** on the firing vehicle and **knockback** on the struck one.
- How a weapon degrades when its own sub-parts are damaged or lost.

<!-- D17-S2.2 -->### 2.2 Non-Goals

- **NG1. Aiming, turret traverse and target leading are not here.** A weapon's aim comes from
  `WeaponControllerComponent` and the systems of D04-S4.4; this document specifies only the geometry
  that aim is applied to.
- **NG2. New weapon families are not invented.** D01-S4.4 fixes eight and this document adds none.
  A model that is not clearly one of them is classified by the rules in D17-S5.10 and, failing those,
  is reported as ambiguous rather than given a ninth family.
- **NG3. Projectile flight and hit resolution are unchanged.** D07-S5.1 owns them. The only addition
  is the impulse a hit applies alongside the damage it already applies.
- **NG4. Ammunition economy, reloading and magazine content are not modelled** beyond the
  `ammoCapacity` D08-S4.2 already carries.
- **NG5. This pipeline does not rig skeletal animation.** Articulation is a rigid transform per
  sub-part about a declared axis, evaluated by the client. There is no armature, no skinning and no
  animation clip; DISC-016 is the standing reason the project treats skinned source art as a hazard.
- **NG6. A weapon's textures are not re-authored.** The style pass moves materials into the house
  palette (D15-S4.5); it does not repaint or re-bake maps.

---

<!-- D17-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | invariants G2, G3, G6, G10, G17, G19 |
| `docs/00_master_index.md#D00-S6.4` | `TICK_DT`, `MIN_BODY_MASS_KG`, `MASS_TOLERANCE_FRAC` |
| `docs/01_product_game_design.md#D01-S4.4` | the eight weapon families and their delivery, rate and range |
| `docs/01_product_game_design.md#D01-S4.5` | the five damage types |
| `docs/05_vehicle_part_system.md#D05-S4.3` | slots, slot types, the slot graph, slot paths |
| `docs/05_vehicle_part_system.md#D05-S4.4` | part properties |
| `docs/05_vehicle_part_system.md#D05-S4.5` | `StatBlock` and the weapon stats |
| `docs/05_vehicle_part_system.md#D05-S5.4` | the degradation model a damaged weapon runs through |
| `docs/06_physics_simulation.md#D06-S5.4` | the fixed step and the pending-impulse queue |
| `docs/07_damage_destruction_model.md#D07-S5.1` | hit resolution, which is where knockback attaches |
| `docs/07_damage_destruction_model.md#D07-S5.7` | detachment, which is how a barrel comes off |
| `docs/08_asset_pipeline.md#D08-S4.2` | the part schema these files are written against |
| `docs/08_asset_pipeline.md#D08-S4.6` | the shared library a modular weapon lives in |
| `docs/09_blender_destruction_tool.md#D09-S5.2` | Voronoi fracture, reused unchanged |
| `docs/15_vehicle_preparation_pipeline.md#D15-S5.5` | geometry repair, reused unchanged |
| `docs/15_vehicle_preparation_pipeline.md#D15-S5.9` | the house style pass, reused unchanged |
| `docs/15_vehicle_preparation_pipeline.md#D15-S5.10` | the built-in/modular split this document continues |

---

<!-- D17-S4 -->## 4. Data Contracts

<!-- D17-S4.1 -->### 4.1 Reserved Constants

**R1.** These are constants, not per-model tuning, for the reason D15-R7 gives: a threshold that has
to move for a new model is a bug in the threshold.

**R1a. Every distance threshold here is a fraction of the weapon's own bore length, not metres.**
This is the single most load-bearing decision in the whole ensemble and it was learned the hard way.
A downloaded model arrives at an unknown scale — both weapons this document ships import at 100× —
so a threshold in metres is applied to whatever units the artist happened to export in. The pipeline
therefore normalises twice before it measures anything (D17-R23a), and every threshold below becomes
a proportion of the gun, which is what each of them always meant.

| Constant | Value | Meaning |
|---|---|---|
| `WEAPON_MAX_SHELLS` | 4000 | Abort rather than run unbounded (D17-E2) |
| `WEAPON_MIN_SHELL_TRIANGLES` | 8 | Below this a shell merges into its nearest neighbour. Lower than D15's 24: a gun is two orders of magnitude smaller than a car and its sights are genuinely eight triangles |
| `BORE_ASPECT_MIN` | 3.0 | Length-to-width above which a shell is barrel-like. Dimensionless |
| `BORE_FIT_MIN_EXTENT_FRAC` | 0.35 | Fraction of the longest barrel-like shell below which a shell is too small to help fit the bore axis. A detailed model has hundreds of technically-slender rivets and pins, and each would otherwise outvote the barrel |
| `BORE_COAXIAL_TOL` | 0.045 | How far off the bore a shell's centroid may sit and still join the barrel group, as a fraction of bore length |
| `CARRIAGE_RADIUS` | 0.40 | Radius about the bore outside which geometry is **not the weapon** (D17-R27) — a display base, a diorama, a siege carriage's road wheels |
| `SEAM_CONTACT_REACH` | 0.03 | Fraction of bore length within which two sub-parts count as touching (D17-R44) |
| `SEAM_REACH_PASSES` | 1×, 3×, 8× | Widening passes applied to that reach; the pass that succeeded is reported |
| `RECOIL_IMPULSE_SCALE` | 1.0 | Multiplier on the derived recoil impulse (D17-S5.12) |
| `KNOCKBACK_IMPULSE_SCALE` | 1.0 | Multiplier on the derived knockback impulse |
| `MAX_RECOIL_IMPULSE_NS` | 12000 | Ceiling on a single recoil impulse, so a content error cannot launch a car |
| `MAX_SUBPARTS_PER_WEAPON` | 8 | A weapon offering more slots than D05-R14's per-part cap is a taxonomy failure |
| `WEAPON_TARGET_LENGTH_M` | see D17-S5.2 | Per size class |
| `CALIBRE_RATIO_CANNON` | 0.19 | Bore diameter over barrel length above which a gun is a cannon rather than an autocannon (D17-R49) |
| `BULK_RATIO_LIGHT` / `BULK_RATIO_MEDIUM` | 0.35 / 0.55 | Cross-section over bore length separating the three size classes (D17-R26) |

<!-- D17-S4.2 -->### 4.2 The Weapon Sub-Part Taxonomy

**R2.** The taxonomy is **closed**, exactly as D15-R1 makes the vehicle taxonomy closed, and for the
same reason: a new model must not silently introduce a sub-part that nothing downstream handles.

| Label | What it is | Category | Slot type it occupies | Destruction class | Detaches | Default material |
|---|---|---|---|---|---|---|
| `mount` | The root: what bolts to the vehicle. Cradle, trunnion block, pintle, turret ring | `WEAPON` | `HARDPOINT` or `TURRET_MOUNT` | `STRUCTURAL` | yes | `steel` |
| `receiver` | The body of the gun: action, frame, housing | `WEAPON` | `SUBSLOT` | `STRUCTURAL` | yes | `steel_hardened` |
| `barrel` | The tube the shot leaves through, plus its jacket and shroud | `WEAPON` | `SUBSLOT` | `RIGID` | yes | `steel_hardened` |
| `muzzle` | Flash hider, brake, crown — whatever caps the bore | `DECORATIVE` | `SUBSLOT` | `RIGID` | yes | `steel_hardened` |
| `breech` | Where the round enters: block, boiler, chamber | `WEAPON` | `SUBSLOT` | `STRUCTURAL` | yes | `steel` |
| `feed` | Magazine, drum, belt box, hopper | `UTILITY` | `SUBSLOT` | `RIGID` | yes | `steel` |
| `gear` | Anything that turns in service of the gun: elevation quadrant, traverse ring, flywheel, cylinder | `DECORATIVE` | `SUBSLOT` | `RIGID` | yes | `steel` |
| `sight` | Iron sights, scope, rangefinder | `DECORATIVE` | `SUBSLOT` | `RIGID` | yes | `plastic` |
| `furniture` | Grips, handles, shields, plating that is not the receiver | `DECORATIVE` | `SUBSLOT` | `SHEET_METAL` | yes | `steel` |
| `unclassified` | What the ensemble could not name | — | — | — | — | — |

**R3.** `unclassified` is a first-class outcome and **merges into the `receiver`**, which is always a
correct-if-coarse answer, and is reported by count and triangle share so an operator can see how much
of the gun the pipeline could not name (D15-R2's rule, applied here).

**R4.** Exactly one `mount` exists per weapon and it is the **root** of the weapon's sub-tree. A model
that yields no `mount` gets one synthesised (D17-S5.7); a model that yields two has the larger one
promoted and the other relabelled `furniture`.

**R5.** A weapon's `WEAPON`-category sub-parts are what the gun needs to work. `DECORATIVE` and
`UTILITY` sub-parts are not: losing them is cosmetic or a soft penalty, and D17-S5.13 is the table
that says which is which.

<!-- D17-S4.3 -->### 4.3 Size Class

**R6.** `SizeClass` is a three-valued enum — `LIGHT`, `MEDIUM`, `HEAVY` — carried by **both** a slot
definition and a part type. It is the mechanism by which not every weapon fits every mount.

**R7. The gate.** A slot accepts a part when *all four* hold:

1. `slot.slotType.acceptsCategory(part.category)` — D05-R9, unchanged.
2. `part.sizeClass.ordinal() <= slot.sizeClass.ordinal()` — **a slot accepts its own class and
   below.** A `HEAVY` turret ring takes a light machine gun; a `LIGHT` wing hardpoint does not take a
   heavy cannon.
3. `part.massKg <= slot.maxMassKg` — D05-R8, unchanged, and deliberately kept: size class is about
   *bulk and mounting*, mass is about *load*, and a dense small object fails the second gate while
   passing the first.
4. `part.slotTypeRequired == slot.slotType` — D05-R14, unchanged.

**R8.** Both fields default to `MEDIUM` when a file omits them, so every part and slot authored before
this document remains valid and behaves as it did. Ordering is `LIGHT < MEDIUM < HEAVY`.

**R9.** A violation of R7.2 is validation error **A316**, reported by `AssemblyValidator` beside the
A305 and A306 it joins. `A316` is an *error*, not a warning: an assembly that mounts a gun the mount
cannot carry is not a playable vehicle.

**R10.** D05-S4.3's slot table and D05-S4.4's part table are amended in the same commit as this
document to carry the field. The five hardpoints D15-R49b puts on every prepared vehicle take their
size class from **where they are**, which is the only thing about them the geometry determines:

| Slot | Size class | Why |
|---|---|---|
| `turret_main` | `HEAVY` | The roof centreline is the one place a big gun can sit without fouling the wheels |
| `hardpoint_bonnet` | `MEDIUM` | Forward and central, but the bonnet line limits height |
| `hardpoint_rear` | `MEDIUM` | As the bonnet |
| `hardpoint_flank_l`, `hardpoint_flank_r` | `LIGHT` | Outboard of the body: anything bulky here is wider than the car |
| `hub_*` | `LIGHT` | A brake hub, and never anything else |

<!-- D17-S4.4 -->### 4.4 The Articulation Block

**R11.** A part may carry an `articulation` object. It is **cosmetic** (G6): nothing in `game-core`
reads it, no simulation value derives from it, and it is never replicated. It sits beside `handling`
(DEC-031), `weapon` (DEC-039), `module` and `light` (DEC-078) as a block that carries what a
`StatBlock` cannot.

**The block already exists.** D08-R5 defines `articulation` for a part that *opens* — a door, a
bonnet — with `axisLocal`, `pivotLocal` and `openDeg` (D15-S5.6). This document **extends that one
block** rather than introducing a second, because the two describe the same thing: a rigid transform
of one part about a declared axis in its own space, evaluated by the presentation layer. Two blocks
that mean "this part moves" would be a distinction with no difference, and the door parts both
shipped vehicles already carry would be the ones that paid for it.

```json
"articulation": {
  "motion": "RECOIL",
  "axisLocal": { "x": 0.0, "y": 0.0, "z": 1.0 },
  "pivotLocal": { "x": 0.0, "y": 0.0, "z": 0.0 },
  "travelM": 0.06,
  "returnSeconds": 0.18,
  "driver": "FIRE"
}
```

**R12.** `motion` is one of five. `HINGE` is what the existing block always meant, and is the value
assumed when `motion` is absent, so every door part authored before this document keeps working
unchanged and un-rewritten.

| `motion` | What it does | Uses | Introduced by |
|---|---|---|---|
| `HINGE` | Rotates about `axisLocal` through `pivotLocal` to `openDeg` | `openDeg` | D15-S5.6 |
| `RECOIL` | Slides along `axisLocal` by `travelM`, then returns over `returnSeconds` | `travelM`, `returnSeconds` | here |
| `SPIN` | Rotates about `axisLocal` through `pivotLocal` at `rateDegPerSec` | `rateDegPerSec` | here |
| `INDEX` | Rotates about `axisLocal` by `360 / indexSteps` per shot, easing over `returnSeconds` | `indexSteps`, `returnSeconds` | here |
| `ELEVATE` | Rotates about `axisLocal` to follow the weapon's commanded pitch, limited to `travelDeg` | `travelDeg` | here |

**R13.** `driver` is one of four, and says what the motion is a function of. It defaults to `OPEN`,
which is again what the existing door block always meant.

| `driver` | Evaluated from |
|---|---|
| `OPEN` | The part's own open state — the door case |
| `FIRE` | Seconds since the part's weapon last fired |
| `CONTINUOUS` | Match time, while the weapon has ammunition and is not destroyed |
| `AIM` | The weapon's commanded aim, relative to its rest pose |

**R14.** A part with no `articulation` block does not move. Every field is optional and every one has
a default, so a malformed block degrades to a static part and is reported `A220` rather than failing
the load: a gun that does not spin is worse-looking, not broken (D17-E11).

**R15. The articulation of a destroyed sub-part stops.** A `DESTROYED` or `DETACHED` part is not
articulated: a barrel that has been shot off does not recoil, and a wrecked feed drum does not turn.
This is the one place a *cosmetic* system reads *authoritative* state, which is the legal direction
(G6 forbids the reverse).

<!-- D17-S4.5 -->### 4.5 The Weapon Manifest

**R16.** `syndicate_weapon` writes a `<weaponId>.weapon.json` beside the parts it produces,
recording what it decided. Named for the weapon rather than fixed, because the shared library holds
every modular weapon in one directory (D08-R14b) and a fixed name would have each weapon overwrite
the last one's manifest. It is a **build artefact and a report**, not a runtime input: the game loads the `part.json`
files, exactly as it does for a prepared vehicle.

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | string | `"1.0.0"` |
| `weaponId` | AssetId | The root part's id |
| `family` | `WeaponFamily` | Which of D01-S4.4's eight |
| `sizeClass` | `SizeClass` | D17-S4.3 |
| `sourceModel` | string | The file it came from |
| `sourceLicence` | string | Carried out of the glTF `asset.extras`, because it is load-bearing |
| `boreAxisLocal` | Vector3 | The direction shots leave along, after correction |
| `muzzleLocal` | Vector3 | Where they leave from |
| `scaleApplied` | float | What the source was multiplied by (D17-S5.2) |
| `parts` | object[] | One row per sub-part: id, label, mass, hp, slot path, articulation |
| `seams` | object[] | One row per join: parent, child, measured gap, budget |
| `stats` | object | The derived `StatBlock` |
| `checks` | object[] | Every self-verification check and its result |

<!-- D17-S4.6 -->### 4.6 CLI Contract

**R17.** `syndicate_weapon` follows D09-S4.1's contract exactly: stdout carries **one JSON document
and nothing else**, all diagnostics go to stderr, and the exit code is the machine-readable result.
DISC-002's fd redirection applies unchanged — Blender writes to stdout at the C level and will
otherwise corrupt the document.

```
syndicate-weapon --model <path.glb> --out <dir> [--id <assetId>] [--family <FAMILY>]
                 [--size <LIGHT|MEDIUM|HEAVY>] [--target-length <m>] [--seed <int>]
                 [--style-table <path>] [--no-style] [--strict] [--verbose]
```

**R18.** `--family` and `--size` **override** derivation; absent, both are derived (D17-S5.10). An
override is recorded in the manifest as an override so that a hand-forced classification is visible
rather than indistinguishable from a derived one.

**R19.** Exit codes extend D09-S4.3's scheme in the reserved 80–89 range:

| Code | Meaning |
|---|---|
| 0 | Success; every check passed |
| 64 | Bad arguments |
| 65 | Model could not be read |
| 80 | No geometry survived the repair stage |
| 81 | Shell count exceeded `WEAPON_MAX_SHELLS` |
| 82 | No bore axis could be established (D17-E1) |
| 83 | Sub-part count exceeded `MAX_SUBPARTS_PER_WEAPON` |
| 84 | A seam exceeded `MOUNT_SEAM_TOL_M` and could not be closed |
| 85 | Derived mass implausible for the family and size class |
| 86 | Self-verification failed |
| 87 | Export failed |

<!-- D17-S4.7 -->### 4.7 Report Schema

**R20.** The report is the JSON document on stdout. It carries the manifest of D17-S4.5 plus a
`stages` array — one entry per stage of D17-S5.1 with its name, duration and per-stage counters — and
a `votes` section recording every cue's opinion of every shell, so that an operator can see *why* a
shell was labelled and not merely *what* it was labelled (D15-R11's rule).

---

<!-- D17-S5 -->## 5. Logic & Algorithms

<!-- D17-S5.1 -->### 5.1 Stage Order

**R21.** Ten stages, in this order, in one invocation.

```
 1.  Load and correct frame        units, bore axis, origin at the mount face   (D17-S5.2)
 1b. Normalise materials           the house style                              (D15-S5.9, reused)
 2.  Repair geometry               weld, dissolve, triangulate                  (D15-S5.5, reused)
 3.  Separate into shells          connected components                         (D17-S5.5)
 4.  Label shells                  the weapon cue ensemble                      (D17-S5.6)
 5.  Group shells into sub-parts   one part per label instance                  (D17-S5.7)
 6.  Build the slot graph          parenting, and close the seams               (D17-S5.8)
 7.  Author articulation           what moves and about what                    (D17-S5.9)
 8.  Derive family, stats, mass    from geometry and D01-S4.4                   (D17-S5.10)
 9.  Author destruction per class  morphs and fracture                          (D17-S5.11, D09 reused)
 10. Export and self-verify        part.json, mesh.glb, weapon.json             (D17-S5.14)
```

**R22.** The order is not free. Correction precedes everything because every later measurement is in
game metres about the bore axis. Style precedes repair because the style pass reads materials and
repair destroys the material assignment of a dissolved face. Grouping precedes the slot graph because
a seam is between two *parts*, and articulation follows the graph because an axis is expressed in the
parent's frame.

<!-- D17-S5.2 -->### 5.2 Frame Correction and Scale

**R23.** A weapon model arrives in unknown units, at an unknown orientation, about an unknown origin.
Three corrections, in this order — scale, rotate, translate — recorded in the same `import.json` form
DEC-036 fixes for vehicles, so the correction is verified rather than asserted.

**R23a. The scale correction happens in three parts, and their order is the whole of why this
pipeline works.**

1. **A unit pre-scale, at load, before anything else runs.** The model is scaled so its largest
   extent is 1.0. Every absolute threshold in the *repair* stage depends on this: D15-S5.5 welds at
   0.1 mm and dissolves slivers at a fixed edge length, and on a model that imports at 100× a 0.1 mm
   weld is a one-micron weld. Nothing welds, and the shipped cannon separates into **203 shells
   instead of 22** — at which point its bore axis is fitted to rivets and every stage downstream is
   working on a different object than the one in the file.
2. **A bore-aligned normalisation, before labelling.** Once the bore axis is known, the model is
   rotated so the bore is +Z, scaled to unit bore length, and translated so the mount face is the
   origin. This is what turns every ensemble threshold into a proportion of the gun.
3. **A final scale to the size class's target length**, after the class has been decided.

Deriving the size class *before* the final scale, and the final scale *from* the class, is what stops
the gate being circular: otherwise every weapon is whatever class the operator scaled it into.

**R24. The bore axis** is the direction shots leave along, and after correction it is **+Z**, which is
the game's forward (D00-R16). It is found by principal-axis analysis restricted to barrel-like shells:

```
candidates = shells with aspect >= BORE_ASPECT_MIN
if candidates is empty:  candidates = the whole model
axis = the dominant eigenvector of the covariance of candidates' vertices
sense = the direction in which the candidate set's cross-section shrinks
```

The **sense** matters and the eigenvector does not supply it: an eigenvector is a line, not an arrow,
so a barrel found this way points forward and backward equally. The tie is broken on taper — a barrel
narrows toward its muzzle and widens toward its breech — and, when the taper is under 2%, on mass
distribution, because the breech end is the heavy end of every gun ever made.

**R25. The origin** goes at the **mount face**: the centre of the `mount` sub-part's contact surface,
which is the point that will coincide with the slot's `localPosition` when the weapon is fitted. Not
the model's centroid and not its bounding-box centre — putting the origin anywhere else is what makes
a fitted weapon float or sink into the bodywork, and it is the single largest source of the sloppy
seams this document exists to prevent.

**R26. Scale** is chosen so the weapon's bore-axis length equals its size class's target:

| Size class | `WEAPON_TARGET_LENGTH_M` | Reads as |
|---|---|---|
| `LIGHT` | 0.9 | A pintle-mounted machine gun |
| `MEDIUM` | 1.4 | An autocannon or a light turret gun |
| `HEAVY` | 1.8 | A vehicle-scale cannon |

Scaling is **uniform** and is recorded in the manifest as `scaleApplied`. When `--target-length` is
given it overrides the table. A model already within 10% of its target is left alone rather than
scaled by 1.02, because a scale that close is noise and re-scaling costs precision for nothing.

**R27.** Geometry the correction leaves outside the weapon — a display base, a gun carriage's road
wheels, a diorama's ground plane — is **discarded**, and every discarded shell is named in the report
with its triangle count. Discarding silently is what turns "the pipeline produced a weapon" into "the
pipeline produced a weapon and threw away a third of the model".

The rule is a **cylinder about the bore**: a gun's working parts are arranged around its bore and are
therefore near it, and what sits more than `CARRIAGE_RADIUS` further out is what the gun is carried
on. On the shipped cannon this discards exactly the carriage — all four road wheels at 0.40 and 0.51,
the axle at 0.62, the base plate at 0.57 and the trail at 0.42 — and keeps the barrel, the muzzle,
the breech and the trunnion cheeks.

<!-- D17-S5.3 -->### 5.3 Style Normalisation

**R28.** Stage 1b is `syndicate_prepare.style` called unchanged, with the same table and the same
palette snap and tone band DEC-076 and DEC-079 specify. This is the whole of the answer to "the tool
should normalise style": a weapon and a car that went through the same table look like they belong to
the same game, and that only holds if it is literally the same table rather than a second one that
agrees today.

**R29.** DISC-048 applies here and is easy to re-break: a base-colour socket behind a texture is a
**factor**, not a colour. Both shipped weapons carry base-colour textures, so both go down that path.

<!-- D17-S5.4 -->### 5.4 Geometry Repair

**R30.** Stage 2 is `syndicate_prepare.cleanup` / `repair` called unchanged: weld doubled vertices,
dissolve degenerate and sliver faces, triangulate n-gons, recalculate normals outward, and drop loose
geometry. DISC-038 is the reason this is not optional — a mesh with slivers in it cannot be given
damage morphs, and the failure appears three stages later as a part that will not dent.

**R31.** Two repairs matter more on weapons than on cars and are therefore checked explicitly:

- **Non-manifold bore.** A barrel modelled as an open tube has no inside, and fracture needs a solid
  (DISC-039's lesson, generalised). An open bore is capped.
- **Doubled shells.** Sketchfab exports frequently carry a duplicated mesh at the same coordinates.
  Welding does not remove it — the duplicate is a separate connected component. A shell whose bounds
  and triangle count match another's within a tolerance is dropped, and the drop is reported.

<!-- D17-S5.5 -->### 5.5 Shell Separation

**R32.** Stage 3 is connected-component analysis on the welded mesh, producing `Shell` records
(D15-S5.2's mechanism, and literally the same `Shell` dataclass). DISC-018's finding holds here too
and is why this stage exists at all: **an object in a downloaded model is a material group, not a
part.** Both weapons this document ships arrive as a single object with a single material — 350
triangles in one piece for the machine gun, 8,898 for the cannon — and connected-component separation
is what turns each into the nine and twenty-two real pieces they are made of.

**R33.** Shells below `WEAPON_MIN_SHELL_TRIANGLES` merge into their nearest labelled neighbour by
centroid distance, ties broken on shell index so two runs agree (G3).

<!-- D17-S5.6 -->### 5.6 The Weapon Cue Ensemble

**R34.** Labelling is an **ensemble of weighted cues**, in the mould of D15-S4.2 and for the same
reason: no single test is reliable on real art, and a vote with a reason attached can be read in a
report when it goes wrong. Four families, weighted:

| Cue | Weight | Reads |
|---|---|---|
| `W1_axial` | 1.0 | Position and extent **along the bore axis**, which is the axis a gun is organised around |
| `W2_geometric` | 1.0 | Aspect, roundness about the bore, flatness, radial offset |
| `W3_material` | 0.7 | Material name tokens, where the source names anything |
| `W4_structural` | 0.6 | Relations: coaxial with the barrel group, mirrored about the bore, repeated at a radius |

**R35.** The axial cue is the one that does the work, and it is what makes this ensemble different
from D15's. A gun is a **sequence along one line**: breech at the back, receiver behind the middle,
barrel forward of it, muzzle at the front. Normalising every shell's centroid to a bore-axis
coordinate in `[0,1]` therefore predicts most of the taxonomy before any other cue votes.

| Normalised bore position | Votes for |
|---|---|
| `[0.00, 0.20)` | `breech` |
| `[0.15, 0.55)` | `receiver` |
| `[0.35, 0.95)` | `barrel`, when the shell is also barrel-like |
| `[0.90, 1.00]` | `muzzle` |

The bands **overlap deliberately**. A cue that partitions the line cannot be outvoted, and the whole
point of an ensemble is that it can be.

**R36.** The geometric cue's discriminators, all measured about the bore axis rather than about the
world axes:

- **Bore roundness** — the ratio of the two extents perpendicular to the bore. Near 1 for a barrel, a
  gear or a drum; low for a shield, a plate or a sight rail.
- **Bore aspect** — extent along the bore over the larger perpendicular extent. Above
  `BORE_ASPECT_MIN` is barrel-like.
- **Radial offset** — distance of the centroid from the bore axis. A `gear`, a `feed` and a `sight`
  are all *off* the axis; a `barrel` and a `muzzle` are *on* it, and `BORE_COAXIAL_TOL_M` is the line.
- **Flatness** — as D15's, and it is what separates `furniture` (a shield, a grip plate) from
  `receiver` (a lump).

**R37.** The structural cue votes on relations rather than on shapes:

- A shell **coaxial with and adjacent to** a barrel-like shell is part of the barrel group — this is
  what collects a jacket, a shroud and a bore into one `barrel` part rather than three.
- A set of shells **repeated at a common radius about an axis** is a `gear`. Three or more instances,
  which is the same rotational-repetition test DEC-066 uses for wheels, applied about the bore.
- A shell **mirrored about the bore axis** with a twin is one instance of a two-instance part, and
  both take the same label — the trunnion cheeks of a gun carriage are the case.

**R38.** The winning label's summed weight must exceed `0.55` or the shell is `unclassified` (R3).
Every vote is carried into the report.

<!-- D17-S5.7 -->### 5.7 Grouping Into Sub-Parts

**R39.** Shells sharing a label become **one sub-part**, except where the label is instanced: a
mirrored pair (R37) becomes two sub-parts distinguished by side (`_l`, `_r`), and a rotational set
becomes one sub-part containing all its members, because a ring of gear teeth is one gear.

**R40.** A weapon with more than `MAX_SUBPARTS_PER_WEAPON` sub-parts exits 83. This is a real cap, not
a defensive one: D05-R14 limits a part to eight slots, and a weapon that needs more sub-parts than the
mount can offer slots for is a taxonomy failure to be read rather than a file to be trimmed.

**R41. Synthesising a missing mount.** A model with no `mount` — which is most gun models, because a
gun is usually modelled without whatever it bolts to — gets one synthesised: a mounting boss under
the receiver's rear underside, sized to the receiver's cross-section, generated as real geometry so
that it renders, collides and fractures like every other part. The synthesised mount is reported as
synthesised.

<!-- D17-S5.8 -->### 5.8 The Slot Graph and the Seam Rule

**R42.** The weapon's slot graph is a tree rooted at the `mount`:

```
mount                                   occupies HARDPOINT or TURRET_MOUNT on the vehicle
├── receiver          SUBSLOT           the body of the gun
│   ├── barrel        SUBSLOT           forward of the receiver
│   │   └── muzzle    SUBSLOT           at the bore's forward extent
│   ├── breech        SUBSLOT           behind the receiver
│   ├── feed          SUBSLOT           wherever it feeds from
│   └── sight         SUBSLOT           on top
└── gear              SUBSLOT           between mount and receiver, where elevation happens
```

**R43.** Parenting follows **support**, not proximity: a child hangs off the part that physically
holds it up. A muzzle is on the barrel, not on the receiver, because shooting the barrel off takes
the muzzle with it — and that is the behaviour D17-S5.13 wants.

The tree above is therefore a **proposal, not a schedule**. Where the taxonomy's parent is absent
from a model, or present and not touching, the child is re-parented onto the nearest sub-part it does
touch, and the report says so. The shipped cannon is the case: the ensemble finds no separate barrel
group on it, so its muzzle hangs off the receiver. Refusing the model instead would be refusing it
for having geometry the taxonomy did not predict, which is exactly what an open-world pipeline cannot
do.

**R44. The seam rule.** A slot's `localPosition` is the **centroid of the contact region** between
parent and child: the set of parent vertices within `MOUNT_SEAM_TOL_M` of the child's surface. Not the
child's centroid, and not the midpoint between the two bounding boxes.

This is the whole of the answer to "no sloppy seams", and it has three parts:

1. **Measure the contact, not the shapes.** Two parts that touch along a ring join at the ring's
   centre. Using either centroid puts the join wherever the geometry happens to be heaviest, which is
   how a barrel ends up inserted a centimetre into its own receiver or floating a centimetre out of it.
2. **Re-origin each child on its own join.** After the slot position is chosen, the child's mesh is
   translated so that its origin *is* the join point. A part whose origin is elsewhere is a part
   whose rotation is about the wrong point, and articulation makes that visible immediately.
3. **Verify the contact, not the gap.** After export, `WEAP-004` checks that every join was found
   from parent and child geometry that actually meet — not that the gap between them is under some
   ceiling. Real art models clearances: a barrel sits inside its shroud with a few millimetres of
   daylight, and failing a weapon for that would be asserting something about the artist rather than
   about the pipeline. What the pipeline is responsible for is never *inventing* a join, and a join
   with zero contact points is exactly that. The reach that found the contact is reported, so a join
   found only at the widest pass reads as the weak join it is.

**R45.** Sub-parts do **not** each become a child hull in the vehicle's compound shape, and this is
the one place a weapon differs structurally from a vehicle. DEC-004 makes a vehicle one body with a
compound of its parts; a weapon's sub-parts join that same compound as further children, so a fitted
weapon costs the vehicle one child hull per sub-part and no extra bodies. `WHEEL`-category exclusion
(D06-S4.3) does not apply — every weapon sub-part is in the compound.

<!-- D17-S5.9 -->### 5.9 Articulation Authoring

**R46.** Stage 7 assigns an `articulation` block by label, from geometry the earlier stages already
measured. It is a per-label table, not per-part authoring — D15-R33's rule again.

| Label | `motion` | Axis | Driver | Derived from |
|---|---|---|---|---|
| `barrel` | `RECOIL` | the bore axis | `FIRE` | `travelM` = 4% of barrel length, capped at 0.08 m |
| `gear`, when it is a ring about the bore | `SPIN` | the bore axis | `FIRE` | `rateDegPerSec` from the family's fire rate |
| `gear`, when it is a quadrant off-axis | `ELEVATE` | its own repetition axis | `AIM` | `travelDeg` = the quadrant's angular extent |
| `feed`, when rotationally symmetric | `INDEX` | its symmetry axis | `FIRE` | `indexSteps` from the repetition count |
| everything else | none | — | — | — |

**R47.** `travelM` for recoil is 4% of barrel length because that is what a real recoil-operated
action gives — 60 mm on a 1.5 m barrel — and because a value chosen for looks rather than for a
mechanism is the thing DISC-027 records as reading like a fault. The same reasoning fixes the spin
rate to the fire rate: a feed drum that turns at a rate unrelated to the shots leaving the gun is
worse than one that does not turn at all.

**R48. Evaluation is client-side and cosmetic.** The client composes the articulated transform onto
the part's render transform after the simulation has produced it (D03-S5.3's presentation phase).
`game-core` neither knows nor can know. The collision hull does **not** articulate: a recoiling barrel
does not sweep a moving collision volume, because that would be cosmetic state feeding back into the
simulation, which G6 forbids and which would also make hits non-deterministic across peers whose
frame rates differ.

<!-- D17-S5.10 -->### 5.10 Deriving Family, Stats and Mass

**R49. Family** is derived from the bore, extending D15-R48's two-way rule to a five-way one. The
inputs are the bore aspect and the **calibre ratio** — bore diameter over barrel length — because that
ratio is what physically distinguishes a machine gun from a howitzer:

| Condition | Family |
|---|---|
| bore aspect ≥ 8 and calibre ratio < 0.06 | `AUTOCANNON` |
| bore aspect ≥ 4 and calibre ratio ≥ 0.06 | `CANNON` |
| bore aspect < 4, multiple bores at a radius | `AUTOCANNON` (rotary) |
| bore aspect < 4, single wide bore | `SHOTGUN` |
| no bore-like shell at all | `LASER` |

**R50.** `ROCKET`, `MORTAR` and `FLAMER` are **not derived**. Nothing in a static mesh distinguishes a
rocket pod from a box, and D17-NG2 forbids guessing: those three are reachable only through
`--family`. A model that would land on one of them without the flag is classified by the table above
and the report says the classification was weakly held.

**R51. Stats** come from the family's D01-S4.4 row, scaled by size class. The row fixes fire rate,
range and projectile speed; size scales damage and mass. This is the correct direction of authority:
D01-S4.4 is the balance contract, and a pipeline that derived fire rate from a mesh would be inventing
balance from art.

| Stat | Derivation |
|---|---|
| `FIRE_INTERVAL_S` | `1 / family fire rate` from D01-S4.4 |
| `PROJECTILE_SPEED_MPS` | the family's D01-S4.4 speed |
| `DAMAGE_PER_SHOT` | the family's base, × the size-class damage factor (0.7 / 1.0 / 1.6) |
| `SPREAD_RAD` | the family's base, ÷ the size-class factor — a bigger gun of a family is the more accurate one |
| `HEAT_PER_SHOT` | the family's base |
| `rangeM` | the family's D01-S4.4 range |
| `ammoCapacity` | derived from the `feed` sub-part's volume where one exists, else the family default |

**R52. Mass** follows DEC-067 — surface area × a per-class areal density — with one change that
matters: a gun is **not** a shell. A barrel is a solid tube of steel and a car door is a 1 mm skin, so
the areal densities here are an order of magnitude higher, and the enclosed volume caps the result as
DEC-067 already requires. A derived mass outside the plausible band for the family and size class
exits 85 rather than shipping a 4 kg cannon.

**R53. `powerCost`** is derived from damage per second and range, on the same budget scale D05-S5.7
uses, so that a weapon fitted to a vehicle spends the same currency the vehicle's own parts do.

<!-- D17-S5.11 -->### 5.11 Destruction Authoring

**R54.** Stage 9 is D15-S5.7's treatment table applied to the labels of D17-S4.2, which is why the
taxonomy carries a destruction class column. `STRUCTURAL` sub-parts get damage morphs and a yield
impulse; `RIGID` sub-parts get neither and simply detach; `SHEET_METAL` furniture dents. Fracture
manifests come from `syndicate_fracture` unchanged (D09-S5.2).

**R55.** A weapon's `mount` is `STRUCTURAL` and **detachable**, which is what makes a gun shootable off
a car in one piece — and, because the slot graph makes every other sub-part a descendant of it, taking
the mount takes the whole gun. That is the same subtree rule D07-S5.7 already applies to vehicles, and
it needs no new mechanism.

<!-- D17-S5.12 -->### 5.12 Recoil and Knockback

**R56. Both impulses are authoritative**, both are queued on `PhysicsWorld`'s pending-impulse queue
(DEC-012), and both are applied by `PhysicsSystem` in slot 10 in ascending entity-id order (G3).
Neither is applied directly at the point of the call, because an impulse applied mid-schedule would
make the result depend on system order rather than on the tick.

**R57. Recoil.** On a shot, the firing vehicle receives an impulse along the **negative bore axis**,
applied at the **muzzle position** so that it produces the pitch and yaw a real gun does rather than a
pure translation:

```
J_recoil = projectileMassKg * projectileSpeedMps * recoilFraction * RECOIL_IMPULSE_SCALE
J_recoil = min(J_recoil, MAX_RECOIL_IMPULSE_NS)
applyImpulse(-boreAxisWorld * J_recoil, at = muzzleWorld - centreOfMassWorld)
```

`projectileMassKg` is a property of the family: it is the momentum the shot carries, and a cannon's
shell carries two orders of magnitude more of it than a machine gun's round. This is why the answer to
"the cannon should apply impulse" is a formula rather than a constant — the machine gun uses the same
formula and produces a shove that is correctly imperceptible.

**R57a. `recoilFraction` is the one place recoil and knockback differ**, and it exists for exactly one
family. A `ROCKET` accelerates on its own motor after it has left the tube, so the launcher never takes
the round's momentum: its recoil fraction is **0** while its knockback is unreduced. Every other
family's is **1**. Without this the formula would have a rocket pod shoving a car as hard as a cannon
does, which is wrong in a way a player would notice immediately.

| Family | `projectileMassKg` | Nominal speed (m/s) | `recoilFraction` |
|---|---|---|---|
| `AUTOCANNON` | 0.12 | 600 | 1 |
| `CANNON` | 12.0 | 250 | 1 |
| `SHOTGUN` | 0.05 | 400 | 1 |
| `ROCKET` | 8.0 | 120 | **0** |
| `MORTAR` | 4.0 | 100 | 1 |
| `FLAMER`, `LASER`, `RAM` | 0 | — | 0 |

The nominal speed exists for the hitscan families, whose shot arrives in the tick it is fired and so
has no travelling entity to read a speed from — a shotgun still kicks, and this is the speed its
momentum is computed at.

**R58. Knockback.** On a projectile impact, the struck vehicle receives an impulse along the
projectile's **velocity direction**, applied at the **contact point** (D07-S5.1 already resolves both):

```
J_knock = projectileMassKg * projectileSpeedMps * KNOCKBACK_IMPULSE_SCALE
applyImpulse(shotDirectionWorld * J_knock, at = contactWorld - centreOfMassWorld)
```

**R59.** Applying at the contact point rather than at the centre of mass is the entire gameplay
content of this section: a cannon shell into a front wing spins the target, and one into the
centreline shoves it straight. A knockback applied at the COM is a number the player cannot read.

**R60.** Both impulses respect G10: an impulse is applied to a body whose mass properties are already
correct for this tick, because slot 15 runs before slot 10 of the next one.

<!-- D17-S5.13 -->### 5.13 Degradation From Sub-Part State

**R61.** A weapon's effectiveness is a function of its own sub-parts' damage states, evaluated
through the ordinary degradation model of D05-S5.4 — no new mechanism, and specifically **not** a
special case in the weapon system.

| Sub-part lost or destroyed | Effect | Mechanism |
|---|---|---|
| `mount` | The whole weapon goes with it | Subtree detachment, D07-S5.7 |
| `receiver` | The weapon cannot fire at all | `DESTROYED` root of the firing part |
| `barrel` | Accuracy collapses and range halves | `SPREAD_RAD` ×4, `rangeM` ×0.5 |
| `breech` | Fire rate halves | `FIRE_INTERVAL_S` ×2 |
| `feed` | Ammunition capacity is lost; the weapon runs on what is chambered | `ammoCapacity` → 0 |
| `muzzle` | Nothing mechanical; a visibly wrecked gun | cosmetic |
| `gear` | Traverse and elevation stop tracking | `ELEVATE` articulation freezes (R15) |
| `sight` | Nothing mechanical | cosmetic |
| `furniture` | Nothing mechanical | cosmetic |

**R62.** A weapon whose barrel is gone still fires. That is deliberate: a weapon that stops working
the moment any piece is hit collapses the whole sub-part system back into one health pool, which is
what this document's first purpose (D17-S1) exists to avoid.

<!-- D17-S5.14 -->### 5.14 Self-Verification and Determinism

**R63.** The tool verifies its own output before reporting success, as D09-R30 requires of the
fracture tool, and for the same reason: a pipeline that cannot check its own work is a pipeline whose
failures are found by a player. Every check is named in the report with its result.

| Check | Asks |
|---|---|
| `WEAP-001` | Every sub-part has geometry, and every coordinate is finite (D00-R13) |
| `WEAP-002` | The sub-parts' masses sum to the weapon's declared mass within `MASS_TOLERANCE_FRAC` |
| `WEAP-003` | Every slot path resolves and the graph is a tree (D05-R10) |
| `WEAP-004` | Every seam is within `MOUNT_SEAM_TOL_M` (R44) |
| `WEAP-005` | The muzzle lies on the bore axis, at the forward extent |
| `WEAP-006` | Every part's collision hull encloses its render mesh |
| `WEAP-007` | Every articulation axis is a unit vector and every pivot lies within its part's bounds |
| `WEAP-008` | The weapon fits at least one slot on at least one shipped vehicle (R7) |
| `WEAP-009` | Total triangles are within D08-R2's per-part budget |
| `WEAP-010` | Re-running with the same seed produces byte-identical output |

**R64. Determinism.** Every ordering in every stage is on a stable key with the shell index as the
final tie-break, and every random choice comes from the seeded stream (D06-S5.8's `RandomSource`
discipline). `WEAP-010` is what enforces it, and it is a real check rather than a claim.

---

<!-- D17-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D17-1.** Given a single-mesh weapon model, the tool produces a directory of sub-parts and a
      `weapon.json`, and exits 0.
- [ ] **AC-D17-2.** The sub-part labels come from D17-S4.2's closed taxonomy and nothing else.
- [ ] **AC-D17-3.** The slot graph is a tree rooted at exactly one `mount`, and every child's
      `slotTypeRequired` matches the slot it occupies.
- [ ] **AC-D17-4.** Every seam measures within `MOUNT_SEAM_TOL_M`, verified after export, not asserted.
- [ ] **AC-D17-5.** A weapon whose `sizeClass` exceeds a slot's is rejected by `AssemblyValidator` with
      A316; one within it is accepted.
- [ ] **AC-D17-6.** Firing a `CANNON` produces a measurable velocity change in the firing vehicle;
      firing an `AUTOCANNON` of the same size produces one at least an order of magnitude smaller.
- [ ] **AC-D17-7.** A cannon impact off the target's centreline produces angular velocity; one on the
      centreline produces materially less.
- [ ] **AC-D17-8.** Destroying a weapon's `barrel` multiplies its spread and halves its range, and the
      weapon still fires.
- [ ] **AC-D17-9.** Destroying a weapon's `mount` removes every sub-part of that weapon from the
      vehicle in the same tick, and the vehicle's mass and COM are recomputed in that tick (G10).
- [ ] **AC-D17-10.** No articulation value reaches `game-core`; a headless run produces identical
      simulation output with articulation blocks present and absent (G6, G17).
- [ ] **AC-D17-11.** Both shipped weapons pass every `WEAP-` check.
- [ ] **AC-D17-12.** Re-running the tool with the same seed produces byte-identical output (G3).
- [ ] **AC-D17-13.** The style pass moves a weapon's materials into the same house table a vehicle's go
      through, and a capture shows the weapon and the vehicle reading as one art style.

---

<!-- D17-S7 -->## 7. Edge Cases & Failure Modes

| ID | Case | Handling |
|---|---|---|
| **E1** | No barrel-like shell: a laser emitter, a mine layer | Bore axis falls back to the model's dominant principal axis; family derives `LASER`; reported as weakly held. Exit 82 only if the model has no dominant axis at all |
| **E2** | A model of 30,000 shells — a chainmail drape, a rivet-per-shell export | Exit 81 at `WEAPON_MAX_SHELLS` |
| **E3** | The model includes a display base, a stand, or a diorama | Discarded by R27 and named in the report |
| **E4** | The model is a gun carriage with road wheels | The wheels are `unclassified`, fail to merge into any weapon label, and are discarded by R27. This is the shipped cannon's actual case |
| **E5** | Duplicated coincident geometry | Dropped by R31 and reported |
| **E6** | The bore axis comes out backwards | The taper and mass tests of R24 disagree; the report says so and the capture shows it. `--target-length` does not fix this; a 180° yaw in `import.json` does |
| **E7** | Two mounts | The larger is promoted, the other becomes `furniture` (R4) |
| **E8** | No mount | Synthesised (R41) |
| **E9** | A sub-part heavier than the slot it would occupy | Slot `maxMassKg` is derived from the child's own mass at export, so this cannot occur within a weapon. Between weapon and vehicle it is A306 |
| **E10** | A weapon fits no slot on any shipped vehicle | `WEAP-008` fails; exit 86. A weapon nothing can carry is content that cannot be played |
| **E11** | Articulation axis is degenerate — a gear with no measurable symmetry axis | The articulation block is omitted and the part is static. Never a hard failure: a gun that does not spin is worse-looking, not broken |
| **E12** | A recoil impulse large enough to flip the firing vehicle | Clamped at `MAX_RECOIL_IMPULSE_NS`; the clamp is reported |
| **E13** | Firing while the vehicle is airborne | Recoil applies normally. A gun fired in flight pushes the vehicle, which is correct |
| **E14** | A destroyed sub-part's articulation | Frozen at its current pose (R15), not snapped to rest |
| **E15** | The source model carries a licence in `asset.extras` | Copied into `weapon.json` and into `SOURCE.md`. A weapon whose licence is lost in processing is a legal problem, not a content one |

---

<!-- D17-S8 -->## 8. Test Cases

| ID | Level | Asserts |
|---|---|---|
| **T-D17-1** | unit | The bore-axis finder recovers a known axis and sense from a synthetic tapered tube |
| **T-D17-2** | unit | The axial cue labels a synthetic four-shell gun breech/receiver/barrel/muzzle in order |
| **T-D17-3** | unit | The structural cue collects three coaxial adjacent tubes into one barrel group |
| **T-D17-4** | unit | The rotational-repetition test finds a six-instance gear ring and not a five-instance random scatter |
| **T-D17-5** | unit | The seam rule puts a slot at the contact-region centroid, not at either centroid |
| **T-D17-6** | unit | Size-class gating accepts equal-and-below and rejects above |
| **T-D17-7** | unit | The family table derives `AUTOCANNON` and `CANNON` from their calibre ratios |
| **T-D17-8** | unit | The articulation table gives a barrel `RECOIL` on the bore axis with travel 4% of its length |
| **T-D17-9** | integration | `AssemblyValidator` reports A316 for an over-class weapon and nothing for an in-class one |
| **T-D17-10** | integration | A weapon assembly loads, spawns, and every sub-part appears in the vehicle's compound |
| **T-D17-11** | physics | A cannon shot changes the firing vehicle's velocity; an autocannon shot changes it ≥10× less |
| **T-D17-12** | physics | An off-centreline impact imparts angular velocity; an on-centreline one imparts ≥5× less |
| **T-D17-13** | physics | Destroying the mount removes the whole weapon subtree and mass is conserved in the same tick |
| **T-D17-14** | integration | A barrel destroyed multiplies spread ×4 and halves range, and the weapon still fires |
| **T-D17-15** | integration | Both shipped weapons pass every `WEAP-` check via the harness |
| **T-D17-16** | integration | Two runs at the same seed produce byte-identical `weapon.json` and meshes |
| **T-D17-17** | unit | An articulation block present or absent produces identical headless simulation output |

---

<!-- D17-S9 -->## 9. Cross-References

| Topic | Authority |
|---|---|
| Weapon families, fire rates, ranges, damage types | `docs/01_product_game_design.md#D01-S4.4` |
| Damage types and their armour interaction | `docs/01_product_game_design.md#D01-S4.5` |
| Slots, slot types, the slot graph, slot paths | `docs/05_vehicle_part_system.md#D05-S4.3` |
| Part properties and the stat block | `docs/05_vehicle_part_system.md#D05-S4.4`, `#D05-S4.5` |
| Degradation curves | `docs/05_vehicle_part_system.md#D05-S5.4` |
| The pending-impulse queue and the fixed step | `docs/06_physics_simulation.md#D06-S5.4` |
| Hit resolution and the contact point | `docs/07_damage_destruction_model.md#D07-S5.1` |
| Detachment and subtree removal | `docs/07_damage_destruction_model.md#D07-S5.7` |
| The part schema and its blocks | `docs/08_asset_pipeline.md#D08-S4.2` |
| The shared part library | `docs/08_asset_pipeline.md#D08-S4.6` |
| Voronoi fracture and shape keys | `docs/09_blender_destruction_tool.md#D09-S5.2` |
| Tool CLI contract and exit codes | `docs/09_blender_destruction_tool.md#D09-S4.1`, `#D09-S4.3` |
| Geometry repair | `docs/15_vehicle_preparation_pipeline.md#D15-S5.5` |
| The house style table | `docs/15_vehicle_preparation_pipeline.md#D15-S4.5`, `#D15-S5.9` |
| Built-in versus modular weapons | `docs/15_vehicle_preparation_pipeline.md#D15-S5.10` |
| The verification harness | `docs/14_test_environment.md#D14-S5.13` |

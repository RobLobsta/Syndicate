# Eclipse — source model

Whole-vehicle source art for the in-game **Eclipse**. Not loaded at runtime (D08-R1).

## Provenance

| | |
|---|---|
| Title | Maserati MC20 |
| Author | VTX — https://sketchfab.com/VTX_car |
| Source | https://sketchfab.com/3d-models/maserati-mc20-9787ff97921646388f9c00aa57497adf |
| Licence | **CC-BY-NC-SA-4.0** — attribution required, **non-commercial**, share-alike |
| Format | glTF 2.0 separate: `scene.gltf` + `scene.bin` + `textures/` |
| Exporter | `Sketchfab-0.3.0` |

**Credit line, required wherever this model or anything derived from it is shared:**

> This work is based on "Maserati MC20"
> (https://sketchfab.com/3d-models/maserati-mc20-9787ff97921646388f9c00aa57497adf) by VTX
> (https://sketchfab.com/VTX_car) licensed under CC-BY-NC-SA-4.0
> (http://creativecommons.org/licenses/by-nc-sa/4.0/)

The non-commercial term is a hard constraint on this file and on every part split out of it. It is
prototype art: it exists so the game has a real car to drive, fracture and photograph while the
pipeline is built. Shipping needs either a licence or replacement art. The in-game name is the
project's own (DEC-033).

## Import correction

```json
{ "scaleToMetres": 1.0389227840196078, "yawDeg": 180.0,
  "translationM": { "x": 0.0, "y": 0.6669, "z": 0.0 } }
```

- **Scale** — the Sketchfab root node carries a uniform `0.9625354409217834`. Undoing it puts the
  car at 4.6682 m long against the MC20's published 4.669 m, so the mesh data was authored in
  metres and the upload scaled it.
- **Yaw** — the model faces **−Z**. Confirmed three ways: the dashboard and instrument-cluster
  geometry sits at z ≈ −0.79, the narrow (245-section) tyres are on the −Z axle and the wide
  (305-section) ones on +Z, and the rear three-quarter render shows the engine cover and tail lights
  at −Z after correction. D00-R16 wants +Z forward, so 180°.
- **Translation** — the tyres' lowest point is 0.6669 m below the file's origin.

## Measured after correction

Everything below is measured by `syndicate-verify --model` and the reader in `game-core`, in the
game's frame: metres, +Y up, +Z forward, origin on the ground at the file's own centreline.

| | Measured | Published (MC20) |
|---|---|---|
| Length | 4.6682 m | 4.669 m |
| Width (incl. mirrors) | 2.1776 m | 1.965 m body |
| Height | 1.2365 m | 1.224 m |
| Height as the reader places it | 1.3338 m | — |
| Wheelbase | 2.7006 m | 2.700 m |
| Front track (tyre centres) | 1.7042 m | 1.682 m |
| Rear track (tyre centres) | 1.6606 m | 1.674 m |

| Axle | Wheel centre | Tyre Ø | Tyre width |
|---|---|---|---|
| Front | `x ±0.8521, y 0.3559, z +1.4565` | 0.7068 m (r 0.3534) | 0.2602 m |
| Rear | `x ±0.8303, y 0.3594, z −1.2441` | 0.7189 m (r 0.3595) | 0.3192 m |

The 9.7 cm gap between the two height rows is the displaced rear-left corner of `DISC-016`, and is
the only measurement in this file that ever recorded it. The corrected height is the first row.

The wheelbase matches the real car to 0.6 mm, which is the strongest evidence the scale factor is
right. The wheel-centre figures are what the chassis part's four `WHEEL` slots want when this model
is split into parts.

## Content

171 mesh nodes, 216,322 vertices, 283,192 triangles, 60 materials, 96 textures — all present, none
unreferenced. 52 degenerate triangles (0.018%).

**163 mesh nodes are skinned, and for ten of them the node transform is not the placement.**

This paragraph previously claimed the opposite — that the joint matrices reproduce the node
transforms and the reader's node-transform-only approach was exact here. It is not. The file holds
two copies of the car (`xmc20` and a partial `xmc20.001`), the second supplying the mirrored
left-hand corners, and ten of its objects are parented to the armature rather than to a joint. Their
placement lives in `skin[1]`'s inverse-bind matrices. Read by node transform alone, the rear-left
wheel lands 0.61 m high on the *front* axle — 2.65 m from where it belongs.

`MODEL-008` counts skinned nodes; it does not compare a posed vertex against an unposed one, and the
two capture angles both hide the displaced corner. The one figure that recorded the discrepancy is
the height below. Full account: `DISC-016`.

The split assets in `assets/parts/` are unaffected: `syndicate_dissect` bakes the armature before it
measures anything, which is how it finds four wheels where the reader finds three.

## Split into parts

Done, by `python3 -m syndicate_dissect --model art-source/vehicles/eclipse --vehicle eclipse`
(`:blender-tool:dissectVehicles`). The wheels are *not* part of the body mesh — every wheel is its
own set of connected components, which is what makes the split a geometry problem rather than a
modelling one (DEC-042).

| Part | Islands | Triangles | Measured |
|---|---|---|---|
| `chassis_eclipse_01` | 143 | 205,682 | 4.6682 × 2.1776 m, floor pan 0.13 m off the road |
| `wheel_eclipse_front_01` | 7 | 18,338 | Ø 0.7068 m, 0.2687 m wide, axle at `±0.8563, 0.3559, +1.4565` |
| `wheel_eclipse_rear_01` | 7 | 20,417 | Ø 0.7189 m, 0.3225 m wide, axle at `±0.8319, 0.3594, −1.2441` |

Each `mesh.glb` carries its visual mesh and a `<partTypeId>_col` convex hull of at most 64 vertices.
The measured diameters match the figures in the table above — which were taken two sessions earlier
by a different tool — to within a tenth of a millimetre.

**The chassis part's authored wheel slots do not yet match these axles.** `part.json` places them at
`y 0.0` and `z ±1.35`; the art has them at `y 0.356` and `z +1.4565 / −1.2441`. Re-authoring the
slots moves the wheelbase by 10 cm and the ride height by 36 cm, which changes handling — so it is a
deliberate change with a calibration re-run attached, not a typo fix.

## Not yet done

The `dmg_25`…`dmg_100` morph targets of D07-S5.5 and the fracture manifests of D09. Both are
that has to happen before `assets/parts/*/mesh.glb` can be filled in.

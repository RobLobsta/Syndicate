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
| Wheelbase | 2.7006 m | 2.700 m |
| Front track (tyre centres) | 1.7042 m | 1.682 m |
| Rear track (tyre centres) | 1.6606 m | 1.674 m |

| Axle | Wheel centre | Tyre Ø | Tyre width |
|---|---|---|---|
| Front | `x ±0.8521, y 0.3559, z +1.4565` | 0.7068 m (r 0.3534) | 0.2602 m |
| Rear | `x ±0.8303, y 0.3594, z −1.2441` | 0.7189 m (r 0.3595) | 0.3192 m |

The wheelbase matches the real car to 0.6 mm, which is the strongest evidence the scale factor is
right. The wheel-centre figures are what the chassis part's four `WHEEL` slots want when this model
is split into parts.

## Content

171 mesh nodes, 216,322 vertices, 283,192 triangles, 60 materials, 96 textures — all present, none
unreferenced. 52 degenerate triangles (0.018%).

**163 mesh nodes are skinned.** The reader ignores joint weights and uses each node's own transform;
here that is exact, because the exporter's joint matrices reproduce the node transforms — the car
does not deform. Checked, not assumed: `MODEL-008` reports the count, and the render confirms
nothing is displaced.

## Not yet done

The wheels are part of the body mesh. Splitting this into `chassis_eclipse_01` and four wheel parts,
with a `_col` hull node and the `dmg_25`…`dmg_100` morph targets of D07-S5.5, is the Blender work
that has to happen before `assets/parts/*/mesh.glb` can be filled in.

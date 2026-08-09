# Stampede — source model

Whole-vehicle source art for the in-game **Stampede**. Not loaded at runtime (D08-R1).

## Provenance

| | |
|---|---|
| Title | 2025 Ford Mustang GTD |
| Author | Ddiaz Design — https://sketchfab.com/ddiaz-design |
| Source | https://sketchfab.com/3d-models/2025-ford-mustang-gtd-754d0865531a4ce6a5872afa9bac74ec |
| Licence | **CC-BY-NC-SA-4.0** — attribution required, **non-commercial**, share-alike |
| Format | glTF 2.0 separate: `scene.gltf` + `scene.bin` + `textures/` |
| Exporter | `Sketchfab-16.61.0`, from an FBX (`FINAL_MODEL.fbx`) |

**Credit line, required wherever this model or anything derived from it is shared:**

> This work is based on "2025 Ford Mustang GTD"
> (https://sketchfab.com/3d-models/2025-ford-mustang-gtd-754d0865531a4ce6a5872afa9bac74ec) by Ddiaz
> Design (https://sketchfab.com/ddiaz-design) licensed under CC-BY-NC-SA-4.0
> (http://creativecommons.org/licenses/by-nc-sa/4.0/)

Same constraint as the Eclipse: non-commercial, share-alike, prototype art. The in-game name is the
project's own (DEC-033).

## Import correction

```json
{ "scaleToMetres": 100.0, "yawDeg": 0.0,
  "translationM": { "x": 0.0, "y": 0.0041, "z": 0.0 } }
```

- **Scale** — the `FINAL_MODEL.fbx` node carries a `0.01` centimetre-to-metre conversion that the
  mesh data had already had applied, so the car arrives 4.9 **centimetres** long. Undoing it gives
  4.9196 m against the GTD's published 4.81 m body; the extra 11 cm is the rear wing, which
  overhangs the bodywork at z −2.458.
- **Yaw** — none needed. The model already faces +Z: the tail lights and diffuser are at −Z, and
  the rear tyres are the wider pair on the −Z axle.
- **Translation** — the tyres sit 4.1 mm below y=0, within `MODEL-007`'s tolerance either way; the
  offset is recorded rather than rounded off so the ground plane is exact.

## Measured after correction

Measured by `syndicate-verify --model`, in the game's frame: metres, +Y up, +Z forward, origin on
the ground.

| | Measured | Published (Mustang GTD) |
|---|---|---|
| Length | 4.9196 m (incl. wing) | 4.81 m body |
| Width (incl. mirrors) | 2.0847 m | 2.03 m body |
| Height | 1.3749 m (incl. wing) | 1.33 m |
| Wheelbase | 2.7174 m | 2.720 m |
| Track (tyre centres) | 1.708 m front, 1.707 m rear | 1.72 m |

| Axle | Wheel centre | Tyre Ø | Tyre width |
|---|---|---|---|
| Front | `x ±0.8540, y 0.3587, z +1.3904` | 0.7090 m (r 0.3545) | 0.3527 m |
| Rear | `x ±0.8535, y 0.3620, z −1.3270` | 0.7241 m (r 0.3621) | 0.3777 m |

Wheelbase within 3 mm of the real car, which is what confirms the ×100.

## Content

1,101 mesh nodes, 173,828 vertices, 234,057 triangles, 24 materials, 34 textures — all present, none
unreferenced. 51 degenerate triangles (0.022%). No skinning.

The node hierarchy is 11 deep and every mesh is a separate `polySurface<n>` node, which is what a
Maya-to-FBX-to-glTF path produces. It reads fine; it is worth knowing before anyone tries to select
"the bonnet" by node name.

## Not yet done

The wheels are part of the body mesh. Splitting this into `chassis_stampede_01` and four wheel
parts, with a `_col` hull node and the `dmg_25`…`dmg_100` morph targets of D07-S5.5, is the Blender
work that has to happen before `assets/parts/*/mesh.glb` can be filled in.

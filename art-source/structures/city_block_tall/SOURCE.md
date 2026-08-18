# city_block_tall — source model

**Becomes:** `str_city_block_tall_01`, a seven-storey stylised block.

| | |
|---|---|
| Arrived as | `art-source/city_alley_kit.blend`, uploaded 2026-08-18 (commit `b8c84bc`) |
| Extracted | 2026-08-18, into `scene.glb` |
| Extent | 9.95 × 8.48 × 22.22 m (X × Z × Y in game axes) |
| Triangles | 3,243 across 2 objects |
| Textures | packed in the `.blend`, carried into `scene.glb` |
| Origin | BlenderKit, asset `7dbb40ee-180b-4ace-8d34-059f641f4923` |
| Licence | **not recorded by the supplier.** See `LICENCE.md` |

## Where it came from

`city_alley_kit.blend` was one small street scene — a ground plane, an asphalt strip, two
mid-rise buildings, four copies of one tree, seven copies of one bench, seven area lights and a
camera — assembled in Blender 5.0 out of **BlenderKit** downloads. The packed texture paths still
carry each download's asset id, which is the only provenance the file preserves:

| Prop | BlenderKit asset id |
|---|---|
| Low-poly building | `ca8fbde2-e023-4f8c-b7fb-68c96e6880ff` |
| Stylised low-poly building | `7dbb40ee-180b-4ace-8d34-059f641f4923` |
| Low-poly wooden bench | `1ba233de-b47e-4290-8c8f-05649608d327` |
| Low-poly tree | no texture, so no id survives |

The scene held duplicates of every prop. **One instance of each was extracted**, not the scene: a
kit is a set of things to place, and D16-S5.7 places them from a rule rather than copying somebody
else's arrangement. The ground plane and the asphalt strip were dropped — D16's terrain generates
both, and a 20 m plane baked into a structure would fight it.

Every modifier and object transform was applied, linked duplicates were made single-user first,
curves were converted to meshes, and the result was recentred on its own footprint with its lowest
vertex on `y = 0`.

## The .blend could not be opened by the project's Blender

It was written by Blender 5.0, whose `.blend` header format Blender 4.2 refuses outright ("not a
blend file" — the file is zstd-compressed and its magic reads `BLENDER17-01v050`). D09 and
D02-S5.5 pin 4.2, so a 5.0 build was fetched **for the extraction only** and the pipeline that
consumes `scene.glb` runs on 4.2 as specified. See `DISC-075`.

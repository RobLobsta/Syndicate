# rocket_turret — source model

**Becomes:** `str_rocket_turret_01`, a four-legged rocket-launcher emplacement.

| | |
|---|---|
| Arrived as | `art-source/turret.blend`, uploaded 2026-08-18 (commit `b8c84bc`) |
| Extracted | 2026-08-18, into `scene.glb` |
| Extent | 38.65 × 38.56 × 31.18 m (X × Z × Y in game axes) |
| Triangles | 163,616 across 134 objects |
| Textures | **none** — see below |
| Licence | **not recorded by the supplier.** See `LICENCE.md` |

## What the .blend held, and what came out

The file carried four collections and 288 objects:

| Collection | Objects | Triangles | Kept |
|---|---|---|---|
| `HighPoly` | 150 | ~144,000 | no |
| `LowPoly` | 56 | 64,686 | yes |
| `LowPoly.002` | 37 | 38,006 | yes |
| `LowPoly.003` | 45 | 58,724 | yes |

The three `LowPoly*` collections are one model split across three UV atlases, not three models;
together they are the game-resolution turret and they are what `scene.glb` holds. `HighPoly` is the
sculpt the low-poly was baked from — at 8,000 triangles per part (D08-R2) nothing in this project
can spend it, so it was not extracted. The `.blend` itself is reachable at `b8c84bc` if it is ever
wanted.

Curves (cable runs), lights, cameras and empties were dropped; every modifier and every object
transform was applied; the model was recentred so its footprint straddles the origin in X and Z and
its lowest vertex sits on `y = 0`. That is D08-R2's "origin at the attachment point" for a thing
whose attachment point is the ground.

## Textures: there are none, and that is the model

The three materials (`UV`, `UV_Two`, `UV_Third`) carried one shared **procedurally generated**
4096 × 4096 UV checker — a modelling aid, not art. It was dropped at export rather than baked into
the `.glb`, because 16 megapixels of magenta grid is not what the turret should look like and the
house style pass (DEC-076, DEC-079) is what decides what it does look like. The model therefore
arrives as untextured geometry with per-object material assignments, which is exactly the input the
style table is designed for.

## What it is

A rocket-launcher emplacement: four splayed legs on round footpads, a turntable, a yoke, and two
box launcher pods carried above it. The pods are a **built-in weapon** in the D15-S5.10 sense —
derived from the model's own geometry rather than authored as a modular weapon (DEC-077) — so the
turret shoots without anything being fitted to it.

Its collapse chain is vertical and is what makes it worth shooting: legs → turntable → yoke → pods.
Take a leg out and everything above it is a part whose parent stopped holding it, which is already
a detachment trigger (D07-S5.7, DEC-071).

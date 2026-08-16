# Machine gun — source model

| | |
|---|---|
| **Title** | Car Combat Machine Gun |
| **Author** | Jorma Rysky (Joona Venäläinen) — https://sketchfab.com/Rysky |
| **Source** | https://sketchfab.com/3d-models/car-combat-machine-gun-d10e54ab85304e31b12d98852fa3e165 |
| **Licence** | **CC-BY-4.0** — http://creativecommons.org/licenses/by/4.0/ |
| **File** | `model.glb`, exactly as supplied |

## The credit line that has to travel with it

> "Car Combat Machine Gun" by Jorma Rysky (Joona Venäläinen), licensed under CC BY 4.0.

CC-BY-4.0 requires attribution and **permits commercial use** — unlike the two vehicles, which are
CC-BY-NC-SA-4.0 (see `art-source/README.md`). A derivative — including every sub-part
`syndicate_weapon` cuts out of it — carries the same attribution requirement.

## What was measured off it

| | |
|---|---|
| Triangles | 350 (445 vertices, 222 after welding) |
| Materials | 1, `MachineGuns`, with base-colour, metallic-roughness and normal maps |
| Connected shells | 9 |
| Source extent | 0.617 × 0.077 × 0.181 in file units; imports into Blender at **100×** |
| Long axis | X in the file; the bore axis is found and corrected to +Z (D17-R24) |

## What the pipeline made of it

`weapon_machinegun_01` — **AUTOCANNON**, **LIGHT**, 45.6 kg, scaled to 0.9 m along the bore.

Five sub-parts: `mount` (synthesised, D17-R41), `receiver`, `barrel` (three coaxial tubes collected
into one part), `breech`, `muzzle`. The barrel recoils 32 mm on each shot (D17-R47).

```bash
python3 -m syndicate_weapon \
    --model ../art-source/weapons/machinegun/model.glb \
    --id weapon_machinegun_01 --seed 7 \
    --out ../assets/parts --style-table ../assets/materials/style.json
```

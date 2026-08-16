# Cannon — source model

| | |
|---|---|
| **Title** | SS. Hope Cannon |
| **Author** | krambulini — https://sketchfab.com/krambulini |
| **Source** | https://sketchfab.com/3d-models/ss-hope-cannon-ddda7243fa5e4131b4e086c016b28a36 |
| **Licence** | **CC-BY-4.0** — http://creativecommons.org/licenses/by/4.0/ |
| **File** | `model.glb`, exactly as supplied |

## The credit line that has to travel with it

> "SS. Hope Cannon" by krambulini, licensed under CC BY 4.0.

## What was measured off it

| | |
|---|---|
| Triangles | 8,898 (10,440 vertices, 4,484 after welding) |
| Materials | 1, `Cannon`, double-sided, with base-colour, occlusion-roughness and normal maps |
| Connected shells | 22 |
| Source extent | 365.8 × 282.0 × 258.2 in file units ≈ 3.66 × 2.82 × 2.58 m; imports at **100×** |
| Long axis | X in the file |

The model is a **siege carriage**: a gun on a four-wheeled trail. Four road wheels in two sizes
(53.1 cm and 59.5 cm), an axle, a base plate and a trail, none of which is the weapon.

## What the pipeline made of it

`weapon_cannon_01` — **CANNON**, **HEAVY**, 273.9 kg, scaled to 1.8 m along the bore.

The carriage is **discarded** by D17-R27's cylinder rule and named in the report: 11 shells and
5,204 of the 8,898 triangles — all four road wheels at 0.40 and 0.51 of bore length from the bore,
the axle at 0.62, the base plate at 0.57 and the trail at 0.42. This is D17-E4, and it is why the
gun ends up a vehicle weapon rather than a static prop.

Six sub-parts survive: `mount` (synthesised), `receiver`, `barrel`, `muzzle`, and the two trunnion
cheeks as `furniture_l` / `furniture_r`. The barrel recoils 72 mm on each shot.

```bash
python3 -m syndicate_weapon \
    --model ../art-source/weapons/cannon/model.glb \
    --id weapon_cannon_01 --seed 7 \
    --out ../assets/parts --style-table ../assets/materials/style.json
```

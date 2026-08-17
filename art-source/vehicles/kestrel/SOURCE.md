# Kestrel — source model

Whole-vehicle source art for the in-game **Kestrel**, the roster's first rotorcraft. Not loaded at
runtime (D08-R1).

## Provenance

| | |
|---|---|
| Title | Cartoonish Helicopter |
| Author | Codematics |
| Source | Animatics Asset Store — https://www.animaticsassetstore.com/ |
| Licence | **Animatics Asset Store single-use commercial licence**, issued 14 July 2023 |
| Supplied as | `helicopter.blend`, plus FBX, OBJ and Maya copies and a `Textures.zip` |
| Format here | glTF 2.0 separate: `scene.gltf` + `scene.bin` + `textures/` |
| Exporter | Blender 4.2 LTS glTF exporter, from the supplied `.blend` |

`License file.txt` beside the original archive is the certificate and is the authority. It grants a
**non-exclusive, commercial, worldwide and revokable** licence for **one Single Use for this
Registered Project**, valid if the End Product is completed while the subscription is active, and
continuing for the life of that End Product thereafter.

**This is a different licence from anything else in `art-source/`, and the difference runs the
opposite way to the one the README warns about.** The two cars are CC-BY-NC-SA-4.0 and cannot ship
commercially at all. This model can — it is a purchased commercial licence — but it is *single-use
and revokable*, where the two CC-BY-4.0 weapons are perpetual and irrevocable. So:

- No attribution line is contractually required, unlike every other model here.
- It is licensed to **this project only**. It cannot be lifted into a second one, and it cannot be
  redistributed as source art.
- "Revokable" means the grant is not permanent in the way a Creative Commons grant is. If this
  project ships commercially, the certificate is the thing to re-check first.

The in-game name is the project's own (DEC-033); the source is not named after any aircraft, and the
reference machine below is a research record rather than a claim about the model's provenance.

## Import correction

```json
{ "scaleToMetres": 1.0, "yawDeg": 0.0,
  "translationM": { "x": -0.76, "y": -0.002318, "z": 0.0 } }
```

- **Scale** — none. The `.blend` is metric at unit scale 1.0 and the geometry is already in metres:
  the airframe measures 10.02 m nose to tail, which is a light utility helicopter's length to within
  a few per cent. This is the first model in the project that needed no scale correction at all.
- **Yaw** — none. The nose is already at **+Z**, which is the sense `VehicleFactory` builds in
  (`setCoordinateSystem(0, 1, 2)`, so Bullet's forward index is Z). Confirmed by measurement rather
  than by eye: the tail rotor sits at `z = -5.03` and the nose at `z = +4.20`. See `DEV-020` — the
  two shipped cars follow the same convention and **D00-R15 says the opposite**, which is a
  documented conflict rather than a decision taken here.
- **Translation** — the model is **not built on its own centreline**. Its mirror plane is at
  `x = 0.760`, not `x = 0`: fitting the best reflection plane over the fuselage's 2,903 vertices
  matches 695 of them at `x = 0.760` and **one** at `x = 0`. Left uncorrected, every shell takes
  side `r` and the left/right classification of D15-R19 collapses. The `y` term is 2.3 mm and is
  just the skids meeting the ground plane.

## Measured after correction

Everything below is measured by `syndicate-verify --model` and the reader in `game-core`, in the
game's frame: metres, +Y up, origin on the ground at the aircraft's centreline.

| | Measured | Reference (Airbus H125) |
|---|---|---|
| Length over airframe | 10.02 m | 10.93 m |
| Length including rotor overhang | 11.63 m | — |
| Height | 3.50 m | 3.14 m |
| Width over airframe | 3.10 m | 2.53 m (over skids 2.16 m) |
| Main rotor diameter | **9.45 m** | 10.69 m |
| Main rotor blades | **3** | 3 |
| Main rotor hub | (0.006, 2.972, 1.339) m | — |
| Tail rotor diameter | 1.02 m | 1.86 m |
| Tail rotor centre | (−0.002, 1.966, −5.032) m | — |
| Triangles | 2,807 | — |
| Materials | 2 | — |

The main rotor's diameter is measured as the furthest a blade reaches from the **hub**, and the hub
is the disc's area centroid — not the centre of its bounding box. A three-blade rotor at rest has
its blades 120° apart, so its box is not centred on its own mast: the box gives 8.20 m and the true
swept disc is 9.45 m. That 13% matters twice over, because rotor thrust goes as the square of the
radius.

At 2,807 triangles the whole aircraft is a third of `D08-R2`'s budget for a *single part*. This is
by a wide margin the cheapest model in the project — the Eclipse is 283,192 triangles.

## What the pipeline made of it

`syndicate-prepare` finds four parts: the airframe, a tail-boom decal, and both rotors. The two
rotors are found by a geometric cue and separated by a role, neither of which existed before this
model (`DEC-093`):

| Part | Label | Role | Mass | Notes |
|---|---|---|---|---|
| `chassis_kestrel_01` | `chassis` | — | 1,423 kg | absorbs the skids, stabilisers, fin and canopy |
| `rotor_kestrel_main_01` | `rotor` | `main` | 154 kg | 4.724 m radius, 3 blades, 424 rpm, 22.4 kN |
| `rotor_kestrel_tail_01` | `rotor` | `tail` | 21 kg | 0.508 m radius, 3,950 rpm, 2.5 kN |
| `decal_kestrel_01` | `decal` | — | 2 kg | the tail-boom marking |

**61% of the model's triangles are `unclassified`** and merge into the chassis, which D15-R2 makes a
first-class outcome rather than a failure. What is in that 61% is, in order of size: the cabin and
engine deck, the vertical fin and tail-rotor shroud, the two horizontal stabilisers, and the landing
skids. None of them has a label in D15-S4.1's taxonomy, because that taxonomy was written for cars —
a skid is not a wheel and a stabiliser is not a panel. Adding labels for them is real work and is
recorded as such rather than done speculatively; the aircraft flies, takes damage and loses its
rotors without them.

## Mass

`profile.json` authors 1,600 kg and the pipeline uses it. Without it the footprint estimate is the
fallback, and for a rotorcraft that estimate needed fixing before it was usable: the bounding box a
kerb mass is derived from is 7.83 m across on this model because that is the **rotor span**, and the
first run produced a **15.7-tonne helicopter** that no rotor was ever going to lift. Rotor shells are
now excluded from that footprint for the same reason the track already excludes wing mirrors, which
brings the un-authored estimate to about 5.4 tonnes — still wrong for an aircraft, but wrong in the
way a heuristic is rather than by an order of magnitude. `--mass` and `profile.json` remain the
answer, exactly as `DEFAULT_AREAL_DENSITY_KG_PER_M2`'s own note says for anything not shaped like a
road vehicle.

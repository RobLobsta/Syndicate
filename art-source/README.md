# art-source — source art, never loaded at runtime

`docs/08_asset_pipeline.md#D08-S4.1` (D08-R1): everything here is what art is authored *from*.
Nothing in this directory is read by the game. The runtime reads `assets/`, and a distribution
build asserts `art-source/` is absent from it (D02-E12, T-D02-9).

```
art-source/
├── parts/                  per-part .blend sources (D08-S4.1) — none yet
├── arenas/                                                    — none yet
├── shared/                 material library                    — none yet
├── vehicles/               whole-vehicle source models         ← two cars and a helicopter
│   ├── eclipse/
│   ├── kestrel/
│   └── stampede/
├── weapons/                whole-weapon source models          ← the two guns
│   ├── machinegun/
│   └── cannon/
└── structures/             whole-structure source models       ← a turret and a city block's worth
    ├── rocket_turret/
    ├── city_block_low/
    ├── city_block_tall/
    ├── street_tree/
    └── street_bench/
```

`vehicles/` is not in D08-S4.1's tree. It holds a whole car as one model — body and wheels in one
mesh — which is the state art arrives in and *not* the state the game can use: a vehicle is a
chassis and four wheels, each its own part with its own mass, health and damage state
(`D05-S4.1`). Splitting one into the other is a Blender job that has not been done yet, so these
files sit here rather than under a vehicle's own `parts/`. `DEV-013` in `.agent-memory/spec_deviations/`
records the bucket and why.

---

## What is here

| Directory | Becomes | Derived from | Triangles | Licence |
|---|---|---|---|---|
| `vehicles/eclipse/` | **Eclipse** | Maserati MC20 | 283,192 | CC-BY-NC-SA-4.0 |
| `vehicles/stampede/` | **Stampede** | Ford Mustang GTD | 234,057 | CC-BY-NC-SA-4.0 |
| `weapons/machinegun/` | `weapon_machinegun_01` | Car Combat Machine Gun | 350 | **CC-BY-4.0** |
| `weapons/cannon/` | `weapon_cannon_01` | SS. Hope Cannon | 8,898 | **CC-BY-4.0** |
| `vehicles/kestrel/` | **Kestrel** | Cartoonish Helicopter (Codematics) | 2,807 | **Animatics single-use commercial** |
| `structures/rocket_turret/` | `str_rocket_turret_01` | `turret.blend` | 163,616 | **not recorded** |
| `structures/city_block_low/` | `str_city_block_low_01` | `city_alley_kit.blend` | 946 | **not recorded** |
| `structures/city_block_tall/` | `str_city_block_tall_01` | `city_alley_kit.blend` | 3,243 | **not recorded** |
| `structures/street_tree/` | `str_street_tree_01` | `city_alley_kit.blend` | 1,134 | **not recorded** |
| `structures/street_bench/` | `str_street_bench_01` | `city_alley_kit.blend` | 4,416 | **not recorded** |

`structures/` is processed by `syndicate_structure` against `docs/16_procedural_arena_generation.md`.
A structure comes out as an **assembly** — a root part on the ground with parts stacked on its slots
— so shooting the bottom of a building drops what stands on it, through the machinery that already
drops a wheel (D16-R80). `structures/README.md` is the file-by-file mapping from the two `.blend`
files that arrived to the five structures that ship.

`weapons/` is processed by `syndicate_weapon` rather than `syndicate_prepare`, against
`docs/17_weapon_system.md`. A weapon comes out as an **assembly** — a mount with a receiver, a
barrel, a breech and a muzzle hanging off it — rather than as one part, which is what lets a barrel
be shot off (D17-S1).

Each holds the model exactly as it was supplied — `scene.gltf`, `scene.bin`, `textures/` and the
author's `license.txt` — plus two files this project adds:

- **`SOURCE.md`** — provenance, licence, the attribution that has to travel with the model, and
  every measurement taken off it.
- **`import.json`** — the correction from the file's units and axes to the game's (`D00-R16`).

### Licensing, which is load-bearing

**The two licences here are not the same, and the difference matters.**

The two **vehicles** are **CC-BY-NC-SA-4.0**: attribution required, **no commercial use**,
derivatives under the same licence. They are prototype and reference art. They can be measured, driven, fractured and
screenshotted here; they cannot ship in anything commercial, and a derivative of one — including a
part split out of it — carries the same licence. Each `SOURCE.md` carries the credit line its
author requires.

The **Kestrel** is a **third** licence and it is neither of the other two. It is a *purchased*
Animatics Asset Store certificate: non-exclusive, commercial, worldwide — and **single-use for this
registered project, and revokable**. So it may ship commercially where the cars may not, and it may
**not** be lifted into a second project or redistributed as source art, where a CC model could be.
No attribution line is contractually required. Its `SOURCE.md` carries the certificate's terms.

The five **structures** arrived with **no terms at all** — no licence text, no author, no
`asset.extras`. D08-R1b says a model with no recorded terms is not processed; they were processed
anyway, deliberately and on the record, because D16-S7 had no content and a subsystem with nothing
in it cannot be looked at. Each directory's `LICENCE.md` says exactly that and says what has to
happen before anything ships. **None of this art may be distributed until it does.**

The two **weapons** are **CC-BY-4.0**: attribution required, commercial use **permitted**, and no
share-alike. They are therefore the only art in this repository that could ship commercially as it
stands — which is worth knowing, and is why the licence is carried out of each model's
`asset.extras` into its `weapon.json` by the tool rather than only written down here (D17-E15).

This is separate from the trademark question `DEC-033` already answers: the in-game names are
Eclipse and Stampede, and no manufacturer's name appears in an asset id or a display name.

---

## `import.json`

Downloaded art is not in metres facing +Z. The correction is recorded once, beside the model, and
applied by everything that reads it:

```json
{
  "scaleToMetres": 1.0389227840196078,
  "yawDeg": 180.0,
  "translationM": { "x": 0.0, "y": 0.6669, "z": 0.0 }
}
```

Applied in that order — scale, then yaw about +Y, then translate — so each field can be read on its
own: the scale is a unit conversion, the yaw is which way the car faces, the translation drops the
origin onto the ground plane. `dev.syndicate.verify.model.ModelImport` is the implementation.

The numbers are not asserted, they are **verified**: the harness applies the correction and then
measures, so a wrong scale fails `MODEL-004` and a model on its side fails `MODEL-005`.

---

## Checking a model

```bash
./gradlew :test-environment:installDist

# headless: geometry, units, axes, resources — no display needed
./test-environment/build/install/syndicate-verify/bin/syndicate-verify \
    --headless --model art-source/vehicles/eclipse/scene.gltf --verbose

# with a render: writes <capture>.png and <capture>_rear.png
xvfb-run -a ./test-environment/build/install/syndicate-verify/bin/syndicate-verify \
    --model art-source/vehicles/eclipse/scene.gltf --capture build/captures/eclipse.png
```

| Check | Asks |
|---|---|
| `MODEL-001` | is there any geometry at all |
| `MODEL-002` | is every coordinate finite (`D00-R13`) |
| `MODEL-003` | do the textures the document names exist beside it |
| `MODEL-004` | is it in metres (`D00-R11`) |
| `MODEL-005` | is Y up (`D00-R16`) |
| `MODEL-006` | is the long axis Z |
| `MODEL-007` | does the origin sit on the ground plane |
| `MODEL-008` | is anything skinned (the reader ignores joints) |
| `MODEL-009` | are degenerate triangles rare |
| `MODEL-010` | is it within `D08-R2`'s per-part triangle budget |

`MODEL-006` can tell that the long axis is Z. It cannot tell **which end is the front** — that is
what the two captured views are for, and it is how the Eclipse's 180° yaw was found.

---

## Adding a model

1. Put the file and everything it references in `art-source/vehicles/<name>/`, with the author's
   licence text.
2. Run `--model` on it headlessly and read the failures.
3. Write `import.json` until `MODEL-004` through `MODEL-007` pass.
4. Render it and look at both views. Fix the yaw if the front is at −Z.
5. Write `SOURCE.md`: where it came from, its licence, the credit line, and what you measured.

## What a model directory holds

| File | What it is | Written by |
|---|---|---|
| `scene.gltf` / `scene.bin` / `textures/` | the downloaded model, untouched | the artist |
| `license.txt`, `SOURCE.md` | its licence and where it came from | you |
| `import.json` | the correction into the game's units and axes | `syndicate-prepare` (DEC-065) |
| `parts.json` | per-model label overrides, where the cue ensemble needs help | you, if needed |
| `profile.json` | the researched figures for the real car this is | you, if you have them |

Only the first two are required. `syndicate-prepare` derives everything else from the geometry, and
each of the last three is a way of telling it something the geometry does not say.

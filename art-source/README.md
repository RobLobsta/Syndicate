# art-source — source art, never loaded at runtime

`docs/08_asset_pipeline.md#D08-S4.1` (D08-R1): everything here is what art is authored *from*.
Nothing in this directory is read by the game. The runtime reads `assets/`, and a distribution
build asserts `art-source/` is absent from it (D02-E12, T-D02-9).

```
art-source/
├── parts/                  per-part .blend sources (D08-S4.1) — none yet
├── arenas/                                                    — none yet
├── shared/                 material library                    — none yet
└── vehicles/               whole-vehicle source models         ← the two cars
    ├── eclipse/
    └── stampede/
```

`vehicles/` is not in D08-S4.1's tree. It holds a whole car as one model — body and wheels in one
mesh — which is the state art arrives in and *not* the state the game can use: a vehicle is a
chassis and four wheels, each its own part with its own mass, health and damage state
(`D05-S4.1`). Splitting one into the other is a Blender job that has not been done yet, so these
files sit here rather than in `assets/parts/`. `DEV-013` in `.agent-memory/spec_deviations/`
records the bucket and why.

---

## What is here

| Directory | Vehicle | Derived from | Triangles |
|---|---|---|---|
| `vehicles/eclipse/` | **Eclipse** | Maserati MC20 | 283,192 |
| `vehicles/stampede/` | **Stampede** | Ford Mustang GTD | 234,057 |

Each holds the model exactly as it was supplied — `scene.gltf`, `scene.bin`, `textures/` and the
author's `license.txt` — plus two files this project adds:

- **`SOURCE.md`** — provenance, licence, the attribution that has to travel with the model, and
  every measurement taken off it.
- **`import.json`** — the correction from the file's units and axes to the game's (`D00-R16`).

### Licensing, which is load-bearing

Both models are **CC-BY-NC-SA-4.0**: attribution required, **no commercial use**, derivatives under
the same licence. They are prototype and reference art. They can be measured, driven, fractured and
screenshotted here; they cannot ship in anything commercial, and a derivative of one — including a
part split out of it — carries the same licence. Each `SOURCE.md` carries the credit line its
author requires.

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

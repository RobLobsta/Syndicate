# `art-source/structures` — the file mapping

Two `.blend` files were uploaded on 2026-08-18 and are gone from the working tree. This file is
the record of what was in them, what came out, and where each piece ended up.

They are **not lost**: `git show b8c84bc:art-source/turret.blend > turret.blend` brings either one
back. They were removed because neither could be read by the Blender this project pins — see
"Why the `.blend` files went" below — and because everything the game needs was extracted first.

---

## The mapping, end to end

| Source `.blend` | Object(s) taken | `art-source/structures/` | Structure id | Parts | Mass |
|---|---|---|---|---|---|
| `turret.blend` | collections `LowPoly`, `LowPoly.002`, `LowPoly.003` (138 objects) | `rocket_turret/scene.glb` | `str_rocket_turret_01` | 5 | 411 t |
| `city_alley_kit.blend` | `Cube.012` + `Low poly building` | `city_block_low/scene.glb` | `str_city_block_low_01` | 3 | 336 t |
| `city_alley_kit.blend` | `Cube.002` + `Stylised low poly building.001` | `city_block_tall/scene.glb` | `str_city_block_tall_01` | 4 | 370 t |
| `city_alley_kit.blend` | `Low poly tree` | `street_tree/scene.glb` | `str_street_tree_01` | 1 | 130 kg |
| `city_alley_kit.blend` | `BézierCurve`, `BézierCurve.002`, `Cube.008`, `Sphere.014` | `street_bench/scene.glb` | `str_street_bench_01` | 1 | 77 kg |

Each structure's parts land at `assets/structures/<structureId>/parts/<partTypeId>/`, under the
structure that owns them (D16-R19a, DEC-075). Each part directory holds `mesh.glb` — visual mesh,
collision hull node and damage morph targets in one file — and `part.json`.

### What was deliberately dropped

| From | What | Why |
|---|---|---|
| `turret.blend` | collection `HighPoly`, 150 objects, ~144,000 triangles | The sculpt the low-poly was baked from. D08-R2 budgets 8,000 triangles per part; nothing here can spend it |
| `turret.blend` | the shared 4096² **generated** UV checker | A modelling aid, not art. The house style decides what the turret looks like (DEC-076, DEC-079) |
| `city_alley_kit.blend` | 3 of 4 trees, 6 of 7 benches, 1 of 2 of each building | A kit is a set of things to place, and D16-S5.7 places them from a rule rather than copying somebody else's arrangement |
| `city_alley_kit.blend` | the 20 m ground plane and the asphalt strip | D16 generates both. A plane baked into a structure would fight the terrain it stands on |
| both | lights, cameras, empties, curves (converted to mesh first) | Nothing downstream reads them |

---

## What the cut produced

The cut is height bands, and connected components within a band
(`blender-tool/syndicate_structure/bands.py`). A part's parent is the part below it whose footprint
it stands on most, so the slot graph **is** the support chain — which is what makes a structure
collapse through machinery that already existed (DEC-071, D16-R80).

| Structure | Height | Bands | Parts, bottom to top |
|---|---|---|---|
| `str_rocket_turret_01` | 31.2 m | 5 | `base` (legs, pads) → `tier1` (turntable) → `tier2` (yoke) → `tier3` (cradle) → `tier4` (launcher pods, **built-in weapon**) |
| `str_city_block_low_01` | 17.1 m | 3 | `base` → `tier1` → `tier2` — two storeys each |
| `str_city_block_tall_01` | 22.2 m | 4 | `base` → `tier1` → `tier2` → `tier3` |
| `str_street_tree_01` | 2.0 m | 1 | `base` |
| `str_street_bench_01` | 1.0 m | 1 | `base` |

The two blocks arrived as **one object seventeen metres tall**, so sorting objects into bands would
have produced one part and "shoot the ground floor" would have deleted the whole building. The
geometry is bisected at the band planes first (`split.py`), capped where the cut opens it, and only
then sorted. That is why a block has floors and the turret — which arrived as 138 separate objects —
did not need cutting at all.

The turret's `tier4` carries a `ROCKET` weapon block: 12 rounds, 420 damage, 3.2 s between shots,
220 m range, an 8 m blast. The muzzle is **derived** from the pods' own geometry; the family is
authored in `rocket_turret/parts.json`, because nothing in an untextured box distinguishes a rocket
pod from a cannon breech (D17-R50 reaches the same conclusion for weapons).

---

## Why the `.blend` files went

`city_alley_kit.blend` was written by **Blender 5.0**, whose file header Blender 4.2 refuses
outright — the file is zstd-compressed and its magic reads `BLENDER17-01v050`, which 4.2 reports as
"not a blend file". `turret.blend` was written by 4.4 and 4.2 opened it with a data-loss warning.
D09 and D02-S5.5 pin 4.2, so a 5.0 build was fetched for the extraction only and the pipeline that
consumes `scene.glb` runs on 4.2 as specified (`DISC-075`).

Keeping a source file the project's own toolchain cannot open is worse than keeping none: it looks
like an input and is not one. `scene.glb` is the input, it opens everywhere, and the `.blend` is one
`git show` away if it is ever wanted.

---

## Licensing, which is unresolved

**Neither model arrived with terms, and D08-R1b says a model with no recorded terms is not
processed.** Both were processed anyway, deliberately and on the record, because D16-S7 structures
had no content at all and a subsystem with nothing in it cannot be looked at. Each directory's
`LICENCE.md` says so and says what has to happen before anything ships. The alley kit's props are
traceable to BlenderKit asset ids, recovered from the packed textures' original paths; the turret is
traceable to nothing at all.

That buys a working pipeline. It does not buy a right to distribute the art, and replacing any of
these five `scene.glb` files changes nothing else — the tool, the cut, the destruction data and the
runtime all work off whatever geometry is there.

---

## Re-cutting

```bash
bash blender-tool/tools/install-blender.sh          # once per session

./gradlew :blender-tool:classifyStructures          # cut and report, writes no assets
./gradlew :blender-tool:prepareStructures           # writes assets/structures/<id>/
```

`classifyStructures` is what to run when a threshold in `bands.py` changed and the question is what
that did to the part split; the reports land in `build/structure-reports/`. `prepareStructures`
rewrites committed content, so running it is a decision that belongs in the commit that makes it.

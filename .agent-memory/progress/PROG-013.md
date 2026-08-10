# PROG-013: meshes can be read; the art is one model per car, not per part

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S5.3, docs/08_asset_pipeline.md#D08-S4.1, docs/03_runtime_modes.md#D03-S5.1

**Status:** superseded (by PROG-015)

## Summary
`game-core` reads glTF headlessly and the loader uses it, so DEV-010 is closed and a `.glb` in a part
directory becomes that part's hull. The two shipped vehicles are renamed Eclipse and Stampede, and
the Stampede is recalibrated onto the Mustang GTD the supplied art actually is. What is still missing
is the split: the supplied models are one mesh per whole car, wheels included.

## Details

**Scope:** `assets/`, `art-source/`, `schemas/`, `asset-pipeline`, `game-client`,
`game-server-headless`, and `game-core`'s `asset` package (DEC-023).

**Status of Work:** (supersedes PROG-012)

| Area | State | Notes |
|---|---|---|
| Headless glTF reader | done | `GltfReader` in `game-core` `asset`: `.glb` and `.gltf`, external and data-URI buffers, composed node transforms, interleaved attributes, sparse accessors, normalized integers, strips and fans, mirrored transforms (DEC-035). 20 unit tests |
| Collision geometry at load | done | `GltfCollisionMeshSource` resolves `mesh.glb#node=<name>`, falling back to the whole file per D08-R3. `ServerRuntime` uses it. DEV-010 resolved |
| `assets/parts/` | in_progress | Six part types, renamed: `chassis_eclipse_01`, `chassis_stampede_01` and two wheel types each |
| `assets/vehicles/` | in_progress | `vehicle_eclipse_01`, `vehicle_stampede_01` |
| Vehicle profiles | done | `STAMPEDE` recalibrated from the Mustang GT3 to the Mustang GTD (DEC-037); its `Cd·A` is now solved backwards from the published top speed rather than estimated |
| `art-source/vehicles/` | done | Two whole-vehicle glTF models with provenance, licence, measurements and an `import.json` correction (DEV-013, DEC-036) |
| Source-art checks | done | `syndicate-verify --model`: ten `MODEL-nnn` checks, headless, plus a textured two-view render. Both cars pass with two warnings each (skinning, triangle budget) |
| Collision meshes for parts | blocked | Not on the reader any more — on the **split**. The supplied art is one mesh per vehicle; a part needs its own. `ShippedContent` still supplies box hulls in tests |
| `assets/arenas/`, `assets/balance/` | not_started | as PROG-012: no `ArenaFactory`, no class targets for AC-D05-18 |
| `schemas/` | not_started | Empty; `AssetLoader` validates by hand-written A1xx-A3xx rules |
| `asset-pipeline` | not_started | `PipelineMain` logs and exits 70 |
| `game-server-headless` | in_progress | as PROG-012, plus real collision geometry when a part has a mesh |
| Transport, match bootstrap, `game-client` | not_started | as PROG-012 |

**History (append-only):**
- 2026-08-09: entry created, superseding PROG-012. The reader and its collision source; the harness's
  `--model` mode; `art-source/vehicles/` with both cars; the Eclipse/Stampede rename and the GTD
  recalibration; `SuppliedVehicleArtTest` holding the art to its documented measurements. All JVM
  modules green under the JDK 21 toolchain override (DISC-007); `check`, `validateDocs` and
  `lintMemory` pass.

**What the next session should pick up:** the **split** — one whole-car model into a chassis part and
four wheel parts, in Blender, with a `<partTypeId>_col` hull node and the `dmg_25`…`dmg_100` morph
targets of D07-S5.5. Every number it needs is measured and recorded in each car's `SOURCE.md`: wheel
centres, tyre diameters, track, wheelbase and the ground plane. After that the parts have real hulls,
`ShippedContent`'s boxes go away, and Phase 6's arena is the remaining blocker to a world.

## Rationale / Context
PROG-012 said the reader was the only thing between the content and a car on screen. That is now
half-wrong in a way worth stating precisely: the reader exists and works on real files, and the
blocker moved one step along to a Blender operation nobody has run. A session reading PROG-012 would
write a second reader; a session reading "meshes load" without the rest would drop a whole car into a
chassis directory and get a chassis that weighs 1500 kg with wheels welded on.

## Impact
`game-core` `asset`, `game-server-headless`, `assets/`, `art-source/`, `VEHICLES.md`. Supersedes
PROG-012.

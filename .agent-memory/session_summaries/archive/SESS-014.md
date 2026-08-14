# SESS-014: a glTF reader, two cars on screen, and the vehicles renamed

**Date:** 2026-08-09
**Category:** session_summaries
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.5, docs/14_test_environment.md#D14-S5.3, docs/05_vehicle_part_system.md#D05-S5.6

**Status:** active

## Summary
Built the headless glTF reader DEV-010 had been waiting for, wired it into the asset loader and the
dedicated server, organised the two supplied car models into `art-source/` with verified import
corrections, added a source-art check-and-render mode to the harness, and renamed the shipped
vehicles to Eclipse and Stampede — recalibrating the Stampede onto the Mustang GTD the art actually
is.

## Details

**The reader.** `GltfReader` in `game-core` `asset`, plus `GltfModel`, `GltfMeshNode`,
`GltfPrimitive`, `GltfMaterial`, `GltfImage`, `GltfOptions` and `GltfException`. It reads `.glb` and
`.gltf`, resolves buffers from the GLB chunk, a `data:` URI or a file beside the document, and
composes every node transform down from the scene root. Interleaved attributes, sparse accessors,
normalized integer attributes, triangle strips and fans, and mirrored transforms are handled; skins,
morph targets and required extensions are refused or ignored explicitly rather than silently.
`GltfCollisionMeshSource` fills the `CollisionMeshSource` seam and `ServerRuntime` uses it, closing
DEV-010 (DEC-035).

**The harness.** `syndicate-verify --model <file>` runs ten `MODEL-nnn` checks over an unprocessed
model and, with `--capture`, renders it from a front and a rear three-quarter through a new textured,
transparency-aware `ModelRenderer`. `GlbReader` became an adapter over the shared reader, which
handed the harness node transforms and `.gltf` support it had silently lacked.

**The art.** Both zips unpacked into `art-source/vehicles/eclipse` and `.../stampede` (DEV-013), each
with an `import.json` correction the checks verify rather than trust (DEC-036). The Eclipse needed a
180° yaw — it faces −Z, found from the render — and the Stampede a ×100 scale, an FBX centimetre
conversion applied to data that had already had it. Corrected, both measure their reference cars'
wheelbases to within 3 mm.

**The rename.** Apex GT → Eclipse, Stampede GT3 → Stampede, across asset ids, directories, profiles,
content and `VEHICLES.md`. The Stampede's reference car moved with it to the Mustang GTD, which
changed every derived figure and turned the shipped pair from road-versus-race into light-versus-heavy
(DEC-037). Two calibration assertions that encoded the old pairing were rewritten rather than propped
up, one of them because the ordering genuinely does not survive the mass change (DISC-014).

**Verification.** `spotlessApply`, `build`, `check`, `validateDocs` and `lintMemory` all pass. 20 new
`game-core` unit tests for the reader and its collision source, 5 for the import correction and its
checks, and 4 integration tests holding the supplied art to the measurements in its `SOURCE.md`.
Captures of both cars are in `docs/captures/`.

## Rationale / Context
Records what a session ending here had done, and in what order the pieces depend on each other: the
reader is what made the checks possible, the checks are what made the import corrections trustworthy,
and the render is what caught the one thing neither could — which way the car faces.

## Impact
`game-core` `asset`, `game-server-headless`, `test-environment`, `art-source/`, `assets/`,
`VEHICLES.md`, `docs/captures/`, `ROADMAP.md`.

# SESS-005: Convex decomposition of the source; the fifth fixture fractures

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/14_test_environment.md#D14-S7.1

**Status:** active

## Summary
Resolved DEV-004 by decomposing a non-convex source into disjoint convex pieces, so it reaches the same exact half-space fracture path a convex source does. `test_complex_hollow` went from `MASS_IMPLAUSIBLE` at 6.4x its part mass to exit 0 at 0.006%, and is back in `fixtureSpecs` and in the Voronoi property tests.

## Details

**The fix (DEC-011).** `decompose.py` builds a solid BSP over the source's face planes; each solid leaf *is* the intersection of the half-spaces along its root path. `cell ∩ source` becomes the union of `cell ∩ piece`, every term an exact polytope intersection and the pieces disjoint. `_cell_exact` takes a list of piece plane-sets and the convex source is the one-piece case, so both paths run the same code. What decided whether this was usable: a node whose faces already bound a convex solid becomes a leaf immediately — without it the tree descends once per face plane, and the sphere exhausted the depth cap rather than returning the single piece it obviously is.

**Verification is inside the decomposition.** The pieces' volumes must sum to the source's within 1e-4 relative before any is returned — a BSP is exact only for a closed, consistently wound mesh, and stage 1 only proves the volume is positive. Failures return a *reason*, logged before falling back to mesh cutting.


**Measured.** All five fixtures exit 0. Re-parsing each exported `shards.glb` and recomputing volume × density — ASSET-004 and ASSET-006, done a second way — gives worst per-shard delta 0.041%, worst conservation delta 0.010%. Two runs of `test_complex_hollow` give byte-identical output (G11).

**Tests.** `tests/unit/test_decompose.py` (11 cases) is where the regression guard lives: CI runs only the pure-Python suite, and this path never touches `bpy`. It covers the hollow box by volume *and* by membership (one piece per material point, none per cavity point), a two-cavity ribbed box, an L-shape, two blocks sharing a plane with material on opposite sides, determinism, and both refusal paths. `test_complex_hollow` joins the in-Blender property tests; 12 pass.

**Two things found on the way.** `_inside_predicate` double-counts a ray grazing the edge two triangles share, reporting an interior point as outside — benign for site placement, wrong as a test oracle, and it cost a debugging cycle (DISC-006). And `test_complex_hollow` has never had the internal cross rib D14-R20 describes, which is why it weighs 2127 kg and not 2500 (DEV-006).

**Not verified here.** `:test-environment:verifyFixtures` could not be re-run: gdx-gltf resolves only from JitPack, which this environment's network policy blocks, so `test-environment` does not compile. `:game-core:test`, `:shared-models:test` and `fastChecks` are green. The GLB re-check above is substitute evidence, not the harness.

## Rationale / Context
States plainly which checks did and did not run: a session reading only the green fixture output would otherwise assume the Bullet-side harness had confirmed it.

## Impact
`blender-tool/syndicate_fracture/decompose.py` (new), `fracture.py`, `blender-tool/build.gradle.kts`, `tests/unit/test_decompose.py` (new), `tests/blender/test_voronoi_properties.py`. Memory: DEC-011, DISC-006, DEV-006 new; DEV-004 resolved; PROG-002 updated.

# SESS-004: Exact Voronoi fracture at 100+ shards, and a CI pipeline that runs once

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.3, docs/12_testing_validation_ci.md#D12-S5.4, docs/14_test_environment.md#D14-S5

**Status:** active

## Summary
Replaced the approximate Voronoi cell construction with exact half-space intersection on convex sources, raised the fixture shard counts to 100-200 and captured real mid-explosion renders at that density, then found and fixed the reason a clean clone would not build (DISC-005) and restructured CI so one commit produces one run instead of two.

## Details

**Voronoi (D09-S5.3).** The suggested fix — a Delaunay/scipy step to bound the cells — was not the defect. A convex source *is* its face half-spaces, so `cell ∩ source` is a single polytope intersection and needs no bounding construction. The real bug was in `_cap_face`: it chained cut segments, which fails whenever a source vertex lies exactly on the cut plane. Replaced with an angular sort about the plane normal. `_build_cells` now dispatches on `is_convex` and takes the exact path when it can. Property tests (bounded / tiling / nearest-site) pass on cube, sphere, plate and cylinder at 100 and 200 shards; `KNOWN_NOT_VORONOI` is empty. `test_complex_hollow` is non-convex, still falls back to mesh cutting, and still fails mass conservation at 6.4x — that is DEV-004, and the fix is convex decomposition of the source.

**Captures (D14-S5).** `docs/captures/` holds cube and sphere explosions at the original shard count and at 100 shards, rendered through the real Bullet simulation.

**Build integrity (DISC-005).** `.gitignore` carried an unanchored `build/`, which git matches at any depth, so `build-logic/src/main/kotlin/dev/syndicate/build/` — a real package — was silently untracked. `git add -A` skips ignored files without a word. This is the same failure that cost the previous session, and I re-diagnosed it wrongly at first. Anchored the patterns and added `SourceTrackingCheckTask`, which runs `git check-ignore` over every module's `src/` and is wired into `fastChecks` and `check`.

**CI (DEC-010).** Workflow triggered on both `push: ["**"]` and `pull_request`, so every commit ran 6 jobs / 573 s. Collapsed to one job whose steps are the stages, `push` limited to `main`, stage 7 deferred, reports uploaded only on failure.

**Practice change.** Local green does not describe what was committed. Before pushing, reproduce CI in a tracked-files-only tree built from `git ls-files`. Added to `CLAUDE.md` section 8, along with the standing requirement to watch a run to completion after every push.

## Rationale / Context
Two of this session's costs came from trusting a working tree instead of a clone, and from generalising a Voronoi correctness claim from two fixtures to all of them. Both are recorded here because they are cheap to repeat.

## Impact
`blender-tool/syndicate_fracture/{geometry,fracture}.py`, `blender-tool/tests/blender/test_voronoi_properties.py`, `.gitignore`, `build-logic/src/main/kotlin/dev/syndicate/build/SourceTrackingCheckTask.kt`, `.github/workflows/ci.yml`, `CLAUDE.md`, `docs/captures/`. Open: DEV-004, D04-S4.4's remaining system catalogue, D12-S5.4 stages 3-7.

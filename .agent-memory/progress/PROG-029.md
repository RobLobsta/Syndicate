# PROG-029: the Blender toolchain — fracture, and a model in a vehicle out

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/14_test_environment.md#D14-S5.13

**Status:** superseded (by PROG-040)

Supersedes: PROG-008
Supersedes: PROG-024

## Summary
Two Python CLI tools run under headless Blender 4.2. `syndicate_fracture` decomposes a mesh into
seeded Voronoi shards with damage-state shape keys, per-shard mass, collision hulls and a glTF
export, and verifies its own output. `syndicate_prepare` takes a downloaded car model and writes
about twenty-five named parts plus an assembly the game loads. Both have been run on real content.

## Details

**Scope:** `blender-tool/`, `test-environment/`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Voronoi fracture, shape keys, hulls, export | done | Exact solid-BSP decomposition, not V-HACD (DEC-011) |
| Self-verification and exit codes | done | The tool checks its own output before reporting success |
| Preparation: repair, classify, roles, hinges | done | Geometry decides what a part is, never the file's names (DEC-042) |
| Preparation: destruction authoring, export | done | Doors open and dent; every pane shatters into 24 shards |
| Run on both shipped cars | done | Axles land on the same millimetre as the hand-authored content |
| Harness re-verification in-engine | done | `test-environment` re-checks output inside real Bullet |
| Vehicles that are not cars | not_started | A tank comes out as one immobile lump — no road-wheel set, turret or track |
| Fracture manifests for the shipped parts | not_started | Tool runs, not a project — see PROG-028 |

`syndicate_dissect` is the **superseded** first-generation cutter: it splits a model into a chassis
and four wheels, which is what the currently shipped content was made with. `syndicate_prepare`
replaces it. It is still linted and still present because the shipped cars came from it; nothing new
should use it.

## Rationale / Context
Two entries described the same pipeline at two moments, and a reader had to know which came second.
More importantly, the limit found by trying — that it makes *cars* — is a fact about the tool that
belongs in its state, not in one session's story.

## Impact
- Adding a vehicle is a command, not a project, provided it is a car.
- Every threshold in the preparation tool was measured against the same two cars; DISC-041's lesson
  (two examples are not a test set) applies to any change here.

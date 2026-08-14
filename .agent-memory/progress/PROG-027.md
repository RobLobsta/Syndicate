# PROG-027: the client — window, render, audio, input, and the shell

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/03_runtime_modes.md#D03-S5.3, docs/01_product_game_design.md#D01-S3, docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

Supersedes: PROG-021
Supersedes: PROG-022

## Summary
The client opens on a title screen, leads to a garage where the player picks a vehicle from the real
art, and deploys into a match it can leave again. Rendering, the HUD, the chase camera, particles,
gamepad and keyboard input, and a runtime engine synthesiser all work. Every screen has been
photographed from the real client running under a virtual display.

## Details

**Scope:** `game-client` — `shell`, `render`, `present`, `audio`, `input`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Fixed-timestep client loop | done | Frame rate decides step count, never step length (D03-R10) |
| Screen shell: menu, garage, match | done | `GameShell` owns transitions; a match's world is built and torn down per match |
| Garage vehicle preview | done | Real `mesh.glb` at real slot transforms; stats from `VehicleProfile` |
| Menu type | done | FreeType + Oswald (SIL OFL), falling back to the built-in font (DEC-072) |
| Render: parts, arena, morphs, particles | done | PBR through gdx-gltf; collision nodes stripped from instances |
| HUD and scoreboard | done | Speed, health, parts, phase, clock, scores |
| Chase camera | done | Two half-lives, neither tuned by a person |
| Input: gamepad and keyboard | done | Peers, not fallbacks; active device observed (DEC-048) |
| Engine audio | done | Synthesised at runtime, own mixer and device (DEC-055, DEC-052) |
| Terrain rendering | not_started | The generator exists and nothing draws it — see PROG-030 |
| Mix and voicing balance | not_started | Every gain is a first guess; nobody has balanced them by ear |
| Options / settings screen | not_started | No way to change resolution, volume or bindings in-game |

**The client is buildable and runnable in the development sandbox.** `./gradlew check` builds and
tests it, and `xvfb-run` plus `--capture` produces a real screenshot. DISC-024 recorded the opposite
and is superseded by DISC-046; a session that still believes the client cannot be verified here will
defer work for no reason.

## Rationale / Context
Two entries in this lineage were about engine audio alone, which made the client's overall state hard
to read: a session looking for "can I see this change?" had to infer the answer from a note about
exhaust pulse trains. The state that matters is which screens exist and whether they can be looked at.

## Impact
- Any visual change should be verified by capture, not by inspection — the recipe is in DISC-046.
- Terrain rendering is the largest unstarted piece of client work and is no longer environment-blocked.

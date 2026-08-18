# PROG-039: the client — window, render, audio, input, the shell, and debris that draws

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/03_runtime_modes.md#D03-S5.3, docs/01_product_game_design.md#D01-S3, docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

Supersedes: PROG-027

## Summary
The client opens on a title screen, leads to a garage where the player picks and arms a vehicle from
the real art, and deploys into a match it can leave again. Rendering, the HUD, the chase camera,
particles, gamepad and keyboard input, and a runtime engine synthesiser all work. New this session:
**debris draws.** A shard used to be a physics body nothing rendered, so authored destruction was
invisible even once it ran.

## Details

**Scope:** `game-client` — `shell`, `render`, `present`, `audio`, `input`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Fixed-timestep client loop | done | Frame rate decides step count, never step length (D03-R10) |
| Screen shell: menu, garage, match | done | `GameShell` owns transitions; a match's world is built and torn down per match |
| Garage vehicle preview and loadout | done | Real `mesh.glb` at real slot transforms; hardpoints armed per DEC-084 |
| Menu type | done | FreeType + Oswald (SIL OFL), falling back to the built-in font (DEC-072) |
| Render: parts, arena, morphs, particles | done | PBR through gdx-gltf; collision nodes stripped from instances |
| **Render: debris shards** | done | One node of `shards.glb` per shard, moved onto its own origin (DEC-086) |
| HUD and scoreboard | done | Speed, health, parts, phase, clock, scores |
| Chase camera | done | Two half-lives, neither tuned by a person |
| Input: gamepad, keyboard, and a script | done | Peers, not fallbacks (DEC-048); `--script` drives a capture. All four axes bound on all three (2026-08-18) |
| Engine audio | done | Synthesised at runtime, own mixer and device (DEC-055, DEC-052). Bus no longer dies mid-match — DISC-073 |
| Terrain rendering proper | not_started | The ground is drawn; chunking, LOD and textures are not — see PROG-036 |
| Mix and voicing balance | not_started | Every gain is a first guess; nobody has balanced them by ear |
| Options / settings screen | not_started | No way to change resolution, volume or bindings in-game |

**How a shard finds its mesh.** `RenderSystem` (slot 26) gained a second undrawn family — debris,
which has no `PartRefComponent` because the part it broke off was destroyed in the tick that made it
(DEC-018, D04-E1). What it does carry is `RigidBodyComponent.shapeKey`, and a `SHARD_HULL` key names
the manifest and the index within it; the manifest then gives the node name in `shards.glb` that the
loader took the hull from. That shared name is why `ShardDefinition.meshNodeName` exists rather than
each side inventing a convention (DEC-086). `RenderSystem` therefore takes an `AssetIndex`, as slot 25
already did.

**Verified by capture, as it has to be.** Frames 120 and 160 of a scripted drive show the fragments on
the ground and in the air at the size a windscreen's pieces should be. A shard offset by its own
placement a second time would have been a metre away and would still have passed every test in the
repository.

## Rationale / Context
The row above this one used to read "Render: parts, arena, morphs, particles — done", and it was
accurate about parts while being silent about the one archetype that had geometry and no renderer.
That silence is how the whole authored destruction path stayed invisible: nothing claimed to draw
debris, so nothing reported that it did not.

## Impact
- Any visual change should be verified by capture, not by inspection — the recipe is in
  `RUNNING_THE_CLIENT.md`, and `--script` is what lets a capture reach anything that needs a verb.
- `PartModels` now caches by `partTypeId + "/" + file`, so a part holds a `mesh.glb` and a
  `shards.glb` entry independently. `loadedCount()` counts both.

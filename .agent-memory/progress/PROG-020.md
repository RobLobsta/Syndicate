# PROG-020: the game has a window; 23 of the 27 systems exist

**Date:** 2026-08-11
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/03_runtime_modes.md#D03-S5.1, docs/03_runtime_modes.md#D03-S5.3

**Status:** active

## Summary
`game-client` boots a real window, builds the same world `ServerRuntime` does, and runs the five presentation slots on top of it. Eight cars drawn from their own art, an arena, a chase camera, a HUD, damage morphs, particle bursts and a 52-sound bank now have a process that uses them. Twenty-three of D04-S4.4's 27 systems exist; the four that remain are all networking.

## Details

**Scope:** `game-client` (all of it), `game-core` `ecs`/`system`/`damage`, `build-logic`, `docs/02`.

**Status of Work:** (supersedes PROG-018 for the schedule; PROG-019 keeps the content half)

| Area | State | Notes |
|---|---|---|
| `InterpolationSystem` (22) | done | Samples once per tick, lerps/slerps per frame (DEC-050). Snaps past 4 m |
| `DamageVisualSystem` (23) | done | D07-S5.5 exactly, asserted against the blueprint's worked table. Applies by morph target *name* when the file carries them |
| `EffectSystem` (24) | done | Sparks, shards, debris puffs and smoke from four event families. One entity per burst, capped at 96 live |
| `AudioSystem` (25) | done | Engine loops keyed on configuration and pitched from `EngineVoice`; impacts by material and severity; detach by destruction class; glass shatter by size |
| `RenderSystem` (26) | done | gdx-gltf PBR, one `SceneAsset` per part type, `_col` nodes dropped from instances |
| Chase camera | done | Trails heading with a frame-rate-independent half-life; pulls back and widens with speed |
| HUD | done | Speed, chassis health, live part count, phase, clock, scoreboard, device, fps |
| `ClientLoop` | done | D03-S5.3's fixed step, clamp, catch-up cap and alpha, with tests per line |
| `ClientRuntime` | done | D03-S5.1 steps 3–8 and D03-S5.6's teardown, on the same factories the server uses |
| Local player join | done | `LocalPlayerFactory`, player id 0, joined before the lobby is filled |
| Capture mode | done | `--capture FILE --capture-frame N` runs the real client and writes a PNG |
| `checkCosmeticIsolation` | done | AC-D07-10 / T-D07-14 as a build gate, verified against a deliberate violation |
| Tyre roll and skid audio | not_started | Needs per-wheel slip and surface, which no component exposes |
| Weapon fire and impact audio | not_started | Slots 8 and 9 emit no events for them |
| Debris settle audio | not_started | Needs a "came to rest" signal the debris path does not produce |
| Slots 2, 18, 19, 20 | not_started | The whole of what is left, and all of it networking |

**What it looks like:** captures written from the real client under Xvfb on llvmpipe at 1600x900 — the Eclipse drawn from its own textures, the arena floor gridded at 5 m, walls, seven bots, a live scoreboard and clock. 41 model instances a frame, ~8 fps on software GL.

## Rationale / Context
Every progress entry since PROG-014 has ended with some version of "nothing renders except the harness". That sentence is no longer true, and the next session needs to know which of the remaining gaps are real work (networking) and which are waiting on a signal that does not exist yet (three of the seven audio families).

## Impact
- Supersedes PROG-018's schedule count of 17/27.
- `docs/02_technical_architecture.md#D02-S4.5` amended: `game-client` gains gdx-controllers and Jackson.
- Two bugs found by running it: DISC-021 and DISC-022.

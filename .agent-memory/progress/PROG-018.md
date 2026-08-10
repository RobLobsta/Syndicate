# PROG-018: a match plays itself, headless, from lobby to scoreboard

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/11_ai_bots_and_match_simulation.md#D11-S5.7, docs/11_ai_bots_and_match_simulation.md#D11-S5.8

**Status:** active

## Summary
Slots 3 and 4 exist, and with them the simulation stops being a sandbox: `MatchSimulatorMain` plays complete matches with no window, eight bots driving four hundred metres each, and writes a report. Seventeen of D04-S4.4's 27 systems now exist, and slot 1 joins them from the client side.

## Details

**Scope:** `game-core` `system`/`match`/`ai`, `game-client` `input`, `game-server-headless`, `assets/`.

**Status of Work:** (supersedes PROG-017 for the areas it names)

| Area | State | Notes |
|---|---|---|
| `MatchFlowSystem` (slot 4) | done | D11-S5.7's five phases, both gates, respawns, sudden death. Input is gated by erasing intent at slot 4 rather than by six later systems checking a flag |
| `BotDecisionSystem` (slot 3) | done | D11-S5.3's six steps. Perception is the enforcement point for AC-D11-2. **17 of 27 systems** |
| `InputCollectionSystem` (slot 1) | done | DEC-048. Gamepad and keyboard as peers; the live device is observed, not configured |
| Offline match simulation | done | D11-S5.8. `MatchSimulatorMain`, a `MatchReport`, and determinism asserted as record equality |
| Win conditions | done | D01-S5.5 for every mode, with every tie-break explicit |
| Bot navigation | in_progress | D11-E4's direct steering, logged once at ERROR. No arena ships a navmesh and the generator is unwritten |
| Weapon content | not_started | The firing path is implemented and tested; no shipped part is a weapon, so every match is currently a draw |
| Networking (18-20), renderer (22-26) | not_started | as PROG-017 |
| Fracture manifests, damage morphs, `schemas/` | not_started | as PROG-017 |

**What runs.** `MatchFlowSystem` (4) is D11-S5.7's state machine — LOBBY fills with bots and puts everyone on the grid, COUNTDOWN lets the cars settle with nothing driven and nothing damageable, ACTIVE runs the clock and the win condition, ENDING lets the last wreck play out, RESULTS holds the scoreboard. `BotDecisionSystem` (3) is D11-S5.3's six steps, perceiving only through a delayed error-injected `SensorSnapshot` and writing only `PlayerInputComponent`. `InputCollectionSystem` (1) does the same for a human, from a gamepad or a keyboard.

**What it proves.** `./gradlew :game-server-headless:installDist` then `MatchSimulatorMain --bots 8 --time-limit 60` gives a whole match at 0.25 ms a tick, every bot driving, no native resources outstanding at teardown, and the same report twice from the same seed.

**What is not there.** Navigation is direct steering with obstacle avoidance rather than a navmesh path — no arena ships one and the generator is unwritten, which is exactly D11-E4's case, logged once at ERROR. No weapon part exists, so a match is currently cars circling each other: every bot scores zero and every match is a draw. That is a content gap, not a systems one; the firing path is implemented and tested.

**The systems that remain** are networking (18-20) and the renderer's five (22-26).

## Rationale / Context
The three progress entries before this one each named the same blocker in different words — "nothing starts a match". It starts now, which moves the honest question from "can this run?" to "is any of it fun?", and the second question needs a weapon in `assets/` far more than it needs another system.

## Impact
`game-core`'s new `match` and expanded `ai` packages, `game-client`'s `input` package, `MatchSimulatorMain`, and the shipped arena's twelve spawn points.

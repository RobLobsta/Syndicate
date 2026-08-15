# PROG-031: opponents, match flow, and the headless runner

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/11_ai_bots_and_match_simulation.md#D11-S5.6, docs/11_ai_bots_and_match_simulation.md#D11-S5.8, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

## Summary
A match runs from lobby through countdown, active play and ending to results, with bots that drive,
choose targets and fight. The whole thing plays with no display, which is what makes balance sweeps
possible. No bot has ever beaten a person, because no person has played one.

## Details

**Scope:** `game-core` `ai`, `match`; `game-server-headless`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Match state machine | done | Lobby → countdown → active → ending → results |
| Bot behaviour tree, sensors, difficulty | done | Same `InputCommand` a human produces (G17) |
| Scoring and scoreboard | done | Kills, deaths, score, per-team |
| Headless match runner | done | A full match, no display, a report at the end |
| Dedicated server process | done | Runs; has no transport to accept anyone over |
| Navmesh navigation | not_started | Bots steer directly with obstacle avoidance, which D11-E4 permits |
| Difficulty calibration | not_started | The scale exists; nobody has lost to it |

## Rationale / Context
The headless runner is the machinery a balance pass depends on, and it is easy to forget it exists
and tune by playing instead. Eight bots fighting for sixty seconds with a report at the end is a
faster loop than a human driving, for every number that is not about feel.

## Impact
- Balance work should start from the offline runner and confirm by playing, not the reverse.
- The navmesh gap is a known, specified degradation rather than a defect.

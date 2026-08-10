# SESS-019: a match that plays itself, a car cut up by geometry, and a noise

**Date:** 2026-08-10
**Category:** session_summaries
**Related Docs:** docs/11_ai_bots_and_match_simulation.md#D11-S5.7, docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S8

**Status:** active

## Summary
Phase 8 landed — slots 3 and 4, and a headless match runner that plays a whole match with no display. Then the D15 preparation pipeline, the shared material decision, and a synthesised sound bank in which different cars sound different.

## Details

**Four commits.** A match that starts and bots to fight it; a whole match played with no display; the shared material and a car that makes a noise; segment a car by geometry and give each one its own engine; then the input layer.

**Six bugs found by running things rather than by reading them.** `World.dispose` freed every entity's Java object and leaked every native one, because it disposed the schedule before recycling the entities and native release belongs to slot 27. A free-for-all got two of six spawn points because `ANY_TEAM` and `FREE_FOR_ALL` are both -1 and the filter compared them for equality. Slot 4 chooses spawn points and slot 5 creates vehicles, so a starting grid saw an empty world and handed the same point out repeatedly. A bot pointing away from its destination got 0.15 throttle and never tripped a stuck detector watching for 0.5. Obstacle avoidance pushed straight back from anything dead ahead, which cannot steer round it. And calling `load_model`'s stages twice deleted 94% of a car.

**Three decisions.** DEC-045: the material says what a part is made of and the part says how it fails — the first cut put `destructionClass` on the material and a chassis rail and a door skin can be the same steel. DEC-046: the bank is synthesised, and the reason is the licence rather than the convenience. DEC-047: engines are keyed on configuration, not weight class, which is what makes two cars sound different.

**Two spec gaps filled.** Input devices, which no blueprint specifies (DEC-048), and D15's stages 6 to 8, which are named as pending in the report rather than quietly skipped.

## Rationale / Context
The pattern from SESS-017 held again and is worth naming a third time: every bug this session was invisible to the tests and visible the moment something ran end to end and printed what it did. The stuck bot, the leaked natives and the deleted car were all found by reading output, not by a red test.

## Impact
`game-core` gains `match` and a real `ai`; `game-client` gains `input`; `blender-tool` gains `syndicate_prepare`; `asset-pipeline` gains `audio`. 17 of D04-S4.4's 27 systems exist, plus slot 1 from the client.

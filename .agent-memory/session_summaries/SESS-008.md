# SESS-008: Implement EntityDestroySystem for deferred destruction

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/04_entity_component_model.md#D04-S5.5

**Status:** active

## Summary
Implemented `EntityDestroySystem` to execute the ECS CLEANUP phase (slot 27). This system safely processes `World`'s destroy queue, recursively tears down vehicle components and constraints, and ensures stable disposal of C++ Bullet native resources. Tested and verified logic under D04 constraints.

## Details
Following progress in PROG-003, `game-core` needed to manage entity destruction within the scheduled ECS lifecycle to ensure safe native teardown. It's critical to tear down Bullet physics constraints *before* their associated rigid bodies to avoid segfaults.

- Modified `World.java` to expose queue primitives and shift teardown to system schedule.
- Created `EntityDestroySystem` handling recursion and safe 2-pass constraint-then-body native destruction.
- Added `EntityDestroySystemTest` providing coverage for T-D04-10.
- Updated `PROG-004`.

## Rationale / Context
The 2-pass destruction loop in `EntityDestroySystem` is fundamentally necessary. An entity holding a body could be attached via a constraint to an entirely different entity. Both might be in the queue. Both constraints must be released before either body is disposed.

## Impact
`game-core`, ECS lifecycle.

# PROG-004: EntityDestroySystem handles deferred destruction and native teardown

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S5.5, docs/04_entity_component_model.md#D04-S4.4

**Status:** superseded (by PROG-005)

## Summary
`EntityDestroySystem` (slot 27, CLEANUP phase) is implemented. It safely tears down entities that were queued for destruction. This includes recursive child entity destruction, releasing native resources like Bullet physics rigid bodies and constraints in the proper dependency order, and returning component instances to their object pools. `World`'s internal destroy queue has been exposed properly so that `EntityDestroySystem` runs within the schedule rather than as a hidden operation.

## Details

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| `EntityDestroySystem` (slot 27) | done | Recursively expands queue via child traversal (chassis wheels/parts, slot graph parts). Tears down constraints across the entire queue first, then bodies, ensuring Bullet's dependency rules (D02-S5.7 rule 4/5) are followed. Recycles components and ID. |
| `World` destroy queue | done | `runDestroyQueue()` logic removed from `tick()` and extracted into `EntityDestroySystem`. `World` exposes `destroyQueue`, `destroyQueueSize`, and `recycleEntity` to support the system. |
| `NativeResourceTracker` | done | Now captures native resources being cleanly destroyed in ECS flow. |
| `game-core` system catalogue (D04-S4.4) | in_progress | 2 of 27 systems implemented. |

**History (append-only):**
- 2026-08-08 (i): Created `EntityDestroySystem` and tests verifying proper resource teardown order and tracking. Tested cascading destruction behavior for vehicles and individual body components. Double-destroy safety tests verified.

**Acceptance criteria now covered:** AC-D04-7 (destroying a vehicle destroys its parts and outstanding natives == 0) and AC-D04-8 (double-destroy safety).

**What the next session should pick up:** `MassPropertySystem` (slot 15) alongside the shape cache (D06-S5.2) and `FractureSystem` (slot 13).

## Rationale / Context
This implements the architectural rule that "no system can read a half-destroyed entity." By deferring native teardown to this system, we ensure simulation fidelity inside the active tick (G3, G19, D04-R15). Furthermore, tearing down all constraints in the batch *before* tearing down rigid bodies is necessary to prevent Bullet physics C++ segfaults.

## Impact
`game-core`, ECS lifecycle.

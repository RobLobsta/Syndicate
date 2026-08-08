# PROG-001: Build system and ECS core exist; simulation subsystems not started

**Date:** 2026-08-07
**Category:** progress
**Related Docs:** docs/00_master_index.md#D00-S4.2, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
The Gradle multi-project build, the guardrail check tasks, `shared-models`, the `game-core` ECS engine, and the `memory-system` tooling are implemented and green. Physics, vehicle, damage, net, AI, and match are not started; the asset schemas and the Blender tool remain the blocking prerequisites for everything downstream of them.

## Details

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Blueprint suite (D00-D14) | done | 430 stable IDs; `:validateDocs` passes as a CI gate |
| Gradle multi-project build (D02-S4.4) | done | 8 modules, wrapper 8.14.3, version catalog, Java 17 toolchain via foojay |
| Guardrail checks (D02-S5.6, D02-R9, D02-R13) | done | See DEC-006 for the task list and what each verifies |
| `memory-system` tooling (D13-S5.5, D13-S5.8) | done | `regenerateIndex` and `lintMemory` L1-L15 implemented and wired into `check` |
| `shared-models` constants + enums | done | D00-S6.4 constants, AssetId, 8 domain enums |
| `shared-models` LaunchConfig (D03-S4.2) | done | Full field set, precedence, validation; see DEC-007 for placement |
| `game-core` ecs (D04-S4.5, S5.1-S5.7) | done | EntityId, Entity, World, Family, ComponentQuery, EventBus, schedule |
| `game-core` util (D06-S5.8) | done | Pcg32, RandomSource with per-subsystem streams, NativeResourceTracker |
| `game-core` component catalogue (D04-S4.3) | not_started | The ~30 component types; ECS engine is ready for them |
| `game-core` system catalogue (D04-S4.4) | not_started | The 27 systems and `SystemSetFactory.forMode` (D03-S5.2) |
| `game-core` physics (D06) | not_started | `PhysicsWorld`, `ShapeCache`, ray-cast vehicle |
| `game-core` vehicle + damage (D05, D07) | not_started | |
| `game-core` net (D10) | not_started | Wire type ids need `component_types.txt` first (D04-R22) |
| `game-core` ai + match (D11) | not_started | |
| `game-client` | not_started | `ClientMain` resolves config and exits; no window, no rendering |
| `game-server-headless` | not_started | `ServerMain` resolves config and exits; no tick loop |
| `asset-pipeline` + `schemas/` (D08) | blocked | Blocked on the D08-S6.1 schema catalogue, which does not exist yet |
| `blender-tool` (D09) | not_started | Package skeleton, ruff config, exit-code constants, Gradle Exec tasks |
| `test-environment` (D14) | blocked | Blocked on `blender-tool` fixture output (D14-S7.3) |
| Test fixtures + golden manifests (D14-S7) | blocked | Blocked on `blender-tool` |
| CI pipeline (D12-S5.4) | in_progress | Stages 0, 1-2, 7 implemented in `.github/workflows/ci.yml`; 3-6 await their subsystems |

**History (append-only):**
- 2026-08-07: initial state recorded; blueprint suite completed
- 2026-08-08: build system, guardrails, shared-models, ECS core, memory tooling -> done; 65 unit tests green

## Rationale / Context
A session opening this repository needs to know which of the eight modules contain real code and which are shells that compile, since all eight now build successfully. Without this table, "the build is green" would be mistaken for "the game runs" — no module yet boots past configuration resolution.

## Impact
Every module in D02-S4.5. The next session's obvious starting point is the D04-S4.3 component catalogue, which the ECS engine is now ready to carry.

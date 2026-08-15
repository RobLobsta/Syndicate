# SESS-003: Component catalogue, destruction tool, and verification harness

**Date:** 2026-08-08
**Category:** session_summaries
**Related Docs:** docs/04_entity_component_model.md#D04-S4.3, docs/09_blender_destruction_tool.md#D09-S5.1, docs/14_test_environment.md#D14-S5.2

**Status:** active

## Summary
Took the repository from "does not compile" to a working destruction pipeline: a mesh goes in, comes out fractured and verified by two independent implementations, and is rendered mid-explosion from the same simulation the checks measured. Cube and sphere captures are in `build/captures/`.

## Details

**Delivered:**
- The six `build-logic` guardrail task classes SESS-002 recorded as done but never committed. A clean clone failed at `:build-logic:compileKotlin`; nothing built.
- The D04-S4.3 component catalogue: 31 components, `ComponentCatalogue` as the append-only wire order of D04-R22, and the value types they are declared in terms of (`StatBlock`, `SlotNode`, `Transform`, `RingBuffer`, `ShapeCacheKey`, `SensorSnapshot`, `InputCommand`, ...).
- The Blender tool (D09): all seven stages, TV-001..TV-012 self-verification gating exit 0, the D09-S4.3 exit-code table, and `assets/materials/materials.json`.
- The verification harness (D14): a GL-free GLB reader, a Bullet `TestWorld`, `DestructionScene`, 31 ASSET/PHYS/PROG checks, the D14-S4.4 report, and a visual mode that captures PNG.

**Verification performed:** `./gradlew spotlessApply check` green (154 JVM tests). `ruff` clean, 68 Python unit tests. Four fixtures process to exit 0 and verify **31/31** in Bullet via `:test-environment:verifyFixtures`. Two runs at one seed produce byte-identical manifests and an equal `topologyHash` (G11). Masses land on their analytic values: cube 7850.0 kg, plate 1570.0 kg.

**Two real bugs the checks caught, which is the point of them existing:**
- PROG-004 failed because the scatter impulses carried an upward bias that summed to a net momentum injection — a free shove every fracture would have given a vehicle. Impulses are now corrected to sum to zero before any becomes velocity.
- PHYS-008 could not pass for the cube and the sphere at once, which turned out to be a double-counted collision margin in hull construction (DISC-004).

**Entries created:** DEC-008, DISC-002/003/004, DEV-002/003/004, PROG-002.

**Not done:** the D04-S4.4 system catalogue and everything downstream (physics, vehicle, damage, net, AI, match); VEH-* and GOLD-* checks; golden manifests; `test_complex_hollow` (DEV-004); the visual mode's console and overlays.

## Rationale / Context
The starting state was misrecorded — PROG-001 and SESS-002 described a green build and 65 passing tests for a tree that did not compile. Half this session's value is that the record now matches what a clean clone does, verified by running it.

## Impact
`build-logic/`, `game-core/component/`, `blender-tool/`, `test-environment/`, `assets/materials/`, `fixtures/meshes/`, and D00-R7 (which now reserves section path `99.9`).

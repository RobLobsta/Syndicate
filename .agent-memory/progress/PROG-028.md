# PROG-028: content — the shipped assets and the runtime that loads them

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.2, docs/08_asset_pipeline.md#D08-S5.2, docs/05_vehicle_part_system.md#D05-S5.6

**Status:** superseded (by PROG-034)

Supersedes: PROG-012

## Summary
Two vehicles ship, calibrated against published figures for real cars and held to them by tests. The
loader reads parts, assemblies, arenas, materials and the sound bank, validates them independently of
the runtime, and degrades rather than refusing to start. The roster is thin: six parts, no weapons, no
armour panels, and one drawable arena.

## Details

**Scope:** `assets/`, `game-core` `asset`, `VehicleProfiles`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Part, assembly, arena, material loading | done | Independent validation with error codes (DEC-041) |
| Two calibrated vehicles | done | Eclipse (MC20-derived), Stampede (Mustang GTD-derived) |
| Per-part meshes and collision hulls | done | One `mesh.glb` per part, `_col` node beside the visual |
| Sound bank | done | 47 files plus the runtime synthesiser |
| Menu typeface | done | `assets/fonts/`, SIL OFL |
| Weapon parts | not_started | Eight families in D01, zero parts in `assets/` |
| Armour parts | not_started | Only in `game-core` test resources |
| Fracture manifests for shipped parts | not_started | A destroyed wheel detaches whole instead of breaking up |
| JSON schemas enforced at load | not_started | `schemas/` exists; content fails on whichever field a hand-written check reads first |
| The 25-part cut of each car | not_started | The pipeline produces it; the game still loads the old 4-part output |

**The single largest coherence gap in the project is that there are no weapons.** Every system that
fires, tracks, impacts and scores a weapon hit is implemented and tested, and there is nothing in
`assets/` for any of it to act on.

## Rationale / Context
It is easy to read the system catalogue and conclude the game is content-complete. It is not: the
gap is not in code, and the sessions most likely to be surprised by it are the ones planning a
playtest.

## Impact
- A playable-feeling alpha needs content work, not systems work.
- Overwriting the shipped 4-part cars with the pipeline's 25-part output is a deliberate content
  decision and is sequenced in `ROADMAP.md`.

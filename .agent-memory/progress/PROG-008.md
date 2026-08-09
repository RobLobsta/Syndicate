# PROG-008: the destruction toolchain works end to end and has not been touched since

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.1, docs/14_test_environment.md#D14-S5.2, docs/13_persistent_memory_system.md#D13-S4.1

**Status:** active

## Summary
The Blender destruction tool fractures a mesh, verifies its own output and exits with the D09-S4.3
codes; the libGDX + Bullet harness re-verifies that output on the game's own physics world, and its
visual mode renders the result. All five D14-S7.1 fixtures pass. This entry exists because that state
was last recorded in PROG-002, which a global supersession chain had buried (DEC-023).

## Details

**Scope:** `blender-tool` and `test-environment` — the two halves of the destruction toolchain, which
talk to each other by file and exit code only (D02-S4.5).

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Fracture (D09-S5.2) | done | Exact Voronoi cells by half-space intersection; a non-convex source is decomposed by solid BSP first (DEC-011). Cell Fracture is unavailable and is not used (DEV-002) |
| Shard count | done | 100–200 shards on the fixtures; `MAX_SHARDS_PER_PART` raised to 256 after measurement (DEC-009) |
| Mass assignment (D09-S5.4) | done | Volume × material density from the shared `materials.json`; G7 holds on every fixture |
| Collision hulls (D09-S5.5) | done | Simplification maximises remaining volume rather than minimising volume increase (DEV-003) |
| glTF export (D09-S5.6) | done | Draco off; stdout is redirected so the tool's JSON contract survives Blender's own writes (DISC-002) |
| Self-verification (D09-S5.7) | done | Exit codes are protected from `bpy` teardown segfaults by `os._exit` (DISC-003) |
| Fixtures (D14-S7.1) | done | All five process to exit 0, including `test_complex_hollow` (DEV-004, resolved). `test_complex_hollow` still lacks its internal rib and weighs 2127 kg, not 2500 (DEV-006, open) |
| Harness physics (D14-S5.5) | done | `TestWorld` delegates to `game-core`'s `PhysicsWorld` (DEV-007, resolved), so both share one collision margin |
| Harness GLB reading | done | `GlbReader` is now an adapter over `game-core`'s `GltfReader` rather than a second parser (DEC-035). Still deliberately not gdx-gltf (DEC-008, DEC-013), and it gains node transforms and `.gltf` support it silently lacked |
| Source-art checks (`--model`) | done | Ten `MODEL-nnn` checks over an unprocessed model — geometry, finiteness, external resources, metres, up axis, long axis, ground plane, skinning, degenerate faces, triangle budget — plus a textured two-view render. Headless except the render |
| Visual mode (D14-S5.13) | done | Mid-explosion captures render; the headless runner creates no GL context |
| Hull vertex budgets | blocked | `btShapeHull` reduces to a fixed 42 directions and no further, so `MAX_SHARD_HULL_VERTICES` (32) cannot be enforced at runtime; meeting it is the tool's job (DISC-009) |
| Known defect: ray predicate | blocked | `fracture._inside_predicate` double-counts a ray grazing a shared edge (DISC-006) |

**Unchanged since:** 2026-08-08 (SESS-005 was the last session to touch the tool; SESS-007 was the
last to touch the harness). Nothing in the 2026-08-09 session modified either module, though
`game-core`'s `PhysicsWorld` gained ray-cast vehicle ownership, which the harness shares.

**History (append-only):**
- 2026-08-09: entry created to carry PROG-002's toolchain state forward as an active record. No
  toolchain work was done; this is a bookkeeping correction, not progress.
- 2026-08-09 (later): the harness gained `--model` mode (`ModelImport`, `ModelInspector`,
  `ModelScene`, `ModelRenderer`) and `GlbReader` became an adapter over `game-core`'s reader. The
  Blender tool was not touched. Both supplied car models pass all ten source-art checks and render.

**What the next session should pick up:** the Blender **split** — one whole-car model in
`art-source/vehicles/` into a chassis part and four wheel parts, which is what PROG-013 is now
blocked on and the first job the tool has had on real art. The two older open items are DISC-006's ray
predicate — a latent correctness bug that has not produced a wrong fixture yet — and DEV-006's
fixture, which does not match its D14-R20 description. Both are worth doing before the tool is
pointed at real art, and neither blocks the simulation work in PROG-007.

## Rationale / Context
A session following CLAUDE.md §4's read protocol reads `INDEX.md` and every **active** progress
entry. Before this entry, no active entry mentioned the destruction toolchain at all — PROG-006's
table said `asset-pipeline: not_started` and nothing else — so the honest conclusion from the memory
system alone was that the pipeline did not exist, and the likely next move was to build a second one.
DEC-023 explains the mechanism that caused it.

## Impact
`blender-tool`, `test-environment`. Restates the toolchain half of PROG-002, which remains superseded
and unedited (D13-S5.6).

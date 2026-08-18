# PROG-043: structures drawn, materialled, and shot at

**Date:** 2026-08-18
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S7, docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/08_asset_pipeline.md#D08-S4.3

**Status:** active

Supersedes: PROG-042

## Summary
**Structures are in the game and can be looked at.** They spawn, draw, take damage per material and
collapse into debris, photographed from the real client. Each of the three failure modes the design
asks for is shipped content: brick breaks apart, steel bends and twists, glass shatters.

## Details

**Scope:** `game-core` `physics` (`ArenaFactory` call sites), `client` `debug` (new),
`ClientLoop`, `ClientRuntime`, `MatchScreen`, `shared-models` (`DestructionClass`, `AudioMaterial`),
`asset-pipeline` (`AssetIndexBuilder`, `SoundBankBuilder`, `SoundSynth`), `blender-tool`
(`syndicate_structure`, `syndicate_policy`, `syndicate_fracture`), `assets/structures`,
`assets/materials`, `assets/audio`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Structures spawned at runtime | done | Was **never** happening: all three callers used the index-less overload (DISC-077) |
| Structures drawn by the client | done | No render work needed; the pass was already generic |
| Per-piece materials | done | Glazing is its own part (DEC-100); 21 parts across 5 structures |
| `MASONRY` destruction class | done | Sixth class in D15-S5.7; FRACTURE at 32 shards, no DEFORM |
| brick breaks apart | done | `str_city_block_low_01`, 3 bands x 32 shards |
| glass shatters | done | 6 glazing parts, 24 shards each |
| steel bends and twists | done | Rocket turret, 5 parts x 4 damage morphs |
| Material-aware toughness | done | `TOUGHNESS_BY_MATERIAL`; glass 0.05 to hardened steel 1.7 |
| TV-013 shard-bounds check | done | Catches the 465 km cell that was shipping (DISC-077) |
| Debug console | done | Time, spawn, AI, damage, view; `--console` drives it from a capture |
| Licence exception | done | D08-R1d, `status: development-exception`, A512 advisory / error under `SYNDICATE_REQUIRE_LICENCE=1` |
| Structures replicated (D16-S7.3) | not_started | Unchanged from PROG-042; nothing sends them |
| Stage 2b — rendering proper (D16-S6) | not_started | Chunking, culling, LOD. Needs a real GPU |
| Tall block fractures | blocked | Source art leans 1-3 m per band; the surface cut makes unbounded cells. Ships unfractured, noted |

**The five structures** now cut into 21 parts (was 14): the two city blocks gained a glazing part
per band, so a facade shatters independently of the wall carrying it.

## Rationale / Context
PROG-042 recorded "the client's render pass does not know they exist". It was drawing anything with
a `PartRefComponent` the whole time; what was missing was the spawn. Every claim in that entry about
structures being in the world was true only of the tests.

## Impact
- `assets/audio` gains 5 files: the `STONE` voice and `part_detach_masonry`.
- `materials.json` gains `brick`, and `concrete` moves from the `COMPOSITE` audio voice to `STONE`.
- The console is the tool ROADMAP step 2 depends on; tuning can now be done without a rebuild.

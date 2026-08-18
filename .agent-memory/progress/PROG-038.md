# PROG-038: simulation core — physics, vehicles, damage, and destruction that now has shards

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.6, docs/08_asset_pipeline.md#D08-S5.3, docs/06_physics_simulation.md#D06-S5.5

**Status:** superseded (by PROG-041)

Supersedes: PROG-026

## Summary
Every simulation system D04-S4.4 names exists, runs headless, and — new this session — the
destruction half is finally fed. `AssetLoader` reads `fracture_manifest.json` and the per-shard
geometry out of `shards.glb`, so `FractureSystem` resolves the reference it has always looked up.
Nothing here is blocked; what is missing is tuning by a person.

## Details

**Scope:** `game-core` — `physics`, `vehicle`, `damage`, `system`, `ecs`, `asset`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Fixed-step world and system schedule | done | 27 slots, fixed order, deterministic iteration (G2, G3) |
| Bullet world, layers, shapes | done | One body per vehicle, compound children per part (DEC-004) |
| Ray-cast vehicle, steering, brakes | done | Tuning from D06-S4.5, not Bullet's demo (DEC-029) |
| Stat aggregation and degradation | done | Recomputed every tick over live parts (DEC-025) |
| Hit resolution, armour, propagation | done | Shared operation; slot 12 is only its schedule slot (DEC-038) |
| Fracture, detach, debris | done | All four detach triggers; mass, COM and inertia in-tick (G10) |
| **Fracture manifests at runtime** | done | Read at load with the shard hulls (DEC-086, DEC-087) |
| Mass properties after structural change | done | Spawn establishes, slot 15 confirms (DEC-021) |
| Weapons and projectiles | done | Two weapons ship as assemblies of sub-parts (PROG-037) |
| Airborne speed clamp | done | Horizontal below 145 km/h, vertical below 55 m/s (DEV-019) |
| Balance of the numbers | not_started | Every constant is a blueprint default nobody has been hit by |

**What changed, and what it had been.** `FractureSystem` has always resolved
`FractureDataComponent.manifestRef` through `AssetIndex.fractureManifest`, and nothing had ever
called `InMemoryAssetIndex.put(FractureManifest)`. So the lookup missed on every glass part in every
match: the part was destroyed without shards and slot 13 logged an error saying so. The whole
authored destruction path — the Blender tool, the shards it cuts, the harness that verifies them —
had never once run inside the game. It now does, for the eight panes of glass the two shipped cars
carry.

The reader is `AssetLoader.loadFractureManifest` plus the `ShardMeshSource` seam
(`GltfShardMeshSource`), the sibling of the `CollisionMeshSource` seam that DEV-010 opened. It raises
D08-S5.4's own codes — A107, A202, A203, A501, A504, A505, A506 — the same vocabulary
`asset-pipeline` raises over the same files, with the two implementations independent as DEC-041
requires.

**Verified by looking at it.** A scripted drive (`--script`, `--seed 4242`) was photographed at
frames 120 and 160: glass fragments strewn on the tarmac beside a rolled Eclipse, and a burst of
shards around a Stampede that has just lost its windows. Both are the shard geometry at plausible
size and in the right place, which is the check that a doubly-offset shard would fail (DEC-086) and
that no unit test can make.

## Rationale / Context
The previous entry in this lineage listed "Fracture, detach, debris — done", which was true of the
code and false of the game. A row that says done for a path with no content behind it is exactly what
hid this for eleven sessions; the table above now separates the systems from the data they need.

## Impact
- A session tuning handling, damage or bots changes constants, not structure.
- A shard is a physics body and a drawable both — see PROG-039 for the drawing half.
- Determinism guarantees (G2, G3, G5) hold across this whole area and are checked by `-Ptags=physics`.
- Making a part other than glass shatter is still a **content** decision needing D15-S5.7 amended, not
  a batch command; the reader is now ready for whatever that decision produces.

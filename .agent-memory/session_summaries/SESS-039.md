# SESS-039: the fracture manifests are loaded, and the glass finally breaks into something

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/08_asset_pipeline.md#D08-S5.3, docs/09_blender_destruction_tool.md#D09-S4.4, docs/07_damage_destruction_model.md#D07-S5.6

**Status:** active

## Summary
Step 0 of the roadmap, done: the runtime reads `fracture_manifest.json` and the shard geometry out of
`shards.glb`, and the client draws the shards. The authored destruction path had never once run
inside the game — every pane of glass in every match was destroyed without shards, with an error in
the log — and it now runs for all eight shipped panes.

## Details

**The reader.** `AssetLoader` gained `loadFractureManifest` and a `ShardMeshSource` seam
(`GltfShardMeshSource`), the sibling of the `CollisionMeshSource` seam DEV-010 opened. It pairs each
manifest entry with its node in `shards.glb` by the name D09-S4.4 gives it, moves the geometry onto
the shard's own origin, and registers the manifest under the part's own id — the key `FractureSystem`
has always looked up and nothing had ever filled. Findings use D08-S5.4's codes.

**Two decisions, neither in a blueprint.** DEC-086: `shards.glb` is exported in the *part's* frame
and D07-S5.6 also composes the shard's `localTransform`, so the offset would be applied twice. Both
readers now invert it, and the manifest's per-shard AABB is checked against the exported geometry
(A501) so the space is verified rather than assumed. DEC-087: a manifest that will not load leaves
`fractureManifestRef` null, so the part takes the documented D07-E5 path and detaches whole rather
than carrying a reference that resolves to nothing and vanishing mid-match.

**The client half.** A shard was a physics body nothing drew. `RenderSystem` gained a debris family
and resolves each shard's model through the same node name the loader used.

**Looked at, not inferred.** A scripted drive under `--seed 4242`, photographed at frames 120 and
160: fragments on the tarmac beside a rolled Eclipse, and a burst of shards around a Stampede that
has just lost its windows. Models drawn per frame goes from 183 in a run with no fractures to 253–328
in one with them.

**One fixture correction.** Three `game-core` test parts declared `fracture_manifest.json` with no
such file beside them. Nothing had read the field, so nothing had caught it.

**Not done.** Only glass has shards — D15-S5.7 working as written, not a gap. The drives showed again
that the desert launches a car off its dunes at full throttle; that is open in the roadmap as a
question of taste.

## Rationale / Context
The roadmap called this "the cheapest large visible improvement left", and it was — but only because
both halves landed. Loading manifests without drawing shards would have changed the physics and
nothing a player sees, which is the shape of the mistake DISC-051 records.

## Impact
`game-core` `asset`; `game-client` `render`. Nine unit tests over the reader's rules, five
integration tests over the eight shipped manifests. `./gradlew check` green; the client built, driven
and photographed.

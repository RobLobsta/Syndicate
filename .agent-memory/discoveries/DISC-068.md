# DISC-068: DestructionClass gates nothing at runtime; the tools are the only enforcement

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/09_blender_destruction_tool.md#D09-S5.1, docs/07_damage_destruction_model.md#D07-S5.5

**Status:** active

## Summary
Nothing in `game-core` or `game-client` asks a part's `DestructionClass` before deforming or
fracturing it. A part dents because its mesh has shape keys and shatters because it declares a
manifest — both purely data presence. `DestructionClass.hasDamageShapeKeys()` is correct and is
called by one unit test and nothing else; the only runtime consumer of the enum at all is
`AudioSystem`, picking a break sound.

## Details
The reasonable assumption — and the one a session will make, because D15-S5.7 reads like a rule —
is that the class is enforced somewhere downstream. It is not, at any layer:

| Layer | What it checks |
|---|---|
| `AssetLoader` | that a declared manifest parses; never that the part may have one |
| `asset-pipeline` gate | A211/A212/A213 on morph *presence*; no rule pairs a class with a transform |
| `FractureSystem` (13) | `DamageStateComponent.state == DESTROYED` and a `FractureDataComponent` |
| `DamageVisualSystem` (23) | that the model instance has a `WeightVector` to write to |

So `syndicate_fracture` — which authors **both** a shard set and four damage morphs in one pass, by
default, for any mesh it is handed — can produce a steel door that dents while it is damaged and then
shatters into 24 shards when it dies, and every one of those layers will perform it without comment.
`fracture_manifest.json` carries no `destructionClass` and no transform discriminator, so there is
nothing for a gate to check even if one wanted to.

The shipped content is correct anyway, for one reason: `syndicate_prepare` holds the invariant by
hand. Its `destruction.TREATMENTS` table transcribes D15-S5.7 faithfully, and it applies the two
halves in two different places — `exporter.export_part` subdivides and morphs, `exporter.fracture_glass`
calls the fracture tool with `damage_morphs=0`. Correctness therefore lives in one orchestrator's
discipline rather than in the tool, the format, the gate or the engine.

Also found in the same pass, and cheap to fix: `--verify-only` and `--keep-blend` are in D09-S4.2's
table, are parsed and validated by `cli.parse`, and are read by nothing. `--verify-only`'s documented
meaning is "produce no new data"; its behaviour is a full destructive run that overwrites the outputs
it claims to be checking. And `--shards` is clamped to `[2, 256]`, so the tool cannot be asked *not*
to fracture — `--damage-morphs 0` disables deformation but there is no counterpart.

## Rationale / Context
The concrete future failure: D15-S5.7 is amended to give shards to a second class — the open question
the roadmap already carries — and somebody runs `syndicate_fracture` over a batch of panels to
produce them. Every one of those panels comes back with dent morphs it must not have, the fixture
gate goes green because it does exactly the same thing to five steel fixtures, and the first evidence
is a door that crumples and then explodes on somebody's screen.

## Impact
`blender-tool/README.md` is the written-up version of this, with the transform model, the six
findings ranked, and a target shape (split the tool per transform, put `transform` and
`destructionClass` in every manifest, add an asset-gate rule pairing them). Nothing is fixed yet;
this entry and that file are the record of why it needs to be.

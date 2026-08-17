# SESS-040: the Blender suite reviewed, and given a design document of its own

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/17_weapon_system.md#D17-S4.2

**Status:** active

## Summary
A review of all four Blender packages against one question: does a tool that produces shards produce
only shards, and does the part it produced them for break like glass and only like glass? The answer
is no at the tool layer and yes at the orchestrator layer, which is why the shipped content is
correct and the suite is not. `blender-tool/README.md` is the write-up.

## Details

**The finding**, recorded as DISC-068 after reading all four packages, the Gradle fixture gate and
the four runtime layers that read their output: `DestructionClass` gates nothing at runtime. A part dents
because its mesh has shape keys and shatters because it declares a manifest — data presence, not
policy. `syndicate_fracture` authors *both* transforms in one pass by default (`--shards 24` and
`--damage-morphs 4`), and cannot be asked not to fracture at all, because `--shards` is clamped to a
minimum of 2. The fixture gate runs all five fixtures — every one steel or aluminium — with
`--damage-morphs 4`, so the gate that proves the tool works proves it doing the forbidden thing.

**Why nothing has broken.** `syndicate_prepare` holds the invariant by hand: its `TREATMENTS` table
transcribes D15-S5.7 and the two halves are applied in two different places, with
`exporter.fracture_glass` calling the fracture tool with `damage_morphs=0`. One orchestrator's
discipline is the whole enforcement.

**Also found:** `--verify-only` and `--keep-blend` are in D09-S4.2, parsed, validated, and read by
nothing — the first's documented meaning is "produce no new data" and its behaviour is a destructive
overwrite. And the three tools disagree about what exit 65 and 66 mean.

**The document.** `blender-tool/README.md`, ~350 lines, the suite's entry point. It defines a
**transform** as a triple — authoring output, runtime representation, runtime trigger — and tabulates
the four the project has (DEFORM, FRAGMENT, DETACH, ARTICULATE) with the properties that follow:
deform is continuous, cosmetic and reversible; fragment is discrete, authoritative and terminal;
collision geometry never deforms. It carries the six findings ranked, a target shape (split the tool per
transform, name the transform and the class in every manifest, add a gate rule pairing them, rename
for the model), and an assessment of transforms not yet built — real-time fracture, melting,
piercing, impact-directed denting — against the architecture rather than against Blender.

**Nothing was changed in the tools.** The review is the deliverable; the fixes are scoped in the
README's §7 and not started, because renaming packages and manifests is a content migration and that
is the user's call to schedule.

## Rationale / Context
The pattern worth keeping from the transform table: every cheap future transform reuses DEFORM's or
FRAGMENT's runtime representation, and every expensive one demands a new one. That is the question to
ask of a proposal first, before asking what it would look like.

## Impact
`blender-tool/README.md` (new), DISC-068, ROADMAP §4. No code changed; no test changed.

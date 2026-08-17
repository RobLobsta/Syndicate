# DISC-069: a Blender patch release moves the topology hash and the shard masses

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S8, docs/00_master_index.md#D00-S5.2

**Status:** active

## Summary
Regenerating the eight shipped glass manifests on Blender **4.2.13 LTS**, where they were originally
cut on **4.2.0**, changed every `topologyHash` and moved shard masses in the seventh significant
figure — 2.465366 kg to 2.465367 kg on the Eclipse's windscreen. Same seed, same source mesh, same
shard count, same tool version.

## Details
G11 makes the tool deterministic for a given seed, and it is: two runs on one Blender produce
byte-identical output, which is what the determinism check tests. What is *not* invariant is the
Blender build underneath it. The fracture path runs geometry through `bmesh` operations whose
floating-point results are free to change between patch releases, and `topologyHash` is a SHA-256
over quantised vertex positions at 1e-6 m — fine enough to notice a change three orders of magnitude
smaller than anything that matters.

That is the hash working, not failing. It is the determinism fingerprint (G11), and a fingerprint
that did not move when the geometry moved would be useless. But it means two things a future session
should know before it panics:

- **A manifest diff after a Blender upgrade is expected**, and a changed `topologyHash` on its own is
  not evidence of a bug. Compare shard *count*, mass conservation and the per-shard masses to a few
  decimal places instead.
- **Golden comparison by topology hash is only valid within one Blender build.** D14-S5.8 compares
  shards by id, which survives this; anything that pinned the hash across environments would fail on
  every CI image bump.

The masses move by about 4e-7 relative, four orders of magnitude inside `MASS_TOLERANCE_FRAC`, so
G7 is untroubled and nothing downstream can observe the difference.

## Rationale / Context
The regeneration was for DEC-089 — the manifests needed `transform` and `destructionClass` fields.
The expectation was a two-line diff per file. Every file also showed a changed topology hash and
several changed masses, which reads alarmingly like the fracture having been altered by the
transform split. It was not: the split removed the morph stage, which never touched the shards.
The variable was `blenderVersion`, which the manifest records for exactly this reason.

## Impact
No code change. It is a reason not to treat `topologyHash` as a cross-environment identity, and a
reason to state the Blender version when reporting that a fracture "changed".

"""The DEFORM transform: damage shape keys, and nothing else.

D00-S6 gives the project two separate words for two separate things — *deformation* is
"continuous visual mesh change driven by shape keys" and its glossary note is "not fracture" —
and this package is the first of them. :mod:`syndicate_fracture` is the second.

They were one tool until now, and it ran both stages unconditionally on every mesh it was handed,
so the documented invocation produced a part that dented *and* shattered. No destruction class in
D15-S5.7 receives both, and nothing at runtime checks (DISC-068): a part dents because its mesh has
shape keys and shatters because it declares a manifest. The tools are therefore the only place the
rule can live, which is why this one refuses a class that does not deform rather than obliging.

What it authors, per D09-S5.3:

- four morph targets ``dmg_25``..``dmg_100`` on the intact mesh, inward-only (D09-R13), monotonic
  in severity, with silhouette vertices held back so the part stays recognisable;
- ``deform_manifest.json`` beside them, declaring the transform, the class, and each level's mean
  and maximum displacement.

Shards never carry morphs (D09-R12) and this tool never sees one: a shard is already the
fully-broken representation.
"""

from __future__ import annotations

TOOL_VERSION = "0.1.0"

__all__ = ["TOOL_VERSION"]

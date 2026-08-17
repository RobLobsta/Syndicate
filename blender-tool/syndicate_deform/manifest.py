"""``deform_manifest.json`` — what the DEFORM transform authored (D09-S4.4's shape).

Deliberately the same header as a fracture manifest: schema and tool version, the source and its
hash, the part id, and — first among the fields that matter — the **transform** and the
**destruction class** it was authored for. Those two are why this file exists as a file rather than
as four shape keys nobody records: without them a consumer cannot tell a part that should deform
from one that merely does, which is the gap that let a steel door end up with shards (DISC-068).

It carries no mass, no volume and no inertia. Deformation is cosmetic (G6, D07-R18) — it changes
what a part looks like and never what it weighs — so a manifest that quoted a mass would be
inviting somebody to spend it.
"""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from syndicate_policy.classes import DEFORM

from . import TOOL_VERSION
from .cli import Args
from .morphs import MorphStats

SCHEMA_VERSION = "1.0.0"

#: The file this manifest is written to, beside the part it describes (D08-S4.6).
MANIFEST_FILE = "deform_manifest.json"


def build(
    part_type_id: str,
    source_path: Path,
    blender_version: str,
    args: Args,
    morphs: list[MorphStats],
    subdivided_from: int,
    subdivided_to: int,
) -> dict[str, Any]:
    """Assemble the manifest. The ``verification`` block is filled in later."""
    return {
        "schemaVersion": SCHEMA_VERSION,
        "toolVersion": TOOL_VERSION,
        "blenderVersion": blender_version,
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sourceFile": str(source_path),
        "sourceHash": file_hash(source_path),
        "partTypeId": part_type_id,
        "transform": str(DEFORM),
        "destructionClass": args.destruction_class,
        "seed": args.seed,
        "parameters": args.parameters_block(),
        "morphTargets": [m.name for m in morphs],
        "morphStats": [m.to_json() for m in morphs],
        "subdivision": {"facesBefore": subdivided_from, "facesAfter": subdivided_to},
        "verification": {"passed": False, "checks": [], "warnings": []},
    }


def file_hash(path: Path) -> str:
    """SHA-256 of the source, so a manifest can be paired with the mesh it came from."""
    if not path.is_file():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(65536), b""):
            digest.update(block)
    return f"sha256:{digest.hexdigest()}"


__all__ = ["MANIFEST_FILE", "SCHEMA_VERSION", "build", "file_hash"]

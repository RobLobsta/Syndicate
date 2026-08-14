"""Check that a prepared vehicle is what its manifests say it is.

The asset gate (`asset-pipeline`) and the runtime loader (`game-core`) are the authorities on
whether content is valid, and both are JVM code. This script is the part of their job that can
be done from the tool side, on the two things they cannot check for each other: that every
mesh named in a `part.json` **exists and contains the nodes and morph targets it promises**,
and that the assembly and the parts agree about slots.

Run it after `syndicate-prepare --out`:

    python3 tools/verify_prepared.py <parts dir> <vehicles dir>

Exit 0 if the vehicle would load, 1 with a list of findings if not. It needs a Blender host,
because the only honest way to ask what is in a `.glb` is to open it.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import bpy

#: Node suffix a collision hull carries (D08-R3).
COLLISION_SUFFIX = "_col"

#: The canonical damage morph names (D08-R6, D07-S5.5).
CANONICAL_MORPHS = ["dmg_25", "dmg_50", "dmg_75", "dmg_100"]


def read_glb(path: Path) -> dict:
    """Node names and their shape-key names, from a fresh scene."""
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(path))
    contents = {}
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH":
            continue
        keys = []
        if obj.data.shape_keys is not None:
            keys = [block.name for block in obj.data.shape_keys.key_blocks if block.name != "Basis"]
        contents[obj.name] = {
            "triangles": len(obj.data.polygons),
            "vertices": len(obj.data.vertices),
            "morphs": keys,
        }
    return contents


def verify(parts_root: Path, vehicles_root: Path) -> list[str]:
    findings: list[str] = []
    parts: dict[str, dict] = {}

    for directory in sorted(parts_root.iterdir()):
        manifest = directory / "part.json"
        if not manifest.is_file():
            findings.append(f"{directory.name}: no part.json")
            continue
        document = json.loads(manifest.read_text())
        parts[document["partTypeId"]] = document

        if document["partTypeId"] != directory.name:
            findings.append(f"{directory.name}: partTypeId is {document['partTypeId']}")

        assets = document["assets"]
        mesh_path = directory / assets["visualMesh"]
        if not mesh_path.is_file():
            findings.append(f"{directory.name}: {assets['visualMesh']} is missing")
            continue

        contents = read_glb(mesh_path)
        # `collisionSource` is "mesh.glb#node=<id>_col" (D08-R3).
        wanted_node = assets["collisionSource"].split("node=")[-1]
        if wanted_node not in contents:
            findings.append(
                f"{directory.name}: collisionSource names '{wanted_node}', "
                f"which is not in the file (nodes: {sorted(contents)})"
            )
        visual = contents.get(document["partTypeId"])
        if visual is None:
            findings.append(f"{directory.name}: no node named after the part")
        else:
            promised = assets.get("morphTargets", [])
            if promised and sorted(visual["morphs"]) != sorted(CANONICAL_MORPHS):
                findings.append(
                    f"{directory.name}: promises {promised} and the mesh carries "
                    f"{visual['morphs']}"
                )
            if not promised and visual["morphs"]:
                findings.append(
                    f"{directory.name}: mesh carries {visual['morphs']} and the manifest "
                    "promises none"
                )
        for optional in ("shardMesh", "fractureManifest"):
            if optional in assets and not (directory / assets[optional]).is_file():
                findings.append(f"{directory.name}: {assets[optional]} is missing")

    for directory in sorted(vehicles_root.iterdir()):
        assembly = json.loads((directory / "assembly.json").read_text())
        chassis = parts.get(assembly["chassis"])
        if chassis is None:
            findings.append(f"{directory.name}: chassis {assembly['chassis']} was not exported")
            continue
        offered = {slot["slotId"]: slot for slot in chassis["slots"]}
        for row in assembly["parts"]:
            if row["partTypeId"] not in parts:
                findings.append(f"{directory.name}: {row['partTypeId']} was not exported")
                continue
            slot = offered.get(row["parentSlotId"])
            if slot is None:
                findings.append(
                    f"{directory.name}: {row['slotPath']} hangs from '{row['parentSlotId']}', "
                    "which the chassis does not offer"
                )
                continue
            required = parts[row["partTypeId"]]["slotTypeRequired"]
            if slot["slotType"] != required:
                findings.append(
                    f"{directory.name}: slot {slot['slotId']} is {slot['slotType']} and "
                    f"{row['partTypeId']} needs {required}"
                )
            if parts[row["partTypeId"]]["massKg"] > slot["maxMassKg"]:
                findings.append(
                    f"{directory.name}: {row['partTypeId']} is heavier than slot "
                    f"{slot['slotId']} allows"
                )
        filled = [row["parentSlotId"] for row in assembly["parts"]]
        if len(filled) != len(set(filled)):
            findings.append(f"{directory.name}: a slot is filled twice")
        for unused in sorted(set(offered) - set(filled)):
            findings.append(f"{directory.name}: slot {unused} is offered and never filled")

        total = sum(parts[row["partTypeId"]]["massKg"] for row in assembly["parts"])
        total += chassis["massKg"]
        if abs(total - assembly["expected"]["totalMassKg"]) > 0.02 * total:
            findings.append(
                f"{directory.name}: parts sum to {total:.1f} kg against an expected "
                f"{assembly['expected']['totalMassKg']} kg"
            )
        budget = sum(parts[row["partTypeId"]]["powerCost"] for row in assembly["parts"])
        budget += chassis["powerCost"]
        if abs(budget - assembly["expected"]["powerBudget"]) > 0.02 * max(1.0, budget):
            findings.append(
                f"{directory.name}: power costs sum to {budget:.2f} against an expected "
                f"{assembly['expected']['powerBudget']}"
            )
    return findings


def main() -> int:
    parts_root, vehicles_root = Path(sys.argv[1]), Path(sys.argv[2])
    findings = verify(parts_root, vehicles_root)
    document = {
        "parts": len(list(parts_root.iterdir())),
        "vehicles": len(list(vehicles_root.iterdir())),
        "findings": findings,
        "ok": not findings,
    }
    print(json.dumps(document, indent=2), file=sys.stderr)
    return 0 if not findings else 1


if __name__ == "__main__":
    code = main()
    sys.stderr.flush()
    import os

    os._exit(code)  # DISC-003: bpy's teardown segfaults and would overwrite this

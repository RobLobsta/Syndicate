"""The JSON a structure ships as: one ``part.json`` per part, one ``structure.json`` (D16-R18).

Pure dictionary construction, so what the tool writes can be asserted without a Blender host —
which is the half of the output most likely to drift, because it is the half nothing looks at
until the asset gate rejects it.

**No new schema.** D16-R19 is explicit that a structure part is an ordinary part: the documents
below are the same ``part.json`` the vehicle pipeline writes, with a ``PANEL`` category and a
``SUBSLOT`` chain instead of wheels and hardpoints. Where a structure needs something a vehicle
part cannot express, that is a defect in the design rather than a licence to fork the schema.
"""

from __future__ import annotations

from .graph import PartPlan, slot_local, slot_path_of
from .mass import armor_value, break_impulse_ns, max_hp

SCHEMA_VERSION = "1.0.0"

#: A structure part carries no power cost. Power is a vehicle's budget for what it can field
#: (D05-S5.6); a building is not fielding anything and has nothing to spend.
POWER_COST = 0.0

#: Slot type per child category (D05-S4.3). A structure's chain is made of the slot types that
#: already accept what hangs on them: a floor is a ``PANEL`` in a ``PANEL`` slot, and a built-in
#: weapon is a ``WEAPON`` on a ``TURRET_MOUNT``.
#:
#: ``SUBSLOT`` would read better — it is literally "a slot on a part that is itself in a slot" — and
#: it is wrong: D05-S4.3 has it accept weapons, utilities and decoration, and a building's third
#: floor is none of those. Inventing a slot type for structures would be the schema fork D16-R19
#: forbids, so the existing types are used as they are defined.
SLOT_TYPE_FOR_CATEGORY = {"PANEL": "PANEL", "WEAPON": "TURRET_MOUNT"}


def display_name(structure_display: str, role: str) -> str:
    """A human-readable name: the structure's, then which piece of it this is."""
    words = role.replace("_", " ").title()
    return f"{structure_display} — {words}"


def category_of(part: PartPlan) -> str:
    """A structure part is a ``PANEL`` unless it carries a built-in weapon (DEC-077)."""
    return "WEAPON" if part.weapon else "PANEL"


def slot_type_for(part: PartPlan) -> str:
    """The slot type a part of this category may occupy (D05-S4.3, A305)."""
    return SLOT_TYPE_FOR_CATEGORY[category_of(part)]


def part_document(
    part: PartPlan,
    children: list[PartPlan],
    structure_display: str,
    morph_targets: list[str],
    has_shards: bool,
) -> dict:
    """One part's ``part.json`` (D08-R5).

    ``morph_targets`` and ``has_shards`` are what the exporter *produced*, not what the class
    was planned to get. A document that promises four shape keys over a mesh carrying none
    passes every JSON check and fails when somebody shoots it, one layer further from the cause.
    """
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "partTypeId": part.part_type_id,
        "displayName": display_name(structure_display, part.role),
        "category": category_of(part),
        "materialId": part.material_id,
        "destructionClass": part.destruction_class,
        "massKg": round(part.mass_kg, 3),
        "maxHp": round(max_hp(part.mass_kg), 1),
        "armorValue": round(armor_value(part.mass_kg), 2),
        "breakImpulseN": round(break_impulse_ns(part.mass_kg), 1),
        "powerCost": POWER_COST,
        "hangsBeforeFalling": False,
        "slotTypeRequired": "ROOT" if part.parent_id is None else slot_type_for(part),
        "assets": {
            "visualMesh": "mesh.glb",
            "collisionSource": f"mesh.glb#node={part.part_type_id}_col",
            "morphTargets": list(morph_targets),
        },
        "slots": [slot_document(part, child) for child in children],
        "stats": {},
        "tags": ["structure", part.role.split("_")[0], "prepared"],
    }
    if has_shards:
        document["assets"]["fractureManifest"] = "fracture_manifest.json"
    if part.weapon:
        document["weapon"] = part.weapon
        document["stats"] = part.weapon.pop("stats", {})
        # A316 gates bulk on a size class (DEC-081). A built-in emplacement weapon is as large as
        # weapons get, and the slot it sits in is authored to match.
        document["sizeClass"] = "HEAVY"
    return document


def slot_document(parent: PartPlan, child: PartPlan) -> dict:
    """One slot on a parent, at the point where the child lands on it."""
    x, y, z = slot_local(parent, child)
    return {
        "slotId": child.slot_id,
        "slotType": slot_type_for(child),
        "sizeClass": "HEAVY",
        "localPosition": {"x": round(x, 4), "y": round(y, 4), "z": round(z, 4)},
        "localRotationDeg": {"order": "XYZ", "x": 0.0, "y": 0.0, "z": 0.0},
        # Generous, and deliberately so: a slot's mass limit exists to stop a light vehicle
        # carrying a heavy weapon (D05-R14). Nothing chooses what goes on a structure's slots —
        # the tool that authored the parts also authored the slots — so a limit here could only
        # ever reject the tool's own output.
        "maxMassKg": round(child.mass_kg * 4.0, 1),
        "isDetachable": True,
        "covers": [],
    }


def parent_slot_path(plans: list[PartPlan], part: PartPlan) -> str:
    """The slot path of the part that carries this one."""
    by_id = {p.part_type_id: p for p in plans}
    return slot_path_of(plans, by_id[part.parent_id])


def structure_document(
    structure_id: str,
    display: str,
    plans: list[PartPlan],
    footprint_radius_m: float,
    height_m: float,
) -> dict:
    """The assembly (D16-R18): a root, the parts on its slots, and what it occupies."""
    root = next(p for p in plans if p.parent_id is None)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "structureId": structure_id,
        "displayName": display,
        "rootPartTypeId": root.part_type_id,
        "staticRoot": True,
        # `parentSlotPath` is the **parent's** path and `slotPath` is this part's, exactly as
        # `assembly.json` records them (D08-S4.4). Writing the part's own path into both reads
        # correctly and resolves to a part hanging off itself.
        "parts": [
            {
                "partTypeId": part.part_type_id,
                "parentSlotPath": parent_slot_path(plans, part),
                "parentSlotId": part.slot_id,
                "slotPath": slot_path_of(plans, part),
            }
            for part in plans
            if part.parent_id is not None
        ],
        "footprint": {
            "radiusM": round(footprint_radius_m, 3),
            "heightM": round(height_m, 3),
        },
        "expected": {"massKg": round(sum(p.mass_kg for p in plans), 3)},
    }


def report_document(structure_id: str, plans: list[PartPlan], findings: list[str]) -> dict:
    """What the tool decided, for a human reading it cold (the D15-R14c argument, for structures).

    Nothing loads this. It answers the one question neither a directory listing nor a dozen
    separate ``part.json`` files answers: what did the cut think this building was made of.
    """
    return {
        "structureId": structure_id,
        "parts": [
            {
                "partTypeId": part.part_type_id,
                "role": part.role,
                "band": part.band,
                "parent": part.parent_id,
                "materialId": part.material_id,
                "destructionClass": part.destruction_class,
                "massKg": round(part.mass_kg, 3),
                "triangles": part.triangles,
                "sourceObjects": sorted(piece.name for piece in part.component.pieces),
                "notes": list(part.notes),
            }
            for part in plans
        ],
        "totalMassKg": round(sum(p.mass_kg for p in plans), 3),
        "totalTriangles": sum(p.triangles for p in plans),
        "findings": list(findings),
    }

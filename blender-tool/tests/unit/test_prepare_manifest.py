"""Stages 5 to 8 end to end, in pure Python: a synthetic pickup becomes a loadable vehicle.

This is the test that says the pipeline is finished rather than merely implemented. It takes a
set of measured shells — the thing the Blender half produces — and runs every decision after
that point: roles, corners, rotational symmetry, grouping, naming, mass, power, hinges, and the
two documents. What it asserts is what the game's own loader and asset gate require:

- ``partTypeId`` and ``slotId`` match the patterns of D08-R6;
- every ``category``/``slotTypeRequired`` pair is one ``SlotType.acceptsCategory`` accepts;
- the assembly's power budget hits its class target, which A312 makes an error;
- ``massKg`` is positive everywhere and the total is a vehicle's mass;
- every source triangle lands in exactly one part (AC-D15-4);
- no ``glass`` part carries damage morphs (AC-D15-10).
"""

from __future__ import annotations

import math
import re

import pytest

from syndicate_prepare import destruction, grouping, hinges, manifest
from syndicate_prepare.labels import CHASSIS, HUB, UNCLASSIFIED, WHEEL
from tests.unit.vehicle_fixture import ASSETS, classified

PART_ID = re.compile(r"^[a-z][a-z0-9_]{2,63}$")
SLOT_ID = re.compile(r"^[a-z][a-z0-9_]{1,31}$")

#: ``SlotType`` (D05-S4.3) as the game declares it, so a mapping that would not load fails here
#: rather than in the loader. Duplicated deliberately and minimally: the point of the test is to
#: catch this file and that enum drifting apart.
SLOT_ACCEPTS = {
    "ROOT": {"CHASSIS"},
    "WHEEL": {"WHEEL"},
    "HARDPOINT": {"WEAPON", "UTILITY"},
    "ARMOR_PANEL": {"ARMOR"},
    "TURRET_MOUNT": {"WEAPON"},
    "ACCESSORY": {"DECORATIVE"},
    "SUBSLOT": {"WEAPON", "UTILITY", "DECORATIVE"},
}


@pytest.fixture
def prepared_pickup():
    shells, parts, corners, body = classified()
    prepared = manifest.prepare_parts("pickup", parts, corners, body)
    chassis_group = grouping.Part(label=CHASSIS, side="c", index=0)
    chassis_group.shells = [s for s in shells if s.label in (CHASSIS, UNCLASSIFIED)]
    chassis = manifest.PreparedPart(
        part_type_id="chassis_pickup_01",
        slot_id="root",
        label=CHASSIS,
        role=None,
        side="c",
        group=chassis_group,
        origin=(0.0, 0.0, 0.0),
        instances=["root"],
    )
    prepared.insert(0, chassis)

    densities = manifest.load_densities(ASSETS / "materials" / "materials.json")
    target, _note = manifest.target_mass_kg(body, None)
    manifest.assign_masses(prepared, chassis, densities, target)
    total = manifest.total_mass_kg(prepared)
    vehicle_class = manifest.vehicle_class_for(total)
    budget = manifest.load_class_targets(ASSETS / "balance" / "classes.json")[vehicle_class]

    stats = manifest.chassis_stats(chassis.mass_kg)
    references = {
        part.part_type_id: manifest.reference_power_cost(
            part,
            manifest.HP_PER_KG[part.destruction_class] * part.mass_kg,
            manifest.ARMOR_PER_KG.get(manifest.PART_CATEGORY[part.label], 0.0) * part.mass_kg,
            stats["engineForceN"]["add"] if part.is_chassis else 0.0,
        )
        for part in prepared
    }
    manifest.distribute_power(prepared, references, budget)
    for part in prepared:
        part.hinge = hinges.infer(part.group, body, None)

    return {
        "shells": shells,
        "parts": parts,
        "prepared": prepared,
        "chassis": chassis,
        "class": vehicle_class,
        "budget": budget,
        "stats": stats,
        "corners": corners,
    }


def documents(prepared_pickup) -> dict[str, dict]:
    prepared = prepared_pickup["prepared"]
    slots = [
        manifest.build_slot(part, slot, position)
        for part in prepared
        if not part.is_chassis
        for slot, position in manifest.slot_positions(part)
    ]
    return {
        part.part_type_id: manifest.build_part_document(
            part,
            chassis_slots=slots if part.is_chassis else [],
            stats=prepared_pickup["stats"] if part.is_chassis else {},
            handling=(
                manifest.DEFAULT_CHASSIS_HANDLING
                if part.is_chassis
                else manifest.DEFAULT_WHEEL_HANDLING
                if part.label == WHEEL
                else None
            ),
        )
        for part in prepared
    }


# ---- The parts a dropped-in pickup comes out with ----------------------------------------


def test_the_pickup_yields_the_parts_a_human_would_name(prepared_pickup):
    names = {part.part_type_id for part in prepared_pickup["prepared"]}
    assert "chassis_pickup_01" in names
    assert "wheel_pickup_front_01" in names and "wheel_pickup_rear_01" in names
    assert "panel_pickup_door_l_01" in names and "panel_pickup_door_r_01" in names
    assert "panel_pickup_bonnet_01" in names
    assert "glass_pickup_windscreen_01" in names
    assert "light_pickup_head_l_01" in names and "light_pickup_head_r_01" in names


def test_a_wheel_and_a_hub_come_out_of_each_corner(prepared_pickup):
    labels = {part.part_type_id: part.label for part in prepared_pickup["prepared"]}
    assert labels.get("wheel_pickup_front_01") == WHEEL
    assert labels.get("hub_pickup_front_01") == HUB


def test_both_wheels_on_an_axle_share_one_part_type_at_mirrored_slots(prepared_pickup):
    wheel = next(
        part for part in prepared_pickup["prepared"]
        if part.part_type_id == "wheel_pickup_front_01"
    )
    assert sorted(wheel.instances) == ["wheel_fl", "wheel_fr"]
    positions = dict(manifest.slot_positions(wheel))
    assert positions["wheel_fl"][0] == -positions["wheel_fr"][0]
    assert positions["wheel_fl"][1] == positions["wheel_fr"][1]


def test_a_wheels_origin_is_its_axle(prepared_pickup):
    """Bullet spins a wheel about its part origin; anywhere else and it orbits the car."""
    wheel = next(
        part for part in prepared_pickup["prepared"]
        if part.part_type_id == "wheel_pickup_front_01"
    )
    assert math.isclose(wheel.origin[1], 0.42, abs_tol=0.03)
    assert math.isclose(abs(wheel.origin[2]), 1.85, abs_tol=0.05)


# ---- AC-D15-4: every triangle in exactly one part ------------------------------------------


def test_every_source_triangle_lands_in_exactly_one_part(prepared_pickup):
    """Counted across instances: a shared wheel type accounts for both corners' shells."""
    shells = prepared_pickup["shells"]
    seen: list[int] = []
    for part in prepared_pickup["prepared"]:
        seen.extend(one.index for one in part.all_shells)
    assert sorted(seen) == sorted(one.index for one in shells)
    assert len(seen) == len(set(seen))


# ---- D08-S4.2 conformance --------------------------------------------------------------------


def test_every_identifier_matches_the_pattern_the_loader_enforces(prepared_pickup):
    for name, document in documents(prepared_pickup).items():
        assert PART_ID.match(name), name
        assert document["partTypeId"] == name
        for slot in document["slots"]:
            assert SLOT_ID.match(slot["slotId"]), slot["slotId"]


def test_every_category_fits_the_slot_type_it_asks_for(prepared_pickup):
    for document in documents(prepared_pickup).values():
        accepts = SLOT_ACCEPTS[document["slotTypeRequired"]]
        assert document["category"] in accepts, document["partTypeId"]


def test_the_chassis_carries_one_slot_per_attached_instance(prepared_pickup):
    chassis = documents(prepared_pickup)["chassis_pickup_01"]
    instances = sum(
        max(1, len(part.instances))
        for part in prepared_pickup["prepared"]
        if not part.is_chassis
    )
    assert len(chassis["slots"]) == instances
    assert len({slot["slotId"] for slot in chassis["slots"]}) == instances


def test_a_decorative_part_carries_no_armour(prepared_pickup):
    """D08-R6: ``armorValue`` must be 0 for a decorative part."""
    for document in documents(prepared_pickup).values():
        if document["category"] == "DECORATIVE":
            assert document["armorValue"] == 0.0


def test_every_part_has_a_positive_mass_health_and_break_impulse(prepared_pickup):
    for document in documents(prepared_pickup).values():
        assert document["massKg"] > manifest.MIN_BODY_MASS_KG
        assert document["maxHp"] > 0.0
        assert document["breakImpulseN"] > 0.0


# ---- Mass and balance ---------------------------------------------------------------------------


def test_the_pickup_weighs_what_a_pickup_weighs(prepared_pickup):
    total = manifest.total_mass_kg(prepared_pickup["prepared"])
    assert 1800.0 < total < 2600.0


def test_the_chassis_carries_most_of_the_mass(prepared_pickup):
    chassis = prepared_pickup["chassis"]
    total = manifest.total_mass_kg(prepared_pickup["prepared"])
    assert chassis.mass_kg / total >= manifest.CHASSIS_MIN_FRACTION


def test_a_wheel_weighs_what_a_wheel_weighs(prepared_pickup):
    wheel = next(
        part for part in prepared_pickup["prepared"]
        if part.part_type_id == "wheel_pickup_front_01"
    )
    assert 15.0 < wheel.mass_kg < 90.0


def test_the_power_budget_hits_its_class_target(prepared_pickup):
    """A312 is an error, not a warning: the budget must equal the class's target."""
    assembly = manifest.build_assembly_document(
        "pickup",
        "Pickup",
        prepared_pickup["prepared"],
        prepared_pickup["chassis"],
        prepared_pickup["class"],
    )
    assert math.isclose(
        assembly["expected"]["powerBudget"], prepared_pickup["budget"], rel_tol=0.01
    )


def test_the_assembly_names_its_chassis_as_a_field_and_not_as_a_part(prepared_pickup):
    """DEC-019: the chassis is a field on the assembly record, not a row in its parts list."""
    assembly = manifest.build_assembly_document(
        "pickup", "Pickup", prepared_pickup["prepared"], prepared_pickup["chassis"],
        prepared_pickup["class"],
    )
    assert assembly["chassis"] == "chassis_pickup_01"
    assert all(row["partTypeId"] != "chassis_pickup_01" for row in assembly["parts"])
    assert all(row["parentSlotPath"] == "root" for row in assembly["parts"])


def test_the_front_wheels_steer_and_the_rear_wheels_drive(prepared_pickup):
    assembly = manifest.build_assembly_document(
        "pickup", "Pickup", prepared_pickup["prepared"], prepared_pickup["chassis"],
        prepared_pickup["class"],
    )
    overrides = {
        row["parentSlotId"]: row.get("overrides")
        for row in assembly["parts"]
        if row["partTypeId"].startswith("wheel_")
    }
    assert overrides["wheel_fl"] == {"isSteering": True, "isDriven": False}
    assert overrides["wheel_rr"] == {"isSteering": False, "isDriven": True}


def test_the_centre_of_mass_sits_on_the_centreline(prepared_pickup):
    """A shared wheel type used at +x and -x must not drag the COM out to one flank."""
    com = manifest.centre_of_mass(prepared_pickup["prepared"])
    assert abs(com[0]) < 0.05
    assert 0.0 < com[1] < 1.5


# ---- AC-D15-10: the destruction classes ------------------------------------------------------


def test_each_label_gets_the_treatment_d15_s5_7_specifies(prepared_pickup):
    for part in prepared_pickup["prepared"]:
        treatment = destruction.treatment_for(part.label)
        assert treatment.destruction_class == part.destruction_class


def test_no_glass_part_carries_damage_shape_keys(prepared_pickup):
    for document in documents(prepared_pickup).values():
        if document["destructionClass"] == "GLASS":
            assert "morphTargets" not in document["assets"]
            assert document["assets"]["shardMesh"] == "shards.glb"


def test_a_panel_deforms_and_a_lamp_does_not(prepared_pickup):
    all_documents = documents(prepared_pickup)
    assert all_documents["panel_pickup_door_l_01"]["assets"]["morphTargets"] == [
        "dmg_25", "dmg_50", "dmg_75", "dmg_100",
    ]
    assert "morphTargets" not in all_documents["light_pickup_head_l_01"]["assets"]


def test_the_manifest_promises_only_what_the_export_produced(prepared_pickup):
    """A part whose morphs failed a D09 guard must not claim four shape keys."""
    door = next(
        part for part in prepared_pickup["prepared"]
        if part.part_type_id == "panel_pickup_door_l_01"
    )
    document = manifest.build_part_document(
        door, [], {}, None, produced={"morphTargets": [], "shards": 0}
    )
    assert "morphTargets" not in document["assets"]


# ---- Stage 6, at the part level ------------------------------------------------------------------


def test_the_doors_and_the_bonnet_are_exported_with_a_hinge(prepared_pickup):
    hinged = {
        part.part_type_id for part in prepared_pickup["prepared"] if part.hinge is not None
    }
    assert "panel_pickup_door_l_01" in hinged
    assert "panel_pickup_door_r_01" in hinged
    assert "panel_pickup_bonnet_01" in hinged


def test_a_hinge_is_written_as_data_on_the_part(prepared_pickup):
    """D15-R30: data on the part, never an armature."""
    door = next(
        part for part in prepared_pickup["prepared"]
        if part.part_type_id == "panel_pickup_door_l_01"
    )
    document = manifest.build_part_document(door, [], {}, None)
    assert set(document["articulation"]) == {"axisLocal", "pivotLocal", "openDeg"}
    assert document["articulation"]["axisLocal"] == {"x": 0.0, "y": 1.0, "z": 0.0}

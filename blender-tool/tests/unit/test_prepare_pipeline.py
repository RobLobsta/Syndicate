"""The orchestration of stages 6 to 8, driven through :func:`syndicate_prepare.prepare.assemble`.

The other preparation tests call the decision modules directly. This one calls the function the
tool itself calls, with the Blender half stubbed out, so that a wiring mistake between the nine
stages is caught here rather than on the first machine that has Blender installed. It is the
only test that reads the documents off the disk they are written to.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from syndicate_prepare import manifest as manifest_module
from syndicate_prepare import prepare
from syndicate_prepare.destruction import TREATMENTS, treatment_for
from syndicate_prepare.labels import (
    CHASSIS,
    DESTRUCTION_CLASS,
    GLASS,
    LABELS,
    PART_CATEGORY,
    SLOT_TYPE_REQUIRED,
    WHEEL,
)
from tests.unit.vehicle_fixture import ASSETS, classified


def options(tmp_path, **overrides):
    return prepare.Options(
        model_dir=tmp_path,
        vehicle="pickup",
        material_table=ASSETS / "materials" / "materials.json",
        balance_table=ASSETS / "balance" / "classes.json",
        **overrides,
    )


@pytest.fixture
def built(tmp_path, monkeypatch):
    """A full stage 6-8 run with the Blender export stubbed to "everything succeeded"."""
    shells, parts, corners, body = classified()

    def fake_export(opts, prepared, chassis, objects):
        return [
            {
                "partTypeId": part.part_type_id,
                "triangles": part.group.triangles,
                "morphTargets": (
                    ["dmg_25", "dmg_50", "dmg_75", "dmg_100"]
                    if treatment_for(part.label).morphs
                    else []
                ),
                "shards": treatment_for(part.label).fracture_shards,
                "notes": [],
                "massOverrideKg": None,
            }
            for part in prepared
        ]

    monkeypatch.setattr(prepare, "_export_all", fake_export)
    stages: dict = {}
    build = prepare.assemble(
        options(tmp_path, out=tmp_path / "parts", vehicles_out=tmp_path / "vehicles"),
        shells,
        parts,
        corners,
        body,
        {},
        stages,
    )
    return {"build": build, "stages": stages, "root": tmp_path}


def test_the_run_writes_a_part_directory_and_an_assembly(built):
    written = built["build"]["written"]
    assert any(path.endswith("part.json") for path in written)
    assert any(path.endswith("assembly.json") for path in written)
    assembly = json.loads(
        (built["root"] / "vehicles" / "vehicle_pickup_01" / "assembly.json").read_text()
    )
    assert assembly["vehicleTypeId"] == "vehicle_pickup_01"
    assert assembly["chassis"] == "chassis_pickup_01"


def test_every_written_part_json_sits_in_a_directory_of_its_own_name(built):
    for path in built["build"]["written"]:
        if not path.endswith("part.json"):
            continue
        document = json.loads(Path(path).read_text())
        assert path.split("/")[-2] == document["partTypeId"]


def test_the_assembly_references_only_parts_that_were_written(built):
    written = {
        json.loads(Path(path).read_text())["partTypeId"]
        for path in built["build"]["written"]
        if path.endswith("part.json")
    }
    assembly = built["build"]["assembly"]
    assert assembly["chassis"] in written
    for row in assembly["parts"]:
        assert row["partTypeId"] in written


def chassis_document(built):
    return next(
        json.loads(Path(path).read_text())
        for path in built["build"]["written"]
        if path.endswith("part.json") and "chassis_pickup_01" in path
    )


def test_every_part_slot_the_chassis_offers_is_filled_by_exactly_one_row(built):
    chassis = chassis_document(built)
    offered = sorted(
        slot["slotId"]
        for slot in chassis["slots"]
        if slot["slotId"] not in manifest_module.HARDPOINT_SLOT_IDS
    )
    filled = sorted(row["parentSlotId"] for row in built["build"]["assembly"]["parts"])
    assert offered == filled


def test_the_chassis_offers_hardpoints_and_the_assembly_fills_none_of_them(built):
    """D15-R42: a mounting point for content this model does not contain."""
    chassis = chassis_document(built)
    hardpoints = {
        slot["slotId"]: slot
        for slot in chassis["slots"]
        if slot["slotId"] in manifest_module.HARDPOINT_SLOT_IDS
    }
    assert set(hardpoints) == set(manifest_module.HARDPOINT_SLOT_IDS)
    assert not hardpoints.keys() & {
        row["parentSlotId"] for row in built["build"]["assembly"]["parts"]
    }
    # Every one of them must take a real weapon, not a badge.
    for slot in hardpoints.values():
        assert slot["maxMassKg"] >= manifest_module.HARDPOINT_MIN_MASS_KG


def test_the_report_blocks_name_every_stage(built):
    stages = built["stages"]
    assert set(stages) >= {"rig", "mass", "balance", "destruction"}
    assert stages["mass"]["chassisKg"] > 0.0
    assert stages["balance"]["vehicleClass"] in ("light", "medium", "heavy")


def test_a_classification_only_run_writes_nothing(tmp_path):
    shells, parts, corners, body = classified()
    build = prepare.assemble(options(tmp_path), shells, parts, corners, body, {}, {})
    assert build["written"] == []
    assert build["exported"] == []
    # ...and still says what it *would* produce, which is the point of running it.
    assert len(build["parts"]) > 5
    assert build["assembly"]["expected"]["totalMassKg"] > 0.0


def test_the_taxonomy_and_the_treatment_table_cover_every_label():
    """A label with no category, slot type or treatment is a part nothing downstream handles."""
    for label in LABELS:
        assert label in PART_CATEGORY
        assert label in SLOT_TYPE_REQUIRED
        assert DESTRUCTION_CLASS[label] in TREATMENTS


def test_the_treatment_table_is_d15_s5_7s():
    """AC-D15-10, at the table rather than at one vehicle."""
    assert TREATMENTS["SHEET_METAL"].morphs is True
    assert TREATMENTS["STRUCTURAL"].morphs is True
    assert TREATMENTS["GLASS"].morphs is False
    assert TREATMENTS["GLASS"].fracture_shards > 0
    assert TREATMENTS["RIGID"].subdivide_edge_m == 0.0
    assert TREATMENTS["RIGID"].morphs is False
    assert TREATMENTS["NONE"].subdivide_edge_m == 0.0 and not TREATMENTS["NONE"].morphs
    # D15-R34: the structural yield is an impulse, comparable with breakImpulseN.
    assert TREATMENTS["STRUCTURAL"].yield_impulse_ns > 0.0
    # A chassis buckles globally and a panel crumples locally, so their cages differ by an
    # order of magnitude rather than by taste.
    assert TREATMENTS["STRUCTURAL"].subdivide_edge_m > TREATMENTS["SHEET_METAL"].subdivide_edge_m


def test_glass_is_the_only_label_that_hangs_before_falling(built):
    for path in built["build"]["written"]:
        if not path.endswith("part.json"):
            continue
        document = json.loads(Path(path).read_text())
        assert document["hangsBeforeFalling"] == (document["destructionClass"] == "GLASS")
    assert DESTRUCTION_CLASS[GLASS] == "GLASS"
    assert DESTRUCTION_CLASS[WHEEL] == "RIGID"
    assert DESTRUCTION_CLASS[CHASSIS] == "STRUCTURAL"

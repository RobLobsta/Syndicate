"""The structure cut, without a Blender host (D16-S7.1).

The cut is the only decision ``syndicate_structure`` makes that is not somebody else's code, so it
is the part worth testing directly. Everything here runs on plain :class:`Piece` values.
"""

from __future__ import annotations

import pytest

from syndicate_structure import bands, documents, graph, mass, materials


def piece(name, lo, hi, *, area=10.0, volume=0.0, material="concrete", triangles=100):
    return bands.Piece(
        name=name,
        lo=lo,
        hi=hi,
        triangles=triangles,
        area_m2=area,
        volume_m3=volume,
        material=material,
    )


def stack(height_m: float, count: int) -> list[bands.Piece]:
    """``count`` slabs stacked to ``height_m``, one per band — a building already in pieces."""
    step = height_m / count
    return [
        piece(f"slab{i}", (-5.0, i * step, -4.0), (5.0, (i + 1) * step, 4.0))
        for i in range(count)
    ]


class TestBandCount:
    def test_a_two_metre_prop_is_one_band(self):
        assert bands.band_count(2.05) == 1

    def test_a_seventeen_metre_block_is_three(self):
        assert bands.band_count(17.12) == 3

    def test_a_thirty_one_metre_tower_is_five(self):
        assert bands.band_count(31.18) == 5

    def test_the_cap_holds(self):
        # A structure is cover to fight around, not a Jenga tower.
        assert bands.band_count(400.0) == bands.MAX_BANDS


class TestCut:
    def test_a_stack_cuts_into_one_part_per_band(self):
        pieces = stack(18.0, 3)
        cut = bands.cut(pieces, bands.edges_for(pieces))
        assert [len(band) for band in cut] == [1, 1, 1]

    def test_things_standing_side_by_side_are_separate_parts(self):
        # Two pods at the same height with disjoint footprints: either can be shot away while the
        # other holds, which is the whole reason the second pass exists.
        left = piece("pod_l", (-8.0, 10.0, -2.0), (-4.0, 14.0, 2.0), area=40.0)
        right = piece("pod_r", (4.0, 10.0, -2.0), (8.0, 14.0, 2.0), area=40.0)
        found = bands.merge_detail(bands.components([left, right]))
        assert len(found) == 2

    def test_detail_is_folded_into_its_neighbour(self):
        wall = piece("wall", (-5.0, 0.0, -4.0), (5.0, 6.0, 4.0), area=200.0)
        cable = piece("cable", (6.0, 0.0, 0.0), (6.2, 6.0, 0.2), area=1.0)
        found = bands.merge_detail(bands.components([wall, cable], gap_m=0.0))
        assert len(found) == 1
        assert {p.name for p in found[0].pieces} == {"wall", "cable"}

    def test_band_zero_is_always_one_root(self):
        # Four legs, no two touching. A structure has exactly one root (D16-R18).
        legs = [
            piece(f"leg{i}", (x, 0.0, z), (x + 1.0, 5.0, z + 1.0), area=20.0)
            for i, (x, z) in enumerate([(-9.0, -9.0), (8.0, -9.0), (-9.0, 8.0), (8.0, 8.0)])
        ]
        top = piece("deck", (-9.0, 6.0, -9.0), (9.0, 11.0, 9.0), area=300.0)
        cut = bands.cut([*legs, top], bands.edges_for([*legs, top], target_m=5.5))
        assert len(cut[0]) == 1
        assert len(cut[0][0].pieces) == 4

    def test_an_empty_model_cuts_into_nothing(self):
        assert bands.cut([], [0.0, 1.0]) == []


class TestSupportChain:
    def test_each_band_hangs_off_the_one_below(self):
        pieces = stack(18.0, 3)
        plans = graph.plan(bands.cut(pieces, bands.edges_for(pieces)), "block")
        assert [p.part_type_id for p in plans] == [
            "struct_block_base_01",
            "struct_block_tier1_01",
            "struct_block_tier2_01",
        ]
        assert plans[0].parent_id is None
        assert plans[1].parent_id == "struct_block_base_01"
        assert plans[2].parent_id == "struct_block_tier1_01"

    def test_slot_paths_are_the_chain(self):
        pieces = stack(18.0, 3)
        plans = graph.plan(bands.cut(pieces, bands.edges_for(pieces)), "block")
        assert graph.slot_path_of(plans, plans[2]) == "root/tier1/tier2"
        assert documents.parent_slot_path(plans, plans[2]) == "root/tier1"

    def test_a_parts_origin_sits_where_it_lands(self):
        # D08-R2's "origin at the attachment point", for a part that stands on another.
        component = bands.Component([piece("p", (-2.0, 4.0, -3.0), (6.0, 9.0, 5.0))])
        assert graph.part_origin(component) == pytest.approx((2.0, 4.0, 1.0))

    def test_a_child_hangs_on_the_part_it_stands_over(self):
        left_side = bands.Component([piece("l", (-10.0, 0.0, -2.0), (-2.0, 5.0, 2.0))])
        right_side = bands.Component([piece("r", (2.0, 0.0, -2.0), (10.0, 5.0, 2.0))])
        left = graph.PartPlan("a", "base_a", 0, left_side)
        right = graph.PartPlan("b", "base_b", 0, right_side)
        over_right = bands.Component([piece("t", (3.0, 5.0, -2.0), (9.0, 9.0, 2.0))])
        assert graph.choose_parent(over_right, [left, right]) is right


class TestMass:
    def test_a_shell_is_weighed_by_its_surface(self):
        # 262 m2 of concrete wall at 400 kg/m2 is about 105 tonnes, and its 234 m3 enclosure as
        # solid concrete would be 562 — the cap must not engage for something this hollow.
        assert mass.part_mass_kg(262.0, 234.0, "concrete", 2400.0) == pytest.approx(104_800.0)

    def test_a_small_solid_is_weighed_as_the_solid_it_is(self):
        # A 1 m concrete cube: 6 m2 of surface would read 2,400 kg, and the enclosure says 2,400 —
        # equal by construction here, so make the lump smaller and the cap has to bite.
        assert mass.part_mass_kg(6.0, 0.5, "concrete", 2400.0) == pytest.approx(1200.0)

    def test_nothing_weighs_less_than_the_simulable_minimum(self):
        assert mass.part_mass_kg(0.0, 0.0, "wood", 700.0) == mass.MIN_BODY_MASS_KG

    def test_an_unknown_material_falls_back_to_the_heaviest_common_answer(self):
        assert mass.areal_density("unobtanium") == mass.DEFAULT_AREAL_DENSITY_KG_PER_M2

    def test_health_and_break_impulse_scale_with_mass_and_have_floors(self):
        assert mass.max_hp(90_000.0) == pytest.approx(5400.0)
        assert mass.max_hp(1.0) == mass.MIN_MAX_HP
        assert mass.break_impulse_ns(1.0) == mass.MIN_BREAK_IMPULSE_NS
        assert mass.armor_value(10_000_000.0) == mass.MAX_ARMOR


class TestDocuments:
    def _plans(self):
        pieces = stack(18.0, 3)
        plans = graph.plan(bands.cut(pieces, bands.edges_for(pieces)), "block")
        for plan in plans:
            plan.mass_kg = 90_000.0
        return plans

    def test_a_part_document_is_an_ordinary_part(self):
        plans = self._plans()
        document = documents.part_document(
            plans[1], [plans[2]], "Concrete Block", ["dmg_25"], False
        )
        assert document["category"] == "PANEL"
        assert document["slotTypeRequired"] == "PANEL"
        assert document["assets"]["morphTargets"] == ["dmg_25"]
        assert "fractureManifest" not in document["assets"]
        assert document["slots"][0]["slotId"] == "tier2"

    def test_a_weapon_part_takes_a_turret_mount(self):
        plans = self._plans()
        plans[2].weapon = {"family": "ROCKET", "stats": {"damagePerShot": {"add": 420.0}}}
        document = documents.part_document(plans[2], [], "Rocket Turret", [], False)
        assert document["category"] == "WEAPON"
        assert document["slotTypeRequired"] == "TURRET_MOUNT"
        assert document["stats"]["damagePerShot"]["add"] == 420.0
        assert document["sizeClass"] == "HEAVY"

    def test_the_structure_document_names_its_root_and_its_footprint(self):
        plans = self._plans()
        document = documents.structure_document("str_block_01", "Concrete Block", plans, 6.4, 17.1)
        assert document["rootPartTypeId"] == "struct_block_base_01"
        assert document["staticRoot"] is True
        assert [p["partTypeId"] for p in document["parts"]] == [
            "struct_block_tier1_01",
            "struct_block_tier2_01",
        ]
        assert document["expected"]["massKg"] == pytest.approx(270_000.0)
        assert document["footprint"] == {"radiusM": 6.4, "heightM": 17.1}


class TestSplitPlan:
    """:mod:`syndicate_structure.split` needs ``bpy``; its band arithmetic does not."""

    def test_an_object_reaching_three_bands_is_copied_three_times(self):
        from syndicate_structure.split import spans

        assert spans(0.0, 17.0, [0.0, 5.7, 11.4, 17.1]) == [0, 1, 2]

    def test_a_sliver_across_a_plane_does_not_earn_a_part(self):
        from syndicate_structure.split import spans

        assert spans(5.69, 5.71, [0.0, 5.7, 11.4, 17.1]) == []


# ---- Material families and toughness (DEC-100) -----------------------------------------


class TestFailureFamilies:
    """A band's parts are split by how they fail, not only by where they stand."""

    def _piece(self, name, material, x0=0.0, y0=0.0):
        from syndicate_structure.bands import Piece

        return Piece(
            name=name,
            lo=(x0, y0, 0.0),
            hi=(x0 + 4.0, y0 + 3.0, 4.0),
            triangles=200,
            area_m2=40.0,
            volume_m3=8.0,
            material=material,
        )

    def test_glazing_and_wall_sharing_a_footprint_become_two_parts(self):
        """The failure this exists for: no spatial rule separates a curtain wall from its wall."""
        pieces = [self._piece("wall", "Building_6_White"), self._piece("glass", "Building_6_Glass")]
        found = bands.components(pieces)
        assert len(found) == 1, "identical footprints must cluster together"
        split = bands.split_families(found)
        assert len(split) == 2
        families = {materials.family_of(c.pieces[0].material) for c in split}
        assert families == {"GLASS", "MASONRY"}

    def test_one_material_is_left_alone(self):
        pieces = [self._piece("a", "UV"), self._piece("b", "UV_Two")]
        found = bands.components(pieces)
        assert len(bands.split_families(found)) == len(found)

    def test_band_zero_keeps_one_load_bearing_root_and_glazing_beside_it(self):
        """D16-R18 gives a structure one root; glazing is not it."""
        pieces = [
            self._piece("wall_l", "concrete_wall", x0=0.0),
            self._piece("wall_r", "concrete_wall", x0=40.0),
            self._piece("pane", "glass_5", x0=0.0),
        ]
        cut = bands.cut(pieces, [0.0, 3.0])
        assert len(cut) == 1
        band = cut[0]
        # The two walls merged into one root; the glazing did not join them.
        assert materials.family_of(band[0].pieces[0].material) == "MASONRY"
        assert len(band[0].pieces) == 2
        assert [materials.family_of(c.pieces[0].material) for c in band[1:]] == ["GLASS"]


class TestMaterialMapping:
    def test_the_four_families_are_recognised_by_name(self):
        assert materials.map_material("Building_6_Glass") == "glass"
        assert materials.map_material("brick_wall_red") == "brick"
        assert materials.map_material("steel_girder") == "steel"
        assert materials.map_material("Wooden_Bench") == "wood"

    def test_an_unrecognised_name_is_masonry(self):
        """A building is mostly masonry, so that is the safe answer for a name that says nothing."""
        assert materials.map_material("UV_Third") == "concrete"
        assert materials.destruction_class_for("concrete") == "MASONRY"

    def test_glass_shatters_masonry_breaks_apart_steel_bends(self):
        """The three behaviours the content has to deliver, as one assertion."""
        assert materials.family_of("glass_5") == "GLASS"
        assert materials.family_of("brick_facade") == "MASONRY"
        assert materials.family_of("steel_mast") == "STRUCTURAL"


class TestToughness:
    def test_glass_is_weaker_than_concrete_at_the_same_mass(self):
        """The defect the family split exposed: mass alone made glazing the toughest thing."""
        heavy = 55_935.0  # what the shipped ground-floor band actually weighs
        assert mass.max_hp(heavy, "glass") < mass.max_hp(heavy, "concrete") / 10.0

    def test_a_curtain_wall_dies_to_one_burst(self):
        """A pane's whole job. 2.3 t of glazing must not need a rocket volley."""
        assert mass.max_hp(2315.0, "glass") <= 200.0

    def test_glass_carries_almost_no_armour(self):
        assert mass.armor_value(2315.0, "glass") < mass.armor_value(2315.0, "concrete") / 10.0

    def test_steel_outlasts_brick_at_the_same_mass(self):
        assert mass.max_hp(50_000.0, "steel") > mass.max_hp(50_000.0, "brick")

    def test_an_unknown_material_is_concrete(self):
        assert mass.toughness("unobtainium") == mass.toughness("concrete")

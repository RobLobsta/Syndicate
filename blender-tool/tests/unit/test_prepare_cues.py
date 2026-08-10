"""The cue ensemble of D15-S4.2, on synthetic geometry and with no Blender host.

Everything that *decides* in the preparation pipeline is pure Python over
:class:`syndicate_prepare.shell.Shell`, which is what makes these tests possible at all — and
which is the reason the split exists.
"""

from __future__ import annotations

import pytest

from syndicate_prepare import cues, grouping, repair
from syndicate_prepare.labels import (
    CHASSIS,
    GLASS,
    LABEL_MIN_CONFIDENCE,
    LIGHT,
    MIN_SHELL_TRIANGLES,
    UNCLASSIFIED,
    WHEEL,
)
from syndicate_prepare.shell import Shell


def shell(index=0, name="obj", material=None, triangles=500, lo=(0, 0, 0), hi=(1, 1, 1), **kwargs):
    centroid = kwargs.pop("centroid", tuple((lo[i] + hi[i]) * 0.5 for i in range(3)))
    return Shell(
        index=index,
        name=name,
        material=material,
        triangles=triangles,
        lo=lo,
        hi=hi,
        centroid=centroid,
        **kwargs,
    )


def car_body():
    """A shell spanning a whole 4.5 m car, so :class:`cues.BodyFrame` has real dimensions."""
    return shell(index=99, name="body", triangles=20_000, lo=(-0.9, 0.0, -2.25), hi=(0.9, 1.3,
        2.25))


# ---- D15-R5: whole tokens, never substrings ---------------------------------------------


def test_tokenise_splits_on_punctuation_and_camel_case():
    assert cues.tokenise("BodyPaint_Material.001") == {"body", "paint", "material"}
    assert cues.tokenise("FFord_MustangGTD_2025Coloured_Material1") >= {"coloured", "material"}


def test_tokenise_does_not_find_rim_inside_primary():
    """D15-R5's own example: substring matching labels a car's paint a wheel."""
    tokens = cues.tokenise("vehicle_generic_smallspecmap_PRIMARY")
    assert "primary" in tokens
    assert "rim" not in tokens


def test_nominal_cue_ignores_a_meaningless_material():
    """D15-E1: on a model whose materials are called bw00.001, C3 contributes nothing."""
    assert cues.material_nominal_votes(shell(material="bw00.001", name="Object_147")) == []


def test_nominal_cue_reads_a_meaningful_one():
    votes = cues.material_nominal_votes(shell(material="FFord_Window_Material1"))
    assert [vote.label for vote in votes] == [GLASS]


# ---- D15-R6: C2 outranks C3 ---------------------------------------------------------------


def test_declared_transparency_beats_a_misleading_name():
    body = cues.BodyFrame([car_body()])
    glazing = shell(
        material="door_panel_material",
        triangles=800,
        lo=(-0.7, 0.7, 0.2),
        hi=(0.7, 1.2, 1.0),
        alpha_mode="BLEND",
        base_alpha=0.35,
        transmission=0.9,
        roughness=0.05,
    )
    cues.label_shell(glazing, body, None)
    assert glazing.label == GLASS


def test_emissive_material_is_a_light():
    body = cues.BodyFrame([car_body()])
    lamp = shell(triangles=300, lo=(0.4, 0.5, 2.0), hi=(0.8, 0.75, 2.2), emissive=2.5)
    cues.label_shell(lamp, body, None)
    assert lamp.label == LIGHT


# ---- C1 geometric ---------------------------------------------------------------------------


def test_a_round_low_outboard_shell_is_a_wheel():
    body = cues.BodyFrame([car_body()])
    wheel = shell(triangles=4000, lo=(0.7, 0.0, 1.0), hi=(0.95, 0.66, 1.66))
    cues.label_shell(wheel, body, None)
    assert wheel.label == WHEEL


def test_a_wing_mirror_is_not_a_wheel():
    """D15-E6: a mirror is outboard and round enough in silhouette; height rejects it."""
    body = cues.BodyFrame([car_body()])
    mirror = shell(triangles=400, lo=(0.88, 0.95, 0.4), hi=(1.05, 1.12, 0.58))
    cues.label_shell(mirror, body, None)
    assert mirror.label != WHEEL


def test_a_shell_spanning_the_car_is_structure():
    body = cues.BodyFrame([car_body()])
    spine = shell(triangles=9000, lo=(-0.4, 0.2, -1.8), hi=(0.4, 0.6, 1.8))
    cues.label_shell(spine, body, None)
    assert spine.label == CHASSIS


def test_a_shell_no_cue_recognises_is_unclassified_not_guessed():
    """D15-R2: unclassified is a first-class outcome, never a confident wrong answer."""
    body = cues.BodyFrame([car_body()])
    anonymous = shell(triangles=120, lo=(0.30, 0.42, 0.10), hi=(0.36, 0.48, 0.16))
    cues.label_shell(anonymous, body, None)
    assert anonymous.label == UNCLASSIFIED
    assert anonymous.confidence < LABEL_MIN_CONFIDENCE


# ---- C4 structural: mirror pairing (D15-R20) ------------------------------------------------


def test_mirror_twins_are_found_across_the_centreline():
    left = shell(index=0, lo=(-1.0, 0.5, 0.0), hi=(-0.8, 0.7, 0.3))
    right = shell(index=1, lo=(0.8, 0.5, 0.0), hi=(1.0, 0.7, 0.3))
    twins = cues.find_mirror_twins([left, right])
    assert twins[0] is right
    assert twins[1] is left


def test_a_shell_on_the_centreline_has_no_twin():
    centre = shell(index=0, lo=(-0.1, 0.5, 0.0), hi=(0.1, 0.7, 0.3))
    assert cues.find_mirror_twins([centre]) == {}


# ---- Small-shell merging (D15-R17) -----------------------------------------------------------


def test_small_shells_merge_into_their_nearest_labelled_neighbour():
    panel = shell(index=0, triangles=2000, lo=(-1, 0.5, -1), hi=(1, 1.0, 1))
    panel.label = CHASSIS
    bolt = shell(index=1, triangles=MIN_SHELL_TRIANGLES - 1, lo=(0.1, 0.9, 0.1), hi=(0.12,
        0.92, 0.12))
    far = shell(index=2, triangles=3000, lo=(4, 0.5, 4), hi=(5, 1.0, 5))
    far.label = WHEEL

    merged = grouping.merge_small_shells([panel, bolt, far])

    assert merged == 1
    assert bolt.merged_into == panel.index
    assert bolt.label == CHASSIS


def test_merging_is_a_no_op_when_every_shell_is_big_enough():
    shells = [shell(index=i, triangles=1000) for i in range(3)]
    assert grouping.merge_small_shells(shells) == 0


# ---- Sides and grouping (D15-R18, D15-R19) ----------------------------------------------------


@pytest.mark.parametrize(
    ("x", "expected"),
    [(-1.0, "l"), (1.0, "r"), (0.0, "c"), (-0.01, "c"), (0.01, "c")],
)
def test_side_of_uses_the_deadband(x, expected):
    assert grouping.side_of(x) == expected


def test_wheels_on_one_side_become_two_parts_not_one():
    """The gap between a front and a rear wheel is far larger than either wheel."""
    front = shell(index=0, triangles=3000, lo=(0.7, 0, 1.2), hi=(0.95, 0.66, 1.86))
    rear = shell(index=1, triangles=3000, lo=(0.7, 0, -1.86), hi=(0.95, 0.66, -1.2))
    for piece in (front, rear):
        piece.label = WHEEL

    parts = grouping.group_into_parts([front, rear], {})

    assert len(parts) == 2
    assert {part.side for part in parts} == {"r"}


def test_a_door_skin_and_its_inner_card_stay_one_part():
    skin = shell(index=0, triangles=2000, lo=(-0.95, 0.3, -0.4), hi=(-0.90, 1.1, 0.7))
    card = shell(index=1, triangles=1200, lo=(-0.88, 0.35, -0.35), hi=(-0.84, 1.05, 0.65))
    for piece in (skin, card):
        piece.label = "panel"

    parts = grouping.group_into_parts([skin, card], {})

    assert len(parts) == 1
    assert parts[0].triangles == 3200


# ---- Angular coverage (D15-R21, D15-R24) --------------------------------------------------------


def test_a_ring_covers_the_whole_circle_and_a_wedge_does_not():
    """T-D15-7: a synthetic annulus reads 360 degrees and a wedge reads 90 or less."""
    import math

    ring = [
        (0.0, 0.5 * math.sin(math.radians(d)), 0.5 * math.cos(math.radians(d)))
        for d in range(0, 360, 3)
    ]
    wedge = [
        (0.0, 0.5 * math.sin(math.radians(d)), 0.5 * math.cos(math.radians(d)))
        for d in range(0, 91, 3)
    ]

    assert grouping.angular_coverage_deg(ring, (0.0, 0.0, 0.0), 24) == pytest.approx(360.0)
    assert grouping.angular_coverage_deg(wedge, (0.0, 0.0, 0.0), 24) <= 105.0


def test_vertices_on_the_axis_contribute_no_bearing():
    """A hub face's centre sits on the axis, where the angle is undefined."""
    assert grouping.angular_coverage_deg([(0.0, 0.0, 0.0)], (0.0, 0.0, 0.0), 24) == 0.0


# ---- Repair (D15-S5.5) -----------------------------------------------------------------------


def test_a_correctly_framed_car_reports_no_repairs():
    """D15-E10: a model already in game units and axes is a no-op with zero corrections."""
    shells = [shell(index=0, triangles=9000, lo=(-0.9, 0.0, -2.25), hi=(0.9, 1.3, 2.25))]
    report = repair.inspect(shells, {})

    assert report.applied_count == 0
    details = {check.check: check.detail for check in report.repairs}
    assert "plausible" in details["scale"]
    assert "D00-R16" in details["orientation"]
    assert "on the ground plane" in details["ground contact"]


def test_a_model_in_the_wrong_frame_says_which_correction_is_missing():
    shells = [shell(index=0, triangles=9000, lo=(-0.9, -2.25, 0.0), hi=(0.9, 2.25, 1.3))]
    report = repair.inspect(shells, {})
    details = {check.check: check.detail for check in report.repairs}
    assert "import.json" in details["orientation"]


def test_symmetry_is_reported_and_never_repaired():
    """D15-R26: real cars are asymmetric on purpose; mirroring that away damages them."""
    lonely = shell(index=0, triangles=900, lo=(0.6, 0.2, -0.4), hi=(0.9, 0.5, 0.2))
    report = repair.inspect([lonely, car_body()], {})

    assert report.symmetry_violations
    assert report.applied_count == 0
    assert lonely.lo == (0.6, 0.2, -0.4)

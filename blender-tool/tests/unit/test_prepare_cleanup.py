"""Stage 2 of D15-S5.1: the corrections that put a dropped-in model in the game's frame.

Every test here is against :mod:`syndicate_prepare.cleanup`, which decides what the correction
is; applying it to a Blender scene is three lines in ``prepare.apply_correction`` driven from
the same :class:`~syndicate_prepare.cleanup.Correction`, so what is asserted here is what the
scene gets.

T-D15-5 and T-D15-6 live here (a model scaled x100, a model yawed 180°), and so does the
property that makes the pipeline safe to re-run: composing the derived correction with whatever
``import.json`` already said has to leave the second run with nothing to do.
"""

from __future__ import annotations

import math

from syndicate_prepare import cleanup
from syndicate_prepare.shell import Shell


def shell(index=0, lo=(0, 0, 0), hi=(1, 1, 1), triangles=500, **kwargs):
    centroid = kwargs.pop("centroid", tuple((lo[i] + hi[i]) * 0.5 for i in range(3)))
    return Shell(
        index=index,
        name=f"obj_{index}",
        material=None,
        triangles=triangles,
        lo=lo,
        hi=hi,
        centroid=centroid,
        **kwargs,
    )


def car(scale=1.0, yaw_deg=0.0, lift=0.0, drift=0.0):
    """A 4.5 x 1.8 x 1.3 m car with a cabin behind its midpoint, transformed as asked.

    The cabin is what the nose test reads, so it is a separate, taller shell sitting behind
    the middle — which is where a cabin is on very nearly every vehicle, and the whole basis
    of :func:`cleanup.choose_yaw`.
    """
    shells = [
        shell(index=0, lo=(-0.9, 0.0, -2.25), hi=(0.9, 1.0, 2.25), triangles=8000),
        shell(index=1, lo=(-0.8, 1.0, -1.6), hi=(0.8, 1.3, 0.2), triangles=3000),
    ]
    correction = cleanup.Correction(
        scale=scale, yaw_deg=yaw_deg, translation=(drift, lift, 0.0)
    )
    cleanup.apply_to_shells(shells, correction)
    return shells


# ---- Scale (T-D15-5) ----------------------------------------------------------------------


def test_a_model_in_centimetres_is_scaled_to_metres():
    shells = car(scale=100.0)
    plan = cleanup.plan(shells)
    assert plan.correction.scale == 0.01
    assert "0.01" in plan.scale_reason


def test_a_model_in_millimetres_is_scaled_to_metres():
    assert cleanup.plan(car(scale=1000.0)).correction.scale == 0.001


def test_a_model_already_in_metres_is_not_scaled():
    plan = cleanup.plan(car())
    assert plan.correction.scale == 1.0
    assert "already a plausible" in plan.scale_reason


def test_a_model_no_unit_factor_can_rescue_is_reported_not_guessed():
    """Not every implausible length is a unit error, and the pipeline must not pretend."""
    huge = [shell(index=0, lo=(-3.0, 0.0, -1e7), hi=(3.0, 2.0, 1e7))]
    plan = cleanup.plan(huge)
    assert plan.correction.scale == 1.0
    assert "no unit factor fixes it" in plan.scale_reason


# ---- Orientation and nose direction (T-D15-6) -----------------------------------------------


def test_a_model_yawed_180_is_turned_round():
    plan = cleanup.plan(car(yaw_deg=180.0))
    assert plan.correction.yaw_deg == 180.0
    assert "yawed 180" in plan.yaw_reason


def test_a_model_lying_along_x_is_yawed_90():
    plan = cleanup.plan(car(yaw_deg=90.0))
    assert plan.correction.yaw_deg in (90.0, 270.0)


def test_the_wheelbase_survives_the_nose_correction():
    """T-D15-6: a yaw changes which axis is which, never how big the vehicle is."""
    straight = cleanup.measure_bounds(car())
    turned = car(yaw_deg=180.0)
    plan = cleanup.plan(turned)
    cleanup.apply_to_shells(turned, plan.correction)
    fixed = cleanup.measure_bounds(turned)
    assert math.isclose(fixed.length, straight.length, abs_tol=1e-6)
    assert math.isclose(fixed.width, straight.width, abs_tol=1e-6)


def test_a_model_on_its_side_is_reported_and_not_repaired():
    """A yaw cannot fix a roll, and guessing turns a visible fault into an invisible one."""
    onto_its_side = [shell(index=0, lo=(-0.65, 0.0, -2.25), hi=(0.65, 1.8, 2.25))]
    plan = cleanup.plan(onto_its_side)
    assert plan.up_axis_ok is False
    assert "lying on its side" in plan.up_axis_detail


# ---- Placement ------------------------------------------------------------------------------


def test_a_floating_model_is_dropped_onto_the_ground_plane():
    plan = cleanup.plan(car(lift=0.62))
    assert math.isclose(plan.correction.translation[1], -0.62, abs_tol=1e-6)


def test_a_model_off_the_centreline_is_centred():
    plan = cleanup.plan(car(drift=0.4))
    assert math.isclose(plan.correction.translation[0], -0.4, abs_tol=1e-3)


def test_a_centred_model_is_left_alone():
    """A car is asymmetric on purpose; a centimetre of bodywork is not a fault (D15-R26)."""
    plan = cleanup.plan(car(drift=0.005))
    assert plan.correction.translation[0] == 0.0


# ---- Composition: the property that makes a re-run safe --------------------------------------


def test_composing_a_correction_with_its_own_derivation_is_idempotent():
    """Run the pipeline twice and the second run finds nothing to do (DEC-036)."""
    shells = car(scale=100.0, yaw_deg=180.0, lift=0.5, drift=0.3)
    first = cleanup.plan(shells).correction
    cleanup.apply_to_shells(shells, first)
    second = cleanup.plan(shells).correction
    assert second.is_identity or (
        abs(second.scale - 1.0) < 1e-9
        and abs(second.yaw_deg) < 1e-9
        and all(abs(value) < 1e-6 for value in second.translation)
    )


def test_composition_agrees_with_applying_both_in_turn():
    first = cleanup.Correction(scale=0.01, yaw_deg=90.0, translation=(0.1, 0.2, 0.3))
    second = cleanup.Correction(scale=2.0, yaw_deg=180.0, translation=(-1.0, 0.5, 0.25))
    point = (1.7, -0.4, 3.1)
    composed = first.then(second)
    assert all(
        math.isclose(a, b, abs_tol=1e-9)
        for a, b in zip(
            composed.apply_to_point(point),
            second.apply_to_point(first.apply_to_point(point)),
            strict=True,
        )
    )


def test_area_and_volume_scale_with_the_correction():
    """A model in millimetres must not export parts a million times too light."""
    one = shell(index=0)
    one.area_m2, one.volume_m3 = 6.0, 1.0
    cleanup.apply_to_shells([one], cleanup.Correction(scale=0.001))
    assert math.isclose(one.area_m2, 6e-6, rel_tol=1e-9)
    assert math.isclose(one.volume_m3, 1e-9, rel_tol=1e-9)


def test_the_import_json_document_carries_the_three_fields_the_loader_reads():
    document = cleanup.Correction(0.5, 90.0, (1.0, 2.0, 3.0)).as_import_json("note")
    assert document["scaleToMetres"] == 0.5
    assert document["yawDeg"] == 90.0
    assert document["translationM"] == {"x": 1.0, "y": 2.0, "z": 3.0}

"""Stage 1b: the house style, decided in pure Python (D15-S4.5, D15-S5.9).

Everything worth asserting about style normalisation is a decision rather than a render: which
surface role a material is, how hard the pass pushes, and what a colour becomes. All three are
pure functions, so none of this needs a Blender host — which is the same split that lets the cue
ensemble be tested without one.
"""

from __future__ import annotations

import colorsys
from pathlib import Path

import pytest

from syndicate_prepare import style

ASSETS = Path(__file__).resolve().parents[3] / "assets"


@pytest.fixture
def table() -> style.StyleTable:
    return style.StyleTable.load(ASSETS / "materials" / "style.json")


def material(name="m", **kwargs) -> style.SourceMaterial:
    return style.SourceMaterial(name=name, **kwargs)


# ---- The shipped table ---------------------------------------------------------------------


def test_the_shipped_style_table_covers_every_surface(table):
    """A role with no row would restyle nothing and silently leave a surface unnormalised."""
    assert set(table.surfaces) == set(style.SURFACES)
    for surface in style.SURFACES:
        row = table.surfaces[surface]
        assert 0.0 <= row.saturation_max <= 1.0
        assert 0.0 <= row.value_min <= row.value_max <= 1.0
        assert 0.0 <= row.grime <= 1.0


def test_a_table_missing_a_surface_is_refused():
    with pytest.raises(style.StyleError):
        style.StyleTable.from_document({"surfaces": [{"surface": "body_paint"}]})


def test_a_table_naming_an_unknown_surface_is_refused():
    with pytest.raises(style.StyleError):
        style.StyleTable.from_document({"surfaces": [{"surface": "spoiler"}]})


# ---- Classification (D15-S5.9, R47f) ---------------------------------------------------------


def test_physical_evidence_outranks_the_name():
    """D15-R6, one stage earlier: a file's declared transmission is what it will render as."""
    glazing = material("BodyPaint_Material1", transmission=0.9)
    assert style.classify(glazing)[0] == style.GLASS


def test_a_blended_low_alpha_is_glazing_whatever_it_is_called():
    blended = material("bw00.001", alpha_mode="BLEND", base_alpha=0.4)
    assert style.classify(blended)[0] == style.GLASS


def test_emission_makes_a_lamp():
    assert style.classify(material("oyctp", emissive=0.8))[0] == style.LIGHT


def test_a_near_black_rough_surface_is_rubber():
    black = material("unnamed", base_colour=(0.03, 0.03, 0.03), roughness=0.9)
    assert style.classify(black)[0] == style.TYRE


def test_a_smooth_metal_is_plating():
    assert style.classify(material("unnamed", metallic=0.95, roughness=0.1))[0] == style.CHROME


def test_names_are_matched_as_whole_words():
    """DISC-037, one stage earlier: `wheelarch` is not a wheel and `wheelwell` is not a tyre."""
    assert style.token_matched("front_wheelarch_paint") != "tyre"
    assert style.classify_by_name("Window_Material1") == style.GLASS
    assert style.classify_by_name("FFord_Callipers_Zone") == style.CHROME


def test_a_camel_case_name_is_split_into_words():
    assert style.classify_by_name("WindowMaterial1") == style.GLASS


def test_the_material_covering_most_of_the_car_is_its_paint():
    """R47g. Without it an untextured, unnamed supercar body renders as matte grey."""
    biggest = material("bw00.001", triangles=35941)
    assert style.classify(biggest, is_dominant=True)[0] == style.BODY_PAINT


def test_a_material_with_no_evidence_is_neutral_rather_than_trim():
    """R47g. The single most consequential row in the table.

    41 of the shipped Eclipse's 60 materials reach this line, both alloy wheels among them. Trim
    is near-black, non-metallic and rough; sending everything unrecognised there turned the whole
    car into grey mush and rendered its wheels as black discs with no spokes.
    """
    unnamed = material("bw00.002", triangles=3486)
    assert style.classify(unnamed, is_dominant=False)[0] == style.NEUTRAL
    # A material a cue actually identifies as trim still gets the trim treatment.
    assert style.classify_by_name("front_bumper_plastic") != style.NEUTRAL


def test_the_neutral_row_preserves_metallic_and_roughness(table):
    """A null target means "leave the artist's value alone", and only neutral uses one."""
    neutral = table.surfaces[style.NEUTRAL]
    assert neutral.metallic is None
    assert neutral.roughness is None
    for surface in style.SURFACES:
        if surface == style.NEUTRAL:
            continue
        assert table.surfaces[surface].metallic is not None, surface
        assert table.surfaces[surface].roughness is not None, surface


# ---- Stylised or realistic (R47c) --------------------------------------------------------------


def test_a_saturated_textureless_model_is_stylised(table):
    cartoon = [
        material("red", base_colour=(0.9, 0.05, 0.05), triangles=8000),
        material("blue", base_colour=(0.05, 0.1, 0.9), triangles=2000),
    ]
    scene = style.classify_scene(cartoon, table)
    assert scene.is_stylised
    assert scene.strength == table.stylised_strength


def test_a_textured_model_is_realistic_however_saturated(table):
    photoscan = [
        material("paint", base_colour=(0.9, 0.05, 0.05), has_base_texture=True, triangles=9000),
        material("trim", base_colour=(0.9, 0.1, 0.1), triangles=1000),
    ]
    scene = style.classify_scene(photoscan, table)
    assert not scene.is_stylised
    assert scene.strength == table.realistic_strength


def test_one_bright_badge_does_not_make_a_photoreal_car_a_cartoon(table):
    """R47c: the measurement is weighted by triangles, which is what makes it robust."""
    car = [
        material(
            "body", base_colour=(0.4, 0.4, 0.42), has_base_texture=True, triangles=200_000
        ),
        material("badge", base_colour=(1.0, 0.0, 0.0), triangles=120),
    ]
    assert not style.classify_scene(car, table).is_stylised


def test_an_empty_scene_is_handled_rather_than_dividing_by_zero(table):
    scene = style.classify_scene([], table)
    assert scene.materials == []
    assert scene.strength == table.realistic_strength


# ---- The colour arithmetic (R47b, R47d, R47e) --------------------------------------------------


def test_a_cartoon_red_keeps_its_hue_and_loses_its_saturation(table):
    """R47b: the hue is gameplay — a faction colour — and the saturation is what makes it a toy."""
    before = (0.9, 0.05, 0.05)
    after = style.restyle(before, table.surfaces[style.BODY_PAINT], table, 1.0, "paint", 1)

    hue_before, sat_before, val_before = colorsys.rgb_to_hsv(*before)
    hue_after, sat_after, val_after = colorsys.rgb_to_hsv(*after)
    assert abs(hue_after - hue_before) < 0.05
    assert sat_after < sat_before
    assert val_after < val_before


def test_a_tyre_goes_black_and_a_chrome_goes_dull(table):
    tyre = style.restyle((0.5, 0.5, 0.5), table.surfaces[style.TYRE], table, 1.0, "t", 1)
    assert max(tyre) < 0.12
    assert style.restyle_scalar(0.02, table.surfaces[style.CHROME].roughness, 1.0) > 0.3


def test_strength_zero_leaves_a_conforming_colour_alone(table):
    """Everything the surface row does is scaled by strength. The tone band is not."""
    before = (0.30, 0.22, 0.18)
    assert style.conforms(before, table, style.TRIM), "the fixture must start inside the band"
    after = style.restyle(before, table.surfaces[style.TRIM], table, 0.0, "x", 1)
    assert after == pytest.approx(before)


# ---- The palette (R47i) --------------------------------------------------------------------


def test_a_hue_is_pulled_onto_the_nearest_allowed_one(table):
    """A limited hue wheel is most of what a coherent art style is."""
    allowed = {round(h) for h in table.palette.hues_deg}
    for degrees in (0, 45, 90, 150, 200, 260, 300, 350):
        source = colorsys.hsv_to_rgb(degrees / 360.0, 0.9, 0.6)
        styled = style.restyle(source, table.surfaces[style.BODY_PAINT], table, 1.0, "p", 1)
        hue = colorsys.rgb_to_hsv(*styled)[0] * 360.0
        nearest = min(allowed, key=lambda t: min(abs(hue - t), 360 - abs(hue - t)))
        assert min(abs(hue - nearest), 360 - abs(hue - nearest)) < 25.0, degrees


def test_the_snap_takes_the_short_way_round_the_wheel(table):
    """A hue at 350 and a target at 6 are 16 degrees apart, not 344."""
    snapped = table.palette.snap(350.0 / 360.0, style.BODY_PAINT) * 360.0
    assert snapped > 340.0 or snapped < 20.0


def test_a_lamp_keeps_its_own_hue(table):
    """The one surface exempt: a tail light is red and a Ravager glow is cyan, palette or not."""
    assert style.LIGHT in table.palette.exempt
    red = colorsys.hsv_to_rgb(0.0, 0.9, 0.8)
    cyan = colorsys.hsv_to_rgb(0.5, 0.9, 0.8)
    for source in (red, cyan):
        styled = style.restyle(source, table.surfaces[style.LIGHT], table, 1.0, "lamp", 1)
        before = colorsys.rgb_to_hsv(*source)[0]
        after = colorsys.rgb_to_hsv(*styled)[0]
        assert abs(after - before) < 0.03


# ---- The tone band (R47j, R47k) ----------------------------------------------------------------


def test_nothing_finishes_outside_the_tone_band(table):
    """The rule the whole thing exists for: no import clashes on brightness.

    Swept over every surface and the extremes an imported asset can arrive at — blown-out white
    photoscan albedo, pure black, and a fully saturated primary.
    """
    for surface in style.SURFACES:
        row = table.surfaces[surface]
        for source in ((1.0, 1.0, 1.0), (0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (0.0, 1.0, 0.35)):
            for strength in (0.0, 0.62, 1.0):
                styled = style.restyle(source, row, table, strength, surface, 1)
                assert style.conforms(styled, table, surface), (surface, source, strength)


def test_white_is_pulled_down_and_black_is_lifted_off_the_floor(table):
    row = table.surfaces[style.NEUTRAL]
    white = style.restyle((1.0, 1.0, 1.0), row, table, 1.0, "w", 1)
    black = style.restyle((0.0, 0.0, 0.0), row, table, 1.0, "b", 1)

    assert style.luminance(white) <= table.tone.luminance_max + 1e-4
    # A black part must stay a shape in shadow rather than becoming a hole in the frame.
    assert style.luminance(black) >= table.tone.luminance_min - 1e-4


def test_a_lamp_may_break_the_ceiling_and_not_the_floor(table):
    """A headlight that is not the brightest thing in frame does not read as a headlight."""
    assert style.LIGHT in table.tone.exempt_from_ceiling
    lit = style.restyle((1.0, 0.98, 0.92), table.surfaces[style.LIGHT], table, 1.0, "lamp", 1)
    assert style.luminance(lit) > table.tone.luminance_max
    assert style.conforms(lit, table, style.LIGHT)
    # And the exemption is only the ceiling, and only for a lamp.
    assert not style.conforms(lit, table, style.BODY_PAINT)


def test_the_conformance_report_counts_what_it_wrote(table):
    inside = style.restyle((0.9, 0.2, 0.2), table.surfaces[style.NEUTRAL], table, 1.0, "a", 1)
    written = [("a", style.NEUTRAL, inside), ("b", style.BODY_PAINT, (1.0, 1.0, 1.0))]
    report = style.conformance_report(written, table)

    assert report["materials"] == 2
    assert report["outsideBand"] == 1
    assert report["offenders"][0]["material"] == "b"


def test_a_tint_never_darkens_past_the_floor(table):
    """R47d: behind a texture that socket is a multiplier, and trim's 0.20 is a silhouette."""
    for surface in style.SURFACES:
        tint = style.tint_for(table.surfaces[surface], table, 1.0, surface, 1)
        assert max(tint) >= style.TINT_VALUE_MIN - 1e-6, surface
        assert max(tint) <= 1.0 + 1e-6, surface


def test_a_tint_is_lighter_than_the_colour_the_same_style_would_assign(table):
    row = table.surfaces[style.TRIM]
    assert max(style.tint_for(row, table, 1.0, "trim", 1)) > max(
        style.restyle((0.5, 0.5, 0.5), row, table, 1.0, "trim", 1)
    )


def test_restyling_is_a_function_of_the_material_and_the_seed_alone(table):
    """G3, and R47e: adding a badge must not change the colour of the doors."""
    row = table.surfaces[style.BODY_PAINT]
    once = style.restyle((0.6, 0.2, 0.2), row, table, 0.55, "door_l", 7)
    again = style.restyle((0.6, 0.2, 0.2), row, table, 0.55, "door_l", 7)
    other = style.restyle((0.6, 0.2, 0.2), row, table, 0.55, "door_r", 7)
    seeded = style.restyle((0.6, 0.2, 0.2), row, table, 0.55, "door_l", 8)

    assert once == again
    assert once != other
    assert once != seeded

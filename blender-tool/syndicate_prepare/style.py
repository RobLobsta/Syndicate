"""Stage 1b: normalise every source material into the house style (D15-S9).

The problem this solves is not a rendering problem. Every vehicle in this game arrives as art
somebody else made — one a photoscanned supercar with 4K textures, the next a flat-shaded
cartoon with primary-colour paint and no textures at all. Both can be prepared into perfectly
correct parts, and parked side by side they still look like two different games.

So before anything measures the model, every material in it is classified into one of the
surface roles in ``assets/materials/style.json`` and moved into that role's colour and
reflectance band. Paint keeps its hue and loses its showroom saturation; chrome stops being a
mirror; tyres go black and rough; everything picks up grime. The result is a roster that reads
as one artist's work.

**Two things this pass must not do**, and both are the reason it is written as a narrow rewrite
rather than as "replace the material":

*It must not touch labelling evidence.* D15-S4.2's C2 cue reads a material's alpha mode, its
transmission, its backface culling and — through C3 — its **name**. Those are the file's own
declaration of what a surface is, they outrank every geometric measurement, and they are the only
reason glass is found at all on a model whose materials are called ``bw00.001`` (DISC-019).
Normalising them would make every windscreen opaque and cost the pipeline its best cue. This
module writes base colour, metallic, roughness and emission colour, and nothing else.

*It must not invent variation.* Every number below is a function of the style table, the source
material and the seed (G3, D15-R30). The grime jitter is hashed from the material's name, so two
runs over the same model produce byte-identical meshes and a diff between them means the art
changed.

Everything in this module is pure except :func:`apply_to_scene`, which is the only function that
touches ``bpy`` — the same split the rest of the pipeline uses, and what lets every classification
and every colour decision be unit-tested with no Blender host.
"""

from __future__ import annotations

import colorsys
import contextlib
import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

try:  # pragma: no cover - exercised only inside a Blender host
    import bpy  # isort: skip
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bpy = None  # type: ignore[assignment]


# ---- The surface roles ------------------------------------------------------------------

BODY_PAINT = "body_paint"
NEUTRAL = "neutral"
TRIM = "trim"
CHROME = "chrome"
GLASS = "glass"
TYRE = "tyre"
LIGHT = "light"
GRILLE = "grille"
INTERIOR = "interior"
UNDERBODY = "underbody"

#: Every surface role, in the order the style table lists them.
SURFACES = (BODY_PAINT, NEUTRAL, TRIM, CHROME, GLASS, TYRE, LIGHT, GRILLE, INTERIOR, UNDERBODY)

#: Name tokens that identify a surface, tried whole-token so that ``wheelarch`` does not match
#: ``wheel``. Ordered most specific first: ``headlight_glass`` is a light, not glazing.
NAME_TOKENS: tuple[tuple[str, tuple[str, ...]], ...] = (
    (LIGHT, ("light", "lights", "lamp", "headlight", "headlamp", "taillight", "tail",
             "brakelight", "indicator", "blinker", "lens", "reflector")),
    (TYRE, ("tyre", "tire", "rubber", "tread")),
    (GLASS, ("glass", "window", "windows", "windscreen", "windshield", "glazing", "screen")),
    (GRILLE, ("grille", "grill", "mesh", "vent", "radiator", "intake")),
    (CHROME, ("chrome", "metal", "steel", "alloy", "calliper", "caliper", "exhaust",
              "polished", "mirror")),
    (INTERIOR, ("interior", "seat", "seats", "dash", "dashboard", "cabin", "cockpit",
                "steering", "wheelwell", "leather", "fabric")),
    (UNDERBODY, ("underbody", "undercarriage", "chassis", "frame", "suspension", "engine",
                 "axle", "drivetrain", "brake", "disc", "rust")),
    (BODY_PAINT, ("body", "paint", "carpaint", "bodywork", "primary", "main", "livery",
                  "shell", "panel")),
)

#: Transmission or (blended alpha below this) that makes a material glazing, whatever it is
#: called. The same threshold the C2 cue uses, deliberately: two different answers to "is this
#: glass" in one pipeline is a defect waiting for a model that lands between them.
GLASS_TRANSMISSION = 0.3
GLASS_ALPHA = 0.9

#: Emission above which a material is a lamp.
LIGHT_EMISSION = 0.1

#: Base-colour value below which an untextured, rough material is rubber.
TYRE_VALUE_MAX = 0.12
TYRE_ROUGHNESS_MIN = 0.55

#: Metallic and roughness that make a material plated metal on its physical inputs alone.
CHROME_METALLIC_MIN = 0.7
CHROME_ROUGHNESS_MAX = 0.35

#: Metres of hue, in degrees, that the grime jitter may move a surface. Small: it exists to
#: keep two materials in the same role from being pixel-identical, not to reintroduce variety.
GRIME_JITTER = 0.06

#: The darkest a **tint** may be (see :func:`tint_for`). A surface band's own value ceiling is a
#: colour, and applying it as a multiplier would render a textured car nearly black — trim's
#: ceiling is 0.20, and 20% of a diffuse map is not weathering, it is a silhouette.
TINT_VALUE_MIN = 0.55


def _optional(value) -> float | None:
    """A style target, or ``None`` for "leave the source's own value alone"."""
    return None if value is None else float(value)


class StyleError(Exception):
    """A style table that cannot be applied, reported rather than guessed around."""


@dataclass(frozen=True)
class SurfaceStyle:
    """One row of ``style.json``: where a surface's colour and reflectance must end up."""

    surface: str
    hue_shift_deg: float = 0.0
    saturation_max: float = 1.0
    value_min: float = 0.0
    value_max: float = 1.0
    #: ``None`` means **preserve** — leave the source's own value alone. Only the neutral row
    #: uses it, and it is the difference between normalising a car and flattening one.
    metallic: float | None = 0.0
    roughness: float | None = 0.5
    grime: float = 0.0


@dataclass
class SourceMaterial:
    """One material as the source file declares it, before anything is rewritten.

    The fields are exactly what :func:`syndicate_prepare.prepare.read_material_physics` reads off
    a Principled BSDF, plus the triangle count the material covers — which is what lets "the
    material carrying most of the car is its paint" be a rule rather than a guess.
    """

    name: str
    base_colour: tuple[float, float, float] = (0.8, 0.8, 0.8)
    metallic: float = 0.0
    roughness: float = 0.5
    transmission: float = 0.0
    base_alpha: float = 1.0
    alpha_mode: str = "OPAQUE"
    emissive: float = 0.0
    has_base_texture: bool = False

    #: Mean Rec. 709 luma of the base-colour image, or ``None`` when there is no texture.
    #:
    #: The one measurement that lets the tone band mean anything for a textured model. Behind a
    #: texture the base-colour socket is a multiplier (DISC-048), so the *material's* colour says
    #: nothing about how bright the surface will actually be — that is in the image, and a
    #: blown-out photoscan albedo is precisely the import the band exists to catch.
    texture_luminance: float | None = None

    triangles: int = 0

    #: Filled in by :func:`classify_scene`.
    surface: str | None = None
    because: str = ""


@dataclass(frozen=True)
class Palette:
    """The hue wheel the whole game is allowed to use (D15-R47i).

    A limited hue set is most of what a coherent art style *is*. Six hues, snapped to at
    :attr:`pull` strength, is the difference between a roster that looks like one artist and one
    that looks like nine well-behaved artists.
    """

    hues_deg: tuple[float, ...] = ()
    pull: float = 0.0
    exempt: frozenset[str] = frozenset()

    def snap(self, hue: float, surface: str) -> float:
        """Pull a hue (in ``[0,1)``) onto the nearest allowed one."""
        if not self.hues_deg or self.pull <= 0.0 or surface in self.exempt:
            return hue
        degrees = (hue % 1.0) * 360.0
        nearest = min(self.hues_deg, key=lambda target: _hue_distance_deg(degrees, target))
        # Shortest way round the wheel: a hue at 350 and a target at 6 are 16 degrees apart, and
        # interpolating the raw numbers would take it the long way through green.
        delta = _signed_hue_delta_deg(degrees, nearest)
        return ((degrees + delta * self.pull) % 360.0) / 360.0


@dataclass(frozen=True)
class Tone:
    """The global luminance band every surface finishes inside (D15-R47j).

    The answer to "brights and darks clashing". Each surface row is a statement about *that*
    surface; this is the one rule that holds across all of them, so an imported material cannot be
    brighter than the brightest thing already in the scene however its own row was written.
    """

    luminance_min: float = 0.0
    luminance_max: float = 1.0
    accent_saturation_max: float = 1.0
    exempt_from_ceiling: frozenset[str] = frozenset()

    def apply(self, colour: tuple[float, float, float], surface: str) -> tuple[float, float, float]:
        hue, saturation, value = colorsys.rgb_to_hsv(*colour)
        saturation = min(saturation, _clamp01(self.accent_saturation_max))
        toned = colorsys.hsv_to_rgb(hue, saturation, value)

        luma = luminance(toned)
        ceiling = 1.0 if surface in self.exempt_from_ceiling else self.luminance_max
        if luma <= 0.0:
            # Pure black carries no hue to scale; lift it to the floor as neutral grey.
            return (self.luminance_min,) * 3
        if luma < self.luminance_min:
            return _scale_to_luminance(toned, self.luminance_min, luma)
        if luma > ceiling:
            return _scale_to_luminance(toned, ceiling, luma)
        return toned


def luminance(colour: tuple[float, float, float]) -> float:
    """Rec. 709 luma. What "how bright is this" means when the answer must survive a hue change."""
    return 0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2]


def _scale_to_luminance(colour, target: float, current: float):
    scale = target / current
    return tuple(_clamp01(component * scale) for component in colour)


def _hue_distance_deg(a: float, b: float) -> float:
    delta = abs(a - b) % 360.0
    return min(delta, 360.0 - delta)


def _signed_hue_delta_deg(frm: float, to: float) -> float:
    delta = (to - frm + 540.0) % 360.0 - 180.0
    return delta


@dataclass
class StyleTable:
    """``assets/materials/style.json``, parsed."""

    style_id: str
    surfaces: dict[str, SurfaceStyle]
    grime_colour: tuple[float, float, float]
    saturation_mean_above: float
    textured_fraction_below: float
    stylised_strength: float
    realistic_strength: float
    palette: Palette = Palette()
    tone: Tone = Tone()

    @classmethod
    def load(cls, path: Path) -> StyleTable:
        path = Path(path)
        if not path.is_file():
            raise StyleError(f"style table not found: {path}")
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise StyleError(f"{path} is not valid JSON: {exc}") from exc
        return cls.from_document(document, str(path))

    @classmethod
    def from_document(cls, document: dict, where: str = "style table") -> StyleTable:
        surfaces: dict[str, SurfaceStyle] = {}
        for row in document.get("surfaces", []):
            name = row.get("surface")
            if name not in SURFACES:
                raise StyleError(f"{where}: \"{name}\" is not one of {list(SURFACES)}")
            surfaces[name] = SurfaceStyle(
                surface=name,
                hue_shift_deg=float(row.get("hueShiftDeg", 0.0)),
                saturation_max=float(row.get("saturationMax", 1.0)),
                value_min=float(row.get("valueMin", 0.0)),
                value_max=float(row.get("valueMax", 1.0)),
                metallic=_optional(row.get("metallic", 0.0)),
                roughness=_optional(row.get("roughness", 0.5)),
                grime=float(row.get("grime", 0.0)),
            )
        missing = [name for name in SURFACES if name not in surfaces]
        if missing:
            raise StyleError(f"{where}: no row for {', '.join(missing)}")

        grime = document.get("grimeColour") or {}
        stylised = document.get("stylisedSource") or {}
        palette_doc = document.get("palette") or {}
        tone_doc = document.get("tone") or {}
        palette = Palette(
            hues_deg=tuple(float(entry["hueDeg"]) for entry in palette_doc.get("hues", [])),
            pull=float(palette_doc.get("pull", 0.0)),
            exempt=frozenset(palette_doc.get("exemptSurfaces", [])),
        )
        tone = Tone(
            luminance_min=float(tone_doc.get("luminanceMin", 0.0)),
            luminance_max=float(tone_doc.get("luminanceMax", 1.0)),
            accent_saturation_max=float(tone_doc.get("accentSaturationMax", 1.0)),
            exempt_from_ceiling=frozenset(tone_doc.get("exemptFromCeiling", [])),
        )
        if tone.luminance_min > tone.luminance_max:
            raise StyleError(
                f"{where}: tone.luminanceMin {tone.luminance_min} is above luminanceMax "
                f"{tone.luminance_max}"
            )
        return cls(
            style_id=document.get("styleId", "style_unnamed"),
            surfaces=surfaces,
            grime_colour=(
                float(grime.get("r", 0.1)),
                float(grime.get("g", 0.09)),
                float(grime.get("b", 0.07)),
            ),
            saturation_mean_above=float(stylised.get("saturationMeanAbove", 0.42)),
            textured_fraction_below=float(stylised.get("texturedFractionBelow", 0.35)),
            stylised_strength=float(stylised.get("stylisedStrength", 1.0)),
            realistic_strength=float(stylised.get("realisticStrength", 0.55)),
            palette=palette,
            tone=tone,
        )


# ---- Classification ------------------------------------------------------------------------


def classify(material: SourceMaterial, is_dominant: bool = False) -> tuple[str, str]:
    """Which surface role a material is, and why.

    Physical evidence first, name second — the same precedence D15-R6 fixes for labelling, and
    for the same reason: a file's declared transmission is what it will actually render as, and
    its material name is a comment somebody typed. On the Eclipse the names are ``bw00.001`` and
    ``oyctp``; on the Stampede they are ``Window_Material1``. Any rule that trusted names alone
    would work on exactly one of the two cars this project ships (DISC-019).

    ``is_dominant`` is the tie-break for a material with no evidence at all: the one covering the
    most triangles on a vehicle is its paint. That is true of every car model anyone has ever
    published, and it is what stops an unnamed, untextured supercar body from being rendered as
    generic matte grey.

    Everything else with no evidence is :data:`NEUTRAL`, **not** :data:`TRIM`, and the distinction
    is the difference between a normalised car and a flattened one. On the shipped Eclipse 41 of 60
    materials reach this line — including both alloy wheels — and the trim row would have painted
    every one of them near-black, non-metallic and rough.
    """
    if material.transmission > GLASS_TRANSMISSION:
        return GLASS, f"transmission {material.transmission:.2f}"
    if material.alpha_mode == "BLEND" and material.base_alpha < GLASS_ALPHA:
        return GLASS, f"blended at alpha {material.base_alpha:.2f}"
    if material.emissive > LIGHT_EMISSION:
        return LIGHT, f"emissive {material.emissive:.2f}"

    token = classify_by_name(material.name)
    if token is not None:
        return token, f"material name contains \"{token_matched(material.name)}\""

    value = max(material.base_colour)
    if value <= TYRE_VALUE_MAX and material.roughness >= TYRE_ROUGHNESS_MIN:
        return TYRE, f"near-black ({value:.2f}) and rough ({material.roughness:.2f})"
    if material.metallic >= CHROME_METALLIC_MIN and material.roughness <= CHROME_ROUGHNESS_MAX:
        return CHROME, f"metallic {material.metallic:.2f}, roughness {material.roughness:.2f}"
    if is_dominant:
        return BODY_PAINT, f"covers the most geometry ({material.triangles} triangles)"
    return NEUTRAL, "no physical or nominal evidence; left nearly as the artist made it"


def classify_by_name(name: str | None) -> str | None:
    """The surface a material's name declares, or None. Whole tokens only (DISC-037)."""
    matched = token_matched(name)
    if matched is None:
        return None
    for surface, tokens in NAME_TOKENS:
        if matched in tokens:
            return surface
    return None


def token_matched(name: str | None) -> str | None:
    """The first NAME_TOKENS token this name contains as a whole word, or None.

    Whole word rather than substring, because ``vehicle_generic_smallspecmap_WHEEL`` once
    defined an axle and took a third of a car with it (DISC-037). The same class of mistake
    here would paint a wheel arch as a tyre.
    """
    if not name:
        return None
    words = _tokenise(name)
    for _surface, tokens in NAME_TOKENS:
        for token in tokens:
            if token in words:
                return token
    return None


def _tokenise(name: str) -> set[str]:
    """Every whole word in a material name, lowercased, singular forms included.

    Two passes, and both are needed by real art. The first splits on non-alphanumerics, which
    covers ``Window_Material1`` and ``vehicle_generic_smallspecmap_WHEEL``. The second splits each
    of those on camel-case boundaries **before** lowercasing, which is what recovers ``window``
    from ``WindowMaterial1`` — a name with no separators at all, and the form half of one shipped
    car uses.

    A trailing ``s`` is also offered as a word, so ``Callipers`` matches ``calliper``. Naive, and
    the naivety is bounded: the vocabulary it is matched against is a fixed list of nine surfaces'
    worth of tokens, so the worst a wrong singular can do is fail to match.
    """
    words: set[str] = set()

    def add(word: str) -> None:
        word = word.lower()
        if not word:
            return
        words.add(word)
        if len(word) > 3 and word.endswith("s"):
            words.add(word[:-1])

    for chunk in _split_on_symbols(name):
        add(chunk)
        for piece in _split_camel(chunk):
            add(piece)
    return words


def _split_on_symbols(name: str) -> list[str]:
    chunks, current = [], []
    for character in name:
        if character.isalnum():
            current.append(character)
        elif current:
            chunks.append("".join(current))
            current = []
    if current:
        chunks.append("".join(current))
    return chunks


def _split_camel(chunk: str) -> list[str]:
    """``WindowMaterial1`` to ``["Window", "Material1"]``; an all-caps chunk stays whole."""
    pieces, current = [], []
    for character in chunk:
        if character.isupper() and current and not current[-1].isupper():
            pieces.append("".join(current))
            current = [character]
        else:
            current.append(character)
    if current:
        pieces.append("".join(current))
    return pieces


@dataclass
class SceneStyle:
    """What :func:`classify_scene` decided about a whole model."""

    materials: list[SourceMaterial] = field(default_factory=list)
    saturation_mean: float = 0.0
    textured_fraction: float = 0.0
    is_stylised: bool = False
    strength: float = 0.0

    def as_dict(self) -> dict:
        return {
            "saturationMean": round(self.saturation_mean, 4),
            "texturedFraction": round(self.textured_fraction, 4),
            "sourceKind": "stylised" if self.is_stylised else "realistic",
            "strength": round(self.strength, 3),
            "materials": [
                {
                    "material": material.name,
                    "surface": material.surface,
                    "because": material.because,
                    "triangles": material.triangles,
                }
                for material in sorted(self.materials, key=lambda m: m.name)
            ],
        }


def classify_scene(materials: list[SourceMaterial], table: StyleTable) -> SceneStyle:
    """Classify every material, and decide how hard to push the whole model.

    The stylised/realistic verdict is measured rather than declared, over two numbers weighted
    by the triangles each material covers: mean colour saturation, and the fraction of geometry
    whose material carries a base-colour texture. A cartoon is saturated and textureless; a
    photoscan is neither. Weighting by triangles is what stops one bright badge on an otherwise
    photoreal car from making the whole model 'cartoon' and having its textures thrown away.
    """
    scene = SceneStyle(materials=list(materials))
    if not materials:
        scene.strength = table.realistic_strength
        return scene

    dominant = max(materials, key=lambda m: (m.triangles, m.name))
    for material in materials:
        material.surface, material.because = classify(material, material is dominant)

    total = sum(max(0, material.triangles) for material in materials)
    if total <= 0:
        weights = [1.0] * len(materials)
        total = float(len(materials))
    else:
        weights = [float(max(0, material.triangles)) for material in materials]

    scene.saturation_mean = sum(
        weight * saturation_of(material.base_colour)
        for weight, material in zip(weights, materials, strict=True)
    ) / total
    scene.textured_fraction = sum(
        weight for weight, material in zip(weights, materials, strict=True)
        if material.has_base_texture
    ) / total

    scene.is_stylised = (
        scene.saturation_mean > table.saturation_mean_above
        and scene.textured_fraction < table.textured_fraction_below
    )
    scene.strength = table.stylised_strength if scene.is_stylised else table.realistic_strength
    return scene


def saturation_of(colour: tuple[float, float, float]) -> float:
    return colorsys.rgb_to_hsv(*(_clamp01(c) for c in colour))[1]


# ---- The colour arithmetic --------------------------------------------------------------------


def restyle(
    colour: tuple[float, float, float],
    style: SurfaceStyle,
    table: StyleTable,
    strength: float,
    jitter_key: str = "",
    seed: int = 1,
) -> tuple[float, float, float]:
    """Move one base colour into a surface's band, then dirty it.

    Six steps, in this order, and the order is the whole design:

    1. **Shift the hue** by the surface's ``hueShiftDeg``. Only rust and sodium do this.
    2. **Snap the hue** onto the palette's nearest allowed one (D15-R47i). Six hues for the whole
       game: a red car stays the reddest thing the palette has rather than being *its own* red.
    3. **Clamp saturation and value** into the surface's band. A clamp rather than an assignment,
       so a red car stays red — the hue is gameplay, the saturation is what makes it a toy.
    4. **Drag toward grime** by the surface's ``grime``, with a small deterministic jitter so two
       materials in one role are not identical.
    5. **Blend the result against the original** by ``strength``. A photoscanned car is pulled
       part of the way; a cartoon is taken all of it.
    6. **Clamp the luminance** into the global tone band (D15-R47j), unscaled by strength. This is
       the rule that stops an imported asset clashing on brightness with everything already there.
    """
    original = tuple(_clamp01(component) for component in colour)
    hue, saturation, value = colorsys.rgb_to_hsv(*original)

    hue = (hue + style.hue_shift_deg / 360.0) % 1.0
    hue = table.palette.snap(hue, style.surface)
    saturation = min(saturation, _clamp01(style.saturation_max))
    value = min(max(value, _clamp01(style.value_min)), _clamp01(style.value_max))
    styled = colorsys.hsv_to_rgb(hue, saturation, value)

    grime = _clamp01(style.grime + _jitter(jitter_key, seed))
    dirtied = tuple(
        _lerp(styled[axis], table.grime_colour[axis], grime) for axis in range(3)
    )
    strength = _clamp01(strength)
    blended = tuple(_lerp(original[axis], dirtied[axis], strength) for axis in range(3))
    # The tone band is applied AFTER the strength blend and is not scaled by it. A rule that
    # applies at 62% to one model and 100% to the next is not a rule -- it is the drift it exists
    # to prevent, and this is the one rule that has to hold across every surface and every import.
    return table.tone.apply(blended, style.surface)


def tint_for(
    style: SurfaceStyle,
    table: StyleTable,
    strength: float,
    jitter_key: str = "",
    seed: int = 1,
    texture_luminance: float | None = None,
) -> tuple[float, float, float]:
    """The base-colour **factor** for a material whose colour comes from a texture.

    A Principled BSDF's Base Color socket means two different things depending on whether
    anything is plugged into it. Unconnected, it is the surface's colour and
    :func:`restyle` is the right operation on it. Connected to an image, it is the
    ``baseColorFactor`` the texture is *multiplied by* — and running the same restyle on it is
    not merely imprecise, it is a category error: writing trim's 0.20 value ceiling into a
    multiplier renders the whole car at a fifth of its brightness.

    So a textured material gets a tint instead: what the style does to a mid-grey, rescaled so
    it never darkens past :data:`TINT_VALUE_MIN`. The hue shift, the desaturation and the grime
    all survive — a textured car still goes warm and dusty — and its diffuse detail survives
    with them.
    """
    tinted = restyle((0.5, 0.5, 0.5), style, table, strength, jitter_key, seed)
    hue, saturation, value = colorsys.rgb_to_hsv(*tinted)
    tint = colorsys.hsv_to_rgb(hue, saturation, min(1.0, max(TINT_VALUE_MIN, value / 0.5)))
    return _fit_to_band(tint, table, style.surface, texture_luminance)


def _fit_to_band(tint, table: StyleTable, surface: str, texture_luminance: float | None):
    """Scale a tint so that ``texture x tint`` finishes inside the global tone band.

    Without this the band is a rule about untextured materials only, which is a rule about almost
    nothing: 41 of the shipped Eclipse's 60 materials are textured. With it, a photoscan whose
    albedo is twice as bright as the roster is darkened by exactly the factor that brings it in,
    and one that is already in range is not touched at all.

    The scale is applied to the tint and not to the image, so the texture ships as the artist made
    it and the correction is one number in the material — which is also the only way it can be
    undone by changing a table.
    """
    ceiling_for = 1.0 if surface in table.tone.exempt_from_ceiling else table.tone.luminance_max
    if texture_luminance is None:
        # An unmeasured texture is the one case where the effective brightness is unknown, and a
        # tint above the ceiling can only ever produce something above it. Clamping the tint itself
        # is the conservative reading: the worst it does is darken an already-dark texture slightly.
        tint_luma = luminance(tint)
        if tint_luma > ceiling_for and tint_luma > 0.0:
            return tuple(_clamp01(c * ceiling_for / tint_luma) for c in tint)
        return tint
    if texture_luminance <= 0.0:
        return tint
    tint_luma = luminance(tint)
    if tint_luma <= 0.0:
        return tint
    effective = texture_luminance * tint_luma
    ceiling = ceiling_for
    if effective > ceiling:
        return tuple(_clamp01(c * ceiling / effective) for c in tint)
    if effective < table.tone.luminance_min:
        return tuple(_clamp01(c * table.tone.luminance_min / effective) for c in tint)
    return tint


def effective_colour(material: SourceMaterial, written: tuple[float, float, float]):
    """What a surface will actually render as: its texture's mean times what was written to it.

    For an untextured material that is just the colour. For a textured one it is the product, and
    that product is the quantity :func:`conforms` has to be asked about — the socket alone is a
    multiplier and says nothing about brightness.
    """
    if material.texture_luminance is None:
        return written
    return tuple(_clamp01(component * material.texture_luminance) for component in written)


def conforms(colour: tuple[float, float, float], table: StyleTable, surface: str) -> bool:
    """Whether a finished colour is inside the style's global tone band (D15-R47k).

    The conformance test a new asset has to pass, and the reason the band is a table entry rather
    than a constant in the code: it is checkable. Every material of every prepared vehicle is
    measured against it and the run reports how many failed, which is what makes "each new asset
    conforms to the current style" an assertion rather than a hope.
    """
    ceiling = 1.0 if surface in table.tone.exempt_from_ceiling else table.tone.luminance_max
    luma = luminance(colour)
    saturation = colorsys.rgb_to_hsv(*(_clamp01(c) for c in colour))[1]
    return (
        luma >= table.tone.luminance_min - 1e-4
        and luma <= ceiling + 1e-4
        and saturation <= table.tone.accent_saturation_max + 1e-4
    )


def restyle_scalar(value: float, target: float, strength: float) -> float:
    """A metallic or a roughness, pulled toward the house figure by ``strength``."""
    return _clamp01(_lerp(_clamp01(value), _clamp01(target), _clamp01(strength)))


def _jitter(key: str, seed: int) -> float:
    """A deterministic ``[-GRIME_JITTER, +GRIME_JITTER]`` from a name and the run's seed (G3).

    Hashed rather than drawn from a random stream so that restyling one material never depends
    on how many materials were restyled before it — which is what would make adding a badge to a
    model change the colour of its doors.
    """
    if not key:
        return 0.0
    digest = hashlib.sha256(f"{seed}:{key}".encode()).digest()
    unit = int.from_bytes(digest[:4], "big") / 0xFFFFFFFF
    return (unit * 2.0 - 1.0) * GRIME_JITTER


def _lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else float(value))


# ---- The Blender half --------------------------------------------------------------------------


def read_scene_materials() -> list[SourceMaterial]:
    """Every material in the scene, measured, with the triangles it covers."""
    if bpy is None:  # pragma: no cover - the pure-Python unit test path
        raise StyleError("reading materials needs a Blender host")

    triangles: dict[str, int] = {}
    for obj in bpy.data.objects:
        if obj.type != "MESH":
            continue
        mesh = obj.data
        mesh.calc_loop_triangles()
        # Every triangle to the slot its polygon uses, so a multi-material object is split
        # between its materials rather than counted wholly against the first.
        slots = [slot.material.name if slot.material else None for slot in obj.material_slots]
        for triangle in mesh.loop_triangles:
            index = triangle.material_index
            name = slots[index] if 0 <= index < len(slots) else None
            if name is not None:
                triangles[name] = triangles.get(name, 0) + 1

    materials = []
    for material in sorted(bpy.data.materials, key=lambda m: m.name):
        if not getattr(material, "use_nodes", False):
            continue
        materials.append(_measure_material(material, triangles.get(material.name, 0)))
    return materials


def _measure_material(material, triangle_count: int) -> SourceMaterial:
    principled = _principled_of(material)
    record = SourceMaterial(name=material.name, triangles=triangle_count)
    modes = {"OPAQUE": "OPAQUE", "BLEND": "BLEND", "CLIP": "HASHED", "HASHED": "HASHED"}
    record.alpha_mode = modes.get(getattr(material, "blend_method", "OPAQUE"), "OPAQUE")
    if principled is None:
        return record

    base = principled.inputs.get("Base Color")
    if base is not None:
        with contextlib.suppress(TypeError, IndexError):
            record.base_colour = (
                float(base.default_value[0]),
                float(base.default_value[1]),
                float(base.default_value[2]),
            )
        record.has_base_texture = bool(base.links)
        if record.has_base_texture:
            record.texture_luminance = _base_texture_luminance(base)
    record.metallic = _socket_float(principled, "Metallic", 0.0)
    record.roughness = _socket_float(principled, "Roughness", 0.5)
    record.base_alpha = _socket_float(principled, "Alpha", 1.0)
    transmission = principled.inputs.get("Transmission Weight")
    record.transmission = (
        _socket_float(principled, "Transmission Weight", 0.0)
        if transmission is not None
        else _socket_float(principled, "Transmission", 0.0)
    )
    strength = _socket_float(principled, "Emission Strength", 0.0)
    colour = principled.inputs.get("Emission Color") or principled.inputs.get("Emission")
    brightness = 0.0
    if colour is not None and hasattr(colour, "default_value"):
        try:
            brightness = max(colour.default_value[0], colour.default_value[1],
                             colour.default_value[2])
        except (TypeError, IndexError):
            brightness = 0.0
    record.emissive = float(strength) * float(brightness)
    return record


#: Pixels above which a base-colour image is not measured.
#:
#: Four megapixels — a 2048-square texture — which every texture either shipped car uses is
#: comfortably under. The read below materialises the whole buffer as float32, so this is the line
#: between "a few megabytes for a number" and "a quarter of a gigabyte for the same number".
MAX_MEASURED_PIXELS = 4_000_000


def _base_texture_luminance(socket) -> float | None:
    """Mean Rec. 709 luma of the image feeding a Base Color socket, or None.

    Three things about a glTF import make the obvious version wrong, and all three were found by
    probing a real model rather than by reading the API:

    *The importer does not connect the image directly.* It inserts a ``Mix`` so the base-colour
    factor multiplies the texture, so the socket's immediate upstream node is not the image. The
    search walks upstream to a bounded depth instead of looking one link back.

    *An imported image has no data until something asks for it.* ``has_data`` is ``False`` on a
    freshly imported material even though the file is perfectly readable, so gating on it skips
    every texture on the vehicle.

    *``image.copy()`` does not copy the pixels.* Copying and scaling to a thumbnail is the obvious
    cheap read and it returns pure white for every image on both shipped cars — the copy is lazy,
    and scaling something with no data fills it. ``foreach_get`` on the original is the read that
    reports what is actually in the file.
    """
    if bpy is None:  # pragma: no cover
        return None
    image = _upstream_image(socket)
    if image is None or image.size[0] < 1 or image.size[1] < 1:
        return None
    if image.size[0] * image.size[1] > MAX_MEASURED_PIXELS:
        return None
    try:
        import numpy

        buffer = numpy.empty(len(image.pixels), dtype=numpy.float32)
        image.pixels.foreach_get(buffer)
    except (RuntimeError, ReferenceError, MemoryError, ImportError):  # pragma: no cover
        return None
    if buffer.size < 4:
        return None
    channels = buffer.reshape(-1, 4)
    return float(
        0.2126 * channels[:, 0].mean()
        + 0.7152 * channels[:, 1].mean()
        + 0.0722 * channels[:, 2].mean()
    )


#: How far upstream of a Base Color socket an image may be before the search gives up.
#:
#: Four. The glTF importer's own graph is one Mix deep; anything much beyond that is a material
#: somebody built by hand, where guessing which of several images is "the" base colour would be
#: worse than reporting none.
MAX_TEXTURE_SEARCH_DEPTH = 4


def _upstream_image(socket, depth: int = 0):
    """The first image feeding a socket, following links through intermediate nodes."""
    if depth > MAX_TEXTURE_SEARCH_DEPTH or not getattr(socket, "links", None):
        return None
    for link in socket.links:
        node = link.from_node
        if getattr(node, "type", None) == "TEX_IMAGE":
            if getattr(node, "image", None) is not None:
                return node.image
            continue
        for upstream in getattr(node, "inputs", ()):
            found = _upstream_image(upstream, depth + 1)
            if found is not None:
                return found
    return None


def _principled_of(material):
    if not getattr(material, "use_nodes", False) or material.node_tree is None:
        return None
    for node in material.node_tree.nodes:
        if node.type == "BSDF_PRINCIPLED":
            return node
    return None


def _socket_float(node, name: str, fallback: float) -> float:
    socket = node.inputs.get(name)
    if socket is None:
        return fallback
    try:
        return float(socket.default_value)
    except (TypeError, ValueError):
        return fallback


def apply_to_scene(table: StyleTable, seed: int) -> dict:
    """Classify and restyle every material in the loaded scene. The D15-S9 report stage.

    The only function here that touches Blender, and it writes exactly four things per material:
    base colour, metallic, roughness and — where one exists — the emission colour. Alpha, alpha
    mode, transmission, backface culling and the material's name are read and left alone, because
    the labelling ensemble that runs after this reads all five as evidence (D15-R6).

    A **stylised** source additionally has its base-colour texture disconnected, which is what
    reskinning a cartoon means: those textures are flat colour, they are what makes the model read
    as a cartoon, and the house colour replaces them outright. A realistic source keeps every
    texture it came with and is only pulled toward the palette.
    """
    if bpy is None:  # pragma: no cover
        raise StyleError("restyling needs a Blender host")

    scene = classify_scene(read_scene_materials(), table)
    restyled = 0
    detached = 0
    written: list[tuple[str, str, tuple[float, float, float]]] = []
    for record in scene.materials:
        material = bpy.data.materials.get(record.name)
        principled = None if material is None else _principled_of(material)
        if principled is None:
            continue
        style = table.surfaces[record.surface]

        base = principled.inputs.get("Base Color")
        if base is not None:
            if scene.is_stylised and base.links:
                # Reskinning a cartoon: its base-colour texture is flat colour, it is what makes
                # the model read as a cartoon, and the house colour replaces it outright.
                for link in list(base.links):
                    material.node_tree.links.remove(link)
                detached += 1
                record.has_base_texture = False
            colour = (
                tint_for(
                    style, table, scene.strength, record.name, seed, record.texture_luminance
                )
                if record.has_base_texture
                else restyle(record.base_colour, style, table, scene.strength, record.name, seed)
            )
            written.append((record.name, record.surface, effective_colour(record, colour)))
            # A socket of another shape is left alone rather than half-written.
            with contextlib.suppress(TypeError, ValueError):
                base.default_value = (colour[0], colour[1], colour[2], 1.0)

        # A null target preserves the source's own value. An alloy wheel forced to the trim row's
        # metallic 0 and roughness 0.78 loses every highlight that makes its spokes readable, and
        # comes out as a black disc — which is exactly what shipped before this existed.
        if style.metallic is not None:
            _set_socket(principled, "Metallic", restyle_scalar(
                record.metallic, style.metallic, scene.strength))
        if style.roughness is not None:
            _set_socket(principled, "Roughness", restyle_scalar(
                record.roughness, style.roughness, scene.strength))

        # The emission COLOUR is restyled and its STRENGTH is not: strength is what the light
        # cue reads (D15-S4.2 C2), and a lamp normalised to zero would stop being findable.
        emission = principled.inputs.get("Emission Color") or principled.inputs.get("Emission")
        if emission is not None and record.emissive > 0.0 and not emission.links:
            try:
                lit = restyle(
                    (emission.default_value[0], emission.default_value[1],
                     emission.default_value[2]),
                    table.surfaces[LIGHT], table, scene.strength, record.name + ":emission", seed,
                )
                emission.default_value = (lit[0], lit[1], lit[2], 1.0)
            except (TypeError, IndexError, ValueError):  # pragma: no cover
                pass
        restyled += 1

    report = scene.as_dict()
    report.update({
        "styleId": table.style_id,
        "restyled": restyled,
        "texturesDetached": detached,
        "preserved": ["name", "alphaMode", "alpha", "transmission", "backfaceCulling",
                      "emissionStrength"],
        "conformance": conformance_report(written, table),
    })
    return report


def conformance_report(written, table: StyleTable) -> dict:
    """How much of what was written is inside the style's tone band (D15-R47k).

    A self-check in the D09 spirit: the band is guaranteed by construction, so a non-zero
    ``outsideBand`` is a defect in this module rather than a fact about the model — which is
    exactly why it is worth measuring on every run rather than asserting once in a test.

    The luminance range is reported because it is the number a person looking at a new asset
    beside the roster actually wants: how bright the brightest thing on this vehicle is.
    """
    if not written:
        return {"materials": 0, "outsideBand": 0, "offenders": []}
    offenders = [
        {
            "material": name,
            "surface": surface,
            "luminance": round(luminance(colour), 4),
            # A base colour factor is a MULTIPLIER, so a texture darker than the floor cannot be
            # lifted into the band by any value of it. Reported as its own thing rather than as a
            # failure, because the honest answer is "this texture is nearly black" and the fix is
            # to replace the texture, which is a decision about the art.
            "unliftable": luminance(colour) < table.tone.luminance_min,
        }
        for name, surface, colour in written
        if not conforms(colour, table, surface)
    ]
    luminances = [luminance(colour) for _n, _s, colour in written]
    return {
        "materials": len(written),
        "outsideBand": len(offenders),
        "unliftable": sum(1 for entry in offenders if entry["unliftable"]),
        "band": [table.tone.luminance_min, table.tone.luminance_max],
        "luminanceRange": [round(min(luminances), 4), round(max(luminances), 4)],
        "offenders": sorted(offenders, key=lambda entry: entry["material"])[:8],
    }


def _set_socket(node, name: str, value: float) -> None:
    socket = node.inputs.get(name)
    if socket is None or socket.links:
        return
    with contextlib.suppress(TypeError, ValueError):
        socket.default_value = value

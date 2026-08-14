"""Stage 2 in full: the corrections of D15-S5.5, derived and **applied** (D15-R25).

:mod:`syndicate_prepare.repair` measures — it answers "is this model in the game's frame?" and
writes the answer into the report. This module answers the next question, which is the one a
drop-in pipeline actually needs: *what transform would put it there*, composed with whatever
``import.json`` already applies, so a model that arrives in centimetres facing backwards ends up
in metres facing ``+z`` without anybody hand-authoring a correction first.

Two rules shape everything here.

**DEC-036 stands.** The correction is still recorded once, in ``import.json`` beside the model,
and is still what the harness verifies. What changes is who writes that file first: the pipeline
derives it and writes it, rather than an operator deriving it by hand and the pipeline trusting
it. A file that is already correct produces an identity residual and is rewritten byte-identically,
which is what makes re-running the pipeline safe.

**D15-R26 stands.** Nothing here mirrors, un-mirrors, or otherwise invents geometry. Every
correction in this module is a similarity transform over the whole model — one scale, one yaw,
one translation — so no relationship between two pieces of the car can change. Cleaning
*topology* (welding doubled vertices, deleting zero-area faces) is the one exception and it is
confined to :data:`WELD_DISTANCE_M`, which is an order of magnitude below the smallest feature
any of D15's thresholds measure.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .labels import (
    CENTRING_TOLERANCE_M,
    MAX_VEHICLE_LENGTH_M,
    MIN_VEHICLE_LENGTH_M,
)
from .shell import Shell

#: Vertices closer together than this are the same vertex. 0.1 mm: below any feature this
#: pipeline measures and well below :data:`labels.MIRROR_TOLERANCE_M`, so welding can never
#: move a shell far enough to change a label.
WELD_DISTANCE_M = 1e-4

#: A triangle with less area than this is degenerate and is deleted. Matches the fracture
#: tool's ``MIN_FACE_AREA_M2``, so a part that survives cleanup can be fractured.
MIN_FACE_AREA_M2 = 1e-8

#: Edges shorter than this are **dissolved** — collapsed into their neighbours rather than
#: deleted, so no hole is left behind. Half a millimetre: invisible at any distance a vehicle
#: is seen from, and the length below which a triangle is a sliver rather than a surface.
#:
#: Deleting was not enough. A sliver just above ``MIN_FACE_AREA_M2`` survives the delete and
#: then collapses the moment a damage morph displaces one of its vertices, which fails D09's
#: zero-area guard and costs the *whole part* its deformation — measured on the Eclipse's
#: chassis, one face in 181,000 triangles was enough.
DEGENERATE_EDGE_M = 5e-4

#: Unit factors tried when a model's overall length is implausible. Every entry is a
#: mismatch somebody's exporter actually makes: metric decades, inches, and the 1/100 that
#: three of the four major DCC packages default to.
#:
#: Deliberately a closed list rather than "scale until it fits". A continuous fit would
#: happily rescale a genuinely 24 m vehicle to 16 m and report success; a wrong *unit* is
#: always one of these, and anything else is a model this pipeline should refuse to guess at.
UNIT_CANDIDATES = (1.0, 0.1, 0.01, 0.001, 100.0, 10.0, 1000.0, 0.0254, 0.3048)

#: The middle of the plausible vehicle-length range, in log space. A candidate scale is
#: chosen by which lands the model's length nearest here, so 4.7 m beats 15.9 m even though
#: both are "plausible".
_TARGET_LENGTH_M = math.sqrt(MIN_VEHICLE_LENGTH_M * MAX_VEHICLE_LENGTH_M)

#: How much taller than the median a shell has to be to count as cabin, for the nose vote.
_CABIN_HEIGHT_FRACTION = 0.82


@dataclass(frozen=True)
class Correction:
    """A model-space correction, in ``import.json``'s own three fields (D08-S4.1).

    Ordered exactly as ``syndicate_dissect.dissect.apply_import_correction`` applies it:
    scale first, then yaw about the game's ``+y``, then translation. Anything this pipeline
    cannot express in those three terms is reported rather than applied, because a fourth
    term would be a term the two tools disagreed about.
    """

    scale: float = 1.0
    yaw_deg: float = 0.0
    translation: tuple[float, float, float] = (0.0, 0.0, 0.0)

    @property
    def is_identity(self) -> bool:
        return (
            abs(self.scale - 1.0) < 1e-9
            and abs(self.yaw_deg) < 1e-9
            and all(abs(value) < 1e-9 for value in self.translation)
        )

    def apply_to_point(self, point: tuple[float, float, float]) -> tuple[float, float, float]:
        """Transform one game-space point. Scale, then yaw, then translate."""
        x, y, z = (value * self.scale for value in point)
        radians = math.radians(self.yaw_deg)
        cos, sin = math.cos(radians), math.sin(radians)
        rotated = (x * cos + z * sin, y, -x * sin + z * cos)
        return tuple(rotated[i] + self.translation[i] for i in range(3))

    def then(self, second: Correction) -> Correction:
        """This correction followed by ``second`` — the composition ``second ∘ self``.

        Needed because the model has *already* been through whatever ``import.json`` said
        by the time this stage measures it (stage 1 applies it), so what is derived here is
        a residual. Writing the residual back would throw away the original; writing the
        composition is what makes the file idempotent.

        The algebra: with ``T·R·S`` the first and ``T₂·R₂·S₂`` the second, and both scales
        uniform, ``T₂·R₂·S₂·T·R·S`` collapses to a single ``T'·R'·S'`` where
        ``S' = s₂·s``, ``R' = yaw₂ + yaw`` and ``T' = t₂ + R₂(s₂·t)``.
        """
        radians = math.radians(second.yaw_deg)
        cos, sin = math.cos(radians), math.sin(radians)
        x, y, z = (value * second.scale for value in self.translation)
        rotated = (x * cos + z * sin, y, -x * sin + z * cos)
        return Correction(
            scale=self.scale * second.scale,
            yaw_deg=(self.yaw_deg + second.yaw_deg) % 360.0,
            translation=tuple(rotated[i] + second.translation[i] for i in range(3)),
        )

    def as_import_json(self, note: str) -> dict:
        """The ``import.json`` document (DEC-036), in the shipped files' own field order."""
        return {
            "_comment": note,
            "scaleToMetres": round(self.scale, 10),
            "yawDeg": round(self.yaw_deg % 360.0, 6),
            "translationM": {
                "x": round(self.translation[0], 6),
                "y": round(self.translation[1], 6),
                "z": round(self.translation[2], 6),
            },
        }

    def as_report(self) -> dict:
        return {
            "scaleToMetres": round(self.scale, 6),
            "yawDeg": round(self.yaw_deg % 360.0, 3),
            "translationM": [round(value, 4) for value in self.translation],
            "identity": self.is_identity,
        }


@dataclass(frozen=True)
class Bounds:
    """The whole model's axis-aligned bounds and triangle-weighted centroid, in game space."""

    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    centroid: tuple[float, float, float]

    @property
    def size(self) -> tuple[float, float, float]:
        return tuple(self.hi[i] - self.lo[i] for i in range(3))

    @property
    def width(self) -> float:
        return self.size[0]

    @property
    def height(self) -> float:
        return self.size[1]

    @property
    def length(self) -> float:
        return self.size[2]


def measure_bounds(shells: list[Shell]) -> Bounds:
    """The model's overall bounds and its triangle-weighted centroid."""
    if not shells:
        return Bounds((0.0, 0.0, 0.0), (0.0, 0.0, 0.0), (0.0, 0.0, 0.0))
    lo = tuple(min(shell.lo[i] for shell in shells) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in shells) for i in range(3))
    weight = sum(shell.triangles for shell in shells) or 1
    centroid = tuple(
        sum(shell.centroid[i] * shell.triangles for shell in shells) / weight for i in range(3)
    )
    return Bounds(lo, hi, centroid)


def choose_scale(bounds: Bounds) -> tuple[float, str]:
    """The unit factor that puts the model's longest horizontal extent in vehicle range.

    The *longest horizontal* extent rather than the ``z`` extent, because scale is decided
    before yaw and a model lying along ``x`` would otherwise be measured across its width and
    scaled up by a factor of two and a bit.
    """
    span = max(bounds.length, bounds.width)
    if span <= 0.0:
        return 1.0, "no geometry to measure"
    if MIN_VEHICLE_LENGTH_M <= span <= MAX_VEHICLE_LENGTH_M:
        return 1.0, f"overall length {span:.3f} m is already a plausible vehicle length"

    viable = [
        factor
        for factor in UNIT_CANDIDATES
        if MIN_VEHICLE_LENGTH_M <= span * factor <= MAX_VEHICLE_LENGTH_M
    ]
    if not viable:
        return 1.0, (
            f"overall length {span:.3f} m is implausible and no unit factor fixes it; "
            "this is not a vehicle-sized model"
        )
    best = min(viable, key=lambda factor: abs(math.log(span * factor / _TARGET_LENGTH_M)))
    return best, f"scaled by {best:g} to bring {span:.3f} to {span * best:.3f} m"


def choose_yaw(shells: list[Shell], bounds: Bounds) -> tuple[float, str]:
    """The multiple of 90° about ``+y`` that puts the long axis on ``z`` and the nose on ``+z``.

    Two independent questions answered in one number, because the answer to both is a yaw:

    1. **Which axis is length?** A vehicle is longer than it is wide, so if ``x`` is the
       longer horizontal extent the model is yawed 90° from where it should be.
    2. **Which end is the nose?** The cabin — the tallest part of the body — sits behind the
       midpoint on very nearly every vehicle, because a bonnet is longer than a boot. If it
       sits ahead, the model is 180° out.

    The second is a vote and not a proof, which is why :func:`plan` reports the margin it won
    by. A 180° error is the one mistake in this module that is invisible in every measurement
    and obvious in the first render.
    """
    quarter = 90.0 if bounds.width > bounds.length else 0.0
    # After a 90° yaw the axes swap, so the nose test has to be run on the swapped extents.
    length_axis = 0 if quarter else 2
    if not shells:
        return quarter, "no geometry; long axis only"

    lo_l = min(shell.lo[length_axis] for shell in shells)
    hi_l = max(shell.hi[length_axis] for shell in shells)
    mid = (lo_l + hi_l) * 0.5
    tallest = max(shell.hi[1] for shell in shells)
    ground = min(shell.lo[1] for shell in shells)
    threshold = ground + (tallest - ground) * _CABIN_HEIGHT_FRACTION
    cabin = [shell for shell in shells if shell.hi[1] > threshold]
    if not cabin:
        return quarter, "no cabin candidates; long axis only"

    weight = sum(shell.triangles for shell in cabin) or 1
    cabin_at = sum(shell.centroid[length_axis] * shell.triangles for shell in cabin) / weight
    bias = cabin_at - mid
    # A 90° yaw about +y sends +x to -z, so a cabin biased towards +x ends up towards -z,
    # which is where a cabin belongs. The 180° is needed only when that lands it forwards.
    forwards = bias > 0.0 if quarter else bias < 0.0
    if forwards:
        return quarter, f"cabin sits {abs(bias):.3f} m behind the midpoint; nose is at +z"
    return (quarter + 180.0) % 360.0, (
        f"cabin sits {abs(bias):.3f} m ahead of the midpoint, so the model is yawed 180°"
    )


def choose_translation(bounds: Bounds) -> tuple[tuple[float, float, float], str]:
    """Ground contact and lateral centring, as one translation (D15-S5.5).

    Ground contact is absolute: the lowest geometry belongs on ``y = 0`` because that is
    where a wheel meets a road, and every slot position the exporter writes is measured from
    it. Lateral centring is applied only past :data:`labels.CENTRING_TOLERANCE_M`, so a car
    that is genuinely asymmetric — one exhaust, a filler on one side — is not shunted
    sideways by a centimetre of its own bodywork (D15-R26 in spirit: the model is left as it
    was authored unless something is actually wrong).

    Longitudinal position is deliberately **not** corrected. A vehicle's origin is its
    centreline at ground level and its ``z`` is measured from wherever the author put it;
    recentring it on the bounding box would move every wheel slot relative to a chassis mesh
    that has to agree with it, for no gain.
    """
    drop = -bounds.lo[1]
    shift = -bounds.centroid[0] if abs(bounds.centroid[0]) > CENTRING_TOLERANCE_M else 0.0
    reasons = []
    if abs(drop) > 1e-6:
        reasons.append(f"lowered {drop:+.4f} m onto the ground plane")
    if shift:
        reasons.append(f"shifted {shift:+.4f} m onto the centreline")
    return (shift, drop, 0.0), "; ".join(reasons) or "already on the ground and centred"


@dataclass
class Plan:
    """The correction stage 2 will apply, and the reasons for each of its three terms."""

    correction: Correction
    scale_reason: str
    yaw_reason: str
    translation_reason: str
    up_axis_ok: bool
    up_axis_detail: str

    def as_dict(self) -> dict:
        return {
            "correction": self.correction.as_report(),
            "scale": self.scale_reason,
            "yaw": self.yaw_reason,
            "translation": self.translation_reason,
            "upAxis": {"ok": self.up_axis_ok, "detail": self.up_axis_detail},
        }


def plan(shells: list[Shell]) -> Plan:
    """Derive the residual correction for an already-loaded model (D15-S5.5, D15-R14).

    Order matters and is the table's: scale, then orientation, then placement. Each stage is
    planned against the model *as the previous stages leave it*, which is why the bounds are
    re-measured twice rather than the three terms being read off one measurement.
    """
    bounds = measure_bounds(shells)
    up_ok, up_detail = check_up_axis(bounds)

    scale, scale_reason = choose_scale(bounds)
    scaled = Correction(scale=scale)
    bounds = _transform_bounds(bounds, scaled)

    yaw, yaw_reason = choose_yaw(_transform_shells(shells, scaled), bounds)
    yawed = Correction(yaw_deg=yaw)
    bounds = _transform_bounds(bounds, yawed)

    translation, translation_reason = choose_translation(bounds)
    correction = Correction(scale=scale, yaw_deg=yaw, translation=translation)
    return Plan(
        correction=correction,
        scale_reason=scale_reason,
        yaw_reason=yaw_reason,
        translation_reason=translation_reason,
        up_axis_ok=up_ok,
        up_axis_detail=up_detail,
    )


def check_up_axis(bounds: Bounds) -> tuple[bool, str]:
    """Whether ``y`` is the shortest extent, which is the one misorientation a yaw cannot fix.

    A vehicle is longer than it is wide and wider than it is tall. If the shortest extent is
    not ``y`` the model is rolled or pitched onto its side, and correcting that needs a term
    ``import.json`` does not carry — so it is **reported and not repaired**, in the same
    spirit as D15-R26. Guessing which way up a model that is already on its side should go
    turns one visible fault into an invisible one.
    """
    width, height, length = bounds.width, bounds.height, bounds.length
    if height <= width and height <= length:
        return True, f"y is the shortest extent ({height:.3f} m), as an upright vehicle's is"
    return False, (
        f"the model is {width:.3f} x {height:.3f} x {length:.3f} (w x h x l): y is not the "
        "shortest extent, so it is lying on its side or on its nose. A yaw cannot fix that — "
        "rotate the source or add the rotation to import.json by hand"
    )


def apply_to_shells(shells: list[Shell], correction: Correction) -> None:
    """Rewrite every shell's measurements through ``correction``, in place.

    The Blender scene is transformed separately, by
    :func:`syndicate_prepare.prepare.apply_correction`. Doing both from the same
    :class:`Correction` — rather than re-measuring the scene afterwards — is what keeps a
    pure-Python test able to assert what the geometry will be.
    """
    for shell in shells:
        corners = [
            correction.apply_to_point((x, y, z))
            for x in (shell.lo[0], shell.hi[0])
            for y in (shell.lo[1], shell.hi[1])
            for z in (shell.lo[2], shell.hi[2])
        ]
        shell.lo = tuple(min(corner[i] for corner in corners) for i in range(3))
        shell.hi = tuple(max(corner[i] for corner in corners) for i in range(3))
        shell.centroid = correction.apply_to_point(shell.centroid)
        shell.vertex_sample = tuple(
            correction.apply_to_point(point) for point in shell.vertex_sample
        )
        # Areas scale with the square of a length and volumes with its cube. Missing this
        # makes every mass in the exported asset wrong by a factor of a million on a model
        # that arrived in millimetres, which is a plausible-looking asset rather than a
        # broken one.
        shell.area_m2 *= correction.scale**2
        shell.volume_m3 *= correction.scale**3


def _transform_shells(shells: list[Shell], correction: Correction) -> list[Shell]:
    """A transformed *copy* of the shells, for planning a later stage against."""
    import copy

    clones = [copy.copy(shell) for shell in shells]
    apply_to_shells(clones, correction)
    return clones


def _transform_bounds(bounds: Bounds, correction: Correction) -> Bounds:
    corners = [
        correction.apply_to_point((x, y, z))
        for x in (bounds.lo[0], bounds.hi[0])
        for y in (bounds.lo[1], bounds.hi[1])
        for z in (bounds.lo[2], bounds.hi[2])
    ]
    return Bounds(
        lo=tuple(min(corner[i] for corner in corners) for i in range(3)),
        hi=tuple(max(corner[i] for corner in corners) for i in range(3)),
        centroid=correction.apply_to_point(bounds.centroid),
    )

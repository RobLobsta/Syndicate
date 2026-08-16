"""Stage 7: what moves, about what axis, driven by what (D17-S5.9).

A per-label table, not per-part authoring — D15-R33's rule, which this document inherits: a part
that needs different numbers is evidence the taxonomy is missing a label, not that the part needs
hand-tuning.

Every number here is derived from something mechanical rather than chosen for looks, and D17-R47 is
the reason. A recoil travel picked because it looked right, or a drum spin at a rate unrelated to
the shots leaving the gun, is the same defect DISC-027 records: a periodic motion untied to the
mechanism that drives it reads as a fault rather than as machinery.

Nothing here touches Blender.
"""

from __future__ import annotations

import math

from .labels import BARREL, BORE_COAXIAL_TOL, FEED, GEAR, ROTATION_MIN_INSTANCES

#: Fraction of barrel length a recoil-operated action gives back (D17-R47). Four percent: 60 mm on
#: a 1.5 m barrel, which is what a real one does.
RECOIL_TRAVEL_FRACTION = 0.04

#: Metres. A barrel longer than 2 m would otherwise slide further than the gun is tall.
MAX_RECOIL_TRAVEL_M = 0.08

#: Seconds a barrel takes to return to battery. Short enough to be back before the next round of a
#: 4/s autocannon, long enough to be seen at 60 fps.
RETURN_SECONDS = 0.18

#: Degrees a quadrant gear tracks through when its own angular extent cannot be measured.
DEFAULT_ELEVATE_DEG = 22.0


def author(parts, bore, family: str, fire_interval_s: float) -> dict:
    """Writes an ``articulation`` block onto every sub-part that moves (D17-R46).

    Returns a per-part summary for the report. A part that gets no block is a static part, which is
    every sub-part of most weapons and is never an error (D17-E11).
    """
    summary = {}
    for part in sorted(parts, key=lambda p: p.name):
        block = _for(part, bore, fire_interval_s)
        part.articulation = block
        if block:
            summary[part.name] = block
    return summary


def _for(part, bore, fire_interval_s):
    if part.label == BARREL:
        return _recoil(part, bore)
    if part.label == GEAR:
        return _gear(part, bore)
    if part.label == FEED:
        return _feed(part, bore, fire_interval_s)
    return None


def _recoil(part, bore) -> dict:
    """A barrel slides back along its own bore and returns (D17-R46, R47)."""
    length = _extent_along(part, bore)
    travel = min(MAX_RECOIL_TRAVEL_M, max(0.005, length * RECOIL_TRAVEL_FRACTION))
    return {
        "motion": "RECOIL",
        "driver": "FIRE",
        # In the part's own local space. After stage 1 the bore is +Z (D17-R24), and the part's own
        # frame is the model's frame translated to its origin — so the bore axis is +Z here too.
        "axisLocal": _vec(0.0, 0.0, 1.0),
        "pivotLocal": _vec(0.0, 0.0, 0.0),
        "travelM": round(travel, 5),
        "returnSeconds": RETURN_SECONDS,
    }


def _gear(part, bore) -> dict:
    """A ring about the bore spins with the gun; a quadrant off to one side tracks elevation.

    The distinction is the same one the geometric cue draws — on the axis or off it — because it is
    the same physical distinction: a traverse ring is concentric with the bore and an elevating
    quadrant is bolted to the side of the cradle.
    """
    radius = bore.radius_of(part.centroid)
    if radius <= BORE_COAXIAL_TOL * max(0.2, _extent_along(part, bore)):
        return {
            "motion": "SPIN",
            "driver": "CONTINUOUS",
            "axisLocal": _vec(0.0, 0.0, 1.0),
            "pivotLocal": _vec(0.0, 0.0, 0.0),
            # One revolution per second at the reference rate; the client scales by how recently the
            # weapon fired, so a gun that is not firing coasts to a stop rather than spinning
            # forever.
            "rateDegPerSec": 360.0,
        }
    return {
        "motion": "ELEVATE",
        "driver": "AIM",
        # About the model's left-right axis: elevation is pitch, and pitch is rotation about X.
        # **Negative** X, because the world is Y-up right-handed (D00-R14): a positive right-hand
        # rotation about +X takes +Z — the bore — toward -Y, which points the muzzle at the ground.
        # A gun that depressed when the player aimed up would be the kind of defect that survives
        # every unit test and is obvious in the first screenshot.
        "axisLocal": _vec(-1.0, 0.0, 0.0),
        "pivotLocal": _vec(0.0, 0.0, 0.0),
        "travelDeg": round(_angular_extent_deg(part, bore), 2),
    }


def _feed(part, bore, fire_interval_s) -> dict | None:
    """A rotationally symmetric feed indexes one position per shot; a box magazine does not move."""
    steps = getattr(part, "repetition", 0)
    if steps < ROTATION_MIN_INSTANCES:
        # A box magazine. Nothing about it turns, and inventing a spin for it would be exactly the
        # kind of motion untied to a mechanism that D17-R47 rejects.
        return None
    return {
        "motion": "INDEX",
        "driver": "FIRE",
        "axisLocal": _vec(0.0, 0.0, 1.0),
        "pivotLocal": _vec(0.0, 0.0, 0.0),
        "indexSteps": int(steps),
        # One index per shot, so the drum turns exactly as fast as rounds leave — which is the whole
        # of R47's point and the reason this reads the fire interval rather than a constant.
        "returnSeconds": round(min(RETURN_SECONDS, max(0.05, fire_interval_s * 0.8)), 4),
    }


def _extent_along(part, bore) -> float:
    """The part's extent along the bore, from its bounds projected onto the axis."""
    corners = [
        (x, y, z)
        for x in (part.lo[0], part.hi[0])
        for y in (part.lo[1], part.hi[1])
        for z in (part.lo[2], part.hi[2])
    ]
    along = [bore.coordinate_of(c) for c in corners]
    return max(along) - min(along)


def _angular_extent_deg(part, bore) -> float:
    """How far a quadrant sweeps, from its own proportions.

    A quadrant gear is a sector of a circle, so the ratio of its long extent to its radius is the
    angle it covers. Bounded well inside a full circle because a gun that elevates 90 degrees is a
    mortar, and D17-NG2 forbids this pipeline deciding a model is one.
    """
    radius = max(1e-4, bore.radius_of(part.centroid))
    span = max(part.size)
    degrees = math.degrees(min(1.5, span / radius))
    return max(6.0, min(45.0, degrees)) if degrees > 0 else DEFAULT_ELEVATE_DEG


def _vec(x, y, z) -> dict:
    return {"x": x, "y": y, "z": z}

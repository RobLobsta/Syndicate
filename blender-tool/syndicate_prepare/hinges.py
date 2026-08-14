"""Stage 6: articulation rigging (D15-S5.6).

An articulated part is a part with a hinge — an axis, a pivot in chassis-local space, and an
open angle (D15-R28). Doors, bonnet and boot are the expected cases, and the roles assigned in
:mod:`syndicate_prepare.roles` are what say which is which.

Three things this module does **not** do, each for a reason recorded in D15:

- It does not build an armature (D15-R30). The game already composes parts down a slot chain,
  so an opening door is a slot whose local rotation animates; a second transform hierarchy that
  the runtime never reads would be a liability rather than a feature.
- It does not decide whether a part detaches (D15-R31). A door may open and later break off; the
  hinge angle is cosmetic state and the attachment is authoritative (G6).
- It does not force a hinge onto a part it cannot place one on. D15-E9 is explicit that a door
  which opens through its own sill is worse than one that does not open, so a hinge whose swing
  drives the panel into the body is discarded and the part is exported rigid.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .roles import BONNET, BOOT, DOOR

#: Open angles per role, in degrees, at the magnitudes a real vehicle uses. The *sign* is not
#: authored: it is derived per part from which way the free edge has to travel, because a left
#: door and a right door open about the same axis in opposite directions and hard-coding either
#: makes half the doors on every vehicle open into the cabin.
OPEN_ANGLE_DEG = {
    DOOR: 62.0,
    BONNET: 52.0,
    BOOT: 48.0,
}

#: How far inside the body's own bounds a swung panel may end up before the hinge is rejected
#: (D15-E9). One collision margin: a door skin that finishes flush with the sill is fine, one
#: that finishes a centimetre inside it is opening through the car.
SWING_CLEARANCE_M = 0.01


@dataclass(frozen=True)
class Hinge:
    """A hinge in chassis-local space, as exported on the part (D15-R30).

    :param axis: unit axis, in the game frame
    :param pivot: a point on the axis, in chassis-local metres
    :param open_deg: signed angle; the sign is the direction the part opens
    :param because: how the hinge was arrived at, for the report
    """

    axis: tuple[float, float, float]
    pivot: tuple[float, float, float]
    open_deg: float
    because: str

    def rotate(self, point: tuple[float, float, float], fraction: float = 1.0):
        """``point`` swung about this hinge by ``fraction`` of its open angle."""
        return _rotate_about(point, self.axis, self.pivot, self.open_deg * fraction)

    def as_dict(self) -> dict:
        return {
            "axisLocal": {
                "x": round(self.axis[0], 6),
                "y": round(self.axis[1], 6),
                "z": round(self.axis[2], 6),
            },
            "pivotLocal": {
                "x": round(self.pivot[0], 4),
                "y": round(self.pivot[1], 4),
                "z": round(self.pivot[2], 4),
            },
            "openDeg": round(self.open_deg, 2),
        }


AXES = {"x": (1.0, 0.0, 0.0), "y": (0.0, 1.0, 0.0), "z": (0.0, 0.0, 1.0)}


def infer(part, body, declared=None) -> Hinge | None:
    """The hinge for one part, in D15-R29's order of reliability.

    1. **Declared** — a ``hinges`` entry in ``parts.json`` naming this part. Always wins, and
       is not swing-checked: an operator who measured a pivot has seen the model, and
       overruling them from a bounding box would defeat the point of the override.
    2. **Panel-edge inference** — a door hinges about the vertical edge nearest the front of
       the car, a bonnet about its rear-most transverse edge, a boot about its forward-most.
    3. **None** — the part is rigid and detaches without opening.
    """
    if declared is not None:
        axis = AXES.get(declared.axis, AXES["y"])
        return Hinge(axis, tuple(declared.pivot), declared.open_deg, "declared in parts.json")

    role = getattr(part, "role", None)
    if role not in OPEN_ANGLE_DEG:
        return None

    candidate = _infer_by_role(part, role)
    if candidate is None:
        return None
    if not clears_body(part, candidate, body):
        return None
    return candidate


def _infer_by_role(part, role: str) -> Hinge | None:
    """The geometric hinge for a panel role, with its sign chosen by where the free edge is."""
    lo, hi = part.lo, part.hi
    centre = part.centre

    if role == DOOR:
        # Vertical axis at the panel's forward edge; the free edge is everything behind it.
        pivot = (centre[0], centre[1], hi[2])
        # A left door's free edge must travel towards -x, a right door's towards +x. With the
        # free edge behind the pivot (dz < 0) the rotation about +y that sends it outboard is
        # positive on the left and negative on the right.
        sign = 1.0 if centre[0] < 0.0 else -1.0
        return Hinge(
            AXES["y"], pivot, sign * OPEN_ANGLE_DEG[DOOR], "vertical edge nearest the nose"
        )

    if role == BONNET:
        # Transverse axis at the rear edge; the free edge is ahead of it and travels up.
        pivot = (centre[0], centre[1], lo[2])
        return Hinge(AXES["x"], pivot, -OPEN_ANGLE_DEG[BONNET], "rear-most transverse edge")

    if role == BOOT:
        pivot = (centre[0], centre[1], hi[2])
        return Hinge(AXES["x"], pivot, OPEN_ANGLE_DEG[BOOT], "forward-most transverse edge")

    return None


def clears_body(part, hinge: Hinge, body) -> bool:
    """D15-E9: whether the part's free edge is outside the body once fully open.

    The free edge is the corner of the part's bounds farthest from the hinge axis — the point
    that sweeps the most and the point that fouls first. Swing it, and require that it finish
    outside the body's own bounds. That is a coarse test against a coarse volume, and it is
    aimed at the failure it can actually catch: a hinge whose *sign* is inverted, which sends
    the panel through the car rather than away from it. A hinge that is a few centimetres out
    is a job for the harness's render (D14-S5.11); a hinge that opens inwards is a job for
    arithmetic, and this is the arithmetic.
    """
    corners = [
        (x, y, z)
        for x in (part.lo[0], part.hi[0])
        for y in (part.lo[1], part.hi[1])
        for z in (part.lo[2], part.hi[2])
    ]
    free = max(corners, key=lambda corner: _distance_to_axis(corner, hinge.axis, hinge.pivot))
    swung = hinge.rotate(free)
    inside = all(
        body.lo[axis] + SWING_CLEARANCE_M <= swung[axis] <= body.hi[axis] - SWING_CLEARANCE_M
        for axis in range(3)
    )
    return not inside


def _distance_to_axis(point, axis, pivot) -> float:
    relative = tuple(point[i] - pivot[i] for i in range(3))
    along = sum(relative[i] * axis[i] for i in range(3))
    perpendicular = tuple(relative[i] - along * axis[i] for i in range(3))
    return math.sqrt(sum(value * value for value in perpendicular))


def _rotate_about(point, axis, pivot, degrees: float):
    """Rodrigues' rotation of ``point`` about the line ``(pivot, axis)``."""
    theta = math.radians(degrees)
    cos, sin = math.cos(theta), math.sin(theta)
    relative = tuple(point[i] - pivot[i] for i in range(3))
    dot = sum(relative[i] * axis[i] for i in range(3))
    cross = (
        axis[1] * relative[2] - axis[2] * relative[1],
        axis[2] * relative[0] - axis[0] * relative[2],
        axis[0] * relative[1] - axis[1] * relative[0],
    )
    return tuple(
        relative[i] * cos + cross[i] * sin + axis[i] * dot * (1.0 - cos) + pivot[i]
        for i in range(3)
    )

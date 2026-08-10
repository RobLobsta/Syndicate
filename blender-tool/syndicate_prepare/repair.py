"""Geometry repair, measured before and after, never silent (D15-S5.5).

D15-R14 puts this stage **before** separation, and the reason is not tidiness: a model whose
scale or orientation is wrong produces measurements in the wrong units, and every geometric
cue is a measurement. Fixing the frame afterwards would mean every threshold in
:mod:`syndicate_prepare.labels` had been compared against nonsense.

D15-R27 requires every repair to be reported with a before and an after. A repair nobody can
see is indistinguishable from a bug — and the specific bug this guards against is a model
that was already correct being "corrected" into a wrong frame, which produces a car that
looks fine in isolation and is 90° out from every other car in the game.

D15-R26 is the rule this module most has to resist breaking: broken symmetry is **reported
and never repaired**. Real cars are asymmetric on purpose — one exhaust, a fuel filler on one
side, left-hand drive — and a pipeline that mirrored those away would damage correct models to
flatter incorrect ones.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .labels import (
    CENTRING_TOLERANCE_M,
    MAX_VEHICLE_LENGTH_M,
    MIN_VEHICLE_LENGTH_M,
    MIRROR_TOLERANCE_M,
    STRAY_SHELL_M,
)
from .shell import Shell


@dataclass
class Repair:
    """One check's finding (D15-R25, D15-R27).

    :param check: which row of D15-S5.5's table
    :param applied: whether anything was changed. ``False`` is the common and desirable case
    :param before: the measurement that triggered the check
    :param after: the measurement afterwards
    :param detail: what was done, or why nothing was
    """

    check: str
    applied: bool
    before: float | str
    after: float | str
    detail: str

    def as_dict(self) -> dict:
        return {
            "check": self.check,
            "applied": self.applied,
            "before": self.before,
            "after": self.after,
            "detail": self.detail,
        }


@dataclass
class RepairReport:
    """Everything the repair stage found, in D15-S5.5's table order."""

    repairs: list[Repair] = field(default_factory=list)
    symmetry_violations: list[str] = field(default_factory=list)
    stray_shells: list[int] = field(default_factory=list)

    def add(self, repair: Repair) -> None:
        self.repairs.append(repair)

    @property
    def applied_count(self) -> int:
        return sum(1 for repair in self.repairs if repair.applied)

    def as_dict(self) -> dict:
        return {
            "checks": [repair.as_dict() for repair in self.repairs],
            "appliedCount": self.applied_count,
            # D15-R26: reported, never repaired. The field exists so that reading the report
            # is enough to know the pipeline saw it and chose not to act.
            "symmetryViolations": self.symmetry_violations,
            "strayShells": self.stray_shells,
        }


def check_scale(length_m: float) -> Repair:
    """Whether the model's overall length is a plausible vehicle length (D15-S5.5).

    Reported rather than applied here: the actual correction belongs in ``import.json``
    (DEC-036), where it is recorded once and confirmed by the harness rather than re-derived
    on every run. What this does is notice that the correction is missing or wrong.
    """
    plausible = MIN_VEHICLE_LENGTH_M <= length_m <= MAX_VEHICLE_LENGTH_M
    return Repair(
        check="scale",
        applied=False,
        before=round(length_m, 4),
        after=round(length_m, 4),
        detail=(
            "overall length is plausible for a vehicle"
            if plausible
            else f"overall length {length_m:.3f} m is outside "
            f"[{MIN_VEHICLE_LENGTH_M}, {MAX_VEHICLE_LENGTH_M}] — fix import.json's scale"
        ),
    )


def check_orientation(width_m: float, height_m: float, length_m: float) -> Repair:
    """Whether the long axis is ``z`` and the up axis is ``y`` (D00-R16, D15-S5.5).

    A car is longer than it is wide and wider than it is tall, and that ordering is what makes
    the check possible without knowing which end is the front.
    """
    correct = length_m >= width_m and width_m >= height_m
    return Repair(
        check="orientation",
        applied=False,
        before=f"{width_m:.3f} x {height_m:.3f} x {length_m:.3f} (w x h x l)",
        after=f"{width_m:.3f} x {height_m:.3f} x {length_m:.3f} (w x h x l)",
        detail=(
            "long axis is z and up axis is y, as D00-R16 requires"
            if correct
            else "extents are not ordered length >= width >= height; fix import.json's rotation"
        ),
    )


def check_nose_direction(shells: list[Shell]) -> Repair:
    """Which way the car faces, by cabin bias (D15-S5.5).

    The cabin — the tallest part of the body — sits behind the midpoint on almost every car,
    because the bonnet is longer than the boot. That is a weaker signal than windscreen rake
    and it needs no surface normals, so it is the one used, and it is *reported* rather than
    applied: a 180° error is visible immediately in the harness's render, and guessing wrong
    here silently reverses a correct model.
    """
    if not shells:
        return Repair("nose direction", False, "n/a", "n/a", "no geometry")
    lo_z = min(shell.lo[2] for shell in shells)
    hi_z = max(shell.hi[2] for shell in shells)
    mid_z = (lo_z + hi_z) * 0.5
    hi_y = max(shell.hi[1] for shell in shells)
    tall = [shell for shell in shells if shell.hi[1] > hi_y * 0.82]
    if not tall:
        return Repair("nose direction", False, "n/a", "n/a", "no cabin candidates")
    cabin_z = sum(shell.centroid[2] for shell in tall) / len(tall)
    bias = cabin_z - mid_z
    return Repair(
        check="nose direction",
        applied=False,
        before=round(bias, 4),
        after=round(bias, 4),
        detail=(
            "cabin sits behind the midpoint, so the nose is at +z as D00-R16 requires"
            if bias < 0
            else "cabin sits ahead of the midpoint; the model may be yawed 180 degrees"
        ),
    )


def check_ground_contact(lowest_y: float) -> Repair:
    """Whether the lowest geometry sits on ``y = 0`` (D15-S5.5)."""
    return Repair(
        check="ground contact",
        applied=False,
        before=round(lowest_y, 4),
        after=round(lowest_y, 4),
        detail=(
            "lowest geometry is on the ground plane"
            if abs(lowest_y) <= 0.02
            else f"lowest geometry is {lowest_y:.3f} m off the ground; "
            "fix import.json's translation"
        ),
    )


def check_lateral_centring(shells: list[Shell]) -> Repair:
    """Whether the body's centroid sits on ``x = 0`` (D15-S5.5)."""
    if not shells:
        return Repair("lateral centring", False, 0.0, 0.0, "no geometry")
    total = sum(shell.triangles for shell in shells) or 1
    centroid_x = sum(shell.centroid[0] * shell.triangles for shell in shells) / total
    return Repair(
        check="lateral centring",
        applied=False,
        before=round(centroid_x, 4),
        after=round(centroid_x, 4),
        detail=(
            "body centroid is on the centreline"
            if abs(centroid_x) <= CENTRING_TOLERANCE_M
            else f"body centroid is {centroid_x:.3f} m off the centreline"
        ),
    )


def check_symmetry(shells: list[Shell], twins: dict[int, Shell]) -> list[str]:
    """Shells with no mirror twin (D15-R26): **reported, never repaired**.

    Only shells big enough to be a part are considered. Every bolt on a car is technically
    asymmetric and listing six thousand of them would bury the one finding that matters.
    """
    violations = []
    for shell in shells:
        if shell.index in twins or shell.merged_into is not None:
            continue
        if abs(shell.centroid[0]) <= MIRROR_TOLERANCE_M:
            # On the centreline: symmetric by being singular, which is what a windscreen and a
            # roof panel are.
            continue
        if shell.triangles < 200:
            continue
        violations.append(
            f"shell {shell.index} ({shell.material or 0}, {shell.triangles} tris) "
            f"at x={shell.centroid[0]:.3f} has no mirror twin"
        )
    return violations


def check_stray_shells(shells: list[Shell]) -> list[int]:
    """Shells far outside the body's hull (D15-S5.5).

    Reported; dropped only beyond :data:`labels.STRAY_SHELL_M`, and the drop is the caller's
    to perform. A stray shell is usually a second vehicle in the file (D15-E5) or a light rig,
    and both of those are worth an operator seeing rather than a tool silently deleting.
    """
    if not shells:
        return []
    core = sorted(shells, key=lambda shell: -shell.triangles)[: max(1, len(shells) // 10)]
    lo = tuple(min(shell.lo[i] for shell in core) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in core) for i in range(3))

    stray = []
    for shell in shells:
        distance = 0.0
        for axis in range(3):
            distance = max(distance, lo[axis] - shell.hi[axis], shell.lo[axis] - hi[axis])
        if distance > STRAY_SHELL_M:
            stray.append(shell.index)
    return stray


def inspect(shells: list[Shell], twins: dict[int, Shell]) -> RepairReport:
    """Runs every check of D15-S5.5's table, in its order."""
    report = RepairReport()
    if not shells:
        return report

    lo = tuple(min(shell.lo[i] for shell in shells) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in shells) for i in range(3))
    width, height, length = (hi[i] - lo[i] for i in range(3))

    report.add(check_scale(length))
    report.add(check_orientation(width, height, length))
    report.add(check_nose_direction(shells))
    report.add(check_ground_contact(lo[1]))
    report.add(check_lateral_centring(shells))
    report.symmetry_violations = check_symmetry(shells, twins)
    report.stray_shells = check_stray_shells(shells)
    return report

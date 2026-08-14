"""Refining a label into the thing an operator would name: a *door*, not a *panel*.

D15-S4.1's taxonomy is deliberately coarse — twelve labels, closed, so nothing downstream can
meet a category it does not handle. But a bonnet and a door are both ``panel`` and they are not
interchangeable: one hinges at its rear edge and one at its front, one is exported as
``panel_pickup_bonnet_01`` and one as ``panel_pickup_door_l_01``, and the slot each hangs from
has to say which it is. This module puts a **role** on each shell alongside its label, and the
role is what the exporter names parts after and what stage 6 infers hinges from.

A role is a refinement, never a reclassification. It cannot turn a ``panel`` into a ``glass``,
and a shell whose role cannot be decided keeps its label and takes no role at all — the same
first-class "could not tell" that D15-R2 makes of ``unclassified``.

The second half of this module is the rotational-symmetry pass of D15-S5.4, which is the one
piece of classification that cannot be done shell by shell (D15-R22) and cannot be done from a
bounding box (D15-R24). It is here rather than in :mod:`syndicate_prepare.cues` because it needs
the wheel corners, and a corner is a group.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .grouping import angular_coverage_deg, rotational_symmetry_order, side_of
from .labels import (
    CHASSIS,
    GLASS,
    GRILLE,
    HUB,
    LIGHT,
    PANEL,
    ROTATION_SECTORS,
    ROTATION_SYMMETRY_MIN_DEG,
    WHEEL,
)
from .shell import Shell

# ---- Roles ---------------------------------------------------------------------------

#: Panel roles. Every one of these hinges, detaches or is named differently from the others.
BONNET = "bonnet"
BOOT = "boot"
DOOR = "door"
FENDER = "fender"
QUARTER = "quarter"
BUMPER = "bumper"
SILL = "sill"
ROOF = "roof"

#: Lamp and glazing roles.
HEAD = "head"
TAIL = "tail"
WINDSCREEN = "windscreen"
REAR_WINDOW = "rear_window"
SIDE_WINDOW = "side_window"
LENS = "lens"

#: Roles a vehicle has exactly one of, and which span the centreline. Their shells are forced
#: to side ``c`` so they group into one part: a windscreen straddles ``x = 0``, so every shell
#: of it more than the side deadband off centre would otherwise take a side and a car would be
#: exported with a left windscreen, a right windscreen and a middle one — which is what both
#: shipped cars did before this existed.
SINGULAR_ROLES = frozenset({"bonnet", "boot", "roof", "windscreen", "rear_window"})

#: Every role, so the report can enumerate them the way it enumerates labels.
ROLES = (
    BONNET, BOOT, DOOR, FENDER, QUARTER, BUMPER, SILL, ROOF,
    HEAD, TAIL, WINDSCREEN, REAR_WINDOW, SIDE_WINDOW, LENS,
)

#: Fewest evenly spaced repeats that count as a bolt pattern rather than a coincidence. Three:
#: two pieces opposite each other are symmetric under half a turn and are just as likely to be
#: the two ends of one bracket, and no wheel is fastened with fewer than three studs.
MIN_SYMMETRY_ORDER = 3

#: A shell whose longest side is under this is furniture rather than a panel — a badge, a
#: handle, a wiper. It keeps its label and takes no role.
MIN_ROLE_EXTENT_M = 0.25

#: A lamp lens or an indicator, as opposed to a windscreen.
MAX_LENS_EXTENT_M = 0.45

#: How far outboard a side panel's centroid sits, as a fraction of the body's half-width.
SIDE_PANEL_OUTBOARD = 0.45

#: Fractions of the wheelbase-ish body length. A door occupies the middle of a vehicle; a
#: bumper occupies the last tenth of either end. These are fractions rather than metres
#: precisely so a pickup and a hatchback need the same numbers (D15-R7).
BUMPER_END_FRACTION = 0.11
DOOR_Z_RANGE = (0.20, 0.74)
ROOF_HEIGHT_FRACTION = 0.74
SILL_HEIGHT_FRACTION = 0.28


def assign_roles(shells: list[Shell], body) -> dict[str, int]:
    """Put a role on every shell whose label has roles. Returns a count per role."""
    counts: dict[str, int] = {}
    for shell in shells:
        role = role_for(shell, body)
        shell.role = role
        if role is not None:
            counts[role] = counts.get(role, 0) + 1
    return dict(sorted(counts.items()))


def role_for(shell: Shell, body) -> str | None:
    """The role of one shell, or ``None`` where its label has none or geometry cannot tell."""
    if shell.label == PANEL:
        return _panel_role(shell, body)
    if shell.label == LIGHT:
        return HEAD if _front_fraction(shell, body) > 0.5 else TAIL
    if shell.label == GLASS:
        return _glass_role(shell, body)
    if shell.label == GRILLE:
        return HEAD if _front_fraction(shell, body) > 0.5 else TAIL
    return None


def _panel_role(shell: Shell, body) -> str | None:
    """Which panel this is, from the plane it lies in and where on the body it sits.

    The plane comes from the shell's thinnest axis, which is the one measurement that
    separates the three families of panel from each other on every vehicle ever built: a
    bonnet is thin vertically, a door is thin laterally, and a bumper is thin longitudinally.
    Position then says which member of the family it is.
    """
    if shell.longest_extent < MIN_ROLE_EXTENT_M:
        return None
    plane = _thin_axis(shell)
    forward = _front_fraction(shell, body)
    height = _height_fraction(shell, body)
    outboard = abs(shell.centroid[0]) / max(1e-6, body.half_width)

    if plane == 2:  # thin front-to-back: an end panel
        return BUMPER

    if plane == 1:  # thin top-to-bottom: a horizontal panel
        # Which end of the car it is over decides first, and height only settles the middle.
        # A boot lid on a saloon sits at three-quarters of the body's height, which is close
        # enough to a roof's that a height test alone calls one the other.
        if forward > 0.55:
            return BONNET
        if forward < 0.45:
            return BOOT
        return ROOF if height > ROOF_HEIGHT_FRACTION else None

    # thin side-to-side: a flank
    if outboard < SIDE_PANEL_OUTBOARD:
        return None
    if height < SILL_HEIGHT_FRACTION:
        return SILL
    if DOOR_Z_RANGE[0] <= forward <= DOOR_Z_RANGE[1]:
        return DOOR
    return FENDER if forward > DOOR_Z_RANGE[1] else QUARTER


def _glass_role(shell: Shell, body) -> str | None:
    """Windscreen, rear window, side window or lamp lens.

    Lens first: a lamp lens is glass by every physical cue the file carries and is nothing
    like a window in size, and calling it a windscreen would put a two-metre slot on the
    chassis for a headlight.
    """
    if shell.longest_extent < MAX_LENS_EXTENT_M:
        return LENS
    outboard = abs(shell.centroid[0]) / max(1e-6, body.half_width)
    if outboard > SIDE_PANEL_OUTBOARD and _thin_axis(shell) == 0:
        return SIDE_WINDOW
    return WINDSCREEN if _front_fraction(shell, body) > 0.5 else REAR_WINDOW


def _thin_axis(shell: Shell) -> int:
    size = shell.size
    return min(range(3), key=lambda axis: size[axis])


def _front_fraction(shell: Shell, body) -> float:
    """Where along the vehicle a shell sits: ``0`` at the tail, ``1`` at the nose."""
    return (shell.centroid[2] - body.lo[2]) / max(1e-6, body.length)


def _height_fraction(shell: Shell, body) -> float:
    return (shell.centroid[1] - body.ground_y) / max(1e-6, body.height)


def is_bumper_end(shell: Shell, body) -> bool:
    """Whether a shell sits in the last tenth of either end (used for bumper naming)."""
    forward = _front_fraction(shell, body)
    return forward > 1.0 - BUMPER_END_FRACTION or forward < BUMPER_END_FRACTION


# ---- Wheel corners and rotational symmetry (D15-S5.4) ----------------------------------


@dataclass
class Corner:
    """One wheel corner: the shells that rotate, the shells that do not, and the axle.

    The axle is measured from the **seed** shells alone — the round, outboard, low things
    that made this a corner in the first place — and frozen before anything else is captured.
    A caliper legitimately sticks out past a tyre's silhouette, so an axle averaged over
    everything captured reports a wheel half again too big, and every number downstream (the
    slot position, the suspension rest length, the radius Bullet is given) inherits it.
    """

    name: str
    seeds: list[Shell] = field(default_factory=list)
    captured: list[Shell] = field(default_factory=list)
    axle: tuple[float, float, float] = (0.0, 0.0, 0.0)
    radius_m: float = 0.0
    width_m: float = 0.0
    rotating: list[Shell] = field(default_factory=list)
    static: list[Shell] = field(default_factory=list)
    coverage: dict[str, float] = field(default_factory=dict)
    symmetry: dict[str, int] = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {
            "corner": self.name,
            "axleM": [round(value, 4) for value in self.axle],
            "diameterM": round(self.radius_m * 2.0, 4),
            "widthM": round(self.width_m, 4),
            "seeds": len(self.seeds),
            "rotatingShells": len(self.rotating),
            "staticShells": len(self.static),
            "coverageDeg": {key: round(value, 1) for key, value in sorted(self.coverage.items())},
            "symmetryOrder": dict(sorted(self.symmetry.items())),
        }


#: How far outside its own radius a shell may sit and still be captured into a corner.
#: Covers the caliper and the hub face, which stick out past the tyre.
WHEEL_CAPTURE_MARGIN = 1.12

# ---- What may *seed* a corner (D15-R23a) -------------------------------------------------
#
# Measured on both shipped cars. Their sixteen genuine tyre and rim shells are round to
# within 1%, 0.548-0.719 m across, 0.03-0.32 m wide, axled at 0.355-0.372 m and offset
# 0.65-1.06 m from the centreline. Every threshold below sits clear of all sixteen.

#: A seed is a disc in side view. The same figure C1 votes on, applied again here because a
#: shell can reach the `wheel` label on a *name* alone and a name cannot define an axle.
WHEEL_SEED_MIN_ROUNDNESS = 0.78

#: Smallest and largest wheel a seed may be. An 18-inch rim is 0.457 m across bare, so
#: nothing below 0.45 is a road wheel; above 1.20 it is a body panel that happens to be round.
MIN_WHEEL_DIAMETER_M = 0.45
MAX_WHEEL_DIAMETER_M = 1.20

#: Widest seed accepted, measured across the axle.
MAX_WHEEL_SEED_WIDTH_M = 0.75

#: A seed's axle sits below this. A 0.7 m tyre has its axle at 0.35 m and even a 1.2 m truck
#: tyre at 0.60.
MAX_WHEEL_SEED_CENTRE_Y_M = 0.65

#: And at least this far off the centreline. Half the narrowest plausible track.
MIN_WHEEL_SEED_OFFSET_M = 0.45


def is_wheel_seed(shell: Shell) -> bool:
    """Whether a shell may define an axle (D15-R23a).

    Seeding and belonging are different questions, and conflating them is what put a
    1.44 m "wheel" on a shipped car. A hub cap, a valve stem and a brake disc all *belong*
    to a wheel and none of them is wheel-shaped; they arrive by capture. What defines where
    the axle **is** must itself be a disc of a road wheel's size, in a road wheel's place —
    because every measurement the corner then makes is taken from these shells alone.

    The specific failure this prevents: the Eclipse carries a flat bracket, 0.35 x 0.10 m and
    round to 0.29, whose material is named `vehicle_generic_smallspecmap_WHEEL`. C3 matches
    the whole token `wheel` and labels it one, and as a seed it dragged the front axle 0.37 m
    rearward, inflated the wheel to 1.44 m across, and captured 891 shells — 37% of the car —
    into a corner that then reported them all as brake furniture.
    """
    _sx, sy, sz = shell.size
    diameter = max(sy, sz)
    return (
        shell.roundness >= WHEEL_SEED_MIN_ROUNDNESS
        and MIN_WHEEL_DIAMETER_M <= diameter <= MAX_WHEEL_DIAMETER_M
        and shell.size[0] <= MAX_WHEEL_SEED_WIDTH_M
        and shell.centroid[1] <= MAX_WHEEL_SEED_CENTRE_Y_M
        and abs(shell.centroid[0]) >= MIN_WHEEL_SEED_OFFSET_M
    )


def find_corners(shells: list[Shell], body) -> list[Corner]:
    """Cluster the wheel-shaped shells into corners, one per road wheel.

    By count rather than by an assumption of four (D15-E4): the sides are split by the sign
    of ``x`` and each side is cut into axles wherever the gap along ``z`` exceeds a wheel's
    own diameter, so a six-wheeler yields six corners and a three-wheeler three.

    Only shells passing :func:`is_wheel_seed` take part. Everything else labelled ``wheel``
    keeps its label and waits to be captured by whichever corner it turns out to sit in.
    """
    seeds = [shell for shell in shells if shell.label == WHEEL and is_wheel_seed(shell)]
    if not seeds:
        return []

    typical = _median([shell.longest_extent for shell in seeds])
    gap = max(0.35, typical * 1.2)

    runs: list[list[Shell]] = []
    for side in ("l", "r", "c"):
        members = sorted(
            (shell for shell in seeds if side_of(shell.centroid[0]) == side),
            key=lambda shell: (shell.centroid[2], shell.index),
        )
        run: list[Shell] = []
        for shell in members:
            if run and shell.centroid[2] - run[-1].centroid[2] > gap:
                runs.append(run)
                run = []
            run.append(shell)
        if run:
            runs.append(run)

    # Axles are named front to back, so a two-axle vehicle gets the fl/fr/rl/rr the shipped
    # assemblies already use and anything else gets a numbered name that cannot collide. The
    # axle a run belongs to is decided by clustering the runs' own z, because a left and a
    # right wheel on one axle never sit at exactly the same z on real art.
    ordered = sorted(runs, key=lambda run: (-_mean_z(run), run[0].index))
    axle_of: list[int] = []
    axle_index = -1
    previous: float | None = None
    for run in ordered:
        centre = _mean_z(run)
        if previous is None or previous - centre > gap:
            axle_index += 1
        axle_of.append(axle_index)
        previous = centre
    axle_count = axle_index + 1

    corners: list[Corner] = []
    for index, run in enumerate(ordered):
        side = side_of(sum(shell.centroid[0] for shell in run) / len(run))
        corners.append(Corner(name=_corner_name(axle_of[index], axle_count, side), seeds=run))
    for corner in corners:
        _measure_axle(corner)
    del body
    return sorted(corners, key=lambda corner: corner.name)


def _corner_name(axle_index: int, axle_count: int, side: str) -> str:
    if axle_count <= 2:
        return ("f" if axle_index == 0 else "r") + side
    return f"a{axle_index}{side}"


def _measure_axle(corner: Corner) -> None:
    """Freeze the corner's axle and tyre size from its seeds alone."""
    lo = tuple(min(shell.lo[i] for shell in corner.seeds) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in corner.seeds) for i in range(3))
    corner.axle = tuple((lo[i] + hi[i]) * 0.5 for i in range(3))
    corner.radius_m = max(hi[1] - lo[1], hi[2] - lo[2]) * 0.5
    corner.width_m = hi[0] - lo[0]


def capture_into_corners(shells: list[Shell], corners: list[Corner]) -> int:
    """Attach every shell sitting inside a corner's sphere of influence to that corner.

    Label-blind on purpose. Whether the caliper the ensemble called ``chassis`` belongs to
    this wheel is a question about where it is, not about what a cue thought of it; the
    symmetry test that follows is what decides whether it *turns* with the wheel.
    """
    seeded = {shell.index for corner in corners for shell in corner.seeds}
    captured = 0
    for shell in shells:
        if shell.merged_into is not None or shell.index in seeded:
            continue
        best: Corner | None = None
        best_distance = 0.0
        for corner in corners:
            reach = corner.radius_m * WHEEL_CAPTURE_MARGIN
            dy = shell.centroid[1] - corner.axle[1]
            dz = shell.centroid[2] - corner.axle[2]
            dx = shell.centroid[0] - corner.axle[0]
            if abs(dx) > corner.width_m * 1.6:
                continue
            distance = math.hypot(dy, dz)
            if distance <= reach and (best is None or distance < best_distance):
                best, best_distance = corner, distance
        if best is None:
            continue
        shell.corner = best.name
        best.captured.append(shell)
        captured += 1
    for corner in corners:
        for shell in corner.seeds:
            shell.corner = corner.name
    return captured


def resolve_rotation(corners: list[Corner]) -> None:
    """Decide what turns with each wheel and what stays put (D15-R21 to D15-R24).

    The unit of judgement is a **material group within one corner** (D15-R22), never a single
    shell. Rotational symmetry is a property of an assembly: a lug nut occupies 15° and
    plainly rotates — what it lacks is not size but a partner to be rotated onto, and the
    material a piece was authored with is the best proxy available for "the same kind of
    part". Judged shell by shell a rim loses its lug nuts, its spoke details and its valve
    stem to the chassis.

    Seeds are tested too (D15-R23): a caliper bolt is square in silhouette and therefore
    passes the roundness test that seeds a corner, and fails this one.
    """
    for corner in corners:
        members = list(corner.seeds) + list(corner.captured)
        groups: dict[str, list[Shell]] = {}
        for shell in members:
            groups.setdefault(shell.material or f"_shell_{shell.index}", []).append(shell)

        for material, group in sorted(groups.items()):
            points = [point for shell in group for point in shell.vertex_sample]
            coverage = angular_coverage_deg(points, corner.axle, ROTATION_SECTORS)
            order = rotational_symmetry_order(points, corner.axle, ROTATION_SECTORS)
            corner.coverage[material] = coverage
            corner.symmetry[material] = order
            # Either test is sufficient, because they catch the two different shapes a piece
            # that turns can have: a solid of revolution covers the circle, and a bolt pattern
            # repeats around it. A caliper does neither: 90-150 degrees of arc, once.
            rotates = coverage >= ROTATION_SYMMETRY_MIN_DEG or order >= MIN_SYMMETRY_ORDER
            for shell in group:
                shell.label = WHEEL if rotates else HUB
                shell.role = None
                (corner.rotating if rotates else corner.static).append(shell)


def dissolve_empty_corners(corners: list[Corner], shells: list[Shell]) -> list[dict]:
    """Discard any corner with nothing in it that turns, and give its shells back (D15-R23b).

    A wheel is defined by something rotating about an axle. If, after
    :func:`resolve_rotation` has judged every material group in a corner, not one of them
    rotates, then whatever the seeding found is not a wheel — and leaving it in place is worse
    than finding nothing, because every shell it captured is exported as unsprung brake
    furniture bolted to a slot that does not exist on the vehicle.

    The shells go back to ``chassis``, which is D15-R2's correct-if-coarse answer, and the
    dissolution is reported.
    """
    kept: list[Corner] = []
    dissolved: list[dict] = []
    for corner in corners:
        if corner.rotating:
            kept.append(corner)
            continue
        dissolved.append(
            {
                "corner": corner.name,
                "axleM": [round(value, 4) for value in corner.axle],
                "diameterM": round(corner.radius_m * 2.0, 4),
                "shells": len(corner.seeds) + len(corner.captured),
                "because": "nothing in it rotates about the axle, so it is not a wheel",
            }
        )
        for shell in corner.seeds + corner.captured:
            shell.corner = None
            shell.label = CHASSIS
            shell.role = None
    corners[:] = kept
    del shells
    return dissolved


def _median(values: list[float]) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) * 0.5


def _mean_z(shells: list[Shell]) -> float:
    return sum(shell.centroid[2] for shell in shells) / len(shells)

"""The weapon cue ensemble of D17-S5.6: four weighted families that vote on what each shell is.

An ensemble rather than a decision tree, for D15-S4.2's reason: no single test is reliable on real
art, and a vote with a reason attached can be read in a report when it goes wrong.

What makes this ensemble different from D15's is **W1**. A car is a three-dimensional arrangement
and has to be reasoned about as one; a gun is a *sequence along one line*, so normalising every
shell's centroid to a bore coordinate in ``[0,1]`` predicts most of the taxonomy before any other
cue votes. The remaining three exist to overrule it, which is the whole point of an ensemble — and
the reason W1's bands overlap (D17-R35).

Nothing here touches Blender. Everything reads a :class:`syndicate_prepare.shell.Shell` and a
:class:`syndicate_weapon.bore.Bore`, which is what lets the whole module be unit-tested.
"""

from __future__ import annotations

import math
from collections import defaultdict

from .bore import aspect_of
from .labels import (
    BARREL,
    BORE_ASPECT_MIN,
    BORE_COAXIAL_TOL,
    BREECH,
    FEED,
    FURNITURE,
    GEAR,
    LABEL_MIN_CONFIDENCE,
    MIRROR_TOLERANCE,
    MOUNT,
    MUZZLE,
    RECEIVER,
    ROTATION_MIN_INSTANCES,
    SIGHT,
    UNCLASSIFIED,
    Vote,
)

#: Material-name tokens that vote for a label (W3). Weak on purpose: one of the two shipped models
#: names its single material ``MachineGuns`` and the other names its single material ``Cannon``,
#: which is exactly as useful as DISC-019 predicts material names are.
MATERIAL_TOKENS = {
    "barrel": BARREL,
    "muzzle": MUZZLE,
    "brake": MUZZLE,
    "flash": MUZZLE,
    "breech": BREECH,
    "chamber": BREECH,
    "boiler": BREECH,
    "receiver": RECEIVER,
    "body": RECEIVER,
    "frame": RECEIVER,
    "mag": FEED,
    "magazine": FEED,
    "drum": FEED,
    "belt": FEED,
    "ammo": FEED,
    "gear": GEAR,
    "cog": GEAR,
    "wheel": GEAR,
    "sight": SIGHT,
    "scope": SIGHT,
    "optic": SIGHT,
    "mount": MOUNT,
    "cradle": MOUNT,
    "trunnion": MOUNT,
    "pintle": MOUNT,
    "base": MOUNT,
    "grip": FURNITURE,
    "handle": FURNITURE,
    "shield": FURNITURE,
    "plate": FURNITURE,
}


def label_shells(shells, bore) -> list[Vote]:
    """Runs the ensemble over every shell, writing ``label``, ``confidence`` and ``votes``.

    Returns the flat list of every vote cast, which the report carries so an operator can see the
    disagreements rather than only the winners.
    """
    measured = _measure(shells, bore)
    all_votes: list[Vote] = []
    per_shell: dict[int, list[Vote]] = defaultdict(list)

    for shell in shells:
        m = measured[shell.index]
        for vote in (
            *axial_votes(shell, m),
            *geometric_votes(shell, m),
            *material_votes(shell),
        ):
            per_shell[shell.index].append(vote)

    # W4 reads relations between shells, so it runs after every shell has been measured and needs
    # the whole set rather than one at a time.
    for shell_index, vote in structural_votes(shells, measured):
        per_shell[shell_index].append(vote)

    for shell in shells:
        votes = per_shell[shell.index]
        all_votes.extend(votes)
        totals: dict[str, float] = defaultdict(float)
        for vote in votes:
            totals[vote.label] += vote.weight
        shell.votes = [v.as_dict() for v in votes]
        if not totals:
            shell.label, shell.confidence = UNCLASSIFIED, 0.0
            continue
        # Ties broken on the label's order in the taxonomy, so two runs agree (G3).
        best = max(sorted(totals.items()), key=lambda kv: kv[1])
        shell.label = best[0] if best[1] >= LABEL_MIN_CONFIDENCE else UNCLASSIFIED
        shell.confidence = round(best[1], 4)
    return all_votes


# ---- Per-shell measurements --------------------------------------------------------------


class Measured:
    """Everything the cues read about one shell, all of it relative to the bore.

    Computed once and shared, because four cue families asking the same shell for its bore
    coordinate four times is the kind of thing that turns a 16-second stage into a 60-second one.
    """

    __slots__ = ("bore_aspect", "bore_length", "bore_position", "bore_roundness", "flatness",
    "radius")

    def __init__(self, bore_position, bore_length, radius, bore_roundness, bore_aspect, flatness):
        self.bore_position = bore_position
        self.bore_length = bore_length
        self.radius = radius
        self.bore_roundness = bore_roundness
        self.bore_aspect = bore_aspect
        self.flatness = flatness


def _measure(shells, bore) -> dict[int, Measured]:
    coordinates = {s.index: bore.coordinate_of(s.centroid) for s in shells}
    lo = min(coordinates.values()) if coordinates else 0.0
    hi = max(coordinates.values()) if coordinates else 1.0
    span = max(1e-6, hi - lo)

    out = {}
    for shell in shells:
        sample = shell.vertex_sample or (shell.centroid,)
        along = [bore.coordinate_of(p) for p in sample]
        radii = [bore.radius_of(p) for p in sample]
        bore_length = max(along) - min(along) if along else 0.0
        cross = 2.0 * (sum(radii) / len(radii)) if radii else 0.0
        out[shell.index] = Measured(
            bore_position=(coordinates[shell.index] - lo) / span,
            bore_length=bore_length,
            radius=bore.radius_of(shell.centroid),
            bore_roundness=_bore_roundness(sample, bore),
            bore_aspect=bore_length / cross if cross > 1e-9 else aspect_of(shell),
            flatness=shell.flatness,
        )
    return out


def _bore_roundness(sample, bore) -> float:
    """How round the shell is in the plane perpendicular to the bore, ``[0,1]``.

    Measured as the ratio of the two perpendicular extents in that plane, which is what separates a
    barrel or a gear from a shield or a sight rail. Measuring roundness about the *world* axes — as
    D15's ``Shell.roundness`` does, correctly, for a wheel — answers the wrong question here,
    because a gun's own axis is rarely a world axis.
    """
    axis = bore.axis
    # Any two vectors perpendicular to the axis and to each other.
    seed = (0.0, 0.0, 1.0) if abs(axis[2]) < 0.9 else (1.0, 0.0, 0.0)
    u = _normalise(_cross(axis, seed))
    v = _cross(axis, u)
    us = [sum(p[i] * u[i] for i in range(3)) for p in sample]
    vs = [sum(p[i] * v[i] for i in range(3)) for p in sample]
    if not us:
        return 0.0
    span_u = max(us) - min(us)
    span_v = max(vs) - min(vs)
    larger = max(span_u, span_v)
    return min(span_u, span_v) / larger if larger > 1e-9 else 0.0


# ---- W1: position along the bore (D17-R35) -----------------------------------------------

#: The overlapping bands of D17-R35, as ``(label, lo, hi, peak_confidence)``.
#:
#: They overlap **deliberately**. A cue that partitions the line cannot be outvoted, and an
#: ensemble whose strongest member cannot be outvoted is a decision tree wearing a costume.
AXIAL_BANDS = (
    (BREECH, 0.00, 0.22, 0.95),
    (RECEIVER, 0.12, 0.58, 0.90),
    (BARREL, 0.35, 0.95, 0.85),
    (MUZZLE, 0.88, 1.00, 1.00),
)

#: Floor on a band vote's confidence, as a fraction of the band's peak.
#:
#: Without it a shell sitting near a band's edge contributes almost nothing, and a receiver that
#: happens to sit at 0.24 of the bore falls under :data:`LABEL_MIN_CONFIDENCE` and comes out
#: ``unclassified`` — which is what the shipped machine gun's receiver did. A band is a statement
#: that a region is *plausible*, so being inside one at all has to be worth something.
AXIAL_EDGE_FLOOR = 0.55


def axial_votes(shell, m: Measured) -> list[Vote]:
    """Where along the gun this shell sits, which predicts most of the taxonomy (D17-R35)."""
    votes = []
    for label, lo, hi, peak in AXIAL_BANDS:
        if not (lo <= m.bore_position <= hi):
            continue
        # Confidence falls off toward the band's edges, so a shell in an overlap contributes to
        # both labels in proportion to how central it is in each.
        centre = (lo + hi) / 2.0
        half = max(1e-6, (hi - lo) / 2.0)
        closeness = 1.0 - abs(m.bore_position - centre) / half
        confidence = peak * max(AXIAL_EDGE_FLOOR, closeness)
        if label == BARREL and m.bore_aspect < BORE_ASPECT_MIN:
            # The barrel band only votes for a shell that is actually barrel-shaped (D17-R35).
            continue
        votes.append(
            Vote(
                "W1_axial",
                label,
                confidence,
                f"bore position {m.bore_position:.2f} in {label} band",
            )
        )
    return votes


# ---- W2: geometry about the bore (D17-R36) -----------------------------------------------


def geometric_votes(shell, m: Measured) -> list[Vote]:
    """Aspect, roundness, radial offset and flatness — all measured about the bore, not the
    world."""
    votes = []
    on_axis = m.radius <= BORE_COAXIAL_TOL

    if m.bore_aspect >= BORE_ASPECT_MIN and on_axis:
        votes.append(
            Vote("W2_geometric", BARREL, min(1.0, 0.5 + 0.1 * m.bore_aspect),
                 f"bore aspect {m.bore_aspect:.1f} on the axis")
        )
    if m.bore_roundness >= 0.85 and not on_axis and m.bore_aspect < BORE_ASPECT_MIN:
        # Round in the bore plane but off the axis: a gear, a hand wheel, a drum.
        votes.append(
            Vote("W2_geometric", GEAR, 0.55 * m.bore_roundness,
                 f"round about the bore ({m.bore_roundness:.2f}) at radius {m.radius:.3f} m")
        )
    # Plate-like *across* the bore rather than merely elongated: a shield is thin in one of the two
    # perpendicular directions. Testing raw flatness here called the receiver furniture, because a
    # receiver is long and a long thing is flat by that measure.
    if m.bore_roundness <= 0.35 and m.bore_aspect < BORE_ASPECT_MIN and not on_axis:
        votes.append(
            Vote(
                "W2_geometric",
                FURNITURE,
                0.6,
                f"thin across the bore (roundness {m.bore_roundness:.2f})",
            )
        )
    if shell.triangles <= 24 and not on_axis and m.bore_position > 0.3:
        votes.append(
            Vote(
                "W2_geometric",
                SIGHT,
                0.45,
                f"small ({shell.triangles} tris) and off-axis, forward of centre",
            )
        )
    if m.bore_roundness >= 0.7 and not on_axis and m.bore_position < 0.6 and shell.triangles > 40:
        votes.append(
            Vote("W2_geometric", FEED, 0.35, "bulky, round-ish and off-axis behind the middle")
        )
    # The receiver is the biggest thing on the bore that is not the barrel: on the axis,
    # substantial,
    # and not slender enough to be a tube. Bounded at the **back** as well as the front, because the
    # breech is also on the axis and also stubby — an unbounded rule claimed it, and a gun whose
    # breech is labelled receiver has no breech to shoot off.
    if on_axis and m.bore_aspect < BORE_ASPECT_MIN and 0.12 <= m.bore_position <= 0.6:
        votes.append(Vote("W2_geometric", RECEIVER, 0.7,
                          f"on the bore, aspect {m.bore_aspect:.1f}, around the middle"))
    # The breech is the receiver's counterpart at the back: same shape, different place. It needs
    # its
    # own vote rather than falling out of the receiver's, because W1's band alone sits just under
    # the
    # confidence floor and a breech with no second cue comes out `unclassified`.
    if on_axis and m.bore_aspect < BORE_ASPECT_MIN and m.bore_position < 0.12:
        votes.append(Vote("W2_geometric", BREECH, 0.7,
                          f"on the bore, aspect {m.bore_aspect:.1f}, at the back"))
    return votes


# ---- W3: material names (D17-R34) --------------------------------------------------------


def material_votes(shell) -> list[Vote]:
    """What the source called it, weighted low because DISC-019 says names are unreliable."""
    name = (shell.material or shell.name or "").lower()
    votes = []
    for token, label in sorted(MATERIAL_TOKENS.items()):
        # Whole-token match. DISC-037 is the standing lesson: a substring match on `wheel` found a
        # bracket called `..._smallspecmap_WHEEL` and took a third of a car with it.
        if _has_token(name, token):
            votes.append(Vote("W3_material", label, 0.6, f"material name contains \"{token}\""))
    return votes


def _has_token(name: str, token: str) -> bool:
    padded = "".join(c if c.isalnum() else " " for c in name)
    return token in padded.split()


# ---- W4: relations between shells (D17-R37) ----------------------------------------------


def structural_votes(shells, measured) -> list[tuple[int, Vote]]:
    """Coaxial adjacency, rotational repetition and mirroring — relations, not shapes."""
    votes: list[tuple[int, Vote]] = []
    votes.extend(_coaxial_with_barrel(shells, measured))
    votes.extend(_rotational_sets(shells, measured))
    votes.extend(_mirrored_pairs(shells, measured))
    return votes


def _coaxial_with_barrel(shells, measured):
    """A shell coaxial with and overlapping a barrel-like one is part of the barrel group.

    This is what collects a jacket, a shroud and a bore into one ``barrel`` rather than three, which
    on the shipped machine gun is exactly the case: three concentric 0.27 m tubes.
    """
    seeds = [s for s in shells if measured[s.index].bore_aspect >= BORE_ASPECT_MIN
             and measured[s.index].radius <= BORE_COAXIAL_TOL]
    out = []
    for shell in shells:
        m = measured[shell.index]
        if m.radius > BORE_COAXIAL_TOL:
            continue
        for seed in seeds:
            if seed.index == shell.index:
                continue
            sm = measured[seed.index]
            if abs(m.bore_position - sm.bore_position) <= 0.22:
                out.append((shell.index, Vote(
                    "W4_structural", BARREL, 0.7,
                    f"coaxial with and adjacent to barrel-like shell {seed.index}")))
                break
    return out


def _rotational_sets(shells, measured):
    """Three or more congruent shells repeated at a common radius about the bore are one gear.

    The same rotational-repetition idea DEC-066 uses for a wheel, applied about the bore. Congruence
    is tested on size and triangle count rather than on vertices, which is enough to find a ring of
    identical teeth and cheap enough to run on every pair.
    """
    out = []
    buckets: dict[tuple, list] = defaultdict(list)
    for shell in shells:
        m = measured[shell.index]
        if m.radius <= BORE_COAXIAL_TOL:
            continue
        key = (
            shell.triangles,
            round(shell.size[0], 2),
            round(shell.size[1], 2),
            round(shell.size[2], 2),
            round(m.radius, 2),
        )
        buckets[key].append(shell)
    for key in sorted(buckets):
        members = buckets[key]
        if len(members) < ROTATION_MIN_INSTANCES:
            continue
        for shell in members:
            out.append((shell.index, Vote(
                "W4_structural", GEAR, 0.75,
                f"one of {len(members)} congruent shells repeated at radius {key[4]:.2f} m")))
    return out


def _mirrored_pairs(shells, measured):
    """A shell whose twin sits at the same bore position and opposite radius is one of a pair.

    Both take the same label, which is what keeps a gun carriage's two trunnion cheeks from being
    labelled differently because one of them happened to catch a different cue.
    """
    out = []
    for i, a in enumerate(shells):
        ma = measured[a.index]
        for b in shells[i + 1:]:
            mb = measured[b.index]
            if abs(ma.bore_position - mb.bore_position) > 0.03:
                continue
            if abs(ma.radius - mb.radius) > MIRROR_TOLERANCE:
                continue
            if abs(a.triangles - b.triangles) > max(2, a.triangles * 0.1):
                continue
            if math.dist(a.centroid, b.centroid) < MIRROR_TOLERANCE:
                continue
            label = MOUNT if ma.bore_position < 0.5 else FURNITURE
            for shell in (a, b):
                twin = b if shell is a else a
                out.append((
                    shell.index,
                    Vote(
                        "W4_structural",
                        label,
                        0.5,
                        f"one of a mirrored pair about the bore with shell {twin.index}",
                    ),
                ))
    return out


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normalise(v):
    length = math.sqrt(sum(c * c for c in v))
    if length < 1e-12:
        return (1.0, 0.0, 0.0)
    return (v[0] / length, v[1] / length, v[2] / length)

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
    BARREL_ROUNDNESS_MIN,
    BORE_ASPECT_MIN,
    BORE_COAXIAL_TOL,
    BREECH,
    DISC_ROUNDNESS_MIN,
    DISC_THINNESS_MAX,
    FEED,
    FURNITURE,
    GEAR,
    LABEL_MIN_CONFIDENCE,
    MIRROR_TOLERANCE,
    MOUNT,
    MOUNT_FORWARD_LIMIT,
    MOUNT_REACH_FLOOR,
    MOUNT_SHARE_MIN,
    MUZZLE,
    NEAR_AXIS_TOL,
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


#: Labels the axial and structural cues stop voting for once the geometry has found the shell to be
#: part of the mounting (D17-R35a). A bracket lying alongside a barrel is coaxial with it and sits
#: in
#: its band, and both of those said "barrel" about the shipped machine gun's front bracket arm.
VETOED_BY_MOUNT = (RECEIVER, BREECH, BARREL)


def _veto_axial_for_mounts(votes):
    """Drops the axial cue's ``receiver`` and ``breech`` votes for a shell called a mount.

    W1 answers "where along the gun is this piece", and that question presumes the piece is *on* the
    gun. A pedestal and a side bracket both sit at a plausible axial position for a receiver — the
    middle — so W1 votes receiver for them, twice over with W2's own lump test, and two moderate
    votes outweigh one confident one. Both shipped weapons lost their mounts that way.

    A veto rather than a weight, because the two cues are not disagreeing about the same question:
    one of them is answering a question that does not apply.
    """
    mounted = any(v.cue == "W2_geometric" and v.label == MOUNT for v in votes)
    if not mounted:
        return votes
    return [
        v
        for v in votes
        if not (v.cue in ("W1_axial", "W4_structural") and v.label in VETOED_BY_MOUNT)
    ]


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
        votes = _veto_axial_for_mounts(per_shell[shell.index])
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

    harmonise_pairs(shells, measured)
    return all_votes


# ---- Per-shell measurements --------------------------------------------------------------


class Measured:
    """Everything the cues read about one shell, all of it relative to the bore.

    Computed once and shared, because four cue families asking the same shell for its bore
    coordinate four times is the kind of thing that turns a 16-second stage into a 60-second one.
    """

    __slots__ = (
        "bore_aspect",
        "bore_length",
        "bore_position",
        "bore_roundness",
        "flatness",
        "mount_offset",
        "mount_share",
        "radius",
    )

    def __init__(
        self,
        bore_position,
        bore_length,
        radius,
        bore_roundness,
        bore_aspect,
        flatness,
        mount_offset=0.0,
        mount_share=0.0,
    ):
        self.bore_position = bore_position
        self.bore_length = bore_length
        self.radius = radius
        self.bore_roundness = bore_roundness
        self.bore_aspect = bore_aspect
        self.flatness = flatness
        #: How far this shell sits along the model's mounting direction, as a fraction of bore
        #: length. Positive is toward whatever the gun bolts to (:func:`mounting_direction`).
        self.mount_offset = mount_offset
        #: That offset as a fraction of the *furthest* offset any shell on this model reaches. The
        #: mount cue reads this rather than :attr:`mount_offset`, because how far a gun's mounting
        #: hangs off its bore is a property of the gun: a pedestal reaches half the gun's length and
        #: a pintle bracket reaches four per cent of it, and both are equally the mount.
        self.mount_share = mount_share


def mounting_direction(shells, bore):
    """The perpendicular direction in which the model hangs off its own bore (D17-R36a).

    A gun's *working* parts are arranged around the bore and are therefore roughly balanced about
    it. Whatever the gun **bolts to** is not: a pedestal hangs below the bore, a side bracket sticks
    out to one side, a pintle drops underneath. So the direction in which the model's geometry is
    most lopsided about its own bore is the direction the mount is in — and that is a property of
    the gun rather than a threshold anybody has to tune per model.

    Returns a unit vector perpendicular to the bore, weighted by triangle count so a heavy pedestal
    decides it and a light aerial does not.
    """
    axis = bore.axis
    seed = (0.0, 0.0, 1.0) if abs(axis[2]) < 0.9 else (1.0, 0.0, 0.0)
    u = _normalise(_cross(axis, seed))
    v = _cross(axis, u)

    total = sum(s.triangles for s in shells) or 1
    mean_u = sum(_component(s.centroid, bore, u) * s.triangles for s in shells) / total
    mean_v = sum(_component(s.centroid, bore, v) * s.triangles for s in shells) / total
    length = math.hypot(mean_u, mean_v)
    if length < 1e-9:
        # Perfectly balanced about its bore: nothing sticks out, so there is no mounting direction
        # to find and the mount cue simply does not vote.
        return None
    return tuple((mean_u * u[i] + mean_v * v[i]) / length for i in range(3))


def _component(point, bore, direction) -> float:
    """How far ``point`` sits from the bore line along ``direction``."""
    along = bore.coordinate_of(point)
    foot = tuple(bore.origin[i] + along * bore.axis[i] for i in range(3))
    return sum((point[i] - foot[i]) * direction[i] for i in range(3))


def _measure(shells, bore) -> dict[int, Measured]:
    coordinates = {s.index: bore.coordinate_of(s.centroid) for s in shells}
    lo = min(coordinates.values()) if coordinates else 0.0
    hi = max(coordinates.values()) if coordinates else 1.0
    span = max(1e-6, hi - lo)

    mount_dir = mounting_direction(shells, bore)

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
            mount_offset=(_component(shell.centroid, bore, mount_dir) if mount_dir else 0.0),
        )

    reach = max((o.mount_offset for o in out.values()), default=0.0)
    if reach >= MOUNT_REACH_FLOOR:
        for measured in out.values():
            measured.mount_share = measured.mount_offset / reach
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
AXIAL_EDGE_FLOOR = 0.62


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
        if label == BARREL and m.bore_aspect < BORE_ASPECT_MIN and m.radius > BORE_COAXIAL_TOL:
            # The barrel band votes for a shell that is either barrel-*shaped* or barrel-*placed*
            # (D17-R35). Requiring the shape alone lost the shipped cannon's barrel entirely: it is
            # modelled in three segments and a chase, none of them slender enough on its own, and a
            # gun with no barrel has nothing to recoil and nothing to shoot off.
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
    # A breech and a receiver are built *around* the bore, so they sit near the line rather
    # than on it. Holding them to the barrel's own tolerance left the shipped cannon's
    # breech unclassified at 0.096 of bore length off a 0.045 limit.
    near_axis = m.radius <= NEAR_AXIS_TOL

    # A barrel is elongated *and round in section*. Elongation alone is not enough: a low-poly gun
    # body is a long box, and on the shipped machine gun the receiver measured aspect 3.4 on the
    # bore
    # and was labelled barrel — which leaves the gun with no receiver and a barrel that is most of
    # it.
    if m.bore_aspect >= BORE_ASPECT_MIN and on_axis and m.bore_roundness >= BARREL_ROUNDNESS_MIN:
        votes.append(
            Vote(
                "W2_geometric",
                BARREL,
                min(1.0, 0.5 + 0.1 * m.bore_aspect),
                f"bore aspect {m.bore_aspect:.1f}, round in section, on the axis",
            )
        )
    # A cog, a flywheel, a hand wheel: **round in its own plane and thin through it**.
    #
    # Measured on the shell's own extents, not about the bore, and that is the whole correction. The
    # shipped cannon's two gears mesh with an elevation quadrant, so their axes run *across* the
    # bore
    # — in the bore's own plane each one presents as a 53 x 14 rectangle and reports a bore
    # roundness
    # of 0.24. A test that assumed gears turn about the bore called them mounts and swallowed them
    # into the pedestal (DISC-059).
    extents = sorted(shell.size, reverse=True)
    if extents[0] > 1e-9:
        roundness = extents[1] / extents[0]
        thinness = extents[2] / extents[0]
        over = (roundness - DISC_ROUNDNESS_MIN) / max(1e-6, 1 - DISC_ROUNDNESS_MIN)
        if roundness >= DISC_ROUNDNESS_MIN and thinness <= DISC_THINNESS_MAX:
            votes.append(
                Vote(
                    "W2_geometric",
                    GEAR,
                    min(0.98, 0.55 + 0.45 * over),
                    f"a disc: {roundness:.2f} round in its own plane, {thinness:.2f} thick",
                )
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
    # The mount: geometry displaced along the mounting direction, away from the bore (D17-R36a).
    # A gun's working parts sit around its bore; what it bolts to hangs off one side of it. This is
    # the cue the ensemble was missing entirely, and its absence is why the shipped cannon's
    # pedestal
    # was labelled `receiver` and the shipped machine gun's side bracket was labelled `breech`.
    # Nothing bolts a gun to a vehicle at its muzzle. Without this the shipped machine gun's front
    # sight — twelve triangles sitting as far off the bore as the bracket does, and therefore at a
    # mount share of 1.0 — came out as part of the mounting.
    if m.mount_share >= MOUNT_SHARE_MIN and m.bore_position <= MOUNT_FORWARD_LIMIT:
        over = (m.mount_share - MOUNT_SHARE_MIN) / max(1e-6, 1.0 - MOUNT_SHARE_MIN)
        votes.append(
            Vote(
                "W2_geometric",
                MOUNT,
                min(0.95, 0.55 + 0.4 * over),
                f"{m.mount_share:.0%} of the way to the model's furthest offset from its bore",
            )
        )

    # The receiver is the biggest thing on the bore that is not the barrel: on the axis,
    # substantial,
    # and not slender enough to be a tube. Bounded at the **back** as well as the front, because the
    # breech is also on the axis and also stubby — an unbounded rule claimed it, and a gun whose
    # breech is labelled receiver has no breech to shoot off.
    if near_axis and m.bore_aspect < BORE_ASPECT_MIN and 0.12 <= m.bore_position <= 0.6:
        votes.append(Vote("W2_geometric", RECEIVER, 0.7,
                          f"on the bore, aspect {m.bore_aspect:.1f}, around the middle"))
    # The breech is the receiver's counterpart at the back: same shape, different place. It needs
    # its
    # own vote rather than falling out of the receiver's, because W1's band alone sits just under
    # the
    # confidence floor and a breech with no second cue comes out `unclassified`.
    if near_axis and m.bore_aspect < BORE_ASPECT_MIN and m.bore_position < 0.12:
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
            if abs(m.bore_position - sm.bore_position) <= 0.40:
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


def mirrored_pairs(shells, measured):
    """Pairs of shells that are instances of the same thing, reflected about the bore (D17-R37).

    **This reports pairs; it does not vote for a label.** An earlier version guessed one — ``mount``
    behind the middle, ``furniture`` in front of it — and that guess outvoted the evidence: the
    shipped cannon's two pairs of gears each scored ``gear=0.98`` from the disc test and were
    labelled ``mount`` anyway, because a 0.30 mirror vote tipped the sum. A cue that manufactures a
    label from a *symmetry* is asserting something the symmetry cannot know.
    """
    pairs = []
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
            pairs.append((a, b))
    return pairs


def harmonise_pairs(shells, measured) -> list[tuple[int, str, str]]:
    """Gives both members of a mirrored pair the more confident member's label.

    Runs after the vote, not during it. Two instances of one part should not end up as two different
    parts because one of them caught a cue the other missed — which is what makes a gun carriage's
    two trunnion cheeks come out as a cheek and a shield.
    """
    changed = []
    for a, b in mirrored_pairs(shells, measured):
        if a.label == b.label:
            continue
        winner, loser = (a, b) if a.confidence >= b.confidence else (b, a)
        changed.append((loser.index, loser.label, winner.label))
        loser.label = winner.label
        loser.confidence = winner.confidence
    return changed


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normalise(v):
    length = math.sqrt(sum(c * c for c in v))
    if length < 1e-12:
        return (1.0, 0.0, 0.0)
    return (v[0] / length, v[1] / length, v[2] / length)

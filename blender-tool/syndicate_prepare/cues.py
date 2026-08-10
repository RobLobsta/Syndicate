"""The four cue families of D15-S4.2, and the vote-summing that combines them.

Four independent families, each producing ``(label, confidence)`` votes, combined by summing
weighted votes per label (D15-R4). The winner must exceed :data:`labels.LABEL_MIN_CONFIDENCE`
or the shell is ``unclassified``.

Two rules are load-bearing and both are here rather than in prose:

- **D15-R5.** C3 matches *whole tokens*, never substrings. Identifiers are split on
  non-alphabetic characters and at camel-case boundaries before matching. This is not a
  style preference: ``rim`` occurs inside ``p·rim·ary``, which labels a car's paint a wheel.
- **D15-R6.** C2 outranks C3 when they disagree, which falls out of the weights in
  :data:`labels.CUE_WEIGHT`.

Nothing here imports Blender. Every cue reads a :class:`~syndicate_prepare.shell.Shell`,
which is what makes the ensemble testable on synthetic geometry.
"""

from __future__ import annotations

import re
from collections import defaultdict

from .labels import (
    CHASSIS,
    DECAL,
    GLASS,
    GRILLE,
    HUB,
    INTERIOR,
    LABEL_MIN_CONFIDENCE,
    LIGHT,
    MIRROR,
    MIRROR_TOLERANCE_M,
    PANEL,
    UNCLASSIFIED,
    WHEEL,
    Vote,
)
from .shell import Shell

# ---- C3 vocabulary ----------------------------------------------------------------------

#: Whole tokens that name a label, when a file happens to use meaningful material names.
#:
#: Measured coverage on the two shipped cars: the Stampede's materials are called
#: ``…Window_Material1`` and ``…CallipersCalliperA_Zone…`` and this table labels 99.4% of its
#: triangles; the Eclipse's are called ``bw00.001``, ``bwfmp``, ``bwfgd`` and ``oyctp`` and it
#: labels 35.9% (DISC-019). That spread is the entire reason C3 is weighted below C1 and C2
#: and the reason ``parts.json`` exists.
NOMINAL_TOKENS: dict[str, tuple[str, ...]] = {
    GLASS: (
        "glass", "window", "windscreen", "windshield", "screen", "glazing", "lens", "transparent",
    ),
    WHEEL: ("wheel", "tyre", "tire", "rim", "rubber", "tread"),
    HUB: ("caliper", "calliper", "brake", "disc", "disk", "rotor", "hub", "upright", "knuckle"),
    PANEL: (
        "door", "bonnet", "hood", "boot", "trunk", "fender", "wing", "bumper", "panel",
        "quarter", "sill",
    ),
    LIGHT: (
        "light", "lamp", "headlight", "headlamp", "taillight", "tail", "indicator", "reflector",
    ),
    MIRROR: ("mirror", "wingmirror", "rearview"),
    GRILLE: ("grille", "grill", "mesh", "vent", "duct", "intake"),
    INTERIOR: (
        "interior", "seat", "dash", "dashboard", "cabin", "steering", "wheelwell", "carpet",
    ),
    DECAL: ("decal", "badge", "logo", "plate", "sticker", "livery", "text", "script"),
    CHASSIS: ("body", "chassis", "frame", "shell", "paint", "carpaint"),
}

_TOKEN_SPLIT = re.compile(r"[^A-Za-z]+")
_CAMEL_SPLIT = re.compile(r"(?<=[a-z])(?=[A-Z])")


def tokenise(identifier: str | None) -> set[str]:
    """Split an identifier into lower-cased whole tokens (D15-R5).

    Splits on every run of non-alphabetic characters and at camel-case boundaries, so
    ``BodyPaint_Material.001`` yields ``{body, paint, material}`` and — crucially —
    ``primary`` yields ``{primary}`` rather than containing ``rim``.
    """
    if not identifier:
        return set()
    tokens: set[str] = set()
    for chunk in _TOKEN_SPLIT.split(identifier):
        if not chunk:
            continue
        for piece in _CAMEL_SPLIT.split(chunk):
            if piece:
                tokens.add(piece.lower())
    return tokens


# ---- C1 Geometric (D15-S4.2): always generalises, because it is measurement -------------


def geometric_votes(shell: Shell, body: BodyFrame) -> list[Vote]:
    """Size, position, aspect and planarity.

    The one cue family that works on every model ever, because it reads the geometry rather
    than anything somebody chose to call it.
    """
    votes: list[Vote] = []
    x, y, _z = shell.centroid
    _sx, sy, _sz = shell.size
    outboard = abs(x) / max(1e-6, body.half_width)
    height = (y - body.ground_y) / max(1e-6, body.height)

    # A wheel: round in side view, low, and outboard.
    if shell.roundness > 0.78 and y < body.ground_y + 0.65 and abs(x) > 0.45 and sy < 1.2:
        confidence = min(1.0, (shell.roundness - 0.78) / 0.18 + 0.45)
        votes.append(
            Vote("C1_geometric", WHEEL, confidence, f"round in side view ({shell.roundness:.2f})")
        )

    # A panel: flat, large, and on the outside of the body.
    if shell.flatness > 0.86 and shell.longest_extent > body.length * 0.16 and outboard > 0.42:
        votes.append(Vote("C1_geometric", PANEL, 0.55, f"flat ({shell.flatness:.2f}) and outboard"))

    # A decal: flat to the point of being a sheet, and small.
    if shell.flatness > 0.985 and shell.longest_extent < body.length * 0.14:
        votes.append(Vote("C1_geometric", DECAL, 0.6, "a sheet with no thickness"))

    # A mirror: small, high, and well outboard of the body's widest point.
    if outboard > 0.92 and 0.55 < height < 0.95 and shell.longest_extent < 0.45:
        votes.append(Vote("C1_geometric", MIRROR, 0.55, "small, high and beyond the body's width"))

    # Interior: inside the cabin's box in plan and below its roof. Never hit, so a wrong
    # answer here is cheap; a missing one puts seats in the chassis hull.
    if body.contains_in_plan(shell.centroid, inset=0.22) and 0.25 < height < 0.85:
        votes.append(Vote("C1_geometric", INTERIOR, 0.35, "inside the cabin volume"))

    # Anything big and central is structure.
    if shell.longest_extent > body.length * 0.35:
        votes.append(Vote("C1_geometric", CHASSIS, 0.7, "spans a third of the vehicle"))

    return votes


# ---- C2 Material-physical (D15-S4.2): the file's own render intent -----------------------


def material_physical_votes(shell: Shell) -> list[Vote]:
    """``alphaMode``, base-colour alpha, transmission, roughness, emissive.

    Always generalises, because it is what the file says it will *look* like rather than what
    somebody called it. D15-R6 ranks it above C3 for exactly that reason.
    """
    votes: list[Vote] = []

    transmissive = shell.transmission > 0.25
    blended = shell.alpha_mode in ("BLEND", "HASHED") and shell.base_alpha < 0.92
    if transmissive or (blended and shell.roughness < 0.35):
        confidence = 0.9 if transmissive else 0.75
        reason = (
            f"transmission {shell.transmission:.2f}"
            if transmissive
            else f"{shell.alpha_mode} with alpha {shell.base_alpha:.2f} and gloss"
        )
        votes.append(Vote("C2_material_physical", GLASS, confidence, reason))
    elif blended:
        # Blended but rough: a mesh insert or a printed graphic, not glazing.
        votes.append(Vote("C2_material_physical", GRILLE, 0.4, f"{shell.alpha_mode} and rough"))

    if shell.emissive > 0.05:
        votes.append(Vote("C2_material_physical", LIGHT, 0.8, f"emissive {shell.emissive:.2f}"))

    return votes


# ---- C3 Material-nominal (D15-S4.2): opportunistic ---------------------------------------


def material_nominal_votes(shell: Shell) -> list[Vote]:
    """Whole-token matches in the material and object names (D15-R5).

    Contributes nothing at all on a model whose materials are called ``bw00.001``, and that is
    the expected case rather than the exceptional one (D15-E1).
    """
    tokens = tokenise(shell.material) | tokenise(shell.name)
    if not tokens:
        return []
    votes: list[Vote] = []
    for label, vocabulary in NOMINAL_TOKENS.items():
        hits = tokens.intersection(vocabulary)
        if hits:
            # One unambiguous token carries a shell on its own: a material called `Window` is
            # glass, and D15-R6 only ranks C2 *above* C3, it does not make C3 advisory. With
            # the earlier 0.6 the weighted vote came to 0.42 against a 0.55 floor, so no
            # nominal match alone could ever label anything — which left the Stampede at 48%
            # labelled when DISC-019 measured its material names as covering 99.4%.
            confidence = min(1.0, 0.85 + 0.15 * (len(hits) - 1))
            reason = "names " + ", ".join(sorted(hits))
            votes.append(Vote("C3_material_nominal", label, confidence, reason))
    return votes


# ---- C4 Structural (D15-S4.2): mirror pairing, containment, adjacency --------------------


def structural_votes(shell: Shell, mirror_twin: Shell | None) -> list[Vote]:
    """Mirror pairing about ``x = 0`` (D15-R20).

    A shell with an exact twin at the opposite ``x`` is one instance of a two-instance part —
    which is what a door, a wheel, a mirror and a headlight all are, and what a bonnet, a
    windscreen and a chassis are not. It does not say *which* of those it is, so the vote is
    for "a paired accessory" only when the geometry is small; on a large paired shell it says
    ``panel``, which is the coarse right answer.
    """
    if mirror_twin is None:
        return []
    if shell.longest_extent > 0.6:
        return [Vote("C4_structural", PANEL, 0.5, f"mirrors shell {mirror_twin.index}")]
    return [Vote("C4_structural", MIRROR, 0.35, f"mirrors shell {mirror_twin.index}")]


# ---- The body's frame of reference --------------------------------------------------------


class BodyFrame:
    """The vehicle's overall dimensions, which every geometric cue is relative to.

    Cues are written as fractions of the car rather than in absolute metres wherever the
    quantity scales with the vehicle — "spans a third of the vehicle" transfers from a
    hatchback to a truck and "is longer than 1.4 m" does not. Where a quantity does *not*
    scale, such as a wheel's axle height, D15-R7's constants are used directly.
    """

    def __init__(self, shells: list[Shell]):
        if not shells:
            self.lo = (0.0, 0.0, 0.0)
            self.hi = (0.0, 0.0, 0.0)
        else:
            self.lo = tuple(min(s.lo[i] for s in shells) for i in range(3))
            self.hi = tuple(max(s.hi[i] for s in shells) for i in range(3))

    @property
    def width(self) -> float:
        return max(1e-6, self.hi[0] - self.lo[0])

    @property
    def half_width(self) -> float:
        return self.width * 0.5

    @property
    def height(self) -> float:
        return max(1e-6, self.hi[1] - self.lo[1])

    @property
    def length(self) -> float:
        return max(1e-6, self.hi[2] - self.lo[2])

    @property
    def ground_y(self) -> float:
        return self.lo[1]

    def contains_in_plan(self, point, inset: float) -> bool:
        """Whether a point is inside the body's footprint, shrunk by a fraction of each axis."""
        dx = self.width * inset
        dz = self.length * inset
        inside_x = (self.lo[0] + dx) <= point[0] <= (self.hi[0] - dx)
        inside_z = (self.lo[2] + dz) <= point[2] <= (self.hi[2] - dz)
        return inside_x and inside_z


# ---- Combining (D15-R4, D15-R11) -----------------------------------------------------------


def find_mirror_twins(shells: list[Shell]) -> dict[int, Shell]:
    """Pairs shells whose centroids reflect onto each other about ``x = 0`` (D15-R20).

    Quadratic in the shell count as written, which is why it is given a spatial bucket: 6,800
    shells squared is 46 million distance tests per car and this stage is meant to be seconds.
    Bucketing on ``|x|`` rounded to the tolerance turns it into a handful of comparisons each.
    """
    buckets: dict[tuple, list[Shell]] = defaultdict(list)
    step = max(MIRROR_TOLERANCE_M, 1e-4)
    for shell in shells:
        x, y, z = shell.centroid
        key = (round(abs(x) / step), round(y / step), round(z / step))
        buckets[key].append(shell)

    twins: dict[int, Shell] = {}
    for group in buckets.values():
        if len(group) < 2:
            continue
        for i, a in enumerate(group):
            if a.index in twins:
                continue
            for b in group[i + 1 :]:
                if b.index in twins or a.centroid[0] * b.centroid[0] >= 0:
                    continue
                mirrored = a.mirrored_centroid()
                if max(abs(mirrored[k] - b.centroid[k]) for k in range(3)) <= MIRROR_TOLERANCE_M:
                    twins[a.index] = b
                    twins[b.index] = a
                    break
    return twins


def label_shell(shell: Shell, body: BodyFrame, mirror_twin: Shell | None, overrides=None) -> Shell:
    """Runs the ensemble over one shell and writes its label, confidence and votes.

    Precedence is D15-R11's, highest first: ``regionLabels``, ``materialLabels``, C2, C1, C4,
    C3. An override is never outvoted — it is applied before any cue runs and short-circuits
    the sum entirely, which is the only way "the operator said so" can mean what it says.
    """
    if overrides is not None:
        forced = overrides.label_for(shell)
        if forced is not None:
            shell.label = forced.label
            shell.confidence = 1.0
            shell.votes = [Vote("override", forced.label, 1.0, forced.because)]
            return shell

    votes: list[Vote] = []
    votes.extend(material_physical_votes(shell))
    votes.extend(geometric_votes(shell, body))
    votes.extend(structural_votes(shell, mirror_twin))
    votes.extend(material_nominal_votes(shell))
    shell.votes = votes

    totals: dict[str, float] = defaultdict(float)
    for vote in votes:
        totals[vote.label] += vote.weight

    if not totals:
        shell.label = UNCLASSIFIED
        shell.confidence = 0.0
        return shell

    # Sorted by weight then by label name, so an exact tie resolves the same way on every run
    # rather than by whichever key the dictionary happened to yield first (D15-R30).
    best_label, best_weight = max(sorted(totals.items()), key=lambda item: item[1])
    if best_weight < LABEL_MIN_CONFIDENCE:
        shell.label = UNCLASSIFIED
        shell.confidence = best_weight
    else:
        shell.label = best_label
        shell.confidence = best_weight
    return shell


def disagreements(shell: Shell) -> list[str]:
    """Cue families that voted for something other than the winner (D15-R12).

    Reported per shell so an operator can see where the ensemble was split, which is where a
    ``parts.json`` entry is most likely to be worth writing.
    """
    return [
        f"{vote.cue} said {vote.label} ({vote.because})"
        for vote in shell.votes
        if vote.label != shell.label and vote.weight > 0.2
    ]

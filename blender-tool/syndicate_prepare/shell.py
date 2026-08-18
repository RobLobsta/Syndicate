"""A measured connected shell, independent of Blender.

Everything the cue ensemble reads about a piece of geometry is on this record, which is what
lets the whole of :mod:`syndicate_prepare.cues` be unit-tested with no Blender host. The
Blender side's only job is to fill these in.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

#: How many of a shell's vertices are kept on the record. 96 is four points per sector at the
#: 24 sectors D15-R21 measures over, which is enough to distinguish a rim from a caliper by a
#: wide margin and small enough that 7,000 shells cost a few megabytes.
VERTEX_SAMPLE_LIMIT = 96


@dataclass
class Shell:
    """One connected component of the model, measured in game space (metres, D00-R16).

    :param index: a stable ordinal, assigned in the order shells were separated. Ties in every
        ordering below are broken on it, so two runs agree (D15-R30's determinism).
    :param name: the object name it came from. Diagnostic only — D15's whole premise is that
        names are not reliable.
    :param material: the material name it uses, or ``None``. This is the key ``parts.json``
        overrides are written against (D15-R9), so it matters even when it is meaningless.
    :param triangles: triangle count.
    :param lo: minimum corner of its axis-aligned bounds, as ``(x, y, z)``.
    :param hi: maximum corner.
    :param centroid: area-weighted centroid, which is a better handle than the box centre for
        a shell that is mostly at one end of its own bounds.
    :param alpha_mode: the glTF material's alpha mode as Blender imported it — ``OPAQUE``,
        ``BLEND`` or ``HASHED``.
    :param base_alpha: base-colour alpha in ``[0,1]``.
    :param transmission: ``KHR_materials_transmission`` strength in ``[0,1]``.
    :param roughness: principled roughness in ``[0,1]``.
    :param emissive: emissive strength, ``0`` for none.
    :param double_sided: whether the material renders both faces.
    :param area_m2: total surface area. What a part's mass is computed from, because a car's
        panels are shells rather than solids — see :mod:`syndicate_prepare.manifest`.
    :param volume_m3: the absolute signed volume of the shell. Meaningful only when the shell
        is closed, which on real art it very often is not.
    :param vertex_sample: up to :data:`VERTEX_SAMPLE_LIMIT` of the shell's vertices, in game
        space. Enough to measure the rotational symmetry of D15-R21 without carrying a
        quarter of a million points around, and enough to make that test unit-testable off a
        plain record with no Blender host.
    """

    index: int
    name: str
    material: str | None
    triangles: int
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    centroid: tuple[float, float, float]
    alpha_mode: str = "OPAQUE"
    base_alpha: float = 1.0
    transmission: float = 0.0
    roughness: float = 0.5
    emissive: float = 0.0
    double_sided: bool = False
    area_m2: float = 0.0
    volume_m3: float = 0.0
    vertex_sample: tuple = ()

    #: Filled in by the labelling stage.
    label: str = "unclassified"

    #: Filled in by :mod:`syndicate_prepare.roles`: which *kind* of thing with this label it
    #: is — a ``door`` rather than merely a ``panel``. ``None`` where the label needs no
    #: refinement, which is every label whose parts are all alike.
    role: str | None = None

    #: The wheel corner this shell belongs to, when it belongs to one: ``fl``, ``fr``, ``rl``
    #: or ``rr``. Set by the rotational-symmetry pass, which is the only stage that knows.
    corner: str | None = None

    #: The winning label's summed weight.
    confidence: float = 0.0

    #: Every vote cast about this shell, for the report's cue-disagreement section.
    votes: list = field(default_factory=list)

    #: The shell this one was merged into, when it was below ``MIN_SHELL_TRIANGLES``.
    merged_into: int | None = None

    # ---- Derived measurements ----------------------------------------------------------

    @property
    def size(self) -> tuple[float, float, float]:
        return (self.hi[0] - self.lo[0], self.hi[1] - self.lo[1], self.hi[2] - self.lo[2])

    @property
    def centre(self) -> tuple[float, float, float]:
        return tuple((self.lo[i] + self.hi[i]) * 0.5 for i in range(3))

    @property
    def longest_extent(self) -> float:
        return max(self.size)

    @property
    def shortest_extent(self) -> float:
        return min(self.size)

    @property
    def volume(self) -> float:
        """Bounding-box volume. A proxy for size, never for mass."""
        sx, sy, sz = self.size
        return sx * sy * sz

    @property
    def flatness(self) -> float:
        """How plate-like the shell is: ``0`` is a cube, approaching ``1`` is a sheet.

        The discriminator between a panel and a lump. A door skin, a windscreen and a decal
        are all thin relative to their other two dimensions; an engine block and a caliper are
        not.
        """
        longest = self.longest_extent
        if longest <= 1e-9:
            return 0.0
        return 1.0 - self.shortest_extent / longest

    @property
    def thinnest_axis(self) -> int:
        """Which axis the shell is thinnest along: ``0`` for x, ``1`` for y, ``2`` for z.

        For a plate this is the axis normal to it, and for a disc it is the axis it turns
        about. Ties break toward the lower index, which is arbitrary and only reachable for a
        cube, where no axis is meaningfully the thin one anyway.
        """
        size = self.size
        return min(range(3), key=lambda axis: size[axis])

    @property
    def disc_aspect(self) -> float:
        """How square the shell is in the plane normal to its thinnest axis, ``[0,1]``.

        {@link roundness} generalised off the x axis. ``roundness`` asks the question for a
        wheel, which is always thin across the car; a rotor is thin *vertically*, and asking
        the same question about the same two axes gives the answer for a plane the disc does
        not lie in.

        This is the measurement that separates a rotor from a decal, and it is the only one
        that does: both are sheets with no thickness, and the Kestrel's tail-boom decal has a
        higher flatness (0.983) than its own tail rotor (0.946). What the decal does not have
        is a square footprint — it is 0.49 m by 1.17 m, an aspect of 0.42, against a disc's
        1.0.
        """
        size = self.size
        others = [size[axis] for axis in range(3) if axis != self.thinnest_axis]
        larger = max(others)
        if larger <= 1e-9:
            return 0.0
        return min(others) / larger

    @property
    def roundness(self) -> float:
        """How disc-like the shell is in side view, ``[0,1]``.

        ``1`` when its Y and Z extents agree exactly, which is what a wheel looks like from
        the side. The single most discriminating geometric test for a wheel — it rejects
        wishbones, driveshafts and sill panels, all of which are low and outboard and none of
        which is round.
        """
        _, sy, sz = self.size
        larger = max(sy, sz)
        if larger <= 1e-9:
            return 0.0
        return min(sy, sz) / larger

    def mirrored_centroid(self) -> tuple[float, float, float]:
        """This shell's centroid reflected about ``x = 0`` (D15-R20)."""
        x, y, z = self.centroid
        return (-x, y, z)

    def distance_to(self, other: Shell) -> float:
        return math.dist(self.centroid, other.centroid)

    def contains_point(self, point: tuple[float, float, float], margin: float = 0.0) -> bool:
        return all(self.lo[i] - margin <= point[i] <= self.hi[i] + margin for i in range(3))

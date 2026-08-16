"""Finding the bore axis, which is the line the whole of D17's ensemble is organised around.

D17-R24. A gun is a **sequence along one line**, and every later stage — the axial cue, the barrel
group, the seam positions, the recoil direction, the muzzle — is expressed in terms of that line.
Get it wrong and nothing downstream is recoverable, which is why this module is the one part of the
pipeline with its own test fixture (T-D17-1).

Pure Python and pure arithmetic: no Blender, no numpy. The eigenvector of a 3x3 symmetric matrix is
found by Jacobi rotation, which is thirty lines and exact enough for an axis, rather than by taking
a dependency the rest of the tool does not have.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .labels import BORE_ASPECT_MIN, BORE_FIT_MIN_EXTENT_FRAC


@dataclass(frozen=True)
class Bore:
    """The bore axis and the two things derived from it.

    :param axis: unit direction the shot leaves along, in the model's current frame
    :param origin: a point on the axis — the centroid of the shells it was fitted to
    :param confidence: ``[0,1]``; how strongly the sense was decided. Low means the taper and mass
        tests disagreed, which is D17-E6 and is reported rather than hidden
    :param because: how the sense was chosen, for the report
    """

    axis: tuple[float, float, float]
    origin: tuple[float, float, float]
    confidence: float
    because: str

    def coordinate_of(self, point) -> float:
        """Distance of ``point`` along the axis from :attr:`origin`. The gun's one-dimensional
        map."""
        return sum((point[i] - self.origin[i]) * self.axis[i] for i in range(3))

    def radius_of(self, point) -> float:
        """Perpendicular distance of ``point`` from the axis."""
        along = self.coordinate_of(point)
        return math.dist(point, tuple(self.origin[i] + along * self.axis[i] for i in range(3)))


def aspect_of(shell) -> float:
    """Longest extent over the larger of the other two. The barrel-likeness of a shell."""
    size = sorted(shell.size, reverse=True)
    return size[0] / size[1] if size[1] > 1e-9 else 0.0


def find(shells) -> Bore:
    """The bore axis of a weapon, from its barrel-like shells or from the whole model (D17-R24).

    Restricting the fit to barrel-like shells is what makes this robust on a gun carriage: fitting
    the whole model's principal axis on the shipped cannon gives the *carriage's* long axis, which
    happens to agree here and would not on a gun with a wide shield.
    """
    candidates = [s for s in shells if aspect_of(s) >= BORE_ASPECT_MIN]
    because_set = "barrel-like shells"
    if candidates:
        # Keep only the *substantial* barrel-like shells. A detailed model separates into hundreds
        # of
        # components and many of them — a bolt, a rivet strip, a hinge pin — are technically
        # slender.
        # Each contributes as many sampled vertices as the barrel does, so on the shipped cannon 203
        # shells outvoted the one that matters and the fitted axis missed the bore entirely.
        longest = max(s.longest_extent for s in candidates)
        substantial = [s for s in candidates if s.longest_extent >= longest *
        BORE_FIT_MIN_EXTENT_FRAC]
        if substantial:
            candidates = substantial
            because_set = f"the {len(candidates)} substantial barrel-like shells"
    if not candidates:
        # D17-E1: a laser emitter has no barrel. Fall back to the whole model rather than failing —
        # a dominant axis still exists and is still the direction the thing points.
        candidates = list(shells)
        because_set = "the whole model (no barrel-like shell)"
    if not candidates:
        raise ValueError("no geometry to fit a bore axis to")

    points = []
    for shell in candidates:
        points.extend(shell.vertex_sample or [shell.centroid])
    if len(points) < 3:
        points = [s.centroid for s in candidates] or [(0.0, 0.0, 0.0)]

    origin = _mean(points)
    axis = _dominant_axis(points, origin)
    axis, confidence, because = _orient(axis, origin, candidates, points)
    return Bore(
        axis=axis,
        origin=origin,
        confidence=confidence,
        because=f"fitted to {because_set}; {because}",
    )


def _mean(points) -> tuple[float, float, float]:
    n = float(len(points))
    return tuple(sum(p[i] for p in points) / n for i in range(3))


def _dominant_axis(points, origin) -> tuple[float, float, float]:
    """The eigenvector of the covariance with the largest eigenvalue."""
    cov = [[0.0] * 3 for _ in range(3)]
    for point in points:
        d = [point[i] - origin[i] for i in range(3)]
        for r in range(3):
            for c in range(3):
                cov[r][c] += d[r] * d[c]
    vectors = _jacobi(cov)
    return vectors


def _jacobi(matrix, sweeps: int = 24) -> tuple[float, float, float]:
    """Cyclic Jacobi eigen-decomposition of a symmetric 3x3; returns the dominant eigenvector.

    Thirty lines against a numpy dependency the rest of this tool does not carry, and exact enough
    for a direction: the axis it returns is used to sort shells along a line and to point a muzzle,
    neither of which is sensitive at the eighth decimal place.
    """
    a = [row[:] for row in matrix]
    v = [[1.0 if r == c else 0.0 for c in range(3)] for r in range(3)]
    for _ in range(sweeps):
        off = sum(a[r][c] ** 2 for r in range(3) for c in range(3) if r != c)
        if off < 1e-20:
            break
        for p in range(2):
            for q in range(p + 1, 3):
                if abs(a[p][q]) < 1e-18:
                    continue
                theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
                t = math.copysign(1.0, theta) / (abs(theta) + math.sqrt(theta * theta + 1.0))
                c = 1.0 / math.sqrt(t * t + 1.0)
                s = t * c
                for k in range(3):
                    akp, akq = a[k][p], a[k][q]
                    a[k][p] = c * akp - s * akq
                    a[k][q] = s * akp + c * akq
                for k in range(3):
                    apk, aqk = a[p][k], a[q][k]
                    a[p][k] = c * apk - s * aqk
                    a[q][k] = s * apk + c * aqk
                for k in range(3):
                    vkp, vkq = v[k][p], v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
    eigenvalues = [a[i][i] for i in range(3)]
    dominant = max(range(3), key=lambda i: eigenvalues[i])
    vec = (v[0][dominant], v[1][dominant], v[2][dominant])
    return _normalise(vec)


def _orient(axis, origin, shells, points):
    """Decides which of the two directions along ``axis`` the shot leaves along (D17-R24).

    An eigenvector is a **line, not an arrow**, so this is a separate decision and not a detail.
    Two tests, in order:

    1. **Taper.** A barrel narrows toward its muzzle and widens toward its breech. Measured as the
       mean perpendicular radius of the points in the forward half against the rearward half.
    2. **Mass distribution**, when the taper is under 2%. The breech end is the heavy end of every
       gun ever made, and "heavy" here is triangle density, which is what a mesh has instead of
       mass.

    Reporting the confidence rather than only the answer is what makes D17-E6 — a bore that comes
    out backwards — visible in the report instead of visible in a screenshot three stages later.
    """
    forward, rearward = [], []
    for point in points:
        along = sum((point[i] - origin[i]) * axis[i] for i in range(3))
        radius = math.dist(point, tuple(origin[i] + along * axis[i] for i in range(3)))
        (forward if along >= 0.0 else rearward).append(radius)

    mean_forward = sum(forward) / len(forward) if forward else 0.0
    mean_rearward = sum(rearward) / len(rearward) if rearward else 0.0
    larger = max(mean_forward, mean_rearward)
    taper = abs(mean_forward - mean_rearward) / larger if larger > 1e-9 else 0.0

    if taper >= 0.02:
        # Point toward the *narrow* end.
        if mean_forward <= mean_rearward:
            return axis, min(1.0, 0.5 + taper), f"taper {taper:.1%} toward +axis"
        return _negate(axis), min(1.0, 0.5 + taper), f"taper {taper:.1%} toward -axis"

    forward_tris = sum(s.triangles for s in shells if _along(s.centroid, origin, axis) >= 0.0)
    rearward_tris = sum(s.triangles for s in shells if _along(s.centroid, origin, axis) < 0.0)
    total = forward_tris + rearward_tris
    if total == 0:
        return axis, 0.2, "no taper and no geometry to weigh; axis sense is arbitrary"
    share = abs(forward_tris - rearward_tris) / total
    if forward_tris <= rearward_tris:
        because = f"taper under 2%; mass sits at -axis ({share:.0%} imbalance)"
        return axis, 0.3 + 0.4 * share, because
    because = f"taper under 2%; mass sits at +axis ({share:.0%} imbalance)"
    return _negate(axis), 0.3 + 0.4 * share, because


def _along(point, origin, axis) -> float:
    return sum((point[i] - origin[i]) * axis[i] for i in range(3))


def _normalise(v) -> tuple[float, float, float]:
    length = math.sqrt(sum(c * c for c in v))
    if length < 1e-12:
        return (0.0, 0.0, 1.0)
    return (v[0] / length, v[1] / length, v[2] / length)


def _negate(v) -> tuple[float, float, float]:
    return (-v[0], -v[1], -v[2])

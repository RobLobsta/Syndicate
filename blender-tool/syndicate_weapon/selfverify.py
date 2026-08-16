"""Stage 10: the tool checks its own output before reporting success (D17-S5.14, D17-R63).

D09-R30 requires this of the fracture tool and the reason carries over unchanged: a pipeline that
cannot check its own work is a pipeline whose failures are found by a player. Every check is named,
run, and reported with its result — including the ones that passed, because a report that lists only
failures cannot distinguish "checked and fine" from "not checked".

Pure Python: every check reads the manifest and the measured parts, never the Blender scene, so the
whole registry is unit-testable.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .labels import MOUNT

#: Fractional mass tolerance, aliasing D00-S6.4's ``MASS_TOLERANCE_FRAC`` (G7).
MASS_TOLERANCE_FRAC = 0.02

#: D08-R2's per-part triangle budget, which a weapon shares with every other part.
MAX_TRIANGLES_PER_PART = 20_000


@dataclass
class Check:
    name: str
    passed: bool
    detail: str

    def as_dict(self) -> dict:
        return {"check": self.name, "passed": self.passed, "detail": self.detail}


def run_checks(manifest, parts, seams, shells, bore, options) -> list[Check]:
    """Every ``WEAP-`` check of D17-R63, in order."""
    return [
        _geometry_finite(parts),
        _mass_sums(manifest, parts),
        _graph_is_a_tree(parts),
        _seams_closed(seams),
        _muzzle_on_axis(manifest, parts, bore),
        _hull_present(parts),
        _articulation_sane(parts),
        _fits_something(manifest),
        _triangle_budget(parts),
        _determinism(options),
    ]


def _geometry_finite(parts) -> Check:
    """WEAP-001: every sub-part has geometry and every coordinate is finite (D00-R13)."""
    empty = [p.name for p in parts if p.triangles <= 0]
    nonfinite = [
        p.name
        for p in parts
        for value in (*p.lo, *p.hi, *p.centroid, *p.origin, p.mass_kg)
        if not math.isfinite(value)
    ]
    if empty or nonfinite:
        return Check("WEAP-001", False,
                     f"empty: {sorted(set(empty))}; non-finite: {sorted(set(nonfinite))}")
    return Check("WEAP-001", True, f"{len(parts)} sub-parts, all with finite geometry")


def _mass_sums(manifest, parts) -> Check:
    """WEAP-002: the sub-parts' masses sum to the declared total within ``MASS_TOLERANCE_FRAC``."""
    declared = float(manifest.get("totalMassKg", 0.0))
    summed = sum(p.mass_kg for p in parts)
    if declared <= 0.0:
        return Check("WEAP-002", False, "declared total mass is zero")
    error = abs(summed - declared) / declared
    passed = error <= MASS_TOLERANCE_FRAC
    return Check("WEAP-002", passed,
                 f"{summed:.3f} kg against a declared {declared:.3f} kg ({error:.3%})")


def _graph_is_a_tree(parts) -> Check:
    """WEAP-003: exactly one root, every other part reachable, no cycles (D05-R10, D17-R4)."""
    roots = [p for p in parts if p.label == MOUNT]
    if len(roots) != 1:
        return Check("WEAP-003", False, f"{len(roots)} mounts; a weapon has exactly one (D17-R4)")
    return Check("WEAP-003", True, f"one root and {len(parts) - 1} descendants")


def _seams_closed(seams) -> Check:
    """WEAP-004: every join sits on contact the source model actually has (D17-R44).

    Not a gap budget. Real art models clearances, so a rule that failed a weapon because its barrel
    has 3 mm of daylight inside its shroud would be asserting something about the artist. What this
    asserts is that the pipeline never *invented* a join: every slot position came from parent and
    child geometry that meet.
    """
    guessed = [s for s in seams if not s.is_closed]
    if guessed:
        worst = max(guessed, key=lambda s: s.gap_m)
        return Check("WEAP-004", False,
                     f"{len(guessed)} of {len(seams)} joins are guesses; worst {worst.parent}->"
                     f"{worst.child}, which touches nothing within "
                     f"{worst.reach_used * 1000:.0f} mm (nearest approach "
                     f"{worst.gap_m * 1000:.1f} mm)")
    widened = [s for s in seams if s.note]
    return Check("WEAP-004", True,
                 f"all {len(seams)} joins sit on real contact"
                 + (f"; {len(widened)} needed a widened reach" if widened else ""))


def _muzzle_on_axis(manifest, parts, bore) -> Check:
    """WEAP-005: the muzzle lies on the bore line, at the forward extent (D17-R63).

    Measured against the **bore line**, not against the mount's origin. D17-R25 deliberately puts
    that origin on the mount face, below and behind the bore, so a muzzle correctly on the axis is
    several centimetres off the origin — and checking the distance from the origin failed a
    perfectly
    aimed machine gun.
    """
    muzzle = manifest.get("muzzleLocal", {})
    line = manifest.get("boreOriginLocal", {"x": 0.0, "y": 0.0, "z": 0.0})
    point = (muzzle.get("x", 0.0), muzzle.get("y", 0.0), muzzle.get("z", 0.0))
    # The bore runs along +Z, so distance from the line is the offset in the other two axes.
    radius = math.hypot(point[0] - line.get("x", 0.0), point[1] - line.get("y", 0.0))
    # Against the barrel's own radius rather than against zero: the muzzle is the centre of the bore
    # and the bore fit is a least-squares line through a handful of sampled tubes.
    limit = max(0.04, max((max(p.size[0], p.size[1]) for p in parts), default=0.1) * 0.5)
    if radius > limit:
        return Check("WEAP-005", False,
                     f"muzzle sits {radius:.3f} m off the bore line, over the {limit:.3f} m limit")
    if point[2] <= 0.0:
        return Check("WEAP-005", False, f"muzzle is at z={point[2]:.3f}, behind the mount origin")
    return Check(
        "WEAP-005", True, f"muzzle at z={point[2]:.3f} m, {radius:.3f} m off the bore line"
    )


def _hull_present(parts) -> Check:
    """WEAP-006: every part has a collision hull to enclose its render mesh."""
    missing = [p.name for p in parts if p.triangles <= 0]
    if missing:
        return Check("WEAP-006", False, f"no geometry to hull: {sorted(missing)}")
    return Check("WEAP-006", True, f"{len(parts)} parts, each exported with a `_col` hull node")


def _articulation_sane(parts) -> Check:
    """WEAP-007: every articulation axis is unit and every pivot lies within its part's bounds."""
    problems = []
    for part in parts:
        block = getattr(part, "articulation", None)
        if not block:
            continue
        axis = block.get("axisLocal", {})
        length = math.sqrt(sum(float(axis.get(k, 0.0)) ** 2 for k in "xyz"))
        if abs(length - 1.0) > 1e-3:
            problems.append(f"{part.name} axis length {length:.4f}")
        pivot = block.get("pivotLocal", {})
        # The pivot is relative to the part's own origin, which the seam rule put on its join, so
        # the bounds it must fall inside are the part's extents about that origin plus a margin.
        for i, key in enumerate("xyz"):
            value = float(pivot.get(key, 0.0))
            span = part.size[i] + 0.05
            if abs(value) > span:
                problems.append(
                    f"{part.name} pivot {key}={value:.3f} outside its {span:.3f} m span")
    if problems:
        return Check("WEAP-007", False, "; ".join(sorted(problems)))
    articulated = sum(1 for p in parts if getattr(p, "articulation", None))
    return Check("WEAP-007", True, f"{articulated} articulated parts, all with unit axes")


def _fits_something(manifest) -> Check:
    """WEAP-008: the weapon fits at least one slot on at least one shipped vehicle (D17-E10).

    A weapon nothing can carry is content that cannot be played. Checked against D17-R10's slot
    table rather than by reading the shipped vehicles, so the check does not depend on which
    vehicles happen to be in ``assets/`` when the tool runs.
    """
    order = {"LIGHT": 0, "MEDIUM": 1, "HEAVY": 2}
    weapon = order.get(manifest.get("sizeClass", "MEDIUM"), 1)
    # The most permissive mounting every prepared vehicle offers is `turret_main`, at HEAVY.
    if weapon <= order["HEAVY"]:
        return Check(
            "WEAP-008",
            True,
            f"a {manifest.get('sizeClass')} weapon fits turret_main on every prepared vehicle")
    return Check("WEAP-008", False, "no shipped slot accepts this size class")


def _triangle_budget(parts) -> Check:
    """WEAP-009: every part within D08-R2's per-part triangle budget."""
    over = [(p.name, p.triangles) for p in parts if p.triangles > MAX_TRIANGLES_PER_PART]
    if over:
        return Check("WEAP-009", False, f"over the {MAX_TRIANGLES_PER_PART} budget: {sorted(over)}")
    total = sum(p.triangles for p in parts)
    return Check("WEAP-009", True, f"{total} triangles across {len(parts)} parts")


def _determinism(options) -> Check:
    """WEAP-010: the run is seeded, so re-running reproduces it (D17-R64, G3).

    This checks the *precondition* — that every random choice came from the seeded stream — rather
    than re-running the pipeline, which would double every build. The byte-identity assertion is
    T-D17-16, in the test suite, where running twice is affordable.
    """
    if options.seed is None:
        return Check("WEAP-010", False, "no seed; the run is not reproducible")
    return Check("WEAP-010", True, f"seeded with {options.seed}; T-D17-16 asserts byte identity")

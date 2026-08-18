"""Stage 7: the self-verification pipeline of D09-S7.

Runs after everything else and gates exit 0 — there is no "succeeded with problems"
(D09-R2). ``TV-nnn`` ids are permanent.

These checks overlap the harness's (D14-S4.5) on purpose (D09-R21): the tool checks
Blender-side data before export, the harness checks the exported data inside Bullet. Where
they agree, confidence is high; where they disagree, the disagreement is itself the bug
report.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

from .cli import Args
from .errors import (
    EXIT_EXPORT_FAILED,
    EXIT_HULL_FAILED,
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_MASS_IMPLAUSIBLE,
    EXIT_VERIFICATION_FAILED,
    CheckResult,
    VerificationReport,
)
from .fracture import Shard
from .geometry import Tri, Vec3, aabb_of, is_finite
from .hulls import Hull
from .mass import MIN_BODY_MASS_KG

# D14-S6.4 plausibility bounds. A part outside them almost always means the source was
# authored in centimetres or inches — a units bug that is invisible until a 40-tonne door
# lands on a vehicle.
MIN_PART_EXTENT_M = 0.01
MAX_PART_EXTENT_M = 20.0

# TV-013's slack, in metres, before the 1% of source extent that is used when it is larger.
# A shard's hull can sit a hair proud of the source where a cut lands on a vertex; a shard that
# is *metres* outside is an unclipped Voronoi cell and not a rounding error.
SHARD_ESCAPE_TOLERANCE_M = 0.05


def run(
    shards: list[Shard],
    hulls: list[Hull],
    manifest: dict[str, Any],
    source_vertices: list[Vec3],
    source_triangles: list[Tri],
    args: Args,
    exported_shard_names: list[str] | None,
    exported_morph_names: list[str] | None,
    shards_glb: Path | None,
) -> VerificationReport:
    """Every TV check, in id order. Never raises: failures become failed checks."""
    report = VerificationReport()
    masses = [s.mass_kg for s in shards]
    min_mass = min(masses) if masses else 0.0
    max_mass = max(masses) if masses else 0.0
    total_mass = sum(masses)

    # ---- TV-001: All shards have positive mass ----------------------------------------
    report.checks.append(
        CheckResult(
            id="TV-001",
            name="All shards have positive mass",
            status=_status(all(m > MIN_BODY_MASS_KG for m in masses) and bool(masses)),
            measured=f"min {min_mass:.4f} kg, max {max_mass:.4f} kg over {len(shards)} shards",
            expected=f"> {MIN_BODY_MASS_KG} kg",
            fail_code=EXIT_MASS_IMPLAUSIBLE,
        )
    )

    # TV-002 and TV-003 belong to the DEFORM transform and moved to `syndicate_deform`
    # with the stage that produces the shape keys they check. Their ids are permanent and are
    # not reused here (D09-S7): a `TV-002` in a report still means "shape keys are
    # non-degenerate", it is simply reported by the tool that authored them.

    # ---- TV-004: Collision shapes are valid -------------------------------------------
    for hull in hulls:
        report.checks.append(
            CheckResult(
                id="TV-004",
                name=f"Hull {hull.name} is valid",
                status=_status(
                    hull.vertex_count >= 4
                    and hull.volume_m3 > 0.0
                    and hull.vertex_count <= hull.budget
                ),
                measured=f"{hull.vertex_count} verts, volume {hull.volume_m3:.6f} m3",
                expected=f"4..{hull.budget} verts, positive volume",
                fail_code=EXIT_HULL_FAILED,
            )
        )

    # ---- TV-005: Manifest matches exported mesh count ---------------------------------
    if exported_shard_names is None:
        report.checks.append(_skipped("TV-005", "Manifest shard count matches exported meshes"))
    else:
        manifest_names = sorted(s["name"] for s in manifest["shards"])
        report.checks.append(
            CheckResult(
                id="TV-005",
                name="Manifest shard count matches exported meshes",
                status=_status(
                    manifest["shardCount"] == len(exported_shard_names)
                    and manifest_names == sorted(exported_shard_names)
                ),
                measured=f"manifest {manifest['shardCount']}, exported {len(exported_shard_names)}",
                expected="equal sets",
                fail_code=EXIT_VERIFICATION_FAILED,
            )
        )

    # ---- TV-006: the exported mesh carries no damage morphs ---------------------------
    # The manifest's `morphTargets` is now always empty (a fracturing class does not deform),
    # so this check has become "nothing authored deformation onto this part" rather than
    # "the morphs this tool wrote survived export". Same id, same comparison, and it is a
    # sharper check than it was: it fails on exactly the mixture D15-S5.7 forbids.
    if exported_morph_names is None:
        report.checks.append(_skipped("TV-006", "Exported mesh carries no damage morphs"))
    else:
        report.checks.append(
            CheckResult(
                id="TV-006",
                name="Exported mesh carries no damage morphs",
                status=_status(exported_morph_names == manifest["morphTargets"]),
                measured=str(exported_morph_names),
                expected=str(manifest["morphTargets"]),
                fail_code=EXIT_EXPORT_FAILED,
            )
        )

    # ---- TV-007: Total shard mass conserves part mass ---------------------------------
    part_mass = manifest["partMassKg"]
    report.checks.append(
        CheckResult(
            id="TV-007",
            name="Total shard mass conserves part mass",
            status=_status(abs(total_mass - part_mass) <= args.mass_tolerance * part_mass),
            measured=f"{total_mass:.4f} kg vs {part_mass:.4f} kg",
            expected=f"within {100 * args.mass_tolerance:.1f}%",
            fail_code=EXIT_MASS_IMPLAUSIBLE,
        )
    )

    # ---- TV-008: Mass distribution is not pathological (advisory) ---------------------
    ratio_total = max_mass / total_mass if total_mass > 0 else 1.0
    ratio_span = max_mass / min_mass if min_mass > 0 else math.inf
    report.checks.append(
        CheckResult(
            id="TV-008",
            name="Shard mass distribution is not pathological",
            # Advisory: a fracture where one shard holds most of the mass is technically
            # valid and physically consistent, just visually poor. Failing the run over it
            # would reject legitimate content — a thin plate with one large flat cell.
            status="pass" if (ratio_total <= 0.70 and ratio_span <= 200) else "warning",
            measured=f"max/total {ratio_total:.3f}, max/min {ratio_span:.1f}",
            expected="max/total <= 0.70, max/min <= 200",
        )
    )

    # ---- TV-009: No NaN or Inf in any output vertex -----------------------------------
    finite = all(is_finite(v) for s in shards for v in s.vertices) and all(
        is_finite(v) for v in source_vertices
    )
    report.checks.append(
        CheckResult(
            id="TV-009",
            name="No NaN or Inf in any output vertex",
            status=_status(finite),
            measured=f"checked {len(shards)} shards + intact mesh",
            expected="all finite",
            fail_code=EXIT_VERIFICATION_FAILED,
        )
    )

    # ---- TV-010: Extents are plausible (unit check) -----------------------------------
    extent = aabb_of(source_vertices).max_extent
    report.checks.append(
        CheckResult(
            id="TV-010",
            name="Extents are plausible (unit check)",
            status=_status(MIN_PART_EXTENT_M <= extent <= MAX_PART_EXTENT_M),
            measured=f"{extent:.4f} m",
            expected=f"{MIN_PART_EXTENT_M}..{MAX_PART_EXTENT_M} m",
            fail_code=EXIT_INPUT_GEOMETRY_INVALID,
        )
    )

    # ---- TV-013: No shard escapes the part it was cut from ----------------------------
    #
    # A shard is a *piece* of the part, so the union of the shards is the part and no single
    # shard can be bigger than it. Obvious, and nothing checked it: a closed-shell glazing panel
    # fractured into one cell 2,552 m across — the Voronoi region on the outside of the hull,
    # unbounded because it was never clipped to the source. It passed TV-001 (positive mass),
    # TV-007 (mass conserved, because the same wrong volume was on both sides), TV-009 (finite)
    # and TV-010 (which measures the *source* extent, not the shards'), and shipped a 99-tonne
    # pane of glass into the game.
    #
    # Hard failure rather than TV-008's advisory. A lopsided mass split is ugly content; a shard
    # outside its own part is arithmetic that has gone wrong, and everything downstream — mass,
    # inertia, the collision hull, the drawn mesh — is wrong with it.
    #
    # The tolerance has to clear the shell path's own geometry. Shell fracture cuts the surface
    # into patches and *then* solidifies each one, so every shard legitimately stands proud of
    # the source by up to its thickness — 0.2 m on a 150 mm masonry wall, which is correct
    # output and not an escape. A factor of two over the thickness covers a patch solidified
    # both ways on a curved surface, and is still four orders of magnitude below the failure
    # this check exists for.
    source_box = aabb_of(source_vertices)
    tolerance = max(
        SHARD_ESCAPE_TOLERANCE_M,
        0.01 * source_box.max_extent,
        2.0 * max(0.0, args.shell_thickness),
    )
    escaped = []
    for shard in shards:
        if not shard.vertices:
            continue  # TV-001 and TV-005 own the empty-shard case; this one needs vertices.
        box = aabb_of(shard.vertices)
        outside = max(
            max(source_box.min[i] - box.min[i], box.max[i] - source_box.max[i]) for i in range(3)
        )
        if outside > tolerance:
            escaped.append((shard.name, outside))
    report.checks.append(
        CheckResult(
            id="TV-013",
            name="No shard lies outside the part's own bounds",
            status=_status(not escaped),
            measured=(
                "all shards within bounds"
                if not escaped
                else "; ".join(f"{name} escapes by {by:.1f} m" for name, by in escaped[:3])
            ),
            expected=f"within {tolerance:.3f} m of the source AABB",
            fail_code=EXIT_VERIFICATION_FAILED,
        )
    )

    # ---- TV-011: Manifest conforms to schema ------------------------------------------
    violations = validate_manifest(manifest)
    report.checks.append(
        CheckResult(
            id="TV-011",
            name="Manifest conforms to schema",
            status=_status(not violations),
            measured="; ".join(violations) if violations else "no violations",
            expected="no schema violations",
            fail_code=EXIT_VERIFICATION_FAILED,
        )
    )

    # ---- TV-012: Shard count matches the manifest -------------------------------------
    report.checks.append(
        CheckResult(
            id="TV-012",
            name="Manifest shard array matches shardCount",
            status=_status(len(manifest["shards"]) == manifest["shardCount"] == len(shards)),
            measured=f"array {len(manifest['shards'])}, count {manifest['shardCount']}, "
            f"shards {len(shards)}",
            expected="all equal",
            fail_code=EXIT_VERIFICATION_FAILED,
        )
    )

    del source_triangles, shards_glb  # measured via the caller's already-computed values
    return report


def _status(passed: bool) -> str:
    return "pass" if passed else "fail"


def _skipped(check_id: str, name: str) -> CheckResult:
    """A check whose input does not exist in this run, e.g. under ``--no-export``.

    Recorded as a pass with an explicit note rather than omitted, so the check list has the
    same shape in every run and a reader can tell "not applicable" from "forgotten".
    """
    return CheckResult(
        id=check_id,
        name=name,
        status="pass",
        measured="skipped: no export in this run",
        expected="n/a",
    )


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    """Structural validation against the D09-S4.4 schema.

    Implemented directly rather than through ``jsonschema`` so the tool has no hard
    dependency inside a Blender host, where installing packages into the embedded
    interpreter is not something the build can assume. The harness validates the same file
    against the real JSON Schema (ASSET-014), so the schema is still enforced — twice, by
    two implementations, which is the D09-R21 pattern.
    """
    violations: list[str] = []
    required = (
        "schemaVersion",
        "toolVersion",
        "blenderVersion",
        "generatedAt",
        "sourceFile",
        "sourceHash",
        "partTypeId",
        "materialId",
        "seed",
        "parameters",
        "partMassKg",
        "partVolumeM3",
        "densityKgPerM3",
        "comLocal",
        "inertiaDiagonal",
        "aabbMin",
        "aabbMax",
        "morphTargets",
        "morphStats",
        "shardCount",
        "shards",
        "collision",
        "topologyHash",
    )
    for key in required:
        if key not in manifest:
            violations.append(f"missing required field '{key}'")

    for key in ("comLocal", "inertiaDiagonal", "aabbMin", "aabbMax"):
        value = manifest.get(key)
        if not isinstance(value, dict) or not all(axis in value for axis in "xyz"):
            violations.append(f"'{key}' is not a vec3")

    if manifest.get("partMassKg", 0) <= 0:
        violations.append("partMassKg must be positive")
    if manifest.get("shardCount", 0) < 2:
        violations.append("shardCount must be at least 2")

    for index, shard in enumerate(manifest.get("shards", [])):
        for key in ("id", "name", "index", "massKg", "volumeM3", "centroid", "aabbMin", "aabbMax"):
            if key not in shard:
                violations.append(f"shard[{index}] missing '{key}'")
        if shard.get("index") != index:
            violations.append(
                f"shard[{index}] has index {shard.get('index')}: array order must match"
            )
    return violations

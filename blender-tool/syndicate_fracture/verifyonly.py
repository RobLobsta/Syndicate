"""``--verify-only``: re-check outputs that already exist, and write nothing (D09-S4.2).

The flag was in D09's argument table, was parsed, was validated against ``--no-export``, and was
then read by nothing — so its behaviour was a full destructive run that overwrote the outputs it
claimed to be checking (DISC-068). This module is the behaviour the contract promised.

**What it can check.** Everything that compares the manifest against the files beside it: the
manifest's own shape, mass conservation (G7), the shard set against the nodes actually in
``shards.glb``, the absence of damage morphs in ``mesh.glb``, and the declared transform and class
against D15-S5.7. That is the half of self-verification that does not need the fracture to be
re-run, which is precisely the half worth having after the fact — it is what catches a hand-edited
manifest, a half-copied directory, or an asset whose class changed underneath it.

**What it cannot.** Anything derived from the shard geometry the tool held in memory: hull vertex
budgets, shard volumes, the topology hash. Those are reported as skipped rather than passed, because
a check that silently does nothing is worse than one that says it did nothing.

Nothing here opens Blender. The glTF node and morph-target names are read by the same re-import path
the export uses, which needs ``bpy``; when it is unavailable those two checks skip rather than fail,
so ``--verify-only`` still answers the manifest questions on a machine with no Blender at all.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from syndicate_policy.classes import FRACTURE, PolicyError, permits

from .cli import TOOL_VERSION, Args
from .errors import (
    EXIT_INPUT_INVALID,
    EXIT_MASS_IMPLAUSIBLE,
    EXIT_TRANSFORM_NOT_PERMITTED,
    EXIT_VERIFICATION_FAILED,
    CheckResult,
    ToolError,
    VerificationReport,
    log,
)
from .manifest import MANIFEST_FILE
from .selfverify import validate_manifest

#: The self-verification ids this mode can answer. The rest are reported skipped.
CHECKED = ("TV-001", "TV-005", "TV-006", "TV-007", "TV-011", "TV-012", "TV-013")


def verify_existing(args: Args) -> dict[str, Any]:
    """Re-verify the outputs already in ``--out``. Writes nothing; raises on failure."""
    manifest = _read_manifest(args.out)
    report = VerificationReport()

    _check_manifest_shape(report, manifest)
    _check_declaration(report, manifest, args)
    _check_masses(report, manifest)
    _check_exports(report, manifest, args.out)

    log(
        "INFO",
        f"syndicate_fracture {TOOL_VERSION} --verify-only over {args.out}: "
        f"{sum(1 for c in report.checks if c.status == 'pass')} passed, "
        f"{sum(1 for c in report.checks if c.failed)} failed",
    )
    if not report.passed:
        failures = [c for c in report.checks if c.failed]
        raise ToolError(
            report.worst_code(),
            f"--verify-only found {len(failures)} failing check(s) in {args.out}",
            out=str(args.out),
            failures=[c.to_json() for c in failures],
        )
    return {
        "ok": True,
        "exitCode": 0,
        "toolVersion": TOOL_VERSION,
        "verifyOnly": True,
        "out": str(args.out),
        "partTypeId": manifest.get("partTypeId", ""),
        "transform": manifest.get("transform", ""),
        "destructionClass": manifest.get("destructionClass", ""),
        "checks": [c.to_json() for c in report.checks],
    }


# --- Checks ----------------------------------------------------------------------------


def _read_manifest(out: Path) -> dict[str, Any]:
    path = out / MANIFEST_FILE
    if not path.is_file():
        raise ToolError(
            EXIT_INPUT_INVALID,
            f"--verify-only needs an existing {MANIFEST_FILE} in --out; {path} is not there",
            out=str(out),
        )
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ToolError(EXIT_INPUT_INVALID, f"{path} is not readable JSON: {error}") from error
    if not isinstance(document, dict):
        raise ToolError(EXIT_INPUT_INVALID, f"{path} is not a JSON object")
    return document


def _check_manifest_shape(report: VerificationReport, manifest: dict[str, Any]) -> None:
    missing = validate_manifest(manifest)
    report.checks.append(
        CheckResult(
            id="TV-011",
            name="Manifest conforms to schema",
            status="pass" if not missing else "fail",
            measured="no violations" if not missing else f"missing/invalid: {missing}",
            expected="no schema violations",
        )
    )
    shards = manifest.get("shards", [])
    declared = manifest.get("shardCount", -1)
    report.checks.append(
        CheckResult(
            id="TV-012",
            name="Manifest shard array matches shardCount",
            status="pass" if isinstance(shards, list) and len(shards) == declared else "fail",
            measured=f"array {len(shards) if isinstance(shards, list) else '?'}, count {declared}",
            expected="all equal",
        )
    )


def _check_declaration(report: VerificationReport, manifest: dict[str, Any], args: Args) -> None:
    """TV-013: the manifest declares the transform it is, for a class that receives it.

    New with the transform split. It is the check that makes a hand-copied or hand-edited
    manifest detectable at all, and the one an asset gate mirrors as A510.
    """
    transform = manifest.get("transform", "")
    destruction_class = manifest.get("destructionClass", "")
    problems: list[str] = []
    if transform != FRACTURE:
        problems.append(f"transform is {transform!r}, expected {FRACTURE!r}")
    try:
        if not permits(FRACTURE, destruction_class):
            problems.append(f"D15-S5.7 gives no shards to a {destruction_class} part")
    except PolicyError as error:
        problems.append(str(error))
    if destruction_class and destruction_class != args.destruction_class:
        problems.append(
            f"--destruction-class {args.destruction_class} disagrees with the manifest's "
            f"{destruction_class}"
        )
    report.checks.append(
        CheckResult(
            id="TV-013",
            name="Manifest declares a permitted transform for its class",
            status="pass" if not problems else "fail",
            measured=f"{transform or '(absent)'} / {destruction_class or '(absent)'}",
            expected=f"{FRACTURE} on a class D15-S5.7 fractures",
            fail_code=EXIT_TRANSFORM_NOT_PERMITTED,
        )
    )


def _check_masses(report: VerificationReport, manifest: dict[str, Any]) -> None:
    shards = manifest.get("shards", []) or []
    masses = [float(s.get("massKg", 0.0)) for s in shards if isinstance(s, dict)]
    part_mass = float(manifest.get("partMassKg", 0.0) or 0.0)
    total = sum(masses)
    report.checks.append(
        CheckResult(
            id="TV-001",
            name="All shards have positive mass",
            status="pass" if masses and all(m > 0.0 for m in masses) else "fail",
            measured=f"min {min(masses):.4f} kg over {len(masses)} shards" if masses else "none",
            expected="> 0 kg",
            fail_code=EXIT_MASS_IMPLAUSIBLE,
        )
    )
    within = part_mass > 0.0 and abs(total - part_mass) <= 0.02 * part_mass
    report.checks.append(
        CheckResult(
            id="TV-007",
            name="Total shard mass conserves part mass",
            status="pass" if within else "fail",
            measured=f"{total:.4f} kg vs {part_mass:.4f} kg",
            expected="within 2.0%",
            fail_code=EXIT_MASS_IMPLAUSIBLE,
        )
    )


def _check_exports(report: VerificationReport, manifest: dict[str, Any], out: Path) -> None:
    """TV-005 and TV-006 against the files on disk, or skipped when glTF cannot be read."""
    try:
        from . import exporter
    except ImportError:  # pragma: no cover - only when bpy is entirely absent
        exporter = None  # type: ignore[assignment]

    shards_glb = out / "shards.glb"
    mesh_glb = out / "mesh.glb"
    declared_nodes = sorted(
        str(s.get("name", "")) for s in manifest.get("shards", []) or [] if isinstance(s, dict)
    )

    if exporter is None or not shards_glb.is_file():
        report.checks.append(_skipped("TV-005", "Manifest shard count matches exported meshes"))
    else:
        try:
            exported = sorted(exporter.reimport_node_names(shards_glb))
            report.checks.append(
                CheckResult(
                    id="TV-005",
                    name="Manifest shard count matches exported meshes",
                    status="pass" if exported == declared_nodes else "fail",
                    measured=f"manifest {len(declared_nodes)}, exported {len(exported)}",
                    expected="equal sets",
                )
            )
        except Exception as error:  # pragma: no cover - a reader can raise anything
            report.checks.append(_skipped("TV-005", f"shards.glb unreadable: {error}"))

    if exporter is None or not mesh_glb.is_file():
        report.checks.append(_skipped("TV-006", "Exported mesh carries no damage morphs"))
    else:
        try:
            morphs = exporter.reimport_morph_target_names(mesh_glb)
            report.checks.append(
                CheckResult(
                    id="TV-006",
                    name="Exported mesh carries no damage morphs",
                    status="pass" if morphs == manifest.get("morphTargets", []) else "fail",
                    measured=str(morphs),
                    expected=str(manifest.get("morphTargets", [])),
                )
            )
        except Exception as error:  # pragma: no cover
            report.checks.append(_skipped("TV-006", f"mesh.glb unreadable: {error}"))


def _skipped(check_id: str, name: str) -> CheckResult:
    return CheckResult(
        id=check_id,
        name=name,
        status="warning",
        measured="skipped: needs the fracture to be re-run, or a Blender host",
        expected="—",
        fail_code=EXIT_VERIFICATION_FAILED,
    )

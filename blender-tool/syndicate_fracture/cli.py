"""The argument schema of D09-S4.2.

Unknown arguments are a fatal usage error, never ignored (D09-R4). A tool whose primary
caller is an agent must reject a misspelled flag loudly: silently ignoring ``--shard=24``
would produce a 24-shard default that looks like success and ships the wrong asset.

The same reasoning now covers two more cases that used to fail quietly:

- **The deformation flags are gone.** ``--damage-morphs`` and ``--morph-amplitude`` belong to
  ``syndicate_deform``; this tool authors the FRACTURE transform and nothing else (D00-S6 makes
  them two different words on purpose). They are still *recognised* so that an old invocation
  gets exit 64 and a sentence naming the other tool, rather than an "unknown argument" that
  reads like a typo.
- **A known flag is never accepted and ignored.** ``--verify-only`` and ``--keep-blend`` were
  parsed, validated and then read by nothing; ``--verify-only`` promised to produce no new data
  and performed a destructive overwrite (DISC-068). Both are implemented.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from syndicate_policy.classes import FRACTURE, parse_class, require_permitted

from .errors import EXIT_TRANSFORM_NOT_PERMITTED, EXIT_USAGE, ToolError

TOOL_VERSION = "0.1.0"

SHARD_MODES = ("uniform", "surface_biased", "impact_biased")
LOG_LEVELS = ("DEBUG", "INFO", "WARN", "ERROR")

# D00-S6.4 / D09-S4.2 defaults, named so the manifest and the help text cannot drift.
DEFAULT_SEED = 1337
DEFAULT_SHARDS = 24
MAX_SHARDS_PER_PART = 256
DEFAULT_HULL_MAX_VERTS = 32
DEFAULT_PART_HULL_MAX_VERTS = 64
DEFAULT_MIN_SHARD_VOLUME = 1e-6
DEFAULT_MASS_TOLERANCE = 0.02
DEFAULT_MATERIAL_TABLE = "assets/materials/materials.json"


@dataclass
class Args:
    """A parsed, validated invocation. Echoed into the manifest so a run is reproducible."""

    input: Path
    out: Path
    object: str | None = None
    seed: int = DEFAULT_SEED
    shards: int = DEFAULT_SHARDS
    shard_mode: str = "uniform"
    impact_point: tuple[float, float, float] | None = None
    #: The part's destruction class (D15-S5.7). Required: the tool refuses to fracture a class
    #: that D15-S5.7 does not give shards to, and it cannot refuse what it was never told.
    destruction_class: str = "GLASS"
    material_table: Path = field(default_factory=lambda: Path(DEFAULT_MATERIAL_TABLE))
    material_override: str | None = None
    hull_max_verts: int = DEFAULT_HULL_MAX_VERTS
    part_hull_max_verts: int = DEFAULT_PART_HULL_MAX_VERTS
    min_shard_volume: float = DEFAULT_MIN_SHARD_VOLUME
    shell_thickness: float = 0.0
    expected_mass: float | None = None
    mass_tolerance: float = DEFAULT_MASS_TOLERANCE
    keep_blend: bool = False
    no_export: bool = False
    verify_only: bool = False
    report: Path | None = None
    log_level: str = "INFO"
    dry_run: bool = False

    def parameters_block(self) -> dict[str, Any]:
        """The ``parameters`` object of the manifest (D09-R8)."""
        return {
            "shards": self.shards,
            "shardMode": self.shard_mode,
            "hullMaxVerts": self.hull_max_verts,
            "minShardVolumeM3": self.min_shard_volume,
            "shellThicknessM": self.shell_thickness,
        }


class _UsageParser(argparse.ArgumentParser):
    """Turns argparse's ``SystemExit(2)`` into the tool's exit 64 (D09-R4)."""

    def error(self, message: str) -> None:  # type: ignore[override]
        raise ToolError(EXIT_USAGE, message, usage=self.format_usage().strip())


def split_blender_args(argv: list[str]) -> list[str]:
    """Everything after the ``--`` separator (D09-R4).

    When Blender runs the tool as ``blender --background --python-expr ... -- <args>``, the
    host's own arguments precede the separator. With no separator the whole list is ours,
    which is the case when the tool runs against ``bpy`` as a module.
    """
    return argv[argv.index("--") + 1 :] if "--" in argv else list(argv)


def parse(argv: list[str] | None = None) -> Args:
    """Parse and validate. Raises ``ToolError(EXIT_USAGE)`` on anything malformed."""
    raw = split_blender_args(list(sys.argv[1:] if argv is None else argv))

    parser = _UsageParser(prog="syndicate_fracture", add_help=True, allow_abbrev=False)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--object")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--shards", type=int, default=DEFAULT_SHARDS)
    parser.add_argument("--shard-mode", choices=SHARD_MODES, default="uniform")
    parser.add_argument("--impact-point")
    # Required, and with no default: the whole point is that the tool knows what it is
    # authoring for and can refuse (D15-S5.7). A default would restore the silence.
    parser.add_argument("--destruction-class")
    # Recognised only so an old invocation gets a sentence instead of "unknown argument".
    parser.add_argument("--damage-morphs", type=int, default=None)
    parser.add_argument("--morph-amplitude", type=float, default=None)
    parser.add_argument("--material-table", type=Path, default=Path(DEFAULT_MATERIAL_TABLE))
    parser.add_argument("--material-override")
    parser.add_argument("--hull-max-verts", type=int, default=DEFAULT_HULL_MAX_VERTS)
    parser.add_argument("--part-hull-max-verts", type=int, default=DEFAULT_PART_HULL_MAX_VERTS)
    parser.add_argument("--min-shard-volume", type=float, default=DEFAULT_MIN_SHARD_VOLUME)
    # A positive thickness says the source is a *surface* and selects the shell path
    # (D09-S5.2.1). Zero, the default, keeps the solid Voronoi path.
    parser.add_argument("--shell-thickness", type=float, default=0.0)
    parser.add_argument("--expected-mass", type=float)
    parser.add_argument("--mass-tolerance", type=float, default=DEFAULT_MASS_TOLERANCE)
    parser.add_argument("--keep-blend", action="store_true")
    parser.add_argument("--no-export", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--log-level", choices=LOG_LEVELS, default="INFO")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--version", action="store_true")

    namespace, unknown = parser.parse_known_args(raw)
    if unknown:
        raise ToolError(EXIT_USAGE, f"unknown argument(s): {' '.join(unknown)}", unknown=unknown)
    if namespace.version:
        raise _VersionRequested()

    if namespace.input is None or namespace.out is None:
        raise ToolError(EXIT_USAGE, "--input and --out are both required")

    _reject_deform_flags(namespace)
    destruction_class = _destruction_class(namespace.destruction_class)

    shards = namespace.shards
    if shards < 2 or shards > MAX_SHARDS_PER_PART:
        # Clamped rather than rejected per D09-S4.2, but the clamp is logged by the caller:
        # silently producing 64 shards for a request of 500 would misreport what shipped.
        shards = max(2, min(shards, MAX_SHARDS_PER_PART))

    impact: tuple[float, float, float] | None = None
    if namespace.impact_point is not None:
        impact = _parse_vec3(namespace.impact_point)
    if namespace.shard_mode == "impact_biased" and impact is None:
        raise ToolError(EXIT_USAGE, "--shard-mode impact_biased requires --impact-point x,y,z")
    if namespace.hull_max_verts < 4 or namespace.part_hull_max_verts < 4:
        raise ToolError(EXIT_USAGE, "hull vertex budgets must be at least 4")
    if namespace.mass_tolerance <= 0.0:
        raise ToolError(EXIT_USAGE, "--mass-tolerance must be positive")
    if namespace.no_export and namespace.verify_only:
        raise ToolError(EXIT_USAGE, "--no-export and --verify-only are contradictory")

    return Args(
        input=namespace.input,
        out=namespace.out,
        object=namespace.object,
        seed=namespace.seed,
        shards=shards,
        shard_mode=namespace.shard_mode,
        impact_point=impact,
        destruction_class=destruction_class,
        material_table=namespace.material_table,
        material_override=namespace.material_override,
        hull_max_verts=namespace.hull_max_verts,
        part_hull_max_verts=namespace.part_hull_max_verts,
        min_shard_volume=namespace.min_shard_volume,
        shell_thickness=namespace.shell_thickness,
        expected_mass=namespace.expected_mass,
        mass_tolerance=namespace.mass_tolerance,
        keep_blend=namespace.keep_blend,
        no_export=namespace.no_export,
        verify_only=namespace.verify_only,
        report=namespace.report,
        log_level=namespace.log_level,
        dry_run=namespace.dry_run,
    )


def _reject_deform_flags(namespace: argparse.Namespace) -> None:
    """Exit 64 naming ``syndicate_deform`` when asked to author damage morphs.

    This tool used to run the deformation stage as well, unconditionally, on every object
    (DISC-068). Dropping the flags silently would leave every old invocation looking like it
    still worked while quietly authoring one transform instead of two — which is the same
    failure in the other direction. So the flags are still parsed, and saying so is the whole
    of their remaining job.
    """
    asked = [
        flag
        for flag, value in (
            ("--damage-morphs", namespace.damage_morphs),
            ("--morph-amplitude", namespace.morph_amplitude),
        )
        if value is not None
    ]
    if asked:
        raise ToolError(
            EXIT_USAGE,
            f"{' and '.join(asked)} moved to syndicate_deform: this tool authors the FRACTURE "
            f"transform only, and no destruction class in D15-S5.7 receives both. Run "
            f"`python3 -m syndicate_deform` for damage shape keys",
            movedTo="syndicate_deform",
            flags=asked,
        )


def _destruction_class(raw: str | None) -> str:
    """The part's class, checked against D15-S5.7 before Blender is ever started.

    Two different failures, two different codes: a class this tool cannot parse is a usage
    error, and a class D15-S5.7 simply does not give shards to is a *content* decision that is
    wrong — exit 77, so an agent knows to fix the label rather than the flags.
    """
    from syndicate_policy.classes import PolicyError

    if raw is None:
        raise ToolError(
            EXIT_USAGE,
            "--destruction-class is required (D15-S5.7); the tool refuses to fracture a class "
            "that does not receive shards, and cannot refuse what it was not told",
        )
    try:
        parsed = parse_class(raw)
    except PolicyError as error:
        raise ToolError(EXIT_USAGE, str(error), destructionClass=raw) from error
    try:
        require_permitted(FRACTURE, parsed)
    except PolicyError as error:
        raise ToolError(
            EXIT_TRANSFORM_NOT_PERMITTED,
            str(error),
            destructionClass=str(parsed),
            transform=str(FRACTURE),
        ) from error
    return str(parsed)


class _VersionRequested(Exception):  # noqa: N818 - control flow, not a failure
    """``--version`` short-circuits parsing; the caller prints and exits 0 (D09-S4.2).

    Named without an ``Error`` suffix deliberately: nothing went wrong, and a caller
    reading ``except VersionRequestedError`` would reasonably expect a non-zero exit.
    """


VersionRequested = _VersionRequested


def _parse_vec3(text: str) -> tuple[float, float, float]:
    parts = text.split(",")
    if len(parts) != 3:
        raise ToolError(EXIT_USAGE, f"expected x,y,z but got '{text}'")
    try:
        return (float(parts[0]), float(parts[1]), float(parts[2]))
    except ValueError as exc:
        raise ToolError(EXIT_USAGE, f"expected three numbers but got '{text}'") from exc

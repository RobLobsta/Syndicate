"""The argument schema of the deform tool.

Deliberately the shape of :mod:`syndicate_fracture.cli` — same ``--input``/``--out``, same
``--seed``, same ``--dry-run``, same fatal treatment of an unknown argument (D09-R4) — because an
agent that has learned one of these tools should not have to learn the other. What differs is
exactly the flags that describe the transform, which is the point of there being two tools.

There is **no ``--shards``**. Asking this tool for shards is a usage error naming
:mod:`syndicate_fracture`, for the same reason the fracture tool rejects ``--damage-morphs``: an
invocation that silently authors one transform when the caller asked for another is the failure
this split exists to remove.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from syndicate_fracture.errors import ToolError
from syndicate_policy.classes import DEFORM, parse_class, require_permitted
from syndicate_policy.exit_codes import EXIT_TRANSFORM_NOT_PERMITTED, EXIT_USAGE

from . import TOOL_VERSION

LOG_LEVELS = ("DEBUG", "INFO", "WARN", "ERROR")

DEFAULT_SEED = 1337
DEFAULT_LEVELS = 4
DEFAULT_AMPLITUDE = 0.06
MAX_LEVELS = 4


@dataclass
class Args:
    """A parsed, validated invocation. Echoed into the manifest so a run is reproducible."""

    input: Path
    out: Path
    object: str | None = None
    destruction_class: str = "SHEET_METAL"
    seed: int = DEFAULT_SEED
    levels: int = DEFAULT_LEVELS
    amplitude: float = DEFAULT_AMPLITUDE
    subdivide: bool = True
    no_export: bool = False
    keep_blend: bool = False
    report: Path | None = None
    log_level: str = "INFO"
    dry_run: bool = False
    _: Any = field(default=None, repr=False)

    def parameters_block(self) -> dict[str, Any]:
        return {
            "levels": self.levels,
            "amplitude": self.amplitude,
            "subdivide": self.subdivide,
        }


class _UsageParser(argparse.ArgumentParser):
    """Turns argparse's ``SystemExit(2)`` into the suite's exit 64 (D09-R4)."""

    def error(self, message: str) -> None:  # type: ignore[override]
        raise ToolError(EXIT_USAGE, message, usage=self.format_usage().strip())


def split_blender_args(argv: list[str]) -> list[str]:
    """Everything after the ``--`` separator (D09-R4)."""
    return argv[argv.index("--") + 1 :] if "--" in argv else list(argv)


def parse(argv: list[str] | None = None) -> Args:
    raw = split_blender_args(list(sys.argv[1:] if argv is None else argv))

    parser = _UsageParser(prog="syndicate_deform", add_help=True, allow_abbrev=False)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--object")
    parser.add_argument("--destruction-class")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--levels", type=int, default=DEFAULT_LEVELS)
    parser.add_argument("--amplitude", type=float, default=DEFAULT_AMPLITUDE)
    parser.add_argument("--no-subdivide", action="store_true")
    parser.add_argument("--no-export", action="store_true")
    parser.add_argument("--keep-blend", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--log-level", choices=LOG_LEVELS, default="INFO")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--version", action="store_true")
    # Recognised only to say where it went, exactly as the fracture tool does in reverse.
    parser.add_argument("--shards", type=int, default=None)

    namespace, unknown = parser.parse_known_args(raw)
    if unknown:
        raise ToolError(EXIT_USAGE, f"unknown argument(s): {' '.join(unknown)}", unknown=unknown)
    if namespace.version:
        raise VersionRequested()

    if namespace.shards is not None:
        raise ToolError(
            EXIT_USAGE,
            "--shards belongs to syndicate_fracture: this tool authors the DEFORM transform "
            "only, and no destruction class in D15-S5.7 receives both",
            movedTo="syndicate_fracture",
        )
    if namespace.input is None or namespace.out is None:
        raise ToolError(EXIT_USAGE, "--input and --out are both required")
    if namespace.levels < 1 or namespace.levels > MAX_LEVELS:
        raise ToolError(EXIT_USAGE, f"--levels must be in [1, {MAX_LEVELS}]")
    if namespace.amplitude <= 0.0:
        raise ToolError(EXIT_USAGE, "--amplitude must be positive")

    return Args(
        input=namespace.input,
        out=namespace.out,
        object=namespace.object,
        destruction_class=destruction_class_of(namespace.destruction_class),
        seed=namespace.seed,
        levels=namespace.levels,
        amplitude=namespace.amplitude,
        subdivide=not namespace.no_subdivide,
        no_export=namespace.no_export,
        keep_blend=namespace.keep_blend,
        report=namespace.report,
        log_level=namespace.log_level,
        dry_run=namespace.dry_run,
    )


def destruction_class_of(raw: str | None) -> str:
    """The part's class, checked against D15-S5.7 before Blender is ever started.

    Two failures, two codes, for the same reason the fracture tool separates them: a class this
    tool cannot parse is a usage error, and a class D15-S5.7 does not deform is a content
    decision that is wrong — exit 77 sends the caller to the label rather than to the flags.
    """
    from syndicate_policy.classes import PolicyError

    if raw is None:
        raise ToolError(
            EXIT_USAGE,
            "--destruction-class is required (D15-S5.7); the tool refuses to dent a class that "
            "does not deform, and cannot refuse what it was not told",
        )
    try:
        parsed = parse_class(raw)
    except PolicyError as error:
        raise ToolError(EXIT_USAGE, str(error), destructionClass=raw) from error
    try:
        require_permitted(DEFORM, parsed)
    except PolicyError as error:
        raise ToolError(
            EXIT_TRANSFORM_NOT_PERMITTED,
            str(error),
            destructionClass=str(parsed),
            transform=str(DEFORM),
        ) from error
    return str(parsed)


class VersionRequested(Exception):  # noqa: N818 - control flow, not a failure
    """``--version`` short-circuits parsing; the caller prints and exits 0."""


__all__ = [
    "DEFAULT_AMPLITUDE",
    "DEFAULT_LEVELS",
    "DEFAULT_SEED",
    "TOOL_VERSION",
    "Args",
    "VersionRequested",
    "destruction_class_of",
    "parse",
    "split_blender_args",
]

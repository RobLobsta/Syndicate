"""The machine-readable failure report of D09-S4.3 and D09-S9.

The tool's primary user is an agent reading an exit code, so the codes are the contract:
grouped so a caller can branch on the category, specific where a specific response exists,
and never reused. D09-R6 makes a specific code win over the general ``VERIFICATION_FAILED``
so the common cases need no report parsing at all.

The codes themselves now live in :mod:`syndicate_policy.exit_codes`, which is the one table
the whole suite shares — three packages previously kept their own copies and disagreed about
what 65 and 66 meant (DISC-068). They are re-exported here so every existing
``from .errors import EXIT_*`` keeps working and there is still one import for a failure path
to reach for.
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from typing import Any

from syndicate_policy.exit_codes import (  # noqa: F401 - re-exported for the whole package
    EXIT_BLENDER_ERROR,
    EXIT_DETERMINISM_VIOLATION,
    EXIT_EXPORT_FAILED,
    EXIT_FRACTURE_FAILED,
    EXIT_HULL_FAILED,
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_INPUT_INVALID,
    EXIT_MASS_IMPLAUSIBLE,
    EXIT_MATERIAL_UNRESOLVED,
    EXIT_NAMES,
    EXIT_OK,
    EXIT_OUTPUT_WRITE_FAILED,
    EXIT_SHAPEKEY_FAILED,
    EXIT_TRANSFORM_NOT_PERMITTED,
    EXIT_USAGE,
    EXIT_VERIFICATION_FAILED,
)

# Codes that always beat EXIT_VERIFICATION_FAILED when several checks fail at once
# (D09-R6). Lower index wins, so a caller sees the most actionable cause.
_SPECIFIC_PRECEDENCE = (
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_MATERIAL_UNRESOLVED,
    EXIT_FRACTURE_FAILED,
    EXIT_SHAPEKEY_FAILED,
    EXIT_HULL_FAILED,
    EXIT_MASS_IMPLAUSIBLE,
    EXIT_EXPORT_FAILED,
    EXIT_DETERMINISM_VIOLATION,
)


class ToolError(Exception):
    """A failure with a specific exit code and a machine-readable payload.

    Every failure path in the tool raises one of these rather than letting an exception
    escape: an agent that receives exit 1 and a Python traceback has no way to decide what
    to do next, which is the whole point of D09-S4.3.
    """

    def __init__(self, code: int, message: str, **details: Any) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details

    def report(self, stage: str = "") -> dict[str, Any]:
        """The failure report of D09-S9."""
        return {
            "ok": False,
            "exitCode": self.code,
            "exitName": EXIT_NAMES.get(self.code, "UNKNOWN"),
            "stage": stage,
            "message": self.message,
            "details": self.details,
        }


def worst_exit_code(codes: list[int]) -> int:
    """The code to exit with when several checks failed (D09-R6).

    A specific code always beats the general ``VERIFICATION_FAILED``, because the specific
    one tells the caller which stage to fix. Among specific codes the earliest stage wins:
    a bad fracture causes bad masses, so reporting the mass failure would send the caller
    to the wrong place.
    """
    if not codes:
        return EXIT_OK
    for candidate in _SPECIFIC_PRECEDENCE:
        if candidate in codes:
            return candidate
    return EXIT_VERIFICATION_FAILED


@dataclass
class CheckResult:
    """One self-verification check (D09-S7). ``TV-nnn`` ids are permanent."""

    id: str
    name: str
    status: str  # "pass" | "fail" | "warning"
    measured: str
    expected: str
    fail_code: int = EXIT_VERIFICATION_FAILED

    @property
    def failed(self) -> bool:
        return self.status == "fail"

    def to_json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "status": self.status,
            "measured": self.measured,
            "expected": self.expected,
        }


@dataclass
class VerificationReport:
    """The embedded verification block of the manifest (D09-R7)."""

    checks: list[CheckResult] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not any(c.failed for c in self.checks)

    @property
    def warnings(self) -> list[CheckResult]:
        return [c for c in self.checks if c.status == "warning"]

    def worst_code(self) -> int:
        return worst_exit_code([c.fail_code for c in self.checks if c.failed])

    def to_json(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "checks": [c.to_json() for c in self.checks],
            "warnings": [c.name for c in self.warnings],
        }


_result_fd: int | None = None


def claim_stdout() -> None:
    """Take exclusive ownership of stdout, so D09-R2 holds against a noisy host.

    Blender writes to the process's stdout at the C level and does not ask permission: the
    glTF exporter alone emits a Draco availability notice on every run. Any of it lands in
    the middle of the JSON document an agent is about to parse, and the tool's one hard
    output guarantee — exactly one JSON document on stdout, always — is broken by a library
    the tool does not control.

    So the real stdout is duplicated to a private descriptor that only :func:`emit_json`
    writes to, and file descriptor 1 is pointed at stderr. Everything the host prints
    "to stdout" from that moment on is diagnostics, which is where D09-R2 puts it anyway.
    """
    global _result_fd
    if _result_fd is not None:
        return
    sys.stdout.flush()
    _result_fd = os.dup(1)
    os.dup2(2, 1)


def emit_json(payload: dict[str, Any]) -> None:
    """Write the tool's one and only stdout document (D09-R2).

    ``sort_keys`` is not cosmetic: G11 requires two runs with the same seed to produce
    equal output, and dict insertion order is not something the rest of the tool should
    have to guarantee for that to hold.
    """
    document = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    if _result_fd is None:
        sys.stdout.write(document)
        sys.stdout.flush()
        return
    os.write(_result_fd, document.encode("utf-8"))


def log(level: str, message: str) -> None:
    """Diagnostics go to stderr. stdout carries JSON and nothing else (D09-R2)."""
    print(f"{level} {message}", file=sys.stderr)

"""The deform tool's self-verification (D09-S7, D09-R21).

Three checks, and two of them carry ids that used to live in the fracture tool's report. **Ids are
permanent and are never reused** (D09-S7), so ``TV-002`` still means "shape keys are
non-degenerate" and ``TV-003`` still means "severity increases across levels" — they are simply
reported by the tool that authors the shape keys, which is where they always belonged.

``TV-006`` is shared with the fracture tool by design, and it asks the mirror-image question in
each: there it is *this part carries no damage morphs*, here it is *the morphs survived export*.
One id, one subject — what the exported mesh's morph targets are — and each tool asserting what its
own transform requires of them.
"""

from __future__ import annotations

import itertools
import math
from typing import Any

from syndicate_fracture.errors import (
    EXIT_EXPORT_FAILED,
    EXIT_SHAPEKEY_FAILED,
    EXIT_VERIFICATION_FAILED,
    CheckResult,
    VerificationReport,
)

from .morphs import MORPH_MIN_DELTA_M, MorphStats


def run(
    morphs: list[MorphStats],
    manifest: dict[str, Any],
    exported_morph_names: list[str] | None,
) -> VerificationReport:
    """Every check, in id order. Never raises: failures become failed checks."""
    report = VerificationReport()

    # ---- TV-002: Shape keys are non-degenerate ----------------------------------------
    for morph in morphs:
        report.checks.append(
            CheckResult(
                id="TV-002",
                name=f"Shape key {morph.name} is non-degenerate",
                status=_status(
                    math.isfinite(morph.max_displacement_m)
                    and math.isfinite(morph.mean_displacement_m)
                    and morph.max_displacement_m >= MORPH_MIN_DELTA_M
                ),
                measured=f"max disp {morph.max_displacement_m:.5f} m, "
                f"mean {morph.mean_displacement_m:.5f} m",
                expected=f"finite, max disp >= {MORPH_MIN_DELTA_M} m",
                fail_code=EXIT_SHAPEKEY_FAILED,
            )
        )

    # ---- TV-003: Shape key severity is monotonic --------------------------------------
    means = [m.mean_displacement_m for m in morphs]
    report.checks.append(
        CheckResult(
            id="TV-003",
            name="Shape key severity increases across levels",
            status=_status(all(b > a for a, b in itertools.pairwise(means))),
            measured=str([round(m, 5) for m in means]),
            expected="strictly increasing",
            fail_code=EXIT_SHAPEKEY_FAILED,
        )
    )

    # ---- TV-006: Morph targets survived export ----------------------------------------
    if exported_morph_names is None:
        report.checks.append(
            CheckResult(
                id="TV-006",
                name="Morph targets present in exported mesh",
                status="warning",
                measured="skipped: --no-export",
                expected="—",
                fail_code=EXIT_VERIFICATION_FAILED,
            )
        )
    else:
        report.checks.append(
            CheckResult(
                id="TV-006",
                name="Morph targets present in exported mesh",
                status=_status(exported_morph_names == manifest["morphTargets"]),
                measured=str(exported_morph_names),
                expected=str(manifest["morphTargets"]),
                fail_code=EXIT_EXPORT_FAILED,
            )
        )

    # ---- TV-013: the manifest declares the transform it is ----------------------------
    # The same id and the same question the fracture tool's --verify-only asks, because it is
    # the check the asset gate mirrors as A510. A manifest that does not say what it is cannot
    # be paired with a part.json that does.
    from syndicate_policy.classes import DEFORM, PolicyError, permits

    problems: list[str] = []
    if manifest.get("transform") != DEFORM:
        problems.append(f"transform is {manifest.get('transform')!r}, expected {DEFORM!r}")
    try:
        if not permits(DEFORM, manifest.get("destructionClass", "")):
            problems.append(
                f"D15-S5.7 does not deform a {manifest.get('destructionClass')} part"
            )
    except PolicyError as error:
        problems.append(str(error))
    report.checks.append(
        CheckResult(
            id="TV-013",
            name="Manifest declares a permitted transform for its class",
            status=_status(not problems),
            measured=f"{manifest.get('transform')} / {manifest.get('destructionClass')}",
            expected=f"{DEFORM} on a class D15-S5.7 deforms",
        )
    )
    return report


def _status(passed: bool) -> str:
    return "pass" if passed else "fail"


__all__ = ["run"]

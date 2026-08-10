"""The segmentation report of D15-S4.4 — the deliverable an operator actually reads.

D15-R12: one JSON document on stdout, carrying per-label shell counts and triangle shares, the
unclassified share, every cue disagreement, and every repair applied.

D15-R13: it carries ``confidence.labelledTriangleFraction``, and below
:data:`labels.REPORT_MIN_LABELLED_FRACTION` the tool exits non-zero in strict mode. A car that
is 64% unnamed has not been prepared, and saying so loudly is the difference between a pipeline
and a plausible-looking one.

The report is written for somebody deciding **whether this model needs a ``parts.json``**, so
its most important section is not the totals — it is ``suggestedOverrides``: the materials that
account for the most unnamed geometry, in order, with the triangle share each would fix. That
is the six lines D15-S1 promises an operator has to write.
"""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from . import cues
from .labels import (
    DESTRUCTION_CLASS,
    DETACHES,
    LABELS,
    REPORT_MIN_LABELLED_FRACTION,
    SLOT_ROLE,
    UNCLASSIFIED,
)
from .shell import Shell

#: How many materials the report suggests overriding. Ten is well past the six D15-R9 says
#: cover the difficult car, and short enough that the list is read rather than skimmed.
MAX_SUGGESTIONS = 10


def build_report(
    vehicle: str,
    model_dir: Path,
    shells: list[Shell],
    parts,
    stages: dict,
    overrides,
    strict: bool,
    elapsed_s: float,
) -> dict:
    """Assembles the whole document."""
    total_triangles = sum(shell.triangles for shell in shells) or 1
    by_label = _label_summary(shells, total_triangles)
    unnamed = by_label.get(UNCLASSIFIED, {}).get("triangleFraction", 0.0)
    labelled_fraction = 1.0 - unnamed

    report = {
        "tool": "syndicate-prepare",
        "vehicle": vehicle,
        "modelDir": str(model_dir),
        "elapsedSeconds": round(elapsed_s, 3),
        "stages": stages,
        "labels": by_label,
        "parts": [_part_summary(part) for part in parts],
        "confidence": {
            "labelledTriangleFraction": round(labelled_fraction, 4),
            "minimum": REPORT_MIN_LABELLED_FRACTION,
            "meets": labelled_fraction >= REPORT_MIN_LABELLED_FRACTION,
        },
        "suggestedOverrides": suggest_overrides(shells),
        "disagreements": _disagreements(shells),
        # D15-S5.1 stages 6 to 8. Named rather than omitted: a pipeline that quietly stopped
        # early is indistinguishable from one that had nothing left to do.
        "pendingStages": [
            {"stage": 6, "name": "rig articulated parts", "status": "not implemented"},
            {"stage": 7, "name": "author destruction per class", "status": "not implemented"},
            {"stage": 8, "name": "re-origin, re-parent, export", "status": "not implemented"},
        ],
        "declaredHinges": [
            {"part": hinge.part, "axis": hinge.axis, "pivot": list(hinge.pivot),
                "openDeg": hinge.open_deg}
            for hinge in overrides.hinges
        ],
    }
    report["ok"] = (not strict) or report["confidence"]["meets"]
    return report


def _label_summary(shells: list[Shell], total_triangles: int) -> dict:
    """Per-label shell counts and triangle shares (D15-R12, D15-R2).

    Every label in the taxonomy appears, including the ones with nothing in them. A label that
    is simply absent from a report is indistinguishable from a label the run never considered,
    and the whole point of a closed set (D15-R1) is that the reader knows what was on offer.
    """
    counts: dict[str, int] = defaultdict(int)
    triangles: dict[str, int] = defaultdict(int)
    confidence_sum: dict[str, float] = defaultdict(float)

    for shell in shells:
        counts[shell.label] += 1
        triangles[shell.label] += shell.triangles
        confidence_sum[shell.label] += shell.confidence

    summary = {}
    for label in LABELS:
        count = counts.get(label, 0)
        summary[label] = {
            "shells": count,
            "triangles": triangles.get(label, 0),
            "triangleFraction": round(triangles.get(label, 0) / total_triangles, 4),
            "meanConfidence": round(confidence_sum.get(label, 0.0) / count, 3) if count else 0.0,
            "slotRole": SLOT_ROLE[label],
            "destructionClass": DESTRUCTION_CLASS[label],
            "detaches": DETACHES[label],
        }
    return summary


def _part_summary(part) -> dict:
    lo, hi = part.lo, part.hi
    return {
        "name": part.name,
        "label": part.label,
        "side": part.side,
        "index": part.index,
        "shells": len(part.shells),
        "triangles": part.triangles,
        "boundsMin": [round(value, 4) for value in lo],
        "boundsMax": [round(value, 4) for value in hi],
        "materials": part.materials,
        "destructionClass": DESTRUCTION_CLASS[part.label],
        "slotRole": SLOT_ROLE[part.label],
    }


def suggest_overrides(shells: list[Shell]) -> list[dict]:
    """The materials that would fix the most unnamed geometry, in order (D15-R9).

    This is the section that makes the whole design practical rather than a research project.
    The Eclipse's 64% unnamed geometry is covered by six material names; without this list an
    operator would have to find those six among sixty by inspection, and with it the report
    hands them over ranked by how much each is worth.

    A material with no name — a shell whose object carried no material slot — is reported under
    a null key rather than skipped, because "the geometry that cannot be overridden by material
    at all" is exactly the case ``regionLabels`` exists for (D15-R10).
    """
    unnamed_by_material: dict[str | None, int] = defaultdict(int)
    shells_by_material: dict[str | None, int] = defaultdict(int)
    total = sum(shell.triangles for shell in shells) or 1

    for shell in shells:
        if shell.label != UNCLASSIFIED:
            continue
        unnamed_by_material[shell.material] += shell.triangles
        shells_by_material[shell.material] += 1

    ranked = sorted(
        unnamed_by_material.items(),
        # Triangles descending, then the material name, so an exact tie is stable across runs.
        key=lambda item: (-item[1], item[0] or ""),
    )
    return [
        {
            "material": material,
            "unnamedTriangles": count,
            "triangleFraction": round(count / total, 4),
            "shells": shells_by_material[material],
            "remedy": "materialLabels" if material else "regionLabels (no material on these)",
        }
        for material, count in ranked[:MAX_SUGGESTIONS]
    ]


def _disagreements(shells: list[Shell]) -> list[dict]:
    """Every shell where a cue family voted against the winner (D15-R12).

    Capped at the largest offenders by triangle count: on a car with 6,800 shells the raw list
    would be tens of thousands of lines, and the ones worth reading are the ones covering real
    area.
    """
    interesting = []
    for shell in shells:
        reasons = cues.disagreements(shell)
        if reasons:
            interesting.append((shell, reasons))
    interesting.sort(key=lambda pair: (-pair[0].triangles, pair[0].index))

    return [
        {
            "shell": shell.index,
            "material": shell.material,
            "triangles": shell.triangles,
            "chose": shell.label,
            "confidence": round(shell.confidence, 3),
            "against": reasons,
        }
        for shell, reasons in interesting[:MAX_SUGGESTIONS * 2]
    ]

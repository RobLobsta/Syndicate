"""``parts.json`` — the per-model override that outranks every cue (D15-S4.3).

D15-R9 is the single decision that makes manual correction affordable: ``materialLabels`` is
keyed by **material**, not by object or by shell. The Eclipse has 6,830 shells and 60
materials, and the 64% of its geometry the ensemble cannot name is covered by six material
names. An operator labels tens of things, never thousands.

D15-R10's ``regionLabels`` is the escape hatch for what a material cannot express — one
material covering both a door and the wing beside it — and is a box in game space applied
after material labels and before any geometric cue.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .labels import LABELS
from .shell import Shell


class OverrideError(Exception):
    """A ``parts.json`` that cannot be applied to this model (D15-E8)."""


@dataclass(frozen=True)
class Forced:
    """A label an override imposes, and the reason to put in the report."""

    label: str
    because: str


@dataclass(frozen=True)
class Region:
    """A box in game space that forces a label, and optionally names the part (D15-R10)."""

    label: str
    part: str | None
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]

    def contains(self, point: tuple[float, float, float]) -> bool:
        return all(self.lo[i] <= point[i] <= self.hi[i] for i in range(3))


@dataclass(frozen=True)
class Hinge:
    """A declared hinge (D15-R29 case 1: declared always wins)."""

    part: str
    axis: str
    pivot: tuple[float, float, float]
    open_deg: float


class Overrides:
    """The parsed ``parts.json``, or an empty one.

    An absent file is not an error: D15-R8 says a model that needs no override is a model the
    ensemble handled. A file that names a material the model does not have **is** an error
    rather than a warning (D15-E8), because it means the override was written against a
    different file — and silently ignoring it would leave the operator believing they had
    fixed something.
    """

    SCHEMA_VERSION = "1.0.0"

    def __init__(
        self,
        material_labels: dict[str, str] | None = None,
        regions: list[Region] | None = None,
        hinges: list[Hinge] | None = None,
    ):
        self.material_labels = dict(material_labels or {})
        self.regions = list(regions or [])
        self.hinges = list(hinges or [])
        self.used_materials: set[str] = set()
        self.used_regions: set[int] = set()

    @property
    def is_empty(self) -> bool:
        return not self.material_labels and not self.regions and not self.hinges

    # ---- Loading ---------------------------------------------------------------------

    @classmethod
    def load(cls, model_dir: Path) -> Overrides:
        """Reads ``<model_dir>/parts.json``, or returns an empty override set."""
        path = Path(model_dir) / "parts.json"
        if not path.is_file():
            return cls()
        try:
            document = json.loads(path.read_text())
        except json.JSONDecodeError as exc:
            raise OverrideError(f"{path} is not valid JSON: {exc}") from exc

        version = document.get("schemaVersion", cls.SCHEMA_VERSION)
        if not str(version).startswith("1."):
            raise OverrideError(
                f"{path} declares schemaVersion {version}; this tool understands 1.x"
            )

        material_labels = {}
        for material, label in (document.get("materialLabels") or {}).items():
            _check_label(label, f"materialLabels[{material}]", path)
            material_labels[material] = label

        regions = []
        for i, entry in enumerate(document.get("regionLabels") or []):
            _check_label(entry.get("label"), f"regionLabels[{i}]", path)
            regions.append(
                Region(
                    label=entry["label"],
                    part=entry.get("part"),
                    lo=_vector(entry.get("boundsMin"), f"regionLabels[{i}].boundsMin", path),
                    hi=_vector(entry.get("boundsMax"), f"regionLabels[{i}].boundsMax", path),
                )
            )

        hinges = []
        for i, entry in enumerate(document.get("hinges") or []):
            hinges.append(
                Hinge(
                    part=entry.get("part", f"hinge_{i}"),
                    axis=str(entry.get("axis", "y")).lower(),
                    pivot=_vector(entry.get("pivot"), f"hinges[{i}].pivot", path),
                    open_deg=float(entry.get("openDeg", 60.0)),
                )
            )
        return cls(material_labels, regions, hinges)

    # ---- Application (D15-R11 precedence) ----------------------------------------------

    def label_for(self, shell: Shell) -> Forced | None:
        """The label this override imposes on a shell, or ``None``.

        Regions beat materials, which is D15-R11's order: a region is written precisely because
        a material was too coarse for the case, so letting the material win would make the
        escape hatch useless.
        """
        for i, region in enumerate(self.regions):
            if region.contains(shell.centroid):
                self.used_regions.add(i)
                named = f" ({region.part})" if region.part else ""
                return Forced(region.label, f"inside declared region {i}{named}")

        if shell.material is not None and shell.material in self.material_labels:
            self.used_materials.add(shell.material)
            return Forced(
                self.material_labels[shell.material], f"declared for material {shell.material}"
            )
        return None

    def verify_against(self, materials: set[str]) -> None:
        """D15-E8: a declared material the model does not have is an error, not a warning."""
        missing = sorted(name for name in self.material_labels if name not in materials)
        if missing:
            raise OverrideError(
                "parts.json names materials this model does not have: "
                + ", ".join(missing)
                + " — the override was written against a different file (D15-E8)"
            )

    def unused_report(self) -> dict:
        """Which declarations never matched anything, for the report.

        Not an error: an override written for a model that later gained a proper label is
        harmless, and deleting it is the operator's call. Silently doing nothing is not
        harmless, which is why it is reported.
        """
        return {
            "unusedMaterialLabels": sorted(set(self.material_labels) - self.used_materials),
            "unusedRegionLabels": [
                i for i in range(len(self.regions)) if i not in self.used_regions
            ],
        }


def _check_label(label, where: str, path: Path) -> None:
    if label not in LABELS:
        raise OverrideError(
            f"{path}: {where} is {label!r}, which is not one of the D15-S4.1 labels"
        )


def _vector(node, where: str, path: Path) -> tuple[float, float, float]:
    if not isinstance(node, dict):
        raise OverrideError(f"{path}: {where} must be an object with x, y and z")
    try:
        return (float(node["x"]), float(node["y"]), float(node["z"]))
    except (KeyError, TypeError, ValueError) as exc:
        raise OverrideError(f"{path}: {where} must be an object with numeric x, y and z") from exc

"""The material density table of D09-S6.3.

Densities come from ``assets/materials/materials.json`` — the same file the game reads
(D09-R18). The tool deliberately carries no built-in copy: two tables would drift, and a
drifted density produces a wrong mass, which produces wrong physics, which is the hardest
class of bug to trace back to its cause.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .errors import EXIT_INPUT_INVALID, EXIT_MATERIAL_UNRESOLVED, ToolError


@dataclass(frozen=True)
class Material:
    material_id: str
    density_kg_per_m3: float
    fracture_brittleness: float = 0.5


class MaterialTable:
    """Materials by id, with no fallback (D09-R19)."""

    def __init__(self, materials: dict[str, Material], source: Path) -> None:
        self._materials = materials
        self.source = source

    def __contains__(self, material_id: str) -> bool:
        return material_id in self._materials

    def ids(self) -> list[str]:
        return sorted(self._materials)

    def resolve(self, material_id: str) -> Material:
        """The material, or exit 67.

        Never defaults to steel. D09-R19 is explicit that an unknown material is a hard
        failure, because a default density is silently wrong rather than loudly missing.
        """
        material = self._materials.get(material_id)
        if material is None:
            raise ToolError(
                EXIT_MATERIAL_UNRESOLVED,
                f"material '{material_id}' is not in the material table",
                materialId=material_id,
                table=str(self.source),
                known=self.ids(),
            )
        return material


def load(path: Path) -> MaterialTable:
    """Read and validate the table (D09-S6.3)."""
    if not path.is_file():
        raise ToolError(
            EXIT_INPUT_INVALID,
            f"material table not found: {path}",
            path=str(path),
        )
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ToolError(
            EXIT_INPUT_INVALID, f"material table unreadable: {exc}", path=str(path)
        ) from exc

    entries = data.get("materials")
    if not isinstance(entries, list) or not entries:
        raise ToolError(
            EXIT_INPUT_INVALID, "material table has no 'materials' array", path=str(path)
        )

    materials: dict[str, Material] = {}
    for entry in entries:
        material_id = entry.get("materialId")
        density = entry.get("densityKgPerM3")
        if not isinstance(material_id, str) or not isinstance(density, int | float):
            raise ToolError(
                EXIT_INPUT_INVALID,
                f"malformed material entry: {entry!r}",
                path=str(path),
            )
        if density <= 0:
            raise ToolError(
                EXIT_INPUT_INVALID,
                f"material '{material_id}' has non-positive density {density}",
                path=str(path),
            )
        materials[material_id] = Material(
            material_id=material_id,
            density_kg_per_m3=float(density),
            fracture_brittleness=float(entry.get("fractureBrittleness", 0.5)),
        )
    return MaterialTable(materials, path)

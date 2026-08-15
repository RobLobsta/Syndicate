"""``profile.json`` — the real car a model is, in numbers (D15-S11).

Everything else in this pipeline is derived from geometry, and that is the whole point: drop in a
model nobody has measured and get a vehicle that drives. The figures it derives are honest
averages — 8 N of tractive force per kilogram, 175 kg per square metre of footprint — and a
vehicle built from them is a plausible vehicle.

It is not a *particular* vehicle. The Eclipse is a Maserati MC20 and the Stampede is a Mustang
GTD, and the difference between them — 470 kg, 145 kW, and which one wins a standing start
against which — is the entire content of the choice a player makes between the two (DEC-033,
DEC-037). No amount of measuring the mesh recovers that: the published kerb mass, the crank
power and the drag area are research, not geometry.

So a model may carry a ``profile.json`` beside it, and when it does, its figures replace the
derived ones. Absent, nothing changes and the derivation stands — which is what makes this a
calibration rather than a requirement, and keeps "drop in a model" true for the next car nobody
has looked up.

**The Java side is the authority on the numbers and this file is a copy of them.** That is not
duplication left to rot: ``VehicleProfileContentTest`` loads the shipped content and asserts it
against ``dev.syndicate.core.vehicle.VehicleProfiles`` field by field, so a figure that drifts
here fails the build with the name of the figure and the two values.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


class ProfileError(Exception):
    """A ``profile.json`` that cannot be applied, reported rather than guessed around."""


@dataclass
class Profile:
    """The researched figures for one vehicle, or an empty record.

    Each block maps onto exactly one place in the emitted content:

    - ``kerb_mass_kg`` replaces the footprint estimate of :func:`manifest.target_mass_kg`
    - ``chassis_stats`` and ``chassis_handling`` replace :func:`manifest.chassis_stats` and
      :data:`manifest.DEFAULT_CHASSIS_HANDLING` on the chassis part
    - ``wheel_stats`` and ``wheel_handling`` replace :data:`manifest.DEFAULT_WHEEL_HANDLING` on
      every wheel part
    - ``steering_stats`` goes on the **front** wheel only, because only a steering wheel
      contributes steering (D05-S5.6 phase 3 filters on ``isSteering``)
    """

    display_name: str | None = None
    vehicle_class: str | None = None
    reference: str | None = None
    kerb_mass_kg: float | None = None
    chassis_stats: dict = field(default_factory=dict)
    chassis_handling: dict = field(default_factory=dict)
    wheel_stats: dict = field(default_factory=dict)
    wheel_handling: dict = field(default_factory=dict)
    steering_stats: dict = field(default_factory=dict)

    SCHEMA_VERSION = "1.0.0"

    @property
    def is_empty(self) -> bool:
        return self.kerb_mass_kg is None and not self.chassis_stats and not self.wheel_stats

    @classmethod
    def load(cls, model_dir: Path) -> Profile:
        """Reads ``<model_dir>/profile.json``, or returns an empty profile."""
        path = Path(model_dir) / "profile.json"
        if not path.is_file():
            return cls()
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ProfileError(f"{path} is not valid JSON: {exc}") from exc
        version = str(document.get("schemaVersion", cls.SCHEMA_VERSION))
        if not version.startswith("1."):
            raise ProfileError(
                f"{path} declares schemaVersion {version}; this tool understands 1.x"
            )
        return cls.from_document(document, str(path))

    @classmethod
    def from_document(cls, document: dict, where: str = "profile") -> Profile:
        kerb = document.get("kerbMassKg")
        if kerb is not None and not (float(kerb) > 0.0):
            raise ProfileError(f"{where}: kerbMassKg must be positive, not {kerb}")
        return cls(
            display_name=document.get("displayName"),
            vehicle_class=document.get("vehicleClass"),
            reference=document.get("referenceVehicle"),
            kerb_mass_kg=None if kerb is None else float(kerb),
            chassis_stats=_stats(document.get("chassisStats"), where, "chassisStats"),
            chassis_handling=_numbers(document.get("chassisHandling"), where, "chassisHandling"),
            wheel_stats=_stats(document.get("wheelStats"), where, "wheelStats"),
            wheel_handling=_numbers(document.get("wheelHandling"), where, "wheelHandling"),
            steering_stats=_stats(document.get("steeringStats"), where, "steeringStats"),
        )

    def as_report(self) -> dict:
        """What the run applied, for the D15-S4.4 report and the parts manifest."""
        return {
            "applied": not self.is_empty,
            "referenceVehicle": self.reference,
            "kerbMassKg": self.kerb_mass_kg,
            "calibrated": sorted(
                name
                for name, block in (
                    ("chassisStats", self.chassis_stats),
                    ("chassisHandling", self.chassis_handling),
                    ("wheelStats", self.wheel_stats),
                    ("wheelHandling", self.wheel_handling),
                    ("steeringStats", self.steering_stats),
                )
                if block
            ),
        }


def _stats(node, where: str, field_name: str) -> dict:
    """A ``{stat: {add, mul}}`` block, in the shape ``part.json`` carries (D08-S4.2).

    A bare number is accepted and read as ``{"add": n}``, because every calibrated figure in
    practice is additive and making an author write the long form for all of them would be
    ceremony that invites a typo.
    """
    if node is None:
        return {}
    if not isinstance(node, dict):
        raise ProfileError(f"{where}: {field_name} must be an object")
    block = {}
    for name, value in node.items():
        if isinstance(value, dict):
            block[name] = {key: float(value[key]) for key in ("add", "mul") if key in value}
        else:
            block[name] = {"add": float(value)}
    return block


def _numbers(node, where: str, field_name: str) -> dict:
    if node is None:
        return {}
    if not isinstance(node, dict):
        raise ProfileError(f"{where}: {field_name} must be an object")
    return {name: float(value) for name, value in node.items()}

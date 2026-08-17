"""The fracture manifest of D09-S4.4 — the tool's contract with the game and the harness.

Every number here is rounded before it is written. That is not cosmetic: G11 requires two
runs with the same seed to produce an *equal* manifest, and the last bits of a float can
differ between two runs of the same arithmetic on different CPUs. Rounding to a precision
far finer than any tolerance makes equality decidable.
"""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from syndicate_policy.classes import FRACTURE

from .cli import TOOL_VERSION, Args
from .errors import VerificationReport
from .fracture import Shard
from .geometry import Vec3, quantise
from .mass import MassResult

SCHEMA_VERSION = "1.0.0"

#: The file a fracture manifest is written to, beside the part it describes (D08-S4.6). Named
#: for its transform, which is the glossary's word for it (D00-S6): deformation manifests are
#: `deform_manifest.json` and are written by a different tool.
MANIFEST_FILE = "fracture_manifest.json"


def to_game_space(v: Vec3) -> Vec3:
    """Blender Z-up right-handed to game Y-up right-handed (D00-R14, D00-R16).

    ``export_yup=True`` applies this to the *mesh* during glTF export. The manifest
    describes that exported mesh, so every vector it carries must be converted the same
    way — a manifest in Blender space would put a shard's spawn offset on the wrong axis
    and lay the debris out sideways.

    This and the exporter flag are the one conversion D00-R16 permits; nothing downstream
    converts again.
    """
    return (v[0], v[2], -v[1])


def inertia_to_game_space(v: Vec3) -> Vec3:
    """Permute an inertia *diagonal* to match ``to_game_space``.

    The diagonal entries are per-axis moments, so the axis relabelling that sends Blender's
    Z to the game's Y carries ``Izz`` to the Y slot. The sign flip on the third axis does
    not matter here: a moment of inertia is quadratic in position, so mirroring an axis
    leaves it unchanged.
    """
    return (v[0], v[2], v[1])


def aabb_to_game_space(lo: Vec3, hi: Vec3) -> tuple[Vec3, Vec3]:
    """Convert a bounding box, re-deriving min/max per axis.

    Needed because ``to_game_space`` negates one axis: converting the corners alone would
    leave ``aabbMin.z`` greater than ``aabbMax.z``, and every consumer that trusts the
    names would compute a negative extent.
    """
    a = to_game_space(lo)
    b = to_game_space(hi)
    return (
        (min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2])),
        (max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2])),
    )

# Six decimals is roughly a micrometre, four orders of magnitude below MASS_DELTA_FRAC on
# any part this game ships. Fine enough to lose nothing; coarse enough to be stable.
_PRECISION = 6


def build(
    part_type_id: str,
    source_path: Path,
    blender_version: str,
    args: Args,
    shards: list[Shard],
    masses: MassResult,
    part_aabb: tuple[Vec3, Vec3],
    part_hull_vertex_count: int,
    part_hull_pieces: int,
) -> dict[str, Any]:
    """Assemble the manifest (D09-S4.4). The ``verification`` block is filled in later."""
    shard_hull_max = max((s.hull_vertex_count for s in shards), default=0)
    aabb_min, aabb_max = aabb_to_game_space(part_aabb[0], part_aabb[1])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "toolVersion": TOOL_VERSION,
        "blenderVersion": blender_version,
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sourceFile": str(source_path),
        "sourceHash": file_hash(source_path),
        "partTypeId": part_type_id,
        # What this manifest is, and who it is for. Without these two fields a consumer cannot
        # tell a manifest that should exist from one that should not, which is why nothing
        # caught the tool authoring shards for a steel door (DISC-068). The asset gate pairs
        # them against `part.json`'s own destructionClass (A510).
        "transform": str(FRACTURE),
        "destructionClass": args.destruction_class,
        "materialId": masses.material_id,
        "seed": args.seed,
        "parameters": args.parameters_block(),
        "partMassKg": _round(masses.part_mass_kg),
        "partVolumeM3": _round(masses.part_volume_m3),
        "densityKgPerM3": _round(masses.density_kg_per_m3),
        "comLocal": _vec(to_game_space(masses.com_local)),
        "inertiaDiagonal": _vec(inertia_to_game_space(masses.inertia_diagonal)),
        "aabbMin": _vec(aabb_min),
        "aabbMax": _vec(aabb_max),
        # Always empty, and kept rather than dropped: TV-006 compares it with what the exported
        # mesh actually carries, so it is the check that a fracturing part shipped no damage
        # morphs rather than a field describing some this tool authored. Deformation manifests
        # are `deform_manifest.json`, written by `syndicate_deform`.
        "morphTargets": [],
        "morphStats": [],
        "shardCount": len(shards),
        "shards": [_shard(s, masses.material_id, part_type_id) for s in shards],
        "collision": {
            "partHullVertexCount": part_hull_vertex_count,
            "partHullPieces": part_hull_pieces,
            "shardHullMaxVertexCount": shard_hull_max,
        },
        "topologyHash": topology_hash(shards),
        "verification": {"passed": False, "checks": [], "warnings": []},
    }


def attach_verification(manifest: dict[str, Any], report: VerificationReport) -> None:
    """Embed the self-verification result so the manifest is self-describing (D09-R8)."""
    manifest["verification"] = report.to_json()


def topology_hash(shards: list[Shard]) -> str:
    """SHA-256 over sorted, quantised shard geometry — the determinism fingerprint (G11).

    Quantised to 1e-6 m and sorted before hashing, so the hash answers "is this the same
    fracture?" rather than "did these floats come out bit-identical?". The second question
    has a different answer on two CPUs running the same code, which would make the hash
    useless as a regression signal (GOLD-007).
    """
    digest = hashlib.sha256()
    for shard in shards:
        digest.update(shard.name.encode("utf-8"))
        for vertex in sorted(quantise(v, 1e-6) for v in shard.vertices):
            digest.update(f"{vertex[0]},{vertex[1]},{vertex[2]};".encode())
        for triangle in sorted(shard.triangles):
            digest.update(f"{triangle[0]},{triangle[1]},{triangle[2]};".encode())
    return "sha256:" + digest.hexdigest()


def file_hash(path: Path) -> str:
    """SHA-256 of the input, so an agent can detect a stale manifest without re-running."""
    if not path.is_file():
        return "sha256:0"
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def _shard(shard: Shard, material_id: str, part_type_id: str) -> dict[str, Any]:
    assert shard.aabb is not None
    centroid = to_game_space(shard.centroid)
    shard_min, shard_max = aabb_to_game_space(shard.aabb.min, shard.aabb.max)
    return {
        # Stable for a given seed, which is what makes golden comparison by id meaningful
        # (D09-R8, D14-S5.8).
        "id": f"{part_type_id}_shard_{shard.index:03d}",
        "name": shard.name,
        "index": shard.index,
        "massKg": _round(shard.mass_kg),
        "volumeM3": _round(shard.volume_m3),
        "centroid": _vec(centroid),
        "localTransform": {
            # Shards are exported in the part's own space, so their transform relative to
            # the part origin is the identity plus the centroid offset. Debris bodies are
            # spawned at this offset from the part's transform (D07-S5.6).
            "position": _vec(centroid),
            "rotation": {"x": 0.0, "y": 0.0, "z": 0.0, "w": 1.0},
        },
        "aabbMin": _vec(shard_min),
        "aabbMax": _vec(shard_max),
        "vertexCount": shard.vertex_count,
        "faceCount": shard.face_count,
        "hullVertexCount": shard.hull_vertex_count,
        "materialId": material_id,
        "neighbors": shard.neighbors,
    }


def _vec(v: Vec3) -> dict[str, float]:
    return {"x": _round(v[0]), "y": _round(v[1]), "z": _round(v[2])}


def _round(value: float) -> float:
    # `+ 0.0` normalises -0.0 to 0.0, which would otherwise make two equal manifests
    # compare unequal as JSON text.
    return round(float(value), _PRECISION) + 0.0

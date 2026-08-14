"""Stage 4: mass assignment (D09-S5.4, D09-S6).

Mass is never authored per shard: it is always ``volume x density``, computed from the
actual geometry (D09-R16). That is what makes G7 — mass conservation across a fracture — a
*consequence* of the algorithm rather than a constraint bolted on afterwards.
"""

from __future__ import annotations

from dataclasses import dataclass

from .cli import Args
from .errors import EXIT_INPUT_GEOMETRY_INVALID, EXIT_MASS_IMPLAUSIBLE, ToolError, log
from .fracture import Shard
from .geometry import Tri, Vec3, inertia_diagonal, mesh_centroid, mesh_volume
from .materials import MaterialTable

# D00-S6.4. A body lighter than this is not simulable; Bullet treats it as very nearly
# static and it jitters.
MIN_BODY_MASS_KG = 0.01


@dataclass
class MassResult:
    """The mass properties of the intact part, for the manifest."""

    material_id: str
    density_kg_per_m3: float
    part_volume_m3: float
    part_mass_kg: float
    com_local: Vec3
    inertia_diagonal: Vec3


def assign_masses(
    source_vertices: list[Vec3],
    source_triangles: list[Tri],
    shards: list[Shard],
    material_id: str,
    materials: MaterialTable,
    args: Args,
    part_volume_override: float | None = None,
) -> MassResult:
    """Compute the part's mass properties and each shard's mass (D09-S6.2).

    ``part_volume_override`` is for a source whose material volume is not what it encloses —
    a shell, whose volume is its area times its thickness (D09-S5.2.1). Everything after it
    is unchanged, including the shard-sum cross-check, which is the point: the shell path has
    to satisfy the same conservation law by the same arithmetic.
    """
    density = materials.resolve(material_id).density_kg_per_m3

    part_volume = (
        part_volume_override
        if part_volume_override is not None
        else mesh_volume(source_vertices, source_triangles)
    )
    if part_volume <= 0.0:
        raise ToolError(
            EXIT_INPUT_GEOMETRY_INVALID,
            "source mesh has zero volume: it is probably not watertight",
            volumeM3=part_volume,
        )
    part_mass = part_volume * density

    if args.expected_mass is not None:
        delta = abs(part_mass - args.expected_mass) / max(args.expected_mass, 1e-9)
        if delta > args.mass_tolerance:
            raise ToolError(
                EXIT_MASS_IMPLAUSIBLE,
                f"computed {part_mass:.4f} kg vs expected {args.expected_mass:.4f} kg "
                "— check units, density, and watertightness",
                computedKg=part_mass,
                expectedKg=args.expected_mass,
                relativeDelta=delta,
            )

    total = 0.0
    for shard in shards:
        shard.volume_m3 = mesh_volume(shard.vertices, shard.triangles)
        shard.mass_kg = shard.volume_m3 * density
        if shard.mass_kg <= MIN_BODY_MASS_KG:
            # Sub-minimum cells were merged in stage 2, so reaching here means the merge
            # threshold and the mass threshold disagree — a tool bug, not bad content.
            raise ToolError(
                EXIT_MASS_IMPLAUSIBLE,
                f"shard {shard.name} mass {shard.mass_kg:.6f} kg is below the minimum "
                "after merging",
                shard=shard.name,
                massKg=shard.mass_kg,
                minimumKg=MIN_BODY_MASS_KG,
            )
        total += shard.mass_kg

    # Voronoi cells leave a hair of a gap at every boundary (the cell margin), so the sum
    # runs slightly *under* the part's mass. The tolerance covers that; a deviation beyond
    # it means geometry was lost, not that floats drifted.
    deviation = abs(total - part_mass)
    if deviation > args.mass_tolerance * part_mass:
        raise ToolError(
            EXIT_MASS_IMPLAUSIBLE,
            f"shard mass sum {total:.4f} kg deviates {100 * deviation / part_mass:.2f}% "
            f"from part mass {part_mass:.4f} kg",
            shardSumKg=total,
            partMassKg=part_mass,
            deviationFrac=deviation / part_mass,
            toleranceFrac=args.mass_tolerance,
        )

    # D09-R17: the rescale comes *after* the tolerance check, never instead of it.
    # Rescaling first would make conservation tautologically true and hide a broken
    # fracture behind a green check.
    scale_factor = part_mass / total if total > 0 else 1.0
    for shard in shards:
        shard.mass_kg *= scale_factor
    log("INFO", f"mass conservation: sum {total:.4f} kg -> rescaled by {scale_factor:.6f}")

    return MassResult(
        material_id=material_id,
        density_kg_per_m3=density,
        part_volume_m3=part_volume,
        part_mass_kg=part_mass,
        com_local=mesh_centroid(source_vertices, source_triangles),
        inertia_diagonal=inertia_diagonal(source_vertices, source_triangles, part_mass),
    )

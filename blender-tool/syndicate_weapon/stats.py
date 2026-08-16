"""Stage 8: family, stats and mass, derived from the gun's own geometry against D01's balance table.

The direction of authority is the point of this module (D17-R51). **D01-S4.4 is the balance
contract**, so fire rate, range and projectile speed come from the family's row there and never from
a mesh — a pipeline that derived a fire rate from geometry would be inventing balance from art. What
geometry *does* decide is which family the thing is, and how big it is.

Nothing here touches Blender.
"""

from __future__ import annotations

from .labels import BARREL, FEED, MUZZLE, TARGET_LENGTH_M

#: The D01-S4.4 rows this pipeline can produce, as
#: ``family -> (fire_rate_per_s, range_m, projectile_speed_mps, base_damage, spread_rad, heat)``.
#:
#: `ROCKET`, `MORTAR` and `FLAMER` are absent deliberately: D17-R50 makes them reachable only
#: through ``--family``, because nothing in a static mesh distinguishes a rocket pod from a box and
#: D17-NG2 forbids guessing. They are still listed here so that an explicit ``--family`` produces a
#: complete part rather than a defaulted one.
FAMILY_ROWS = {
    "AUTOCANNON": (6.0, 200.0, 600.0, 22.0, 0.012, 0.06),
    "CANNON": (0.4, 300.0, 250.0, 320.0, 0.004, 0.30),
    "SHOTGUN": (1.0, 25.0, 400.0, 90.0, 0.090, 0.10),
    "ROCKET": (0.5, 150.0, 120.0, 210.0, 0.020, 0.20),
    "MORTAR": (0.3, 120.0, 100.0, 260.0, 0.030, 0.25),
    "FLAMER": (0.0, 12.0, 0.0, 55.0, 0.140, 0.04),
    "LASER": (0.0, 100.0, 0.0, 70.0, 0.0, 0.09),
}

#: Damage multiplier per size class (D17-R51). Spread is divided by the same figure — a bigger gun
#: of a family is the more accurate one, which is what makes the size classes a trade rather than a
#: strict ordering.
SIZE_DAMAGE_FACTOR = {"LIGHT": 0.7, "MEDIUM": 1.0, "HEAVY": 1.6}

#: Plausible finished mass per size class, in kg (D17-R52). Outside this band the tool exits 85
#: rather than shipping a 4 kg cannon.
MASS_BAND_KG = {"LIGHT": (8.0, 120.0), "MEDIUM": (25.0, 280.0), "HEAVY": (60.0, 750.0)}

#: Calibre ratio above which a barrel is a cannon's rather than an autocannon's (D17-R49).
#:
#: Measured off the two shipped models rather than off real ordnance, and the gap between them is
#: wide: the machine gun's barrel assembly is 0.16 of its own length across and the siege cannon's
#: is 0.23. Real-world figures would put this near 0.06, which classifies both shipped models as
#: cannon — game art is chunkier than the thing it depicts, and a threshold has to be calibrated
#: against what it will actually see.
CALIBRE_RATIO_CANNON = 0.19

#: Bulk ratios separating the three size classes (D17-R26). See :func:`bulk_ratio`.
BULK_RATIO_LIGHT = 0.35
BULK_RATIO_MEDIUM = 0.55

#: Rounds a feed of one cubic decimetre holds, by family. Used only when the model has a `feed`.
ROUNDS_PER_LITRE = {"AUTOCANNON": 14, "CANNON": 1, "SHOTGUN": 8, "ROCKET": 1, "MORTAR": 1}

#: Ammunition when no `feed` sub-part exists, by family. `-1` is unlimited (`WeaponBlock`).
DEFAULT_AMMO = {"AUTOCANNON": 400, "CANNON": 24, "SHOTGUN": 60, "ROCKET": 12, "MORTAR": 16,
                "FLAMER": -1, "LASER": -1}


def derive_family(parts, bore) -> tuple[str, float, str]:
    """Which of D01-S4.4's families this is, from the bore (D17-R49).

    The two inputs are the **bore aspect** and the **calibre ratio** — bore diameter over barrel
    length — because that ratio is what physically separates a machine gun from a howitzer. A 0.6 m
    machine gun barrel is 25 mm across; a 1.5 m cannon barrel is 150 mm.

    Returns ``(family, confidence, because)``. The confidence is carried into the report so a weakly
    held classification is visible rather than indistinguishable from a firm one (D17-R50).
    """
    barrels = [p for p in parts if p.label == BARREL]
    if not barrels:
        return "LASER", 0.35, "no barrel-like sub-part; nothing in the mesh says it fires a shot"

    barrel = max(barrels, key=lambda p: p.area_m2)
    length = _extent_along(barrel, bore)
    diameter = _cross_extent(barrel, bore)
    if length <= 1e-6:
        return "AUTOCANNON", 0.3, "barrel has no measurable length"
    aspect = length / diameter if diameter > 1e-9 else 99.0
    calibre_ratio = diameter / length

    if aspect >= 3.0 and calibre_ratio < CALIBRE_RATIO_CANNON:
        return "AUTOCANNON", 0.85, f"bore aspect {aspect:.1f}, calibre ratio {calibre_ratio:.3f}"
    if aspect >= 3.0:
        return "CANNON", 0.85, f"bore aspect {aspect:.1f}, calibre ratio {calibre_ratio:.3f}"
    if _has_multiple_bores(parts, bore):
        return "AUTOCANNON", 0.7, "several bores repeated at a radius: a rotary gun"
    if calibre_ratio >= CALIBRE_RATIO_CANNON:
        return "CANNON", 0.6, f"short but wide-bored (calibre ratio {calibre_ratio:.3f})"
    return "SHOTGUN", 0.5, f"short single bore (aspect {aspect:.1f})"


def derive_size(parts, bore, family: str) -> tuple[str, str]:
    """The size class a weapon lands in (D17-R26).

    **Scale-invariant on purpose.** By the time this runs the model has been normalised to unit bore
    length (D17-R23a), so its absolute size carries no information — and it never did: a downloaded
    model's units are unknown, so a rule reading "is it longer than 0.75 m" is reading whatever the
    artist happened to export in. What *is* scale-invariant is the gun's own proportions, and the
    calibre ratio is the one that separates a machine gun from a howitzer physically rather than by
    fiat.

    Scaling is then a consequence of the classification rather than the other way round. The reverse
    would make every weapon whatever class the operator scaled it into, and the gate would mean
    nothing.
    """
    if family == "CANNON":
        return "HEAVY", "a cannon is a vehicle-scale gun by definition (D01-S4.4)"
    if family in ("SHOTGUN", "FLAMER"):
        return "LIGHT", f"a {family.lower()} is a close-range sidearm (D01-S4.4)"
    bulk = bulk_ratio(parts, bore)
    if bulk <= BULK_RATIO_LIGHT:
        return "LIGHT", f"bulk ratio {bulk:.2f}: slender for its length"
    if bulk <= BULK_RATIO_MEDIUM:
        return "MEDIUM", f"bulk ratio {bulk:.2f}"
    return "HEAVY", f"bulk ratio {bulk:.2f}: as wide as it is long"


def bulk_ratio(parts, bore) -> float:
    """The weapon's largest cross-section over its bore length — how bulky it is for its length.

    This, and not the barrel's calibre, is what size class means: D17-R7 makes size class a
    statement about **bulk and mounting**, and mass a separate gate about load. A pintle machine gun
    is a thin rod (0.29 on the shipped one) and a siege gun on a carriage is nearly as wide as it is
    long (0.77). Calibre ratio separates the two *families* well and separates their *sizes* badly,
    because a stubby light gun and a stubby heavy gun have the same calibre ratio and are not the
    same size of thing.
    """
    # A synthesised mount is this pipeline's own invention (D17-R41), so counting it would let the
    # tool's guess about a mounting boss decide the weapon's size class.
    real = [p for p in parts if not p.synthesised] or list(parts)
    along, lateral, vertical = [], [], []
    axis = bore.axis
    seed = (0.0, 0.0, 1.0) if abs(axis[2]) < 0.9 else (1.0, 0.0, 0.0)
    u = _normalise(_cross(axis, seed))
    v = _cross(axis, u)
    for part in real:
        for corner in _corners(part):
            along.append(bore.coordinate_of(corner))
            lateral.append(sum(corner[i] * u[i] for i in range(3)))
            vertical.append(sum(corner[i] * v[i] for i in range(3)))
    if not along:
        return 0.0
    length = max(along) - min(along)
    # The larger perpendicular *span*, not twice the largest radius: the bore line runs through the
    # barrel rather than through the weapon's centre, so a radius from it double-counts the offset
    # and reported the shipped machine gun as a third bulkier than it is.
    cross = max(max(lateral) - min(lateral), max(vertical) - min(vertical))
    return cross / length if length > 1e-9 else 0.0


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normalise(vec):
    import math

    length = math.sqrt(sum(c * c for c in vec))
    if length < 1e-12:
        return (1.0, 0.0, 0.0)
    return (vec[0] / length, vec[1] / length, vec[2] / length)


def target_length(size_class: str) -> float:
    return TARGET_LENGTH_M[size_class]


def stat_block(family: str, size_class: str) -> dict:
    """The D05-S4.5 stats a weapon of this family and size exposes (D17-R51)."""
    rate, _range_m, speed, damage, spread, heat = FAMILY_ROWS[family]
    factor = SIZE_DAMAGE_FACTOR[size_class]
    stats = {
        "damagePerShot": {"add": round(damage * factor, 3)},
        "heatPerShot": {"add": round(heat, 4)},
    }
    if rate > 0.0:
        stats["fireIntervalS"] = {"add": round(1.0 / rate, 4)}
    if speed > 0.0:
        stats["projectileSpeedMps"] = {"add": round(speed, 1)}
    if spread > 0.0:
        # Divided, not multiplied: a bigger gun of a family is the more accurate one (D17-R51).
        stats["spreadRad"] = {"add": round(spread / factor, 5)}
    return stats


def fire_interval_s(family: str) -> float:
    rate = FAMILY_ROWS[family][0]
    return 1.0 / rate if rate > 0.0 else 0.05


def weapon_block(family: str, parts, bore, muzzle_local) -> dict:
    """The D08-R5 ``weapon`` block: what the gun *is*, as against what it does (DEC-039)."""
    return {
        "family": family,
        "damageType": None,
        "ammoCapacity": ammo_capacity(family, parts),
        "blastRadiusM": 3.5 if family in ("ROCKET", "MORTAR") else 0.0,
        "rangeM": round(FAMILY_ROWS[family][1], 1),
        "muzzleLocal": {
            "x": round(muzzle_local[0], 5),
            "y": round(muzzle_local[1], 5),
            "z": round(muzzle_local[2], 5),
        },
    }


def ammo_capacity(family: str, parts) -> int:
    """Rounds at spawn: derived from the feed's volume where there is one, else the family
    default."""
    feeds = [p for p in parts if p.label == FEED]
    if not feeds:
        return DEFAULT_AMMO.get(family, -1)
    litres = sum(p.size[0] * p.size[1] * p.size[2] for p in feeds) * 1000.0
    per_litre = ROUNDS_PER_LITRE.get(family, 1)
    return max(1, round(litres * per_litre))


def power_cost(family: str, size_class: str) -> float:
    """The balance currency a weapon spends, from damage per second and range (D17-R53).

    On the same scale D05-S5.7 uses for a vehicle's own parts, so that fitting a gun spends the
    budget the chassis and wheels are drawn against rather than a second currency nobody reconciles.
    """
    rate, range_m, _speed, damage, _spread, _heat = FAMILY_ROWS[family]
    effective_rate = rate if rate > 0.0 else 8.0
    dps = damage * SIZE_DAMAGE_FACTOR[size_class] * effective_rate
    return round(dps * 0.05 + range_m * 0.02, 3)


def check_mass(total_kg: float, size_class: str) -> None:
    """D17-R52: a derived mass outside the plausible band is exit 85, not a shipped 4 kg cannon."""
    lo, hi = MASS_BAND_KG[size_class]
    if not (lo <= total_kg <= hi):
        raise ValueError(
            f"derived mass {total_kg:.1f} kg is outside the plausible band for a "
            f"{size_class} weapon "
            f"({lo:.0f}-{hi:.0f} kg); the geometry, the size class or the areal densities disagree "
            "(D17-R52)"
        )


def muzzle_local(parts, bore, mount_origin) -> tuple:
    """Where shots leave, in the mount's local space: the forward extent on the bore centreline."""
    forward = [p for p in parts if p.label in (MUZZLE, BARREL)]
    if not forward:
        forward = list(parts)
    if not forward:
        return (0.0, 0.0, 0.0)
    best = max(_extent_along(p, bore) + bore.coordinate_of(p.centroid) for p in forward)
    tip = tuple(bore.origin[i] + best * bore.axis[i] for i in range(3))
    return tuple(tip[i] - mount_origin[i] for i in range(3))


def _has_multiple_bores(parts, bore) -> bool:
    barrels = [p for p in parts if p.label == BARREL]
    return len(barrels) >= 3


def _extent_along(part, bore) -> float:
    corners = _corners(part)
    along = [bore.coordinate_of(c) for c in corners]
    return max(along) - min(along)


def _cross_extent(part, bore) -> float:
    """The barrel's outside diameter: the larger extent perpendicular to the bore.

    Measured from the two perpendicular *extents* rather than from the maximum corner radius. A
    bounding box's corner sits at the diagonal, so a corner radius overstates the diameter by up to
    root two — enough on the shipped machine gun to push its calibre ratio from 0.11 to 0.16 and
    classify a machine gun as a cannon.
    """
    lo, hi = part.lo, part.hi
    axis = bore.axis
    # The extent along each world axis, weighted by how perpendicular that axis is to the bore.
    extents = [(hi[i] - lo[i], 1.0 - abs(axis[i])) for i in range(3)]
    perpendicular = [e for e, weight in sorted(extents, key=lambda t: -t[1])[:2]]
    return max(perpendicular) if perpendicular else 0.0


def _model_extent_along(parts, bore) -> float:
    along = []
    for part in parts:
        along.extend(bore.coordinate_of(c) for c in _corners(part))
    return (max(along) - min(along)) if along else 0.0


def _corners(part):
    return [
        (x, y, z)
        for x in (part.lo[0], part.hi[0])
        for y in (part.lo[1], part.hi[1])
        for z in (part.lo[2], part.hi[2])
    ]

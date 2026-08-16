"""Unit tests for the weapon pipeline's decision modules (D17-S8, T-D17-1 to T-D17-8).

Every module under test is pure Python by construction — the Blender-touching half lives in
``syndicate_weapon.weapon`` and is exercised by running the tool on real art — so these run with no
Blender host at all, which is the property that makes them worth having in CI.
"""

from __future__ import annotations

import math

import pytest

from syndicate_prepare.shell import Shell
from syndicate_weapon import articulate, bore, cues, graph, grouping, labels, selfverify, stats


def make_shell(index, centre, size, triangles=64, material=None, samples=None):
    """A shell at ``centre`` with extents ``size``, sampled on its own bounding box."""
    lo = tuple(centre[i] - size[i] / 2 for i in range(3))
    hi = tuple(centre[i] + size[i] / 2 for i in range(3))
    if samples is None:
        samples = tuple(
            (x, y, z)
            for x in (lo[0], centre[0], hi[0])
            for y in (lo[1], centre[1], hi[1])
            for z in (lo[2], centre[2], hi[2])
        )
    return Shell(
        index=index, name=f"shell_{index}", material=material, triangles=triangles,
        lo=lo, hi=hi, centroid=tuple(centre), area_m2=0.1, volume_m3=0.001,
        vertex_sample=samples,
    )


def tube(index, z_centre, length, radius, triangles=64, rings=12):
    """A cylinder on the +Z axis, sampled as two rings — the shape every barrel test needs."""
    samples = []
    for end in (z_centre - length / 2, z_centre + length / 2):
        for k in range(rings):
            angle = 2 * math.pi * k / rings
            samples.append((radius * math.cos(angle), radius * math.sin(angle), end))
    shell = make_shell(index, (0.0, 0.0, z_centre), (2 * radius, 2 * radius, length), triangles,
                       samples=tuple(samples))
    return shell


# ---- T-D17-1: the bore-axis finder --------------------------------------------------------


def test_bore_axis_recovers_a_known_axis_and_sense():
    """T-D17-1. A tapered tube's axis and its pointing direction are both recovered."""
    # Narrow at +Z, wide at -Z: a barrel, pointing +Z.
    samples = []
    for i in range(21):
        t = i / 20.0
        z = t
        radius = 0.10 - 0.06 * t
        for k in range(8):
            angle = 2 * math.pi * k / 8
            samples.append((radius * math.cos(angle), radius * math.sin(angle), z))
    shell = make_shell(0, (0.0, 0.0, 0.5), (0.2, 0.2, 1.0), 500, samples=tuple(samples))

    found = bore.find([shell])

    assert abs(abs(found.axis[2]) - 1.0) < 0.02, "the axis should be the tube's own long axis"
    assert found.axis[2] > 0, "the tube narrows toward +Z, so the shot leaves that way"
    assert found.confidence > 0.5


def test_bore_axis_falls_back_to_the_whole_model_with_no_barrel():
    """D17-E1. A laser emitter has no barrel; a dominant axis still exists."""
    blob = make_shell(0, (0.0, 0.0, 0.0), (0.4, 0.2, 0.2), 200)
    found = bore.find([blob])
    assert "no barrel-like shell" in found.because
    assert abs(found.axis[0]) > 0.8, "the blob's own long axis is X"


def test_bore_fit_ignores_slivers():
    """D17-R24. Hundreds of technically-slender rivets must not outvote the one real barrel."""
    barrel = tube(0, 0.5, 1.0, 0.05, triangles=600)
    # Fifty slender pins, all lying along X, each an order of magnitude shorter than the barrel.
    slivers = [
        make_shell(i + 1, (0.0, 0.2 + 0.01 * i, 0.1), (0.06, 0.01, 0.01), 12)
        for i in range(50)
    ]
    found = bore.find([barrel, *slivers])
    assert abs(found.axis[2]) > 0.9, "the barrel decides the axis, not fifty pins"


# ---- T-D17-2, T-D17-3: the cue ensemble ---------------------------------------------------


def unit_bore():
    return bore.Bore(axis=(0.0, 0.0, 1.0), origin=(0.0, 0.0, 0.0), confidence=1.0, because="test")


def test_axial_cue_labels_a_synthetic_gun_in_order():
    """T-D17-2. Breech, receiver, barrel and muzzle, back to front along the bore."""
    shells = [
        make_shell(0, (0.0, 0.0, 0.05), (0.10, 0.10, 0.10), 120),   # breech, at the back
        make_shell(1, (0.0, 0.0, 0.30), (0.09, 0.09, 0.25), 200),   # receiver
        tube(2, 0.70, 0.45, 0.025, triangles=180),                   # barrel
        make_shell(3, (0.0, 0.0, 0.97), (0.05, 0.05, 0.05), 60),     # muzzle, at the front
    ]
    cues.label_shells(shells, unit_bore())
    got = {s.index: s.label for s in shells}
    assert got[0] == labels.BREECH
    assert got[1] == labels.RECEIVER
    assert got[2] == labels.BARREL
    assert got[3] == labels.MUZZLE


def test_structural_cue_collects_coaxial_tubes_into_one_barrel():
    """T-D17-3. A bore, a barrel and a shroud are one barrel part, not three."""
    shells = [
        make_shell(0, (0.0, 0.0, 0.15), (0.10, 0.10, 0.20), 200),  # receiver behind them
        tube(1, 0.65, 0.50, 0.020, triangles=120),                  # bore
        tube(2, 0.65, 0.50, 0.030, triangles=120),                  # barrel
        tube(3, 0.65, 0.48, 0.040, triangles=120),                  # shroud
    ]
    cues.label_shells(shells, unit_bore())
    barrels = [s.index for s in shells if s.label == labels.BARREL]
    assert barrels == [1, 2, 3]

    parts = grouping.group(shells, unit_bore(), "w")
    barrel_parts = [p for p in parts if p.label == labels.BARREL]
    assert len(barrel_parts) == 1, "three coaxial tubes are one barrel"
    assert sorted(barrel_parts[0].shells) == [1, 2, 3]


def test_rotational_repetition_finds_a_gear_ring_and_not_a_scatter():
    """T-D17-4. Six congruent shells at a common radius are a gear; five random ones are not."""
    ring = [
        make_shell(i, (0.09 * math.cos(i * math.pi / 3), 0.09 * math.sin(i * math.pi / 3), 0.3),
                   (0.02, 0.02, 0.02), 40)
        for i in range(6)
    ]
    votes = cues.structural_votes(ring, cues._measure(ring, unit_bore()))
    assert any(v.label == labels.GEAR for _, v in votes)

    scatter = [
        make_shell(i, (0.05 + 0.03 * i, 0.02 * i, 0.2 + 0.05 * i),
                   (0.02 + 0.005 * i, 0.03, 0.02), 40 + 7 * i)
        for i in range(5)
    ]
    votes = cues.structural_votes(scatter, cues._measure(scatter, unit_bore()))
    assert not any(v.label == labels.GEAR for _, v in votes)


def test_material_cue_matches_whole_tokens_only():
    """DISC-037's lesson: a substring match on `wheel` once took a third of a car."""
    named = make_shell(0, (0, 0, 0), (0.1, 0.1, 0.1), material="vehicle_smallspecmap_WHEELBASE")
    assert not any(v.label == labels.GEAR for v in cues.material_votes(named))
    exact = make_shell(1, (0, 0, 0), (0.1, 0.1, 0.1), material="Cannon_Gear_A")
    assert any(v.label == labels.GEAR for v in cues.material_votes(exact))


# ---- T-D17-5: the seam rule ---------------------------------------------------------------


def test_seam_sits_on_the_contact_region_not_on_either_centroid():
    """T-D17-5. Two parts meeting at a plane join there, wherever their mass happens to be."""
    # A long parent whose centroid is far from the join, and a short child meeting it at z = 0.5.
    parent = grouping.SubPart(label=labels.RECEIVER, name="receiver", shells=[0])
    parent.lo, parent.hi, parent.centroid = (-0.05, -0.05, 0.0), (0.05, 0.05, 0.5), (0.0, 0.0, 0.1)
    child = grouping.SubPart(label=labels.BARREL, name="barrel", shells=[1])
    child.lo, child.hi, child.centroid = (-0.02, -0.02, 0.5), (0.02, 0.02, 1.5), (0.0, 0.0, 1.3)

    vertices = {
        0: tuple((x, y, 0.5) for x in (-0.03, 0.0, 0.03) for y in (-0.03, 0.0, 0.03)),
        1: tuple((x, y, 0.5) for x in (-0.02, 0.0, 0.02) for y in (-0.02, 0.0, 0.02)),
    }
    seam = graph.measure_seam(parent, child, vertices, bore_length_m=1.0)

    assert seam.is_closed, "the two parts touch, so this is a real contact"
    assert abs(seam.position[2] - 0.5) < 1e-6, "the join is at the contact plane"
    assert abs(seam.position[2] - parent.centroid[2]) > 0.3, "and not at the parent's centroid"
    assert abs(seam.position[2] - child.centroid[2]) > 0.3, "nor at the child's"


def test_seam_reports_a_guess_when_two_parts_do_not_meet():
    """D17-R44. A join between parts that touch nothing is a guess, and says so."""
    parent = grouping.SubPart(label=labels.RECEIVER, name="receiver", shells=[0])
    parent.lo, parent.hi, parent.centroid = (0, 0, 0), (0.1, 0.1, 0.1), (0.05, 0.05, 0.05)
    child = grouping.SubPart(label=labels.BARREL, name="barrel", shells=[1])
    child.lo, child.hi, child.centroid = (0, 0, 5), (0.1, 0.1, 5.1), (0.05, 0.05, 5.05)
    vertices = {0: ((0.0, 0.0, 0.0),) * 3, 1: ((0.0, 0.0, 5.0),) * 3}

    seam = graph.measure_seam(parent, child, vertices, bore_length_m=1.0)
    assert not seam.is_closed
    assert seam.contact_points == 0


# ---- T-D17-6: size-class gating -----------------------------------------------------------


@pytest.mark.parametrize(
    "slot_class,part_class,accepted",
    [
        ("HEAVY", "LIGHT", True), ("HEAVY", "MEDIUM", True), ("HEAVY", "HEAVY", True),
        ("MEDIUM", "LIGHT", True), ("MEDIUM", "MEDIUM", True), ("MEDIUM", "HEAVY", False),
        ("LIGHT", "LIGHT", True), ("LIGHT", "MEDIUM", False), ("LIGHT", "HEAVY", False),
    ],
)
def test_size_class_accepts_its_own_class_and_below(slot_class, part_class, accepted):
    """T-D17-6. The Python side of D17-R7.2; the Java side is SizeClassTest."""
    order = {"LIGHT": 0, "MEDIUM": 1, "HEAVY": 2}
    assert (order[part_class] <= order[slot_class]) is accepted


# ---- T-D17-7: family derivation -----------------------------------------------------------


def barrel_part(length, diameter):
    part = grouping.SubPart(label=labels.BARREL, name="barrel", shells=[0])
    part.lo = (-diameter / 2, -diameter / 2, 0.0)
    part.hi = (diameter / 2, diameter / 2, length)
    part.centroid = (0.0, 0.0, length / 2)
    part.area_m2 = math.pi * diameter * length
    return part


def test_family_derives_autocannon_and_cannon_from_the_calibre_ratio():
    """T-D17-7. The ratio that separates a machine gun from a howitzer, physically."""
    slender = barrel_part(length=0.5, diameter=0.05)     # ratio 0.10
    family, confidence, _ = stats.derive_family([slender], unit_bore())
    assert family == "AUTOCANNON"
    assert confidence > 0.5

    stubby = barrel_part(length=0.5, diameter=0.15)      # ratio 0.30
    family, _, _ = stats.derive_family([stubby], unit_bore())
    assert family == "CANNON"


def test_family_is_laser_when_nothing_looks_like_a_barrel():
    """D17-E1, and it is reported as weakly held rather than asserted."""
    lump = grouping.SubPart(label=labels.RECEIVER, name="receiver", shells=[0])
    lump.lo, lump.hi, lump.centroid = (0, 0, 0), (0.2, 0.2, 0.2), (0.1, 0.1, 0.1)
    family, confidence, _ = stats.derive_family([lump], unit_bore())
    assert family == "LASER"
    assert confidence < 0.5, "a family nothing supports must not be reported confidently"


def test_stats_come_from_the_family_table_not_from_the_mesh():
    """D17-R51. Balance is content; a pipeline that derived fire rate from art would invent it."""
    for size in ("LIGHT", "MEDIUM", "HEAVY"):
        block = stats.stat_block("AUTOCANNON", size)
        assert block["fireIntervalS"]["add"] == pytest.approx(1.0 / 6.0, abs=1e-4)
        assert block["projectileSpeedMps"]["add"] == pytest.approx(600.0)
    # Damage scales with size and spread scales inversely: a bigger gun of a family is the accurate
    # one.
    light = stats.stat_block("AUTOCANNON", "LIGHT")
    heavy = stats.stat_block("AUTOCANNON", "HEAVY")
    assert heavy["damagePerShot"]["add"] > light["damagePerShot"]["add"]
    assert heavy["spreadRad"]["add"] < light["spreadRad"]["add"]


def test_implausible_mass_is_rejected():
    """D17-R52. A 4 kg cannon is what this exists to stop."""
    with pytest.raises(ValueError):
        stats.check_mass(4.0, "HEAVY")
    stats.check_mass(280.0, "HEAVY")


# ---- T-D17-8: articulation authoring ------------------------------------------------------


def test_barrel_gets_recoil_along_the_bore_at_four_percent_of_its_length():
    """T-D17-8. The travel is a mechanism's figure, not a look (D17-R47)."""
    part = barrel_part(length=1.5, diameter=0.08)
    articulate.author([part], unit_bore(), "CANNON", stats.fire_interval_s("CANNON"))
    block = part.articulation
    assert block["motion"] == "RECOIL"
    assert block["driver"] == "FIRE"
    assert block["axisLocal"] == {"x": 0.0, "y": 0.0, "z": 1.0}
    assert block["travelM"] == pytest.approx(0.06, abs=1e-3), "4% of a 1.5 m barrel"


def test_recoil_travel_is_capped():
    part = barrel_part(length=6.0, diameter=0.2)
    articulate.author([part], unit_bore(), "CANNON", 2.5)
    assert part.articulation["travelM"] == pytest.approx(articulate.MAX_RECOIL_TRAVEL_M)


def test_a_box_magazine_does_not_move():
    """D17-R47. Inventing a spin for something with no mechanism is the defect, not the fix."""
    feed = grouping.SubPart(label=labels.FEED, name="feed", shells=[0])
    feed.lo, feed.hi, feed.centroid = (0, 0, 0), (0.1, 0.2, 0.1), (0.05, 0.1, 0.05)
    feed.repetition = 1
    articulate.author([feed], unit_bore(), "AUTOCANNON", 0.16)
    assert feed.articulation is None


# ---- Grouping and the taxonomy ------------------------------------------------------------


def test_a_receiver_is_never_split_by_side():
    """A gun has one receiver. Splitting it produced `receiver_l` and `receiver_r`, which is not a
    weapon — and no tolerance tuning fixes it, because the geometry really is on both sides."""
    left = make_shell(0, (-0.05, 0.0, 0.3), (0.05, 0.05, 0.2), 100)
    right = make_shell(1, (0.05, 0.0, 0.3), (0.05, 0.05, 0.2), 100)
    for shell in (left, right):
        shell.label = labels.RECEIVER
    parts = grouping.group([left, right], unit_bore(), "w")
    receivers = [p for p in parts if p.label == labels.RECEIVER]
    assert len(receivers) == 1
    assert receivers[0].name == "receiver"


def test_furniture_does_split_by_side():
    left = make_shell(0, (-0.08, 0.0, 0.3), (0.02, 0.10, 0.2), 100)
    right = make_shell(1, (0.08, 0.0, 0.3), (0.02, 0.10, 0.2), 100)
    for shell in (left, right):
        shell.label = labels.FURNITURE
    parts = grouping.group([left, right], unit_bore(), "w")
    names = sorted(p.name for p in parts if p.label == labels.FURNITURE)
    assert names == ["furniture_l", "furniture_r"]


def test_a_weapon_with_no_mount_gets_one_synthesised():
    """D17-R41, D17-E8. Most gun models are modelled without whatever they bolt to."""
    shell = make_shell(0, (0.0, 0.0, 0.3), (0.08, 0.08, 0.3), 200)
    shell.label = labels.RECEIVER
    parts = grouping.group([shell], unit_bore(), "w")
    mounts = [p for p in parts if p.label == labels.MOUNT]
    assert len(mounts) == 1
    assert mounts[0].synthesised


def test_every_taxonomy_label_has_a_complete_row():
    """D17-R2: the taxonomy is closed, so a label missing from any table is a defect."""
    for label in labels.LABELS:
        assert label in labels.PART_CATEGORY
        assert label in labels.SLOT_TYPE_REQUIRED
        assert label in labels.DESTRUCTION_CLASS
        assert label in labels.DEFAULT_MATERIAL
        assert label in labels.AREAL_DENSITY_KG_PER_M2


def test_slot_type_agrees_with_part_category():
    """A part whose category its slot type will not accept does not load (D05-S5.1)."""
    accepts = {
        "TURRET_MOUNT": {"WEAPON"},
        "SUBSLOT": {"WEAPON", "UTILITY", "DECORATIVE"},
        "HARDPOINT": {"WEAPON", "UTILITY"},
    }
    for label in labels.LABELS:
        slot = labels.SLOT_TYPE_REQUIRED[label]
        assert labels.PART_CATEGORY[label] in accepts[slot], label


# ---- Self-verification --------------------------------------------------------------------


def test_self_verification_rejects_a_second_mount():
    parts = []
    for name in ("mount", "mount_two"):
        part = grouping.SubPart(label=labels.MOUNT, name=name, shells=[0])
        part.lo, part.hi, part.centroid = (0, 0, 0), (0.1, 0.1, 0.1), (0.05, 0.05, 0.05)
        part.triangles = 12
        parts.append(part)
    check = selfverify._graph_is_a_tree(parts)
    assert not check.passed


def test_self_verification_catches_a_mass_that_does_not_sum():
    part = grouping.SubPart(label=labels.MOUNT, name="mount", shells=[0])
    part.mass_kg = 10.0
    check = selfverify._mass_sums({"totalMassKg": 40.0}, [part])
    assert not check.passed

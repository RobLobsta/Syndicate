"""What a structure part weighs, and how hard it is to break (D16-S7.1, DEC-067).

The rule is the vehicle pipeline's — ``area x areal density``, capped by what the mesh encloses
— and it is here rather than imported because the *table* is different and the difference is
large. :mod:`syndicate_prepare.manifest` carries 78.5 kg/m² for a car's structural members;
a building's wall is 165 mm of concrete and weighs 400. Using a car's figure would give a
seven-storey block the mass of a small van, and the first thing anybody noticed would be that
shooting its base sent it into orbit.

**Why areal density and not volume x density.** A building mesh is a shell: the enclosed volume
of one 10 x 8 x 6 m floor is 480 m³, which as solid concrete is 1,150 tonnes and as an actual
floor is nearer 90. The enclosure is still used, as a *cap*, for the same reason DEC-067 gives:
a part cannot contain more material than fits inside it, so a small solid lump — a footpad, a
bollard — is weighed as the lump it is.
"""

from __future__ import annotations

#: Mass per square metre of surface, by material. A structure is built out of thicknesses that
#: are stable per material in the way a vehicle's are stable per destruction class, because a
#: building is not weight-optimised: a wall is as thick as it needs to be to stand up and hold
#: heat in, and that lands concrete at 150-200 mm nearly everywhere.
#:
#: Measured against the shipped content: a six-storey 10 x 8 m block comes out at 439 t over its
#: three floors, which is right for reinforced concrete once the enclosure cap has taken out the
#: air; and the rocket turret — 38 m across the legs, 31 m tall — comes out at 376 t, which is a
#: heavy weapon emplacement and not a bridge. Steel is 60 rather than a plate thickness because
#: a detailed mesh counts both faces of every tube, so 7.6 mm of nominal wall is what 15 mm of
#: real one measures as.
AREAL_DENSITY_KG_PER_M2 = {
    "concrete": 400.0,
    "brick": 350.0,
    "steel": 60.0,
    "steel_hardened": 70.0,
    "aluminium": 40.0,
    "glass": 30.0,
    "wood": 30.0,
    "plastic": 12.0,
    "composite": 25.0,
    "rubber": 20.0,
    "trim": 8.0,
    "lead": 220.0,
}

#: For a material with no row above. Deliberately the concrete figure: the fallback for "we do
#: not know what this structure is made of" should be the heaviest common answer, because a
#: structure that is too light is the one that misbehaves — it gets shoved by a car.
DEFAULT_AREAL_DENSITY_KG_PER_M2 = 400.0

#: Below this thickness a mesh is treated as a surface rather than as a solid, so its enclosure
#: does not cap its mass. Same number and same reason as the vehicle pipeline's ``MIN_WALL_M``.
MIN_WALL_M = 0.0005

#: D00-S6.4. Nothing lighter than this is simulable.
MIN_BODY_MASS_KG = 0.01

#: Hit points per kilogram. An order of magnitude below the vehicle table's 2.0 for
#: ``STRUCTURAL``, because a structure part weighs tonnes rather than kilogrammes and a
#: 90-tonne floor at 2 hp/kg would need 180,000 points of damage — about nine minutes of
#: sustained cannon fire — to break. At 0.06 the same floor is 5,400 hp: a couple of rocket
#: volleys, which is what "shoot the bottom out of a building" should cost.
HP_PER_KG = 0.06

#: No structure part is more fragile than this, however light. A bench is not a windscreen.
MIN_MAX_HP = 120.0

#: Armour per kilogram (D07-S5.2). Flat across materials: what a structure is made of already
#: shows up in its mass and in its material's resistances, and a third knob on the same axis
#: is how content stops being predictable.
ARMOR_PER_KG = 0.0025

#: Ceiling on that armour. A part heavy enough to reach it is a wall, and a wall that shrugs
#: off more than this is one the player cannot make progress against.
MAX_ARMOR = 60.0

#: Break impulse in N·s per kilogram (D06-R22). A structure part is *bolted down*, not hung on
#: a bracket, so this is well above the vehicle table's 16: a car hitting a building at 20 m/s
#: delivers about 30,000 N·s and must not knock a floor off.
BREAK_IMPULSE_NS_PER_KG = 45.0

#: And nothing below this, so a bench still resists being nudged.
MIN_BREAK_IMPULSE_NS = 4000.0


def areal_density(material_id: str) -> float:
    """Mass per square metre for a material, or the default for one with no row."""
    return AREAL_DENSITY_KG_PER_M2.get(material_id, DEFAULT_AREAL_DENSITY_KG_PER_M2)


def part_mass_kg(
    area_m2: float, enclosed_m3: float, material_id: str, density_kg_per_m3: float
) -> float:
    """One part's mass: its surface's worth of material, capped by what it can hold.

    See the module docstring. The cap only engages for a mesh thick enough to be a solid; an
    open or shell-like mesh keeps the surface reading, which is the common case for a building.
    """
    surface = area_m2 * areal_density(material_id)
    if enclosed_m3 > area_m2 * MIN_WALL_M:
        surface = min(surface, enclosed_m3 * density_kg_per_m3)
    return max(MIN_BODY_MASS_KG, surface)


def max_hp(mass_kg: float) -> float:
    """How much damage a part of this mass absorbs before it is destroyed (D07-S5.3)."""
    return max(MIN_MAX_HP, mass_kg * HP_PER_KG)


def armor_value(mass_kg: float) -> float:
    """The armour a part of this mass carries (D07-S5.2)."""
    return min(MAX_ARMOR, mass_kg * ARMOR_PER_KG)


def break_impulse_ns(mass_kg: float) -> float:
    """The impulse that shears a part off its parent (D06-R22, D05-R23)."""
    return max(MIN_BREAK_IMPULSE_NS, mass_kg * BREAK_IMPULSE_NS_PER_KG)

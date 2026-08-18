"""What a structure's pieces are made of, and how each therefore fails (D15-S5.7, D16-S7.1).

Split out of :mod:`~syndicate_structure.structure` so that :mod:`~syndicate_structure.bands` can
consult it without importing a module that needs a Blender host. That is not a packaging detail:
the *cut* has to know about materials, because a band's glazing and its walls fail differently and
a part is the unit that fails (DEC-100).

The rule the whole module implements is D15-R32's, applied to a building rather than to a car:
**how a part fails follows from what it is.** For a vehicle that is the part's label — a door skin
dents whatever it is made of. For a structure there are no labels to read, because a downloaded
building is one mesh with three material slots on it, so the material *is* the only statement the
art makes about what a piece is. A pane of glass, a brick wall and a steel mast in the same band
are three parts, not one part whose material is whichever of them has the most surface.
"""

from __future__ import annotations

#: Source material names meaning glazing, matched case-insensitively as substrings.
GLASS_TOKENS = ("glass", "window", "glazing", "pane")

#: ...meaning masonry: brick, block, stone, rendered concrete.
BRICK_TOKENS = ("brick", "masonry", "stone", "block", "wall", "plaster", "stucco")

#: ...meaning structural metal. A mast, a leg, a frame, a girder.
STEEL_TOKENS = ("steel", "metal", "iron", "girder", "frame", "beam", "mast", "truss")

#: ...meaning timber.
WOOD_TOKENS = ("wood", "timber", "plank", "bench", "bark", "trunk")

#: The material a structure piece gets when its name says nothing. Concrete rather than brick
#: because it is the more common structural material and the more forgiving mistake: both are
#: masonry and fail identically, and they differ only in density and blast resistance.
DEFAULT_MATERIAL = "concrete"

#: Which ``materials.json`` id each token set maps to, in the order they are tested. Glass is
#: first because it is the one mistake that shows — a pane that dents instead of shattering —
#: and the ordering is what makes "Building_6_Glass" glass rather than a wall on the "wall" token.
_TOKEN_MAP: tuple[tuple[tuple[str, ...], str], ...] = (
    (GLASS_TOKENS, "glass"),
    (STEEL_TOKENS, "steel"),
    (WOOD_TOKENS, "wood"),
    (BRICK_TOKENS, "brick"),
)

#: How each material fails. Everything not named here is masonry, which is what a structure is
#: mostly made of and the safest default for an unrecognised name in a building.
_CLASS_BY_MATERIAL: dict[str, str] = {
    "glass": "GLASS",
    "steel": "STRUCTURAL",
    "steel_hardened": "STRUCTURAL",
    "aluminium": "STRUCTURAL",
    "wood": "STRUCTURAL",
    "brick": "MASONRY",
    "concrete": "MASONRY",
}


def map_material(source_name: str) -> str:
    """A source material name to a ``materials.json`` id.

    Substring matching on the artist's own material names, which is the only cue an untextured
    downloaded building offers. It is wrong sometimes and that is accepted: ``parts.json`` beside
    the model overrides it per role (D15-S4.3's mechanism), and the failure mode of a miss is a
    wall made of the wrong kind of masonry rather than a wall that behaves like glass.
    """
    lowered = (source_name or "").lower()
    for tokens, material in _TOKEN_MAP:
        if any(token in lowered for token in tokens):
            return material
    return DEFAULT_MATERIAL


def destruction_class_for(material_id: str) -> str:
    """How a part of this material fails (D15-S5.7).

    Three answers, and each is the visible behaviour the material owes a player:

    * ``GLASS`` — glazing bursts into slivers. No dented state exists.
    * ``MASONRY`` — brick, block and concrete come apart into pieces you can see the edges of.
      Also no dented state: masonry has no plastic range, so a wall that dents is a wall made of
      something else.
    * ``STRUCTURAL`` — steel and timber yield. They bend and twist and stay one piece, which is
      the thing masonry cannot do and the reason the two are separate classes (DEC-100).
    """
    return _CLASS_BY_MATERIAL.get(material_id, "MASONRY")


def family_of(source_name: str) -> str:
    """The failure family of a piece, straight from its source material name.

    This is what :func:`~syndicate_structure.bands.cut` groups on. It is the destruction class
    rather than the material id so that brick and concrete — which fail identically — stay in one
    part instead of splitting a wall in two on a rendering difference nobody can shoot.
    """
    return destruction_class_for(map_material(source_name))

"""Turning a whole-structure model into a destructible assembly (D16-S7).

A structure is an assembly in the D05 sense — a root part with parts on its slots — and it is
*only* that: D16-R81 says that if building one needs a new system, a new component or a change to
the damage pipeline, the design has drifted. This package is the authoring half of holding that
line. It takes one model of a building, a tower or a piece of street furniture and cuts it into the
parts whose slot graph **is** its support chain, so that shooting the bottom one drops everything
above it through machinery that already exists.

**Why the cut is horizontal.** A vehicle is dissected by a cue ensemble (DEC-042) because its parts
are functionally different from one another — a wheel turns, a door opens, a windscreen shatters —
and telling them apart takes eleven geometric cues. A structure has no such variety and needs none:
what makes it interesting is that it stands up, and what stands up is a stack. So the cue is height.
Bands along Y become the support chain, connected components within a band become the parts that can
be shot away independently, and band 0 is the root that is bolted to the ground.

That is the whole idea, and it is why this orchestrator is a fifth the size of
:mod:`syndicate_prepare`. Everything downstream of the cut — style, mass, hulls, morphs, shards,
export — is the vehicle pipeline's, reused rather than reimplemented.
"""

__all__ = ["bands", "documents", "graph", "mass", "split"]

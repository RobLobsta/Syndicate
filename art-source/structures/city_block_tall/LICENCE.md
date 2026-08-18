# licence

**status: development-exception** (D08-R1d)

**Terms: UNRESOLVED. This model may be used in development and may NOT be distributed.**

The project owner granted the exception on 2026-08-18, for pre-alpha development only. It is
recorded rather than assumed: D08-R1d permits an unresolved model to be processed while its
`LICENCE.md` says so and says what is known, and the asset gate reports A512 for every model
carrying it. `SYNDICATE_REQUIRE_LICENCE=1` turns that advisory into a build failure, which is what
any distribution pipeline sets.

Nothing below is invented. Where the terms are unknown this file says they are unknown.

---

## What is known about this model

D08-R1b requires the source, the author and the licence beside every model. `city_alley_kit.blend`
arrived with none of the three. What is recoverable is that the props are **BlenderKit** downloads,
because the packed textures kept their original Windows paths and those carry each download's asset
id (`SOURCE.md` lists them). BlenderKit hosts assets under several different licences — CC0,
CC-BY, and a royalty-free store licence among them — so knowing the id says *where* a model came
from and says nothing about what may be done with it.

It is processed under D08-R1d's development exception, deliberately and on the record: D16-S7 structures had no content at
all, and a subsystem with nothing in it cannot be looked at. That buys a working pipeline, not a
right to distribute the art.

**Before anything ships:** each asset id above has to be resolved to its author and its licence on
BlenderKit, and this file replaced with that answer — including the attribution line, if one is
required. If any of them turns out to forbid it, that prop's `scene.glb` is replaced and nothing
else changes.

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

D08-R1b requires the source, the author and the licence beside every model, and says a model with
no recorded terms is not processed. This one arrived as a bare `.blend` with no licence text, no
`asset.extras`, and no author metadata of any kind — the file carries nothing to attribute to.

It is processed here anyway, deliberately and on the record, because the geometry is the input to a
subsystem (D16-S7 structures) that had no content at all and could not otherwise be built or looked
at. What that buys is a working pipeline and a structure that can be driven at and shot; what it
does not buy is the right to distribute this art.

**Before anything ships:** the supplier of `turret.blend` has to say where it came from and under
what terms, and this file has to be replaced with that answer. If the terms turn out to forbid it,
the geometry is replaced and nothing else changes — the tool, the part split, the destruction data
and the runtime all work off whatever `scene.glb` holds.

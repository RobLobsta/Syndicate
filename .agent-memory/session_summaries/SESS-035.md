# SESS-035: two guns, taken apart

**Date:** 2026-08-16
**Category:** session_summaries
**Related Docs:** docs/17_weapon_system.md#D17-S1, docs/17_weapon_system.md#D17-S5.6, docs/05_vehicle_part_system.md#D05-S4.3

**Status:** active

## Summary
The user supplied two weapon models and asked for the whole feature: separated like the vehicles are,
parts that move, a tool that normalises style and fixes geometry, damage that reads logically, an
impulse from the cannon, slot restrictions, clean attachment, and screenshots. An eighteenth
blueprint (D17) was written, a third Blender tool built, and both weapons now ship fitted and
photographed.

## Details
Four questions were asked up front and all four answers shaped the design: scale the cannon to fit
and drop its carriage; make sub-parts separately damageable; gate slots by size class; apply impulse
both ways. Each is recorded as a decision (DEC-080 to DEC-083).

The work that was not anticipated was **scale**. Both models import at 100×, which silently defeats
every absolute threshold in the reused D15 repair stage — the cannon separated into 203 shells
instead of 22 and classified as a laser weighing 631 kg. Two normalisations before labelling fixed
it (DISC-057). The seam rule was also wrong as first specified: a gap budget asserts something about
the artist, and what the pipeline owes is that a join sits on real contact (DISC-058).

Looking at the result found four more defects that every check had passed: decorative sub-parts
declaring armour (A205, which made the whole weapon unavailable), hub parts failing their own newly
classed slots (A316), the garage drawing only chassis children, and a synthesised mount rendering
pure white and then, once given a material, exporting a mesh the runtime could not read.

## Rationale / Context
The user's phrase "other weapon types may vary quite a bit" is why the cue ensemble is an ensemble
and why the taxonomy is closed rather than tuned per model: the two shipped guns are a 350-triangle
pintle gun and an 8,898-triangle siege piece on a carriage, and they already disagree about almost
everything.

## Impact
- `docs/17_weapon_system.md` (D17); D00, D05 and D08 amended; `A220`, `A221` and `A316` added.
- `blender-tool/syndicate_weapon`: nine modules, eight of them pure Python and unit-tested.
- `SizeClass`, `FiringImpulse`, `PartArticulation`, `ArticulationState`; `--vehicle` on the client.
- 296 Python tests, `./gradlew check validateDocs` and the physics suite all green.
- Captures in `build/captures/`: both vehicles armed in the garage, and the cannon in a match.

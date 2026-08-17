# SESS-037: a maintenance pass that closed the deviation backlog and then went driving

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/17_weapon_system.md#D17-S5.13, docs/16_procedural_arena_generation.md#D16-S5.4, docs/13_persistent_memory_system.md#D13-S5.6

**Status:** active

## Summary
A maintenance session with a mandate to resolve open decisions, delete stale text, and finish
unfinished work by implementing it. Nine spec deviations closed, three stale documents corrected, two
specified-but-unimplemented subsystems built (D17-S5.13 and D16 stage 3), and the client taught to
drive itself — which immediately found two defects nothing else had.

## Details

**The deviation backlog.** Thirteen active entries, seven of which said "amendment deferred to the
session that writes X" — and X had been written, in some cases twenty sessions earlier. Nine are now
resolved, with the amendments applied across D03, D05, D06, D08 and D09. Four stay open on purpose.

**Stale text.** `README.md` claimed there were no weapons, that the arena was an undrawn flat box, and
that there were 17 documents D00–D16. All three were wrong, in the first file anybody reads.
`GEMINI.md` and `assets/README.md` were wrong too.

**D17-S5.13, sub-part degradation.** Barrel gone means spread ×4 and range ×0.5; breech halves the
fire rate; receiver stops the gun; feed leaves a chambered burst (DEC-085). Folded onto the mount as
`mul` terms in slot 6 — no new mechanism, as D17-R61 demands — with the label read off the
`sub_<label>` slot id the Blender tool already writes. Verified on the real shipped Eclipse.

**D16 stage 3.** `RoadCarver` implements D16-R35's three passes; the desert ships a 612 m highway
whose falloff digs 4 m cuttings and raises 4 m embankments unauthored. Grip is read at each suspension
ray's contact point inside the shared control operation, so sand is slower than tarmac and a replay
grips identically.

**The ground in the house style.** Both DEC-079 mechanisms now apply to theme albedo.

**Then it was driven.** `--script` plays a written-down drive through the same component a keyboard
writes; `--capture-frames` photographs several moments of one run. That found DISC-063 and DISC-062.
`verifyBeforePush` turns CLAUDE.md §8.1's prose into one task.

## Rationale / Context
The session's stated goal was fewer unfinished sections, by finishing them. The part worth carrying
forward is that the two most valuable findings came from *playing* rather than from building: the
degradation table and the road carve both passed every test they were given, and the two things that
were actually wrong with the game were only visible from a car.

## Impact
- Resolved: DEV-001, 002, 003, 009, 011, 012, 013, 014, 015. Amended: D03, D05, D06, D08, D09.
- New: DEC-085, DISC-062, DISC-063, PROG-036, PROG-037.
- Decisions taken by the user: `armorValue` stays (DEC-073 closed), the ground follows the palette,
  loopback single-player deferred to the sockets step.
- Not done and named as such: weapon fracture manifests and D16 structures both need Blender, which
  this sandbox does not have; `schemas/` remains an empty directory five requirements point at.

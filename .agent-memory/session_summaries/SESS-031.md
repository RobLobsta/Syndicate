# SESS-031: a game you can start, and a repository that says what it is

**Date:** 2026-08-14
**Category:** session_summaries
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/01_product_game_design.md#D01-S3, docs/13_persistent_memory_system.md#D13-S4.1

**Status:** active

## Summary
A review session that turned into a build session. The client now opens on a main menu, leads to a
garage where the player picks a vehicle from the real art, and deploys into a match it can leave;
the distribution runs from any working directory and there is a Windows packaging task. Alongside
that: the memory system was consolidated, the roadmap rewritten as a sequential plan, and several
claims that had stopped being true were corrected.

## Details

**Built.** `dev.syndicate.client.shell` — a screen at a time, with `GameShell` owning every
transition, so a match's Bullet world is built on deploy and torn down on exit rather than living
for the process. The garage draws the real `mesh.glb` at the real slot transforms and reads its
figures off the `VehicleProfile` the handling came from. All three screens were photographed from
the running client.

**Found stale.** `DISC-024` said `game-client` could not be built in this environment. It builds,
tests, and *runs* under `xvfb-run`, and that discovery had been load-bearing — the roadmap deferred
terrain rendering because of it. Superseded by `DISC-046`. `README.md` still described a project
with no renderer and no networking, and both root assistant manuals counted fifteen blueprints when
there are seventeen.

**Caught by testing the artefact.** The packaged build found its vehicles and then could not find
their meshes, their sounds or its own typeface: the asset-root fallback had been written in the
loader, and five other things open files under that root. Moved to `LaunchConfigResolver`, where it
happens once.

**Consolidated.** Nine active progress entries became eight subsystem-state entries with no
session-diary framing and no "N of 27 systems" counts; twenty-six session summaries became one era
entry plus an archive that D13 now defines.

## Rationale / Context
The session's own lesson is the one in DISC-046: a recorded environmental limitation has a shelf
life, and re-testing one costs a single command. Two of this session's largest results — that the
client is verifiable here, and that the vertical slice could be built and *seen* — both followed from
not trusting a note written two days earlier.

## Impact
- `ROADMAP.md` is now a sequential plan; the phase gantt and the 27/27 bar are gone.
- Terrain rendering is the next piece of work and is not blocked by anything.
- The open naming question — `ARMOR` as a part category meaning "body panel" — is recorded in
  DEC-073 and left for the user to decide.

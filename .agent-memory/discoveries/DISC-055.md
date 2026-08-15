# DISC-055: the last file you change is the one you never re-check

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/13_persistent_memory_system.md#D13-S5.5, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
CI failed stage 1 on `INDEX.md: differs from a freshly generated index (D13-R8)`. The cause was not
a forgotten command — `regenerateIndex` had been run — but its **position in the sequence**: the
session summary was written after it, and a session summary is an index entry. The verification ran
against a tree that no longer existed by the time it was committed.

## Details
The failing order was: implement → `regenerateIndex` → run the checks → write SESS-034 → commit.
Every step was individually correct and the commit was still broken, because step 4 invalidated the
output of step 2 and nothing re-ran it.

This is not specific to the index. The same shape produces every other stage-1 failure this project
has had: `spotlessApply`, then one more Java edit; `validateDocs`, then one more doc section. The
generated-artefact tasks (`regenerateIndex`, `spotlessApply`) consume the whole tree, so they are
only valid with respect to the tree as it stood when they ran, and the natural writing order —
finish the work, then write down what the work was — guarantees a later edit.

Two things follow, and they are now written into CLAUDE.md §8.1:

1. The generators run **after** the last edit, not after the last code edit. `git add -A` first, so
   what is verified and what is committed are the same set of files.
2. The reproduction runs from a tracked-files-only tree
   (`git ls-files -z | xargs -0 tar -cf - | tar -xf - -C …`), which is the only thing that catches
   an untracked file the working directory was quietly relying on — the DISC-005 failure mode.

Reproducing all four stages that way locally takes about six minutes and costs no runner time.

## Rationale / Context
Worth an entry because the instinct after a green local run is that the commit is verified, and for
generated artefacts that instinct is wrong in a way the diff does not show: `INDEX.md` looked
plausible and complete, and there was no way to tell by reading it that it was one entry stale.

## Impact
- CLAUDE.md gains §8.1 (the literal pre-push procedure, in order) and §8.2 (cheap habits: memory
  first, targeted test tasks, blueprint IDs in comments for review, batched generators).
- `.github/workflows/ci.yml` remains the authority on the stage list; §8.1 mirrors it and says so,
  so a stage added there is not silently missed here.

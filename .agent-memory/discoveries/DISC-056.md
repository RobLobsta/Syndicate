# DISC-056: the Actions API status you read can be an hour stale

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
While watching the run that fixed DISC-055, the `mcp__github__actions_*` responses reported the
last stage `in_progress` for the better part of an hour. The job had actually finished green in
**2m14s**. The stall was in the API cache, not on the runner, and the run was nearly cancelled
because of it.

## Details
Two independent endpoints agreed with each other and were both wrong: `list_workflow_jobs` and
the PR's `get_check_runs` returned byte-identical `in_progress` snapshots across many polls,
minutes apart, well after `completed_at: 22:55:23`. Earlier in the same run the same thing
happened on a smaller scale — the assemble stage was read as an 18-minute stall and had in fact
taken 34 seconds. Agreement between endpoints is not freshness; they share a cache.

What makes this expensive is the wrong conclusion it invites. A stage that looks stuck for 45
minutes reads as a hang introduced by the branch, and the natural responses — cancel the run,
hunt a hang that does not exist — cost runner minutes and session time. The local cold repro
that settled it (`test -Ptags=unit,integration --no-build-cache --no-daemon`, no `DISPLAY`)
finished in 1m11s, which was the first solid evidence the commit was fine.

The defence is a number rather than a rule: **this pipeline costs 2–3 minutes end to end**, and
`list_workflow_runs` on the default branch shows that history. Anything that appears to exceed
it by an order of magnitude is more likely a stale read than a hang, and the way to find out is
to re-run the suspect stage locally — never to cancel, because cancelling a run that has already
passed spends the minutes twice and proves nothing.

Incidentally worth knowing: `get_job_logs` returns HTTP 404 while a job is running, so it cannot
be used to peek at a stage in flight; it only helps after the fact.

## Rationale / Context
Recorded because the failure mode is invisible from inside — the data looks like a live status
and carries no staleness marker — and because the instinctive reaction to it (cancel and
investigate) is precisely the one that wastes the metered resource CLAUDE.md §8 exists to
protect.

## Impact
- CLAUDE.md §8.1 gains the staleness warning next to the polling instruction, with the 2–3
  minute baseline to compare against.

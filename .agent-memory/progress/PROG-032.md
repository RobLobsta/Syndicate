# PROG-032: networking — replication runs, over loopback only

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/10_networking_multiplayer.md#D10-S5.4, docs/10_networking_multiplayer.md#D10-S5.5, docs/10_networking_multiplayer.md#D10-S5.9

**Status:** active

Supersedes: PROG-023

## Summary
An authority and a client exchange a handshake, spawns, structural events, bit-packed delta snapshots
and validated input across a `Transport`, with prediction and reconciliation, verified end to end over
`LoopbackTransport`. There is no socket transport, so it has only ever talked to itself, and neither
runtime shell constructs a network endpoint.

## Details

**Scope:** `game-core` `net`, `system` slots 2, 18, 19, 20; `shared-models` `net`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Transport abstraction | done | The seam a socket implementation plugs into |
| Handshake, snapshots, deltas, baselines | done | Per-peer baselines; bit-packed wire format |
| Input validation and the jitter buffer | done | Server never trusts a client's numbers |
| Prediction and reconciliation | done | Vehicle control is a shared operation so it can be replayed (DEC-061) |
| Socket transport (TCP + UDP) | not_started | One class implementing an interface that already exists |
| Runtime wiring | not_started | Client and server both build worlds with no endpoints, so slots 2/18/19/20 are absent from every shipping schedule |
| Lag compensation | not_started | Fully specified, nothing written |
| Scoreboard / phase / chat / ping messages | not_started | Wire slots reserved so adding them cannot renumber what ships |
| Bandwidth measurement | not_started | Budget is 128 kbit/s down, 32 up, per client at twelve players |

Single-player is *specified* to run through the in-process pair, which is what would make "there is no
separate single-player code path" true in practice. It does not today: the client hosts its own match
with the networking systems absent.

## Rationale / Context
"All 27 systems exist" invites the reading that multiplayer works. Four of those systems have never
run in a shipping configuration, and the distance to real multiplayer is a specific short list rather
than an unknown.

## Impact
- Wiring single-player through the loopback pair would exercise replication every time anyone plays,
  which is the cheapest possible test of it.

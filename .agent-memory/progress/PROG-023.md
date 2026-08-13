# PROG-023: all 27 systems exist; replication runs end to end over loopback

**Date:** 2026-08-13
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/10_networking_multiplayer.md#D10-S5.4, docs/10_networking_multiplayer.md#D10-S5.5

**Status:** active

## Summary
The four networking slots of D04-S4.4 — 2, 18, 19 and 20 — are implemented, and with them the
system catalogue is complete. An authority and a client exchange a handshake, spawns, structural
events, bit-packed delta snapshots and validated input across a `Transport`, verified end to end
over `LoopbackTransport`. What does not exist yet is a socket transport and the runtime wiring that
would use one.

## Details

**Scope:** `game-core` `net`/`system`, `shared-models` `net`. The simulation half is PROG-014's
lineage; the toolchain is PROG-008; content is PROG-019.

**Status of Work:** (supersedes PROG-020 for the schedule)

| Area | State | Notes |
|---|---|---|
| `Transport` / `TransportListener` (D02-R5) | done | Polled, never pushed, so a packet cannot land between two systems (G2) |
| `LoopbackTransport` (D02-R19) | done | Pooled copies, lossless, ordered; single-player runs the replication path (AC-D10-19) |
| Bit codec (D10-R8) | done | `BitWriter`/`BitReader`, LSB-first, bounds-checked; a truncated packet throws and is counted |
| Quantisation (D10-S4.3) | done | 16-bit position over ±400 m (1.22 cm), smallest-three rotation in 32 bits, 12-bit velocities, 8-bit health |
| Snapshot format (D10-S4.4) | done | Self-describing masks, absolute values, idempotent (AC-D10-5) |
| Delta compression (D10-S5.4) | done | Per-peer baselines, `SNAPSHOT_HISTORY` of 64, full-snapshot recovery (AC-D10-8) |
| Baseline NACK (D10-R18) | done | New message (DEC-058); three NACKs force a full snapshot (D10-E5) |
| Relevance (D10-S5.10) | done | Distance-based; debris never sent (AC-D10-18); parts follow their vehicle (DEC-060) |
| Jitter buffer (D10-S5.3) | done | Adaptive delay, quick to grow and slow to shrink; a miss repeats movement and zeroes fire (D10-R15) |
| Input validation (D10-S5.9) | done | Clamp-and-count, temporal bounds, per-packet rate limit; suspicion never auto-bans (D10-R27) |
| Handshake (D10-S5.8) | done | Protocol and content mismatch refused with both values (AC-D10-15) |
| `InputReceiveSystem` (2) | done | The only point in a tick where a client's message changes anything |
| `NetworkSendSystem` (18) | done | Adopts new vehicles, forwards structural events, sends staggered snapshots |
| `NetworkReceiveSystem` (19) | done | Records prediction, sends input, applies snapshots (DEC-062) |
| `ReconciliationSystem` (20) | done | Threshold check, snap, replay through `VehicleControl` (DEC-061), decaying visual offset |
| `VehicleControl` | done | Slot 7's arithmetic as a shared operation so slot 20 can replay it |
| KryoNet transport | not_started | `Transport` has one implementation, and it is in-process |
| Runtime wiring | not_started | `ServerRuntime` and `ClientRuntime` still build a `CoreSystemProvider` with no endpoints, so the four slots are absent from every shipping schedule |
| Lag compensation (D10-S5.7) | not_started | No `HitboxHistory`; DEV-018 |
| Score, phase, chat, ping messages | not_started | Ids reserved, codecs absent; DEV-018 |

**Verification:** `WireFormatTest` (12), `SnapshotCodecTest` (6) and `LoopbackReplicationTest` (13)
cover T-D10-2, -3, -4, -5, -9, -10, -16, -17, -20, -21 and AC-D10-5, -6, -7, -8, -15, -18, -20.
`./gradlew check` is green across every module that builds in this environment.

**Next Steps:**
1. A KryoNet `Transport` (TCP control + UDP state), which is the last thing between this and two
   processes talking.
2. Wire `ServerRuntime` and `ClientRuntime` to build the transports of D02-S5.3, so single-player
   goes through loopback replication and AC-D10-22 becomes runnable.
3. Lag compensation and the remaining messages (DEV-018).

## Rationale / Context
This entry exists because the catalogue reaching 27/27 is the milestone the roadmap has been
measuring against since Phase 0, and because the gap between "the systems exist" and "a second
machine can join" is now one transport rather than a phase of work — which is a materially different
thing for the next session to know.

## Impact
Supersedes PROG-020 for the schedule. PROG-019 keeps content; PROG-008 keeps the toolchain.

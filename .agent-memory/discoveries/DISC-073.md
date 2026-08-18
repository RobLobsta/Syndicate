# DISC-073: the engine audio bus died mid-match on a float that rounded up

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
Every match silenced its own engines partway through. `EngineMixer`'s delay line is read at a
fractional position wrapped into range by adding `DELAY_FRAMES` while it is negative; at 28,249 a
float's spacing is about 0.002, so any read position in that last sliver below zero became *exactly*
`DELAY_FRAMES` and indexed one past the end. The render thread's catch-all logged one WARN and
stopped the bus for the rest of the match.

## Details
```
WARN d.s.client.audio.EngineAudioOutput - engine audio stopped:
     java.lang.ArrayIndexOutOfBoundsException: Index 28249 out of bounds for length 28249
```

The read position is `write - 1 - delay`, where `delay` is the propagation delay in frames and
`write` walks the whole line every 110 blocks. It goes negative whenever the write pointer is behind
the delay, which is most of the time, and the wrap adds `DELAY_FRAMES` back. The failure needs the
delay's fractional part to fall in a 0.002-wide band, so it fires on roughly one write-pointer
traversal in five hundred — often enough to hit every match of any length, rarely enough that a
1,600-block sweep across two metres of approach missed it.

**Reproduced deliberately, not swept.** Hold one voice at the distance whose delay is `512.001`
frames and render 130 blocks — one full traversal — and it throws, on the exact index the client
reported. That is `aVoiceWhoseDelayLandsOnAFrameBoundaryDoesNotKillTheBus`.

The fix is one comparison: after the negative wrap, subtract `DELAY_FRAMES` again if the result
landed on it.

## Rationale / Context
Three things about how this failed are worth more than the fix.

**It was found by running the game, not by a test.** Eleven audio tests pass and none of them holds a
voice still for long enough at a distance chosen badly enough. The WARN line in a capture log is the
entire evidence trail.

**It degraded rather than crashed.** The bus catches `RuntimeException` so one bad block cannot take
the game down — correct, and it converts a hard failure into every engine in the match going quiet
with the game carrying on. A future session tuning the mix (ROADMAP step 2) could easily spend an
afternoon on a synthesiser that was fine and a bus that had already stopped.

**The magic number was the whole diagnosis.** `Index 28249 out of bounds for length 28249` names
`DELAY_FRAMES` exactly, which is what turned "audio sometimes dies" into an arithmetic question.

## Impact
`game-client` `audio` (`EngineMixer`). Any other buffer wrapped by adding its own length to a float
has the same latent bug; this codebase has one other candidate, the `airState` filter, which is not
indexed. Verified over a three-minute match with no WARN.

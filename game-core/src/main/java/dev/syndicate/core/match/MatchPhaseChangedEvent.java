/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.model.MatchPhase;

/**
 * The match moved between phases (docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>Replicated on the {@code CONTROL} channel — reliable and ordered (D10-S4.2) — because a client
 * that missed the transition into {@code ACTIVE} would show a countdown over a fight that has
 * already started. Carrying the phase it came <em>from</em> as well as the one it went to makes a
 * dropped-then-resent event self-describing rather than something the receiver has to reconcile
 * against its own belief.
 *
 * @param from the phase being left
 * @param to the phase being entered
 * @param tick the tick the transition happened on
 */
public record MatchPhaseChangedEvent(MatchPhase from, MatchPhase to, long tick) {}

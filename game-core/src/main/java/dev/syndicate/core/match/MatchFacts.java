/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.MatchPhase;

/**
 * Read-only access to the match singleton, with a defined answer when there is not one
 * (docs/04_entity_component_model.md#D04-R5).
 *
 * <p>Every accessor here has a permissive default, and that is the point rather than defensive
 * habit. Most of the test suite builds a bare {@link World} with no match entity, drives a vehicle
 * into a wall and asserts on the damage — and a system that threw, or that treated "no match" as
 * "damage disabled", would break every one of those tests the moment the gates of D01-R21/R22
 * landed. "No match singleton" therefore means "no match rules are in force", which is both the
 * safe reading and the true one.
 */
public final class MatchFacts {

    private MatchFacts() {
        throw new AssertionError("no instances");
    }

    /** The match rules, or null when the world has no match singleton. */
    public static MatchRulesComponent rules(World world) {
        return world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
    }

    /** The match state, or null when the world has no match singleton. */
    public static MatchStateComponent state(World world) {
        return world.getComponent(EntityId.MATCH, MatchStateComponent.class);
    }

    /** The match clock, or null when the world has no match singleton. */
    public static MatchClockComponent clock(World world) {
        return world.getComponent(EntityId.MATCH, MatchClockComponent.class);
    }

    /** The configured mode, defaulting to {@code DEATHMATCH}. */
    public static GameMode mode(World world) {
        MatchRulesComponent rules = rules(world);
        return rules == null ? GameMode.DEATHMATCH : rules.mode;
    }

    /** The current phase, defaulting to {@code ACTIVE} — an unmanaged world is a world in play. */
    public static MatchPhase phase(World world) {
        MatchStateComponent state = state(world);
        return state == null ? MatchPhase.ACTIVE : state.phase;
    }

    /** Whether driver intent reaches vehicles this tick (D01-R21). Open when unmanaged. */
    public static boolean isInputEnabled(World world) {
        MatchStateComponent state = state(world);
        return state == null || state.inputEnabled;
    }

    /** Whether damage events are applied this tick (D01-R22). Open when unmanaged. */
    public static boolean isDamageEnabled(World world) {
        MatchStateComponent state = state(world);
        return state == null || state.damageEnabled;
    }
}

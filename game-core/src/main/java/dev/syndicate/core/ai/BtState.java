/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

/**
 * The behaviour-tree node a bot is currently executing
 * (docs/04_entity_component_model.md#D04-S4.3.4, docs/11_ai_bots_and_match_simulation.md#D11-S5.3).
 *
 * <p>An enum rather than a node reference because it is replicated as authoritative state (D04
 * classifies {@code behaviorTreeState} as {@code A}), and a wire format cannot carry a pointer into
 * a tree the receiver builds independently. It is also what makes a bot's decision legible in a
 * replay without re-running the tree.
 */
public enum BtState {
    /** No decision yet this match, or the bot has no vehicle. */
    IDLE,

    /** Moving toward an objective or patrol point with no engagement. */
    PATROL,

    /** Closing on a target that is known but not yet in weapons range. */
    PURSUE,

    /** In range and firing. */
    ENGAGE,

    /** Breaking contact: low integrity, or outmatched. */
    RETREAT,

    /** Repositioning to restore line of sight or leave a bad position. */
    REPOSITION,

    /** Recovering from being stuck, flipped, or wedged. */
    UNSTICK
}

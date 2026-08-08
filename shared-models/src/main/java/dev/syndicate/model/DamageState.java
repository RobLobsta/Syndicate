/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The authoritative, replicated damage state of a part (docs/07_damage_destruction_model.md#D07-S5.3).
 *
 * <p>Transitions are monotonic within a life (G8) and detachment is one-way (G9), which
 * {@link #canTransitionTo(DamageState)} makes checkable rather than merely documented. This is not
 * the shape key weight: deformation is cosmetic and continuous, this is gameplay and discrete
 * (D00-S6.1).
 */
public enum DamageState {
    INTACT,
    DAMAGED,
    CRITICAL,
    DESTROYED,
    DETACHED;

    /**
     * True when {@code next} is a legal successor. Only forward moves are legal; health may rise
     * again only through an explicit replicated repair event, which re-enters an earlier state via
     * a dedicated path rather than through this check (G8).
     */
    public boolean canTransitionTo(DamageState next) {
        return next.ordinal() > this.ordinal();
    }

    /** True once the part no longer contributes to vehicle stats. */
    public boolean isGone() {
        return this == DESTROYED || this == DETACHED;
    }
}

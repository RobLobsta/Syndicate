/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The event families a prepared vehicle needs a sound for
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R36).
 *
 * <p>Every one of these is keyed to something the simulation <em>already emits</em>. That is the
 * point of the list and the reason it is short: an audio inventory invented independently of the
 * events it would be triggered by is an inventory half of which can never play, and the other half
 * of which needs a new event added to fire it.
 *
 * <p>{@link #isLoop()} matters more than it looks. A loop must be seamless, which rules out sourcing
 * it from a generative model (D15-R38) and constrains how it is synthesised — the buffer has to
 * contain a whole number of cycles of everything periodic in it.
 */
public enum AudioEvent {

    /** Engine, pitched by RPM. Per vehicle class, never per vehicle (D15-R37). */
    ENGINE_LOOP(true),

    /** Ignition: starter, catch, and the flare down to idle. Per configuration, as the loop is. */
    ENGINE_START(false),

    /** Shutdown: fuel cut, the last charges, and the rock back onto compression. */
    ENGINE_STOP(false),

    /** Off-throttle: the exhaust popping as the driver lifts. */
    ENGINE_OVERRUN(false),

    /**
     * The second voice of a forced-induction engine: a supercharger's whine or a turbo's rush.
     *
     * <p>Keyed on {@link Induction}, which is the same kind of axis as {@link EngineConfiguration}
     * and obeys the same rule (D15-R37): a closed set, one asset each, nothing per vehicle.
     */
    INDUCTION_LOOP(true),

    /** A turbo letting go on lift. Only {@link Induction#TURBO} has one. */
    INDUCTION_RELEASE(false),

    /** A vehicle burning. `DamageSystem` (12) already runs the burn timer this rides on. */
    FIRE_LOOP(true),

    /** Tyres rolling, blended by surface. The ray-cast wheel already computes what this needs. */
    TYRE_ROLL(true),

    /** Tyres sliding, blended by slip. Same source, different filter. */
    TYRE_SKID(true),

    /** A collision, chosen by material pair and scaled by impulse. `CollisionEventSystem` (11). */
    IMPACT(false),

    /** A part leaving a vehicle, chosen by its destruction class. `DetachSystem` (14). */
    PART_DETACH(false),

    /** Glazing breaking. The one sound a player will notice missing (D15-S8). */
    GLASS_SHATTER(false),

    /** Debris coming to rest, by material and mass. Driven by the existing debris lifetime. */
    DEBRIS_SETTLE(false),

    /** A weapon firing. One per family (D01-R8). */
    WEAPON_FIRE(false),

    /** A weapon's projectile arriving. The other half of each family's pair. */
    WEAPON_IMPACT(false);

    private final boolean loop;

    AudioEvent(boolean loop) {
        this.loop = loop;
    }

    /** Whether this event plays as a continuous loop rather than as a one-shot. */
    public boolean isLoop() {
        return loop;
    }

    /** The asset-id token for this event, e.g. {@code glass_shatter}. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

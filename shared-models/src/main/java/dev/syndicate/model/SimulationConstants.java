/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The reserved constants of docs/00_master_index.md#D00-S6.4.
 *
 * <p>D00-R26 defines these once and forbids any document restating a different value. This class is
 * the code-side counterpart: no other class may declare a constant with one of these names and a
 * different value. When a value here changes, D00-S6.4 changes in the same commit.
 */
public final class SimulationConstants {

    // ---- Simulation timing (G2) --------------------------------------------------

    /** Simulation ticks per second. Global; a runtime mode may not vary it (D00-E5). */
    public static final int TICK_RATE_HZ = 60;

    /**
     * Fixed timestep in seconds. The simulation advances only in steps of this size; frame rate
     * decides how many steps happen, never how long one is (G2, D03-R10).
     */
    public static final float TICK_DT = 1.0f / TICK_RATE_HZ;

    /** Nanoseconds per tick, for the headless loop's sleep target (D03-S5.4). */
    public static final long TICK_DT_NANOS = 1_000_000_000L / TICK_RATE_HZ;

    /** Bullet's catch-up substep cap (D06-S5.4). */
    public static final int MAX_SUBSTEPS = 4;

    /** Authoritative snapshot send rate (D10-S5.3). Modes may change this; the tick rate they may not. */
    public static final int SNAPSHOT_RATE_HZ = 20;

    // ---- Physics (G13) -----------------------------------------------------------

    /** Minimum mass of a dynamic rigid body. A static body is exactly 0; nothing else is legal. */
    public static final float MIN_BODY_MASS_KG = 0.01f;

    /** Mass conservation tolerance when a part fractures into shards (G7, D14-S6.4). */
    public static final float MASS_TOLERANCE_FRAC = 0.02f;

    /** Gravity vector components (D06-S4.1). Stored as scalars so this class stays dependency-free. */
    public static final float WORLD_GRAVITY_X = 0.0f;

    public static final float WORLD_GRAVITY_Y = -9.81f;
    public static final float WORLD_GRAVITY_Z = 0.0f;

    // ---- Content limits ----------------------------------------------------------

    /** Hard cap on parts in an assembly (D05-S4.1). */
    public static final int MAX_PARTS_PER_VEHICLE = 64;

    /** Hard cap on shards produced from one part (D09-S4.3). */
    public static final int MAX_SHARDS_PER_PART = 64;

    /** Default debris despawn time in seconds (D07-S5.8). */
    public static final float DEBRIS_LIFETIME_S = 12.0f;

    /** Global debris body budget (D07-S5.8). */
    public static final int MAX_DEBRIS_BODIES = 256;

    // ---- Damage ------------------------------------------------------------------

    /** Health fraction at or below which a part enters {@code DAMAGED} (D07-S5.3). */
    public static final float DAMAGE_THRESHOLD_DAMAGED = 0.66f;

    /** Health fraction at or below which a part enters {@code CRITICAL} (D07-S5.3). */
    public static final float DAMAGE_THRESHOLD_CRITICAL = 0.33f;

    /** Health fraction at which a part enters {@code DESTROYED} (D07-S5.3). */
    public static final float DAMAGE_THRESHOLD_DESTROYED = 0.0f;

    /** Fraction of damage passed to each slot-graph neighbour (D07-S5.4). */
    public static final float PROPAGATION_FRACTION = 0.20f;

    /** Slot-graph hops damage propagation may travel (D07-S5.4). */
    public static final int PROPAGATION_MAX_DEPTH = 2;

    private SimulationConstants() {
        throw new AssertionError("no instances");
    }
}

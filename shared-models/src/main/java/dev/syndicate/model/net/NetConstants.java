/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * Every number docs/10_networking_multiplayer.md names, in one place.
 *
 * <p>The counterpart to {@code SimulationConstants} for the replication layer: D10 states these
 * inline in its pseudocode, and a value restated at each use site is a value that will eventually
 * be restated differently. {@code TICK_RATE_HZ} and {@code SNAPSHOT_RATE_HZ} are <b>not</b> here —
 * they are D00-S6.4's and live in {@code SimulationConstants}, which D00-R26 makes the sole
 * authority for a name it defines.
 */
public final class NetConstants {

    // ---- Protocol identity (D10-S4.5) --------------------------------------------

    /**
     * Bumped on any wire-format change: a new message, a changed field encoding, a renumbered
     * component id. Two peers whose versions differ cannot talk and are not allowed to try
     * (D10-R11).
     */
    public static final int PROTOCOL_VERSION = 1;

    /** The peer id a client addresses the authority by. Peers are numbered from 1. */
    public static final int SERVER_PEER_ID = 0;

    /** Not a peer. */
    public static final int NO_PEER_ID = -1;

    // ---- Input (D10-S4.2, S5.3) ---------------------------------------------------

    /**
     * How many past commands ride along with each {@code InputCommand} (D10-R4).
     *
     * <p>Six, because six 16 ms commands is 100 ms of loss covered — longer than any burst a
     * playable connection produces — for a few dozen bytes. Making the channel reliable instead
     * would cost a round trip on a message the next tick supersedes.
     */
    public static final int INPUT_REDUNDANCY = 6;

    /** The jitter buffer's starting delay, about 50 ms (D10-S5.3). */
    public static final int INPUT_TARGET_DELAY_TICKS = 3;

    /** Floor and ceiling for the adaptive delay. */
    public static final int INPUT_MIN_DELAY_TICKS = 1;

    public static final int INPUT_MAX_DELAY_TICKS = 10;

    /** How far from the wanted tick {@code selectFor} will reach for a command (D10-S5.3). */
    public static final int INPUT_SELECT_MAX_DISTANCE = 4;

    /** The window the jitter buffer grows its delay on. */
    public static final int INPUT_MISS_WINDOW_TICKS = 120;

    /** The longer, clean window it shrinks on — slow to shrink, quick to grow. */
    public static final int INPUT_CLEAN_WINDOW_TICKS = 600;

    /** Miss rate above which the buffer lengthens its delay (D10-S5.3). */
    public static final float INPUT_MISS_RATE_GROW = 0.02f;

    // ---- Snapshots and baselines (D10-S5.4) ---------------------------------------

    /** How many snapshots a peer's unacknowledged history holds before a full one is forced (D10-R17). */
    public static final int SNAPSHOT_HISTORY = 64;

    /** {@code baselineTick} of a full snapshot: delta against nothing (D10-S4.4). */
    public static final long FULL_SNAPSHOT_BASELINE = 0L;

    /** NACKs from one peer before the authority stops trying to delta and sends a full one (D10-E5). */
    public static final int MAX_NACKS_BEFORE_FULL = 3;

    /** Ticks a snapshot for an unknown network id is held before it is dropped (D10-E17). */
    public static final int SPAWN_GRACE_TICKS = 30;

    // ---- Prediction and reconciliation (D10-S5.5) ---------------------------------

    /** Position error a prediction may carry before a rewind is worth its cost. */
    public static final float RECONCILE_POS_THRESHOLD_M = 0.05f;

    /** Rotation error, same. */
    public static final float RECONCILE_ROT_THRESHOLD_RAD = 0.02f;

    /**
     * How much of the visible correction survives each tick.
     *
     * <p>Snapping the simulation is correct and snapping the camera is not, so the renderer carries
     * a decaying offset: 0.85 per tick settles in about 0.3 s, which reads as a nudge.
     */
    public static final float VISUAL_OFFSET_DECAY_PER_TICK = 0.85f;

    /** Below this the visual offset is dropped rather than decayed forever. */
    public static final float VISUAL_OFFSET_EPSILON_M = 0.001f;

    /** Consecutive reconciling ticks that indicate a bug rather than a bad connection (D10-E6). */
    public static final int RECONCILE_PERSISTENT_TICKS = 300;

    // ---- Interpolation (D10-S5.6) -------------------------------------------------

    /** How far in the past remote entities are rendered: two snapshot intervals plus margin. */
    public static final int INTERP_DELAY_MS = 100;

    /** How long a client extrapolates past its newest sample before it freezes. */
    public static final int EXTRAPOLATE_MAX_MS = 150;

    // ---- Lag compensation (D10-S5.7) ----------------------------------------------

    /** One second of hitbox history; the cap on how far a shot may be rewound (D10-R23). */
    public static final int HISTORY_TICKS = 60;

    // ---- Validation (D10-S5.9) ----------------------------------------------------

    /** How far ahead of the authority a command may claim to be before it is dropped (D10-E1). */
    public static final int MAX_FUTURE_TICKS = 10;

    /** Inputs per second above which a peer is rate-limited: 1.5× the tick rate. */
    public static final float MAX_INPUT_RATE_FACTOR = 1.5f;

    /** Suspicion added for a clamped field, a dropped future command, and rate limiting. */
    public static final int SUSPICION_CLAMPED = 1;

    public static final int SUSPICION_FUTURE_TICK = 5;
    public static final int SUSPICION_RATE_LIMIT = 2;
    public static final int SUSPICION_UNAUTHENTICATED_ADMIN = 10;

    // ---- Relevance (D10-S5.10) ----------------------------------------------------

    /** Vehicles further than this from a peer's own vehicle are not sent to it. */
    public static final float VEHICLE_RELEVANCE_M = 400f;

    /** Projectiles further than this are not sent. */
    public static final float PROJECTILE_RELEVANCE_M = 250f;

    // ---- Connection lifecycle (D10-S5.8) ------------------------------------------

    /** A peer's vehicle survives this long after it goes quiet, so a blip is not a death. */
    public static final int DISCONNECT_GRACE_TICKS = 180;

    /** Ticks without a packet before either end declares the other gone: 15 s. */
    public static final int PEER_TIMEOUT_TICKS = 900;

    /** Ticks the handshake may take: 10 s. */
    public static final int HANDSHAKE_TIMEOUT_TICKS = 600;

    // ---- Quantisation (D10-S4.3) --------------------------------------------------

    /**
     * Half-extent of the coordinate range positions are quantised over, in metres.
     *
     * <p>D10-S4.3 asks for 16 bits "quantised to arena bounds (≈1.2 cm)", and ±400 m is the bound
     * that produces exactly that: 800 m over 65,535 steps is 1.22 cm. It is a protocol constant
     * rather than a per-arena one on purpose — deriving the encoding from the loaded arena would
     * make the wire format depend on content, and two peers with different arenas would decode each
     * other's positions into silently wrong places instead of failing the content hash.
     */
    public static final float POSITION_RANGE_M = 400f;

    /** Bits per position axis. */
    public static final int POSITION_BITS = 16;

    /** Bits per component of a smallest-three quaternion, plus 2 for the dropped index. */
    public static final int ROTATION_COMPONENT_BITS = 10;

    /** Linear velocity range, ±, in metres per second. */
    public static final float LINEAR_VELOCITY_RANGE_MPS = 60f;

    /** Angular velocity range, ±, in radians per second. */
    public static final float ANGULAR_VELOCITY_RANGE_RAD_PER_S = 30f;

    /** Bits per velocity component. */
    public static final int VELOCITY_BITS = 12;

    /** Bits for a health fraction (D07-S5.9). */
    public static final int HEALTH_BITS = 8;

    /** Bits for a damage state ordinal. */
    public static final int DAMAGE_STATE_BITS = 3;

    /** Bits for a weapon's cooldown, ammunition and heat, each (D10-S4.3). */
    public static final int WEAPON_FIELD_BITS = 8;

    /** Bits for a control axis: throttle, steer and brake each travel as a byte. */
    public static final int CONTROL_AXIS_BITS = 8;

    /** Bits for an aim angle. */
    public static final int AIM_BITS = 16;

    /** Bits for a fire mask: one per weapon group. */
    public static final int FIRE_MASK_BITS = 8;

    private NetConstants() {
        throw new AssertionError("no instances");
    }
}

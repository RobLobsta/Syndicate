/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * Exit codes for the game executables (docs/03_runtime_modes.md#D03-S4.4).
 *
 * <p>These are the game's codes only. The verification harness (D14-S4.2) and the Blender tool
 * (D09-S4.3) have their own, because they are different programs; D03-R8 states the ranges do not
 * overlap in meaning and must not be conflated.
 *
 * <p>AC-D03-7 requires every code here to be produced by its stated cause and covered by a test.
 */
public enum ExitCode {

    /** Clean shutdown. */
    OK(0),

    /** Unknown flag, bad value, or contradictory flags. A typo is fatal, never a warning (D03-R6). */
    USAGE(64),

    /** Asset root missing or unreadable. */
    ASSETS_NOT_FOUND(66),

    /** Asset validation failed while {@code strictAssets} was set. */
    ASSETS_INVALID(67),

    /** A rendering mode was requested with no display available. */
    MODE_UNAVAILABLE(69),

    /** Unhandled exception during startup or shutdown. */
    INTERNAL_ERROR(70),

    /** The server could not bind its port. */
    PORT_IN_USE(74),

    /** The client could not reach or handshake with the server. */
    CONNECT_FAILED(76),

    /** Bullet natives unavailable for this platform. Never fall back to a stub (D02-E1). */
    NATIVES_MISSING(78);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /** The process exit status this maps to. */
    public int code() {
        return code;
    }
}

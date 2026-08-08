/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The four runtime modes of docs/03_runtime_modes.md#D03-S4.1.
 *
 * <p>There is one game configured four ways, not four code paths (D03-R3). The boolean properties
 * below are exactly the MODE PROPERTIES block of D03-S5.2; {@code SystemSetFactory} filters the
 * fixed system catalogue by them, so a mode can never accidentally run a system it should not have.
 */
public enum RuntimeMode {

    /** Client joined to a remote authority. Predicts locally, authors nothing (G15). */
    LOCAL_CLIENT(true, false, true, true),

    /** Client with an embedded authority over a loopback transport. Zero remote peers. */
    SINGLE_PLAYER(true, true, true, true),

    /** Listen server: authority plus a local rendering client, accepting remote peers. */
    HOSTED_MULTIPLAYER(true, true, true, true),

    /** Dedicated authoritative server. No window, no GL context, no audio (G17). */
    DEDICATED_SERVER(false, true, false, false);

    private final boolean hasInput;
    private final boolean isAuthority;
    private final boolean isClient;
    private final boolean renders;

    RuntimeMode(boolean hasInput, boolean isAuthority, boolean isClient, boolean renders) {
        this.hasInput = hasInput;
        this.isAuthority = isAuthority;
        this.isClient = isClient;
        this.renders = renders;
    }

    /** True when input devices are polled. False on the dedicated server (console commands only). */
    public boolean hasInput() {
        return hasInput;
    }

    /** True when this process owns authoritative state for the match (G1). */
    public boolean isAuthority() {
        return isAuthority;
    }

    /** True when this process runs the client-side half of replication. */
    public boolean isClient() {
        return isClient;
    }

    /** True when PRESENT-phase systems 22-26 are in the schedule (D04-S4.4). */
    public boolean renders() {
        return renders;
    }

    /** Headless is the exact complement of rendering; no mode both renders and runs headless. */
    public boolean isHeadless() {
        return !renders;
    }

    /** True when starting this mode requires a display; drives exit code 69 (D03-S5.1). */
    public boolean requiresDisplay() {
        return renders;
    }

    /** True when the process binds a listening socket. */
    public boolean acceptsConnections() {
        return this == HOSTED_MULTIPLAYER || this == DEDICATED_SERVER;
    }
}

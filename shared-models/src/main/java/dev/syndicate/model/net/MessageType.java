/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * The message catalogue of docs/10_networking_multiplayer.md#D10-S4.2, as wire ids.
 *
 * <p>Each constant carries the {@link Channel} D10-R3 assigns it, so the channel travels with the
 * message rather than with the call site. The numeric {@link #wireId()} is written as the first
 * byte of every packet and is <b>append-only</b> for the same reason component type ids are
 * (D04-R22): a renumbering would make two builds that both believe they agree disagree about what
 * arrived.
 *
 * <p>{@link #SNAPSHOT_NACK} is not in D10-S4.2's table. D10-R18 requires a client to NACK a delta
 * whose baseline it does not have, and no message in that table can carry one; the row was added to
 * D10-S4.2 in the same commit that added this constant (DEC-058).
 */
public enum MessageType {

    // ---- Handshake and lifecycle (D10-S5.8) --------------------------------------
    CLIENT_HELLO(1, Channel.CONTROL),
    SERVER_HELLO(2, Channel.CONTROL),
    REJECT(3, Channel.CONTROL),
    MATCH_CONFIG(4, Channel.CONTROL),
    DISCONNECT(5, Channel.CONTROL),

    // ---- Entity lifecycle and structural destruction (D07-S5.9) ------------------
    SPAWN_ENTITY(6, Channel.CONTROL),
    DESPAWN_ENTITY(7, Channel.CONTROL),
    STRUCTURAL_EVENT(8, Channel.CONTROL),

    // ---- State (D10-S4.4) --------------------------------------------------------
    SNAPSHOT(9, Channel.STATE),
    INPUT_COMMAND(10, Channel.STATE),
    SNAPSHOT_NACK(11, Channel.STATE),

    // ---- Combat feedback ---------------------------------------------------------
    HIT_CONFIRM(12, Channel.STATE),
    DAMAGE_RECEIVED(13, Channel.STATE),

    // ---- Match state -------------------------------------------------------------
    SCORE_UPDATE(14, Channel.CONTROL),
    MATCH_PHASE(15, Channel.CONTROL),

    // ---- Peer traffic ------------------------------------------------------------
    SELECT_VEHICLE(16, Channel.CONTROL),
    CHAT_MESSAGE(17, Channel.CONTROL),
    ADMIN_COMMAND(18, Channel.CONTROL),
    PING(19, Channel.STATE),
    PONG(20, Channel.STATE);

    private static final MessageType[] BY_WIRE_ID = buildLookup();

    private final int wireId;
    private final Channel channel;

    MessageType(int wireId, Channel channel) {
        this.wireId = wireId;
        this.channel = channel;
    }

    /** The byte this message is tagged with on the wire. Stable forever. */
    public int wireId() {
        return wireId;
    }

    /** The channel D10-R3 assigns this message. */
    public Channel channel() {
        return channel;
    }

    /**
     * The message with this wire id, or null when the id is unknown.
     *
     * <p>Null rather than a throw: an unknown id is what a peer running a newer build sends, and a
     * receiver should log and drop one packet rather than fail the connection. The handshake's
     * protocol version is what turns a genuine mismatch into a refusal (D10-R11).
     */
    public static MessageType byWireId(int wireId) {
        return wireId >= 0 && wireId < BY_WIRE_ID.length ? BY_WIRE_ID[wireId] : null;
    }

    private static MessageType[] buildLookup() {
        int max = 0;
        for (MessageType type : values()) {
            max = Math.max(max, type.wireId);
        }
        MessageType[] lookup = new MessageType[max + 1];
        for (MessageType type : values()) {
            if (lookup[type.wireId] != null) {
                throw new IllegalStateException(
                        "duplicate message wire id " + type.wireId + ": " + lookup[type.wireId] + " and " + type);
            }
            lookup[type.wireId] = type;
        }
        return lookup;
    }
}

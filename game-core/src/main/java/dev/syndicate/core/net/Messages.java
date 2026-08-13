/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.DespawnReason;
import dev.syndicate.model.net.DisconnectReason;
import dev.syndicate.model.net.MessageType;
import dev.syndicate.model.net.NetConstants;
import dev.syndicate.model.net.RejectReason;
import dev.syndicate.model.net.StructuralEventType;

/**
 * The wire form of every message in docs/10_networking_multiplayer.md#D10-S4.2 that the replication
 * layer sends.
 *
 * <p>Reader and writer sit next to each other for each message, which is the same reasoning as
 * {@link SnapshotCodec}: a field added to one and not the other is a protocol break that compiles,
 * and adjacency is the only defence that costs nothing.
 *
 * <p>Every packet begins with one byte of {@link MessageType#wireId()}, so a receiver can dispatch
 * before it knows what it is holding, and an unknown id costs one dropped packet rather than a
 * desynchronised stream.
 *
 * <p><b>Not yet encoded here:</b> {@code ChatMessage}, {@code AdminCommand}, {@code ScoreUpdate},
 * {@code MatchPhase} and {@code Ping}/{@code Pong}. Each is in {@link MessageType} with its wire id
 * reserved, and each needs a subsystem this layer does not yet drive — see DEV-016.
 */
public final class Messages {

    private static final int MESSAGE_TYPE_BITS = 8;

    /** Bits for the number of commands in an input packet's redundancy window. */
    private static final int INPUT_COUNT_BITS = 4;

    private Messages() {
        throw new AssertionError("no instances");
    }

    /** Writes a message's type byte. Every {@code write*} below calls this first. */
    public static void writeType(BitWriter writer, MessageType type) {
        writer.writeBits(type.wireId(), MESSAGE_TYPE_BITS);
    }

    /** Reads a message's type, or null when the id is one this build does not know. */
    public static MessageType readType(BitReader reader) {
        return MessageType.byWireId(reader.readBits(MESSAGE_TYPE_BITS));
    }

    // ---- Handshake ------------------------------------------------------------------

    /** A client's opening message (D10-S5.8 step 1). */
    public record ClientHello(int protocolVersion, long contentHash, String clientVersion, String playerName) {}

    public static void writeClientHello(BitWriter writer, ClientHello hello) {
        writeType(writer, MessageType.CLIENT_HELLO);
        writer.writeInt(hello.protocolVersion());
        writer.writeLongBits(hello.contentHash(), 64);
        writer.writeString(hello.clientVersion());
        writer.writeString(hello.playerName());
    }

    public static ClientHello readClientHello(BitReader reader) {
        return new ClientHello(reader.readInt(), reader.readLongBits(64), reader.readString(), reader.readString());
    }

    /** The authority's acceptance (D10-S5.8 step 2). */
    public record ServerHello(int peerId, long serverTick, int protocolVersion, long contentHash, long matchSeed) {}

    public static void writeServerHello(BitWriter writer, ServerHello hello) {
        writeType(writer, MessageType.SERVER_HELLO);
        writer.writeInt(hello.peerId());
        writer.writeTick(hello.serverTick());
        writer.writeInt(hello.protocolVersion());
        writer.writeLongBits(hello.contentHash(), 64);
        writer.writeLongBits(hello.matchSeed(), 64);
    }

    public static ServerHello readServerHello(BitReader reader) {
        return new ServerHello(
                reader.readInt(),
                reader.readTick(),
                reader.readInt(),
                reader.readLongBits(64),
                reader.readLongBits(64));
    }

    /**
     * A refusal, with enough detail to explain it.
     *
     * <p>{@code detail} carries both sides' values for a mismatch, which D10-R11 requires: a
     * refusal that says only "content mismatch" leaves both operators guessing which of them is out
     * of date.
     */
    public record Reject(RejectReason reason, String detail) {}

    public static void writeReject(BitWriter writer, Reject reject) {
        writeType(writer, MessageType.REJECT);
        writer.writeBits(reject.reason().ordinal(), 8);
        writer.writeString(reject.detail());
    }

    public static Reject readReject(BitReader reader) {
        RejectReason reason = enumOf(RejectReason.values(), reader.readBits(8), "reject reason");
        return new Reject(reason, reader.readString());
    }

    /** The match's shape, sent once after acceptance (D10-S5.8 step 2). */
    public record MatchConfig(String arenaId, long matchSeed, int scoreLimit, int timeLimitTicks) {}

    public static void writeMatchConfig(BitWriter writer, MatchConfig config) {
        writeType(writer, MessageType.MATCH_CONFIG);
        writer.writeString(config.arenaId());
        writer.writeLongBits(config.matchSeed(), 64);
        writer.writeInt(config.scoreLimit());
        writer.writeInt(config.timeLimitTicks());
    }

    public static MatchConfig readMatchConfig(BitReader reader) {
        return new MatchConfig(reader.readString(), reader.readLongBits(64), reader.readInt(), reader.readInt());
    }

    /** Either side ending the connection. */
    public record Disconnect(DisconnectReason reason, String detail) {}

    public static void writeDisconnect(BitWriter writer, Disconnect disconnect) {
        writeType(writer, MessageType.DISCONNECT);
        writer.writeBits(disconnect.reason().ordinal(), 8);
        writer.writeString(disconnect.detail());
    }

    public static Disconnect readDisconnect(BitReader reader) {
        DisconnectReason reason = enumOf(DisconnectReason.values(), reader.readBits(8), "disconnect reason");
        return new Disconnect(reason, reader.readString());
    }

    // ---- Entity lifecycle -----------------------------------------------------------

    /**
     * A vehicle appearing, as a block of network ids rather than an entity per part.
     *
     * <p>{@code baseNetworkId} names the vehicle and {@code blockSize} covers it and every part in
     * ascending slot-path order, which both peers derive by building the same assembly through the
     * same factory (DEC-059). A forty-part vehicle is therefore one message, and the ids on both
     * sides agree by construction rather than by enumeration.
     */
    public record SpawnEntity(
            int baseNetworkId,
            int blockSize,
            String assemblyId,
            int ownerPeerId,
            int teamId,
            float x,
            float y,
            float z,
            int packedRotation,
            long tick) {}

    public static void writeSpawnEntity(BitWriter writer, SpawnEntity spawn) {
        writeType(writer, MessageType.SPAWN_ENTITY);
        writer.writeInt(spawn.baseNetworkId());
        writer.writeBits(spawn.blockSize(), 16);
        writer.writeString(spawn.assemblyId());
        writer.writeInt(spawn.ownerPeerId());
        writer.writeInt(spawn.teamId());
        // Full floats rather than the quantised position of a snapshot: a spawn happens once, is on
        // the reliable channel, and is the transform every later delta is measured against.
        writer.writeFloat(spawn.x());
        writer.writeFloat(spawn.y());
        writer.writeFloat(spawn.z());
        writer.writeInt(spawn.packedRotation());
        writer.writeTick(spawn.tick());
    }

    public static SpawnEntity readSpawnEntity(BitReader reader) {
        return new SpawnEntity(
                reader.readInt(),
                reader.readBits(16),
                reader.readString(),
                reader.readInt(),
                reader.readInt(),
                reader.readFloat(),
                reader.readFloat(),
                reader.readFloat(),
                reader.readInt(),
                reader.readTick());
    }

    /** An entity leaving. */
    public record DespawnEntity(int networkId, DespawnReason reason, long tick) {}

    public static void writeDespawnEntity(BitWriter writer, DespawnEntity despawn) {
        writeType(writer, MessageType.DESPAWN_ENTITY);
        writer.writeInt(despawn.networkId());
        writer.writeBits(despawn.reason().ordinal(), 8);
        writer.writeTick(despawn.tick());
    }

    public static DespawnEntity readDespawnEntity(BitReader reader) {
        int networkId = reader.readInt();
        DespawnReason reason = enumOf(DespawnReason.values(), reader.readBits(8), "despawn reason");
        return new DespawnEntity(networkId, reason, reader.readTick());
    }

    /** One of the four destruction events of D07-S5.9, on the reliable channel. */
    public record StructuralEvent(
            StructuralEventType type, int vehicleNetworkId, int partNetworkId, String slotPath, long tick) {}

    public static void writeStructuralEvent(BitWriter writer, StructuralEvent event) {
        writeType(writer, MessageType.STRUCTURAL_EVENT);
        writer.writeBits(event.type().ordinal(), 8);
        writer.writeInt(event.vehicleNetworkId());
        writer.writeInt(event.partNetworkId());
        writer.writeString(event.slotPath());
        writer.writeTick(event.tick());
    }

    public static StructuralEvent readStructuralEvent(BitReader reader) {
        StructuralEventType type = enumOf(StructuralEventType.values(), reader.readBits(8), "structural event");
        return new StructuralEvent(type, reader.readInt(), reader.readInt(), reader.readString(), reader.readTick());
    }

    // ---- Input ----------------------------------------------------------------------

    /**
     * Writes a client's input packet: the newest command plus its redundancy window, and the
     * snapshot tick the client is acknowledging.
     *
     * <p>The acknowledgement rides here rather than in a message of its own (DEC-058). A client
     * sends input at 60 Hz whatever else is happening, so the acknowledgement is never later than
     * 16 ms, and an authority that had to wait for a separate ACK message would be deltaing against
     * a staler baseline for no gain.
     *
     * @param commands newest first, as {@code PredictionComponent.pendingInputs} holds them
     * @param count how many of them to send, capped at {@code INPUT_REDUNDANCY + 1}
     */
    public static void writeInputCommand(
            BitWriter writer, InputCommand[] commands, int count, long acknowledgedSnapshotTick) {
        int capped = Math.min(count, NetConstants.INPUT_REDUNDANCY + 1);
        writeType(writer, MessageType.INPUT_COMMAND);
        writer.writeTick(acknowledgedSnapshotTick);
        writer.writeBits(capped, INPUT_COUNT_BITS);
        for (int i = 0; i < capped; i++) {
            writeOneCommand(writer, commands[i]);
        }
    }

    private static void writeOneCommand(BitWriter writer, InputCommand command) {
        writer.writeInt(command.sequence);
        writer.writeTick(command.commandTick);
        writer.writeBits(Quantisation.encodeControlAxis(command.throttle), NetConstants.CONTROL_AXIS_BITS);
        writer.writeBits(Quantisation.encodeControlAxis(command.steer), NetConstants.CONTROL_AXIS_BITS);
        writer.writeBits(
                Quantisation.encodeUnit(command.brake, NetConstants.CONTROL_AXIS_BITS), NetConstants.CONTROL_AXIS_BITS);
        writer.writeBits(Quantisation.encodeAngle(command.aimYawRad), NetConstants.AIM_BITS);
        writer.writeBits(Quantisation.encodeAngle(command.aimPitchRad), NetConstants.AIM_BITS);
        writer.writeBits(command.fireMask & 0xFF, NetConstants.FIRE_MASK_BITS);
    }

    /** The tick a client acknowledges, read before its commands. */
    public static long readInputAcknowledgedTick(BitReader reader) {
        return reader.readTick();
    }

    /** How many commands follow. */
    public static int readInputCount(BitReader reader) {
        return reader.readBits(INPUT_COUNT_BITS);
    }

    /** Reads one command into {@code out}, which is returned. */
    public static InputCommand readOneCommand(BitReader reader, InputCommand out) {
        out.sequence = reader.readInt();
        out.commandTick = reader.readTick();
        out.throttle = Quantisation.decodeControlAxis(reader.readBits(NetConstants.CONTROL_AXIS_BITS));
        out.steer = Quantisation.decodeControlAxis(reader.readBits(NetConstants.CONTROL_AXIS_BITS));
        out.brake = Quantisation.decodeUnit(
                reader.readBits(NetConstants.CONTROL_AXIS_BITS), NetConstants.CONTROL_AXIS_BITS);
        out.aimYawRad = Quantisation.decodeAngle(reader.readBits(NetConstants.AIM_BITS));
        out.aimPitchRad = Quantisation.decodeAngle(reader.readBits(NetConstants.AIM_BITS));
        out.fireMask = reader.readBits(NetConstants.FIRE_MASK_BITS);
        return out;
    }

    // ---- Snapshot recovery ----------------------------------------------------------

    /**
     * "I cannot decode a delta against that baseline" (D10-R18).
     *
     * <p>Not in D10-S4.2's table, which R18 nonetheless requires; the row was added to the document
     * in the same commit (DEC-058). Discarding silently would leave the client waiting for a
     * baseline that never comes, and applying the delta to the wrong base would corrupt state with
     * nothing to notice it.
     */
    public record SnapshotNack(long missingBaselineTick) {}

    public static void writeSnapshotNack(BitWriter writer, SnapshotNack nack) {
        writeType(writer, MessageType.SNAPSHOT_NACK);
        writer.writeTick(nack.missingBaselineTick());
    }

    public static SnapshotNack readSnapshotNack(BitReader reader) {
        return new SnapshotNack(reader.readTick());
    }

    // ---- Combat feedback ------------------------------------------------------------

    /** Confirmation to a shooter that a shot landed (D10-S4.2). */
    public record HitConfirm(
            int targetNetworkId, String slotPath, float damageApplied, int damageTypeOrdinal, long tick) {}

    public static void writeHitConfirm(BitWriter writer, HitConfirm confirm) {
        writeType(writer, MessageType.HIT_CONFIRM);
        writer.writeInt(confirm.targetNetworkId());
        writer.writeString(confirm.slotPath());
        writer.writeFloat(confirm.damageApplied());
        writer.writeBits(confirm.damageTypeOrdinal(), 8);
        writer.writeTick(confirm.tick());
    }

    public static HitConfirm readHitConfirm(BitReader reader) {
        return new HitConfirm(
                reader.readInt(), reader.readString(), reader.readFloat(), reader.readBits(8), reader.readTick());
    }

    /** Notification to a victim that it was hit, and from where. */
    public record DamageReceived(
            int attackerPeerId,
            String slotPath,
            float amount,
            int damageTypeOrdinal,
            float dirX,
            float dirY,
            float dirZ) {}

    public static void writeDamageReceived(BitWriter writer, DamageReceived damage) {
        writeType(writer, MessageType.DAMAGE_RECEIVED);
        writer.writeInt(damage.attackerPeerId());
        writer.writeString(damage.slotPath());
        writer.writeFloat(damage.amount());
        writer.writeBits(damage.damageTypeOrdinal(), 8);
        writer.writeFloat(damage.dirX());
        writer.writeFloat(damage.dirY());
        writer.writeFloat(damage.dirZ());
    }

    public static DamageReceived readDamageReceived(BitReader reader) {
        return new DamageReceived(
                reader.readInt(),
                reader.readString(),
                reader.readFloat(),
                reader.readBits(8),
                reader.readFloat(),
                reader.readFloat(),
                reader.readFloat());
    }

    private static <T> T enumOf(T[] values, int ordinal, String what) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new BitReader.MalformedPacketException(what + " ordinal " + ordinal + " is out of range");
        }
        return values[ordinal];
    }
}

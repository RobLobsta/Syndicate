/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.InterpolationComponent;
import dev.syndicate.core.component.NetworkReplicatedComponent;
import dev.syndicate.core.component.PredictionComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.DisconnectReason;
import dev.syndicate.model.net.MessageType;
import dev.syndicate.model.net.NetConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client half of replication: the handshake, applying snapshots, and sending input
 * (docs/10_networking_multiplayer.md#D10-S5.5, #S5.6, #S5.8).
 *
 * <p>Driven by slot 19, which calls {@link #sendInput} and {@link #receive} each tick, and read by
 * slot 20, which asks it what the authority says about the local vehicle. As on the authority side,
 * the cross-tick state — the frame history, the connection state machine, the pending correction —
 * lives here rather than in either system (D04-R3).
 *
 * <p>Two rules shape everything below:
 *
 * <ul>
 *   <li><b>Remote entities are set from the snapshot; the local vehicle is not</b> (D10-R19). A
 *       remote car's authoritative transform is the truth and is applied directly. The local car's
 *       is compared against what this client predicted, and only a disagreement past
 *       {@code RECONCILE_POS_THRESHOLD_M} costs a correction — which is slot 20's job, not this
 *       class's.
 *   <li><b>Damage is never predicted</b> (D10-R20). A pure client has no {@code DamageSystem} in its
 *       schedule at all (D03-S5.2), so the only way health or damage state changes here is a
 *       snapshot. There is no code path by which a client can destroy a part the authority kept.
 * </ul>
 */
public final class NetworkClient implements TransportListener {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkClient.class);

    /** Where the client is in D10-S5.8's state machine. */
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        SYNCING,
        PLAYING
    }

    /** How a client turns a {@code SpawnEntity} into a real vehicle. */
    @FunctionalInterface
    public interface VehicleSpawner {
        /**
         * Builds the named assembly at the given transform.
         *
         * @return the vehicle entity, or {@link EntityId#NULL} if it could not be built
         */
        int spawn(World world, AssetId assemblyId, Matrix4 transform, int teamId);
    }

    /** One predicted state, kept so reconciliation can compare like with like. */
    private static final class PredictedState {
        long tick;
        final Vector3 position = new Vector3();
        final Quaternion rotation = new Quaternion();
    }

    private final Transport transport;
    private final NetworkRegistry registry = new NetworkRegistry();
    private final SnapshotCodec codec = new SnapshotCodec();
    private final BitWriter writer = new BitWriter();
    private final BitReader reader = new BitReader();
    private final InputAck ack = new InputAck();
    private final VehicleSpawner spawner;

    /** Frames this client has arrived at, keyed by authority tick, newest last. */
    private final TreeMap<Long, SnapshotFrame> frames = new TreeMap<>();

    private final SnapshotFrame working = new SnapshotFrame();
    private final List<PredictedState> predicted = new ArrayList<>();
    private final InputCommand[] outgoing = new InputCommand[NetConstants.INPUT_REDUNDANCY + 1];
    private final Matrix4 scratchMatrix = new Matrix4();
    private final Quaternion scratchRotation = new Quaternion();
    private final Vector3 scratchPosition = new Vector3();
    private final EntityState authoritativeLocal = new EntityState();

    private final long contentHash;
    private final String playerName;
    private final String clientVersion;

    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private int peerId = NetConstants.NO_PEER_ID;
    private long matchSeed;
    private String arenaId = "";

    private int localVehicleNetworkId = NetworkId.NONE;
    private int localVehicleEntity = EntityId.NULL;
    private long lastAppliedServerTick = NetConstants.FULL_SNAPSHOT_BASELINE;
    private long acknowledgedSnapshotTick = NetConstants.FULL_SNAPSHOT_BASELINE;
    private boolean hasPendingCorrection;
    private int nextSequence = 1;
    private int discardedStaleSnapshots;
    private int nackedSnapshots;

    private World pollWorld;
    private long pollTick;

    public NetworkClient(
            Transport transport, long contentHash, String playerName, String clientVersion, VehicleSpawner spawner) {
        this.transport = transport;
        this.contentHash = contentHash;
        this.playerName = playerName;
        this.clientVersion = clientVersion;
        this.spawner = spawner;
        for (int i = 0; i < outgoing.length; i++) {
            outgoing[i] = new InputCommand();
        }
    }

    // ---- Accessors -------------------------------------------------------------------

    public ConnectionState connectionState() {
        return connectionState;
    }

    public int peerId() {
        return peerId;
    }

    public long matchSeed() {
        return matchSeed;
    }

    public String arenaId() {
        return arenaId;
    }

    public NetworkRegistry registry() {
        return registry;
    }

    /** The local player's vehicle, or {@link EntityId#NULL} before the authority has spawned one. */
    public int localVehicleEntity() {
        return localVehicleEntity;
    }

    /** True when a snapshot has arrived that slot 20 has not yet compared against its prediction. */
    public boolean hasPendingCorrection() {
        return hasPendingCorrection;
    }

    /** The authority's state for the local vehicle, valid while {@link #hasPendingCorrection}. */
    public EntityState authoritativeLocal() {
        return authoritativeLocal;
    }

    /** The tick that state describes. */
    public long authoritativeTick() {
        return lastAppliedServerTick;
    }

    /** Snapshots discarded because a newer one had already been applied (AC-D10-6). */
    public int discardedStaleSnapshots() {
        return discardedStaleSnapshots;
    }

    /** Deltas refused for want of their baseline (AC-D10-7). */
    public int nackedSnapshots() {
        return nackedSnapshots;
    }

    /** Clears the correction flag once slot 20 has acted on it. */
    public void clearPendingCorrection() {
        hasPendingCorrection = false;
    }

    // ---- Connection ------------------------------------------------------------------

    /** Sends {@code ClientHello} and enters {@code CONNECTING} (D10-S5.8 step 1). */
    public void connect() {
        writer.reset();
        Messages.writeClientHello(
                writer,
                new Messages.ClientHello(NetConstants.PROTOCOL_VERSION, contentHash, clientVersion, playerName));
        transport.send(NetConstants.SERVER_PEER_ID, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
        connectionState = ConnectionState.CONNECTING;
    }

    /** Tells the authority this client is leaving, then releases the transport. */
    public void disconnect(DisconnectReason reason) {
        if (connectionState != ConnectionState.DISCONNECTED) {
            writer.reset();
            Messages.writeDisconnect(writer, new Messages.Disconnect(reason, ""));
            transport.send(NetConstants.SERVER_PEER_ID, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
        }
        connectionState = ConnectionState.DISCONNECTED;
        transport.dispose();
    }

    // ---- Slot 19: send input, then apply what arrived ---------------------------------

    /**
     * Records this tick's input in the prediction buffer and sends it with its redundancy window
     * (D10-S5.5 step 1, D10-R4).
     *
     * <p>The acknowledgement of the newest snapshot rides along (DEC-058): a client sends input
     * every tick regardless, so the authority learns what baseline to delta against within 16 ms
     * without a message of its own.
     */
    public void sendInput(
            World world,
            long tick,
            float throttle,
            float steer,
            float brake,
            float aimYawRad,
            float aimPitchRad,
            int fireMask) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            return;
        }
        PredictionComponent prediction = localVehicleEntity == EntityId.NULL
                ? null
                : world.getComponent(localVehicleEntity, PredictionComponent.class);
        InputCommand command = prediction == null ? outgoing[0] : prediction.pendingInputs.next();
        command.sequence = nextSequence++;
        command.commandTick = tick;
        command.throttle = throttle;
        command.steer = steer;
        command.brake = brake;
        command.aimYawRad = aimYawRad;
        command.aimPitchRad = aimPitchRad;
        command.fireMask = fireMask;

        int count = 1;
        outgoing[0].set(command);
        if (prediction != null) {
            count = Math.min(prediction.pendingInputs.size(), outgoing.length);
            for (int i = 0; i < count; i++) {
                outgoing[i].set(prediction.pendingInputs.get(i));
            }
        }

        writer.reset();
        Messages.writeInputCommand(writer, outgoing, count, acknowledgedSnapshotTick);
        transport.send(NetConstants.SERVER_PEER_ID, Channel.STATE, writer.buffer(), 0, writer.byteLength());
    }

    /** Records where this client predicted its own vehicle would be, for slot 20 to compare against. */
    public void recordPrediction(World world, long tick) {
        if (localVehicleEntity == EntityId.NULL) {
            return;
        }
        TransformComponent transform = world.getComponent(localVehicleEntity, TransformComponent.class);
        if (transform == null) {
            return;
        }
        PredictedState state = predictionSlot(tick);
        state.tick = tick;
        state.position.set(transform.position);
        state.rotation.set(transform.rotation);
    }

    private PredictedState predictionSlot(long tick) {
        int index = (int) Math.floorMod(tick, (long) PredictionComponent.CAPACITY);
        while (predicted.size() <= index) {
            predicted.add(new PredictedState());
        }
        return predicted.get(index);
    }

    /** What this client predicted at {@code tick}, or null when it is older than the buffer. */
    public boolean predictionAt(long tick, Vector3 positionOut, Quaternion rotationOut) {
        PredictedState state = predictionSlot(tick);
        if (state.tick != tick) {
            return false;
        }
        positionOut.set(state.position);
        rotationOut.set(state.rotation);
        return true;
    }

    /** Drains the transport and applies everything that arrived. */
    public void receive(World world, long tick) {
        pollWorld = world;
        pollTick = tick;
        transport.poll(this);
        pollWorld = null;
    }

    // ---- Transport callbacks ----------------------------------------------------------

    @Override
    public void onPeerDisconnected(int peerId, DisconnectReason reason) {
        LOG.info("disconnected from the authority: {}", reason);
        connectionState = ConnectionState.DISCONNECTED;
    }

    @Override
    public void onMessage(int fromPeerId, Channel channel, byte[] payload, int offset, int length) {
        try {
            reader.reset(payload, offset, length);
            MessageType type = Messages.readType(reader);
            if (type == null) {
                LOG.debug("authority sent an unknown message type; dropping the packet");
                return;
            }
            switch (type) {
                case SERVER_HELLO -> handleServerHello();
                case REJECT -> handleReject();
                case MATCH_CONFIG -> handleMatchConfig();
                case SPAWN_ENTITY -> handleSpawn();
                case DESPAWN_ENTITY -> handleDespawn();
                case STRUCTURAL_EVENT -> handleStructural();
                case SNAPSHOT -> handleSnapshot();
                case DISCONNECT -> handleDisconnect();
                default -> LOG.debug("authority sent {}, which the client does not consume yet", type);
            }
        } catch (BitReader.MalformedPacketException e) {
            LOG.warn("dropping malformed packet from the authority: {}", e.getMessage());
        }
    }

    private void handleServerHello() {
        Messages.ServerHello hello = Messages.readServerHello(reader);
        peerId = hello.peerId();
        matchSeed = hello.matchSeed();
        connectionState = ConnectionState.SYNCING;
        LOG.info("accepted as peer {} at authority tick {}", peerId, hello.serverTick());
    }

    private void handleReject() {
        Messages.Reject reject = Messages.readReject(reader);
        // Both values are in the detail string, per D10-R11: a refusal that does not say which side
        // is out of date leaves both operators guessing.
        LOG.error("connection refused: {} — {}", reject.reason(), reject.detail());
        connectionState = ConnectionState.DISCONNECTED;
    }

    private void handleMatchConfig() {
        Messages.MatchConfig config = Messages.readMatchConfig(reader);
        arenaId = config.arenaId();
        matchSeed = config.matchSeed();
    }

    private void handleSpawn() {
        Messages.SpawnEntity spawn = Messages.readSpawnEntity(reader);
        if (registry.entityOf(spawn.baseNetworkId()) != EntityId.NULL) {
            return;
        }
        Quantisation.unpackRotation(spawn.packedRotation(), scratchRotation);
        scratchMatrix.idt();
        scratchMatrix.set(scratchPosition.set(spawn.x(), spawn.y(), spawn.z()), scratchRotation.nor());

        int vehicleEntity = spawner.spawn(pollWorld, AssetId.of(spawn.assemblyId()), scratchMatrix, spawn.teamId());
        if (vehicleEntity == EntityId.NULL) {
            // G18: content that cannot be resolved degrades rather than refusing. The authority will
            // keep sending state for a vehicle this client cannot draw, and the log line is what
            // makes that visible instead of mysterious.
            LOG.error("cannot spawn assembly {} the authority sent; the vehicle will be missing", spawn.assemblyId());
            return;
        }
        ReplicationTagging.tag(pollWorld, vehicleEntity, spawn.baseNetworkId(), spawn.ownerPeerId(), registry);
        attachClientComponents(vehicleEntity, spawn.ownerPeerId());

        if (spawn.ownerPeerId() == peerId) {
            localVehicleNetworkId = spawn.baseNetworkId();
            localVehicleEntity = vehicleEntity;
        }
        if (connectionState == ConnectionState.SYNCING) {
            connectionState = ConnectionState.PLAYING;
        }
    }

    /**
     * Adds the client-side components a replicated vehicle needs.
     *
     * <p>A remote vehicle gets an {@code InterpolationComponent} because it is rendered 100 ms in
     * the past (D10-S5.6); the local one gets a {@code PredictionComponent} because it is the only
     * entity this client predicts (D10-R19). Neither is ever on the same entity, which is what makes
     * "is this mine?" answerable by looking at the components.
     */
    private void attachClientComponents(int vehicleEntity, int ownerPeerId) {
        if (ownerPeerId == peerId) {
            if (pollWorld.getComponent(vehicleEntity, PredictionComponent.class) == null) {
                pollWorld.addComponent(vehicleEntity, new PredictionComponent());
            }
        } else if (pollWorld.getComponent(vehicleEntity, InterpolationComponent.class) == null) {
            pollWorld.addComponent(vehicleEntity, new InterpolationComponent());
        }
    }

    private void handleDespawn() {
        Messages.DespawnEntity despawn = Messages.readDespawnEntity(reader);
        int entityId = registry.entityOf(despawn.networkId());
        if (entityId == EntityId.NULL) {
            return;
        }
        ReplicationTagging.untag(pollWorld, entityId, registry);
        for (SnapshotFrame frame : frames.values()) {
            frame.remove(despawn.networkId());
        }
        if (despawn.networkId() == localVehicleNetworkId) {
            localVehicleNetworkId = NetworkId.NONE;
            localVehicleEntity = EntityId.NULL;
        }
        pollWorld.destroyEntity(entityId);
    }

    private void handleStructural() {
        Messages.StructuralEvent event = Messages.readStructuralEvent(reader);
        // Republished locally so the client's own presentation systems — shards, debris, sound —
        // react to it exactly as they do on a listen server, from the same event type (DEC-005).
        pollWorld.events().emit(event);
    }

    private void handleSnapshot() {
        SnapshotCodec.Header header = codec.readHeader(reader, ack);
        if (header.serverTick() <= lastAppliedServerTick) {
            // Out of order or duplicated. Discarded by tick number, never applied (AC-D10-6): STATE
            // is unordered, so this is a normal event rather than an error.
            discardedStaleSnapshots++;
            return;
        }
        SnapshotFrame baseline = null;
        if (header.baselineTick() != NetConstants.FULL_SNAPSHOT_BASELINE) {
            baseline = frames.get(header.baselineTick());
            if (baseline == null) {
                // D10-R18: a delta against a baseline this client does not have is undecodable.
                // NACK and discard, rather than apply it to a wrong base and corrupt state silently.
                nackedSnapshots++;
                writer.reset();
                Messages.writeSnapshotNack(writer, new Messages.SnapshotNack(header.baselineTick()));
                transport.send(NetConstants.SERVER_PEER_ID, Channel.STATE, writer.buffer(), 0, writer.byteLength());
                return;
            }
        }

        working.clear();
        if (baseline != null) {
            working.copyFrom(baseline);
        }
        codec.decodeEntities(reader, working, header.serverTick());

        applyFrame(pollWorld, working, true);
        storeFrame(working);
        recordAcknowledgement();
        lastAppliedServerTick = header.serverTick();
        acknowledgedSnapshotTick = header.serverTick();
        if (connectionState == ConnectionState.SYNCING) {
            connectionState = ConnectionState.PLAYING;
        }
    }

    /**
     * Records how far the authority has got through this client's input.
     *
     * <p>Kept on the component rather than in this class because it is what slot 20 replays from,
     * and D04-S4.3.5 puts {@code lastAckedTick} there. Commands at or before it have been simulated
     * by the authority and must not be replayed on top of its answer.
     */
    private void recordAcknowledgement() {
        if (localVehicleEntity == EntityId.NULL) {
            return;
        }
        PredictionComponent prediction = pollWorld.getComponent(localVehicleEntity, PredictionComponent.class);
        if (prediction != null) {
            prediction.lastAckedTick = ack.lastProcessedTick;
        }
    }

    private void applyFrame(World world, SnapshotFrame frame, boolean bufferSamples) {
        for (Map.Entry<Integer, EntityState> entry : frame.entries()) {
            int networkId = entry.getKey();
            EntityState state = entry.getValue();
            if (networkId == localVehicleNetworkId) {
                // The local vehicle is predicted, so its authoritative state is a comparison rather
                // than an assignment. Slot 20 decides whether the difference is worth a rewind.
                if (bufferSamples) {
                    authoritativeLocal.set(state);
                    hasPendingCorrection = true;
                }
                continue;
            }
            int entityId = registry.entityOf(networkId);
            if (entityId == EntityId.NULL || !world.isAlive(entityId)) {
                // D10-E17: state for an id this client has not spawned. The CONTROL channel carries
                // the spawn and will land; until then there is nothing to apply it to.
                continue;
            }
            codec.apply(world, entityId, state);
            if (bufferSamples) {
                bufferInterpolationSample(world, entityId, state, frame.tick());
            }
        }
    }

    /**
     * Puts every remote entity back where the newest snapshot says it is.
     *
     * <p>Called by slot 20 after a rewind-and-replay. Replaying the local vehicle means stepping the
     * physics world, which advances <em>every</em> body in it — so the remote ones are restored from
     * the authority's state afterwards rather than left several ticks into a future nobody asked
     * them to simulate (D10-R19, D10-R24).
     */
    public void reapplyLatestFrame(World world) {
        if (frames.isEmpty()) {
            return;
        }
        applyFrame(world, frames.lastEntry().getValue(), false);
    }

    /**
     * Records a remote entity's authoritative transform for slot 22 to render between.
     *
     * <p>The physics body has already been set to this same state by {@link SnapshotCodec#apply};
     * what goes in the buffer is for <em>rendering</em>, 100 ms behind (D10-R22). Mixing the two is
     * what makes a remote vehicle collide with where it used to be.
     */
    private void bufferInterpolationSample(World world, int entityId, EntityState state, long tick) {
        InterpolationComponent interpolation = world.getComponent(entityId, InterpolationComponent.class);
        if (interpolation == null || !state.has(ReplicatedComponent.TRANSFORM)) {
            return;
        }
        TransformSample sample = interpolation.buffer.next();
        sample.tick = tick;
        int slot = ReplicatedField.POSITION.slot();
        sample.position.set(
                Quantisation.decodePositionAxis(state.values[slot]),
                Quantisation.decodePositionAxis(state.values[slot + 1]),
                Quantisation.decodePositionAxis(state.values[slot + 2]));
        Quantisation.unpackRotation(state.values[ReplicatedField.ROTATION.slot()], sample.rotation);
        sample.rotation.nor();
    }

    private void storeFrame(SnapshotFrame frame) {
        SnapshotFrame stored = new SnapshotFrame();
        stored.copyFrom(frame);
        frames.put(frame.tick(), stored);
        while (frames.size() > NetConstants.SNAPSHOT_HISTORY) {
            frames.remove(frames.firstKey());
        }
    }

    private void handleDisconnect() {
        Messages.Disconnect disconnect = Messages.readDisconnect(reader);
        LOG.info("the authority closed the connection: {} ({})", disconnect.reason(), disconnect.detail());
        connectionState = ConnectionState.DISCONNECTED;
    }

    /** Registered network ids, for tests and diagnostics. */
    public int replicatedEntityCount() {
        return registry.size();
    }

    /** How many snapshot frames are held as potential baselines. */
    public int frameHistorySize() {
        return frames.size();
    }

    /** Drops any {@code NetworkReplicatedComponent} this client attached, for a clean match reset. */
    public void reset(World world) {
        for (Map.Entry<Integer, Integer> binding : registry.bindings()) {
            NetworkReplicatedComponent replicated =
                    world.getComponent(binding.getValue(), NetworkReplicatedComponent.class);
            if (replicated != null) {
                replicated.reset();
            }
        }
        registry.clearBindings();
        frames.clear();
        working.clear();
        localVehicleEntity = EntityId.NULL;
        localVehicleNetworkId = NetworkId.NONE;
        lastAppliedServerTick = NetConstants.FULL_SNAPSHOT_BASELINE;
        acknowledgedSnapshotTick = NetConstants.FULL_SNAPSHOT_BASELINE;
        hasPendingCorrection = false;
    }
}

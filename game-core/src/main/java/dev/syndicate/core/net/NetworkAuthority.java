/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.NetworkReplicatedComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.DespawnReason;
import dev.syndicate.model.net.DisconnectReason;
import dev.syndicate.model.net.MessageType;
import dev.syndicate.model.net.NetConstants;
import dev.syndicate.model.net.RejectReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authority half of replication: peers, their input, and the snapshots they receive
 * (docs/10_networking_multiplayer.md#D10-S5.2, #S5.4, #S5.8).
 *
 * <p>Two schedule slots drive it and nothing else may: slot 2 calls {@link #receive} and
 * {@link #applyInputs}, slot 18 calls {@link #replicate}. Everything cross-tick lives here rather
 * than in either system, because D04-R3 makes systems stateless with respect to gameplay and per-peer
 * baselines are the largest piece of cross-tick state in the project.
 *
 * <p>It is deliberately <b>the same object in every mode</b> (D10-R32). A dedicated server with
 * twelve peers, a listen server with a loopback peer plus eleven remote ones, and a single-player
 * match with exactly one loopback peer all run this class; there is no local shortcut, which is what
 * makes AC-D10-19 and AC-D10-22 checkable.
 */
public final class NetworkAuthority implements TransportListener {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkAuthority.class);

    /** Ticks between snapshots: 3 at 60 Hz simulation and 20 Hz snapshots (D10-R13). */
    public static final int TICKS_PER_SNAPSHOT =
            SimulationConstants.TICK_RATE_HZ / SimulationConstants.SNAPSHOT_RATE_HZ;

    /** What the authority knows about one replicated vehicle. */
    private static final class VehicleRecord {
        int entityId;
        int baseNetworkId;
        int blockSize;
        String assemblyId = "";
        int ownerPeerId = NetConstants.NO_PEER_ID;
        int teamId;
        float x;
        float y;
        float z;
        int packedRotation;
        long spawnTick;
    }

    private final Transport transport;
    private final NetworkRegistry registry = new NetworkRegistry();
    private final SnapshotCodec codec = new SnapshotCodec();
    private final BitWriter writer = new BitWriter();
    private final BitReader reader = new BitReader();

    private final TreeMap<Integer, PeerSession> peers = new TreeMap<>();
    private final TreeMap<Integer, Integer> peerByPlayerEntity = new TreeMap<>();
    private final TreeMap<Integer, VehicleRecord> vehiclesByBaseId = new TreeMap<>();
    private final List<Messages.StructuralEvent> pendingStructural = new ArrayList<>();
    private final InputCommand scratchCommand = new InputCommand();
    private final Quaternion scratchRotation = new Quaternion();
    private final Vector3 scratchPosition = new Vector3();

    private final long contentHash;
    private final long matchSeed;
    private final String arenaId;
    private final int maxPlayers;

    /** Set for the duration of a {@link #receive} call, so the listener callbacks can reach them. */
    private World pollWorld;

    private long pollTick;
    private int malformedPackets;

    public NetworkAuthority(Transport transport, long contentHash, long matchSeed, String arenaId, int maxPlayers) {
        this.transport = transport;
        this.contentHash = contentHash;
        this.matchSeed = matchSeed;
        this.arenaId = arenaId;
        this.maxPlayers = maxPlayers;
    }

    /** The wire-identity index, shared with whatever spawns and destroys replicated entities. */
    public NetworkRegistry registry() {
        return registry;
    }

    /** Every connected peer, in ascending id order (G3). */
    public Collection<PeerSession> peers() {
        return peers.values();
    }

    /** The session for a peer, or null. */
    public PeerSession peer(int peerId) {
        return peers.get(peerId);
    }

    /** How many packets have failed to decode. A rising count is a hostile or broken client. */
    public int malformedPackets() {
        return malformedPackets;
    }

    /**
     * Associates a peer with the player entity it drives.
     *
     * <p>The authority does not decide this: which peer is which player is a match-flow question
     * (D11-S5.7), and a headless match with no peers at all is a legitimate configuration. What this
     * class does with the association is find a peer's vehicle when that player spawns one.
     */
    public void bindPlayer(int peerId, int playerEntity) {
        peerByPlayerEntity.put(playerEntity, peerId);
    }

    /** Queues a destruction event for the reliable channel (D07-S5.9). */
    public void enqueueStructural(Messages.StructuralEvent event) {
        pendingStructural.add(event);
    }

    // ---- Slot 2: receive and apply --------------------------------------------------

    /** Drains the transport. Called by {@code InputReceiveSystem} before anything reads input. */
    public void receive(World world, long tick) {
        pollWorld = world;
        pollTick = tick;
        transport.poll(this);
        pollWorld = null;
    }

    /**
     * Writes each peer's chosen command onto the vehicle it drives (D10-S5.2 step 1).
     *
     * <p>Peers are iterated in ascending id order, which is what makes the result independent of
     * connection order (G3), and every command has already been through {@link InputValidator} —
     * this method never sees an unvalidated field (G15).
     */
    public void applyInputs(World world, long tick) {
        for (PeerSession peer : peers.values()) {
            InputCommand command = peer.inputBuffer.selectFor(tick);
            if (command == null) {
                peer.missedInputTicks++;
                if (!peer.hasAppliedInput) {
                    continue;
                }
                // D10-R15: repeat the movement, zero the firing. A repeated movement keeps the
                // vehicle plausible through a dropped packet; repeated firing would let a lagging
                // client shoot without asking.
                scratchCommand.set(peer.lastInput);
                scratchCommand.fireMask = 0;
                command = scratchCommand;
            } else {
                peer.lastInput.set(command);
                peer.hasAppliedInput = true;
            }

            peer.ack.set(command.sequence, tick);
            if (peer.vehicleEntity == EntityId.NULL) {
                continue;
            }
            PlayerInputComponent input = world.getComponent(peer.vehicleEntity, PlayerInputComponent.class);
            if (input == null) {
                continue;
            }
            input.throttle = command.throttle;
            input.steer = command.steer;
            input.brake = command.brake;
            input.aimYawRad = command.aimYawRad;
            input.aimPitchRad = command.aimPitchRad;
            input.fireMask = command.fireMask;
            input.commandTick = command.commandTick;
            input.sequence = command.sequence;
        }
    }

    // ---- Slot 18: replicate ----------------------------------------------------------

    /** Assigns identities to new vehicles, retires dead ones, and sends this tick's traffic. */
    public void replicate(World world, long tick, int[] vehicleEntities, int vehicleCount) {
        adoptNewVehicles(world, tick, vehicleEntities, vehicleCount);
        retireDeadVehicles(world, tick);
        flushStructuralEvents();
        sendSnapshots(world, tick);
    }

    private void adoptNewVehicles(World world, long tick, int[] vehicleEntities, int vehicleCount) {
        for (int i = 0; i < vehicleCount; i++) {
            int entityId = vehicleEntities[i];
            if (registry.networkIdOf(entityId) != NetworkId.NONE) {
                continue;
            }
            int ownerPeerId = ownerPeerOf(world, entityId);
            int blockSize =
                    ReplicationTagging.replicatedEntities(world, entityId).size();
            int baseNetworkId = registry.allocateBlock(blockSize);
            ReplicationTagging.tag(world, entityId, baseNetworkId, ownerPeerId, registry);

            VehicleRecord record = new VehicleRecord();
            record.entityId = entityId;
            record.baseNetworkId = baseNetworkId;
            record.blockSize = blockSize;
            record.ownerPeerId = ownerPeerId;
            record.spawnTick = tick;
            VehicleChassisComponent chassis = world.getComponent(entityId, VehicleChassisComponent.class);
            record.assemblyId = chassis == null || chassis.assemblyId == null ? "" : chassis.assemblyId.value();
            TeamComponent team = world.getComponent(entityId, TeamComponent.class);
            record.teamId = team == null ? 0 : team.teamId;
            TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
            if (transform != null) {
                record.x = transform.position.x;
                record.y = transform.position.y;
                record.z = transform.position.z;
                record.packedRotation = Quantisation.packRotation(transform.rotation);
            }
            vehiclesByBaseId.put(baseNetworkId, record);

            if (ownerPeerId != NetConstants.NO_PEER_ID) {
                PeerSession session = peers.get(ownerPeerId);
                if (session != null) {
                    session.vehicleEntity = entityId;
                    session.vehicleNetworkId = baseNetworkId;
                }
            }
        }
    }

    private int ownerPeerOf(World world, int vehicleEntity) {
        OwnerComponent owner = world.getComponent(vehicleEntity, OwnerComponent.class);
        if (owner == null || owner.ownerEntity == EntityId.NULL) {
            return NetConstants.NO_PEER_ID;
        }
        Integer peerId = peerByPlayerEntity.get(owner.ownerEntity);
        return peerId == null ? NetConstants.NO_PEER_ID : peerId;
    }

    /**
     * Drops vehicles the simulation has destroyed, telling every peer that knew about them.
     *
     * <p>Driven by liveness rather than by an event because it must be true after any path that
     * removes a vehicle — wrecked, despawned at the end of a match, or destroyed with its owner —
     * and a subscriber per path is a subscriber that will eventually be forgotten.
     */
    private void retireDeadVehicles(World world, long tick) {
        List<Integer> dead = new ArrayList<>();
        for (Map.Entry<Integer, VehicleRecord> entry : vehiclesByBaseId.entrySet()) {
            if (!world.isAlive(entry.getValue().entityId)) {
                dead.add(entry.getKey());
            }
        }
        for (int baseNetworkId : dead) {
            VehicleRecord record = vehiclesByBaseId.remove(baseNetworkId);
            for (int i = 0; i < record.blockSize; i++) {
                registry.unbind(baseNetworkId + i);
            }
            for (PeerSession peer : peers.values()) {
                if (peer.knownNetworkIds.remove(baseNetworkId)) {
                    sendDespawn(peer, baseNetworkId, DespawnReason.DESTROYED, tick);
                }
                if (peer.vehicleNetworkId == baseNetworkId) {
                    peer.vehicleEntity = EntityId.NULL;
                    peer.vehicleNetworkId = NetworkId.NONE;
                }
            }
        }
    }

    private void flushStructuralEvents() {
        if (pendingStructural.isEmpty()) {
            return;
        }
        for (Messages.StructuralEvent event : pendingStructural) {
            for (PeerSession peer : peers.values()) {
                if (!peer.handshakeComplete) {
                    continue;
                }
                // Sent to every peer that knows the vehicle, and to one that does not only if it is
                // about to: a structural event for an unknown id is harmless (the client drops it)
                // while a missing one is permanent divergence (D10-R2).
                writer.reset();
                Messages.writeStructuralEvent(writer, event);
                transport.send(peer.peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
            }
        }
        pendingStructural.clear();
    }

    private void sendSnapshots(World world, long tick) {
        for (PeerSession peer : peers.values()) {
            if (!peer.handshakeComplete) {
                continue;
            }
            if (Math.floorMod(tick, TICKS_PER_SNAPSHOT) != peer.staggerOffset) {
                continue;
            }
            syncSpawns(world, peer, tick);

            SnapshotFrame current = peer.borrowFrame();
            current.setTick(tick);
            captureFor(world, peer, current);

            SnapshotFrame baseline = peer.baseline();
            writer.reset();
            Messages.writeType(writer, MessageType.SNAPSHOT);
            codec.encode(writer, current, baseline, peer.ack);
            transport.send(peer.peerId, Channel.STATE, writer.buffer(), 0, writer.byteLength());
            peer.storeSent(current);
        }
    }

    /** Tells a peer about vehicles that have become relevant, and forgets ones that have not. */
    private void syncSpawns(World world, PeerSession peer, long tick) {
        for (VehicleRecord record : vehiclesByBaseId.values()) {
            boolean relevant = Relevance.isRelevantTo(world, peer.vehicleEntity, record.entityId);
            boolean known = peer.knownNetworkIds.contains(record.baseNetworkId);
            if (relevant && !known) {
                peer.knownNetworkIds.add(record.baseNetworkId);
                writer.reset();
                Messages.writeSpawnEntity(
                        writer,
                        new Messages.SpawnEntity(
                                record.baseNetworkId,
                                record.blockSize,
                                record.assemblyId,
                                record.ownerPeerId,
                                record.teamId,
                                record.x,
                                record.y,
                                record.z,
                                record.packedRotation,
                                record.spawnTick));
                transport.send(peer.peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
            } else if (!relevant && known) {
                peer.knownNetworkIds.remove(record.baseNetworkId);
                sendDespawn(peer, record.baseNetworkId, DespawnReason.NO_LONGER_RELEVANT, tick);
            }
        }
    }

    private void sendDespawn(PeerSession peer, int networkId, DespawnReason reason, long tick) {
        writer.reset();
        Messages.writeDespawnEntity(writer, new Messages.DespawnEntity(networkId, reason, tick));
        transport.send(peer.peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
    }

    /** Captures every entity this peer is allowed to see, in ascending network id order (G3). */
    private void captureFor(World world, PeerSession peer, SnapshotFrame frame) {
        for (Map.Entry<Integer, Integer> binding : registry.bindings()) {
            int networkId = binding.getKey();
            int entityId = binding.getValue();
            if (!world.isAlive(entityId)) {
                continue;
            }
            if (!Relevance.isRelevantTo(world, peer.vehicleEntity, entityId)) {
                continue;
            }
            NetworkReplicatedComponent replicated = world.getComponent(entityId, NetworkReplicatedComponent.class);
            int ownerPeerId = replicated == null ? NetConstants.NO_PEER_ID : replicated.ownerPeerId;
            codec.capture(world, entityId, networkId, ownerPeerId, peer.peerId, frame.getOrCreate(networkId));
            if (replicated != null) {
                replicated.lastSentTick = frame.tick();
            }
        }
    }

    // ---- Transport callbacks ----------------------------------------------------------

    @Override
    public void onPeerConnected(int peerId) {
        // Nothing yet: a peer exists for this class only once its ClientHello has been accepted.
        // A socket that opens and says nothing must not be able to allocate a session (D10-S5.8).
        LOG.debug("peer {} connected; awaiting ClientHello", peerId);
    }

    @Override
    public void onPeerDisconnected(int peerId, DisconnectReason reason) {
        PeerSession peer = peers.remove(peerId);
        if (peer == null) {
            return;
        }
        LOG.info("peer {} disconnected: {}", peerId, reason);
        // The vehicle is deliberately left running. D10-S5.8 gives a disconnected peer
        // DISCONNECT_GRACE_TICKS before its vehicle is destroyed, so a three-second blip does not
        // cost a player their car (AC-D10-16). Whoever owns the match decides when the grace is up.
    }

    @Override
    public void onMessage(int peerId, Channel channel, byte[] payload, int offset, int length) {
        try {
            reader.reset(payload, offset, length);
            MessageType type = Messages.readType(reader);
            if (type == null) {
                LOG.debug("peer {} sent an unknown message type; dropping the packet", peerId);
                return;
            }
            PeerSession peer = peers.get(peerId);
            if (peer == null) {
                if (type == MessageType.CLIENT_HELLO) {
                    handleClientHello(peerId);
                }
                return;
            }
            peer.lastPacketTick = pollTick;
            switch (type) {
                case INPUT_COMMAND -> handleInput(peer);
                case SNAPSHOT_NACK -> handleNack(peer);
                case DISCONNECT -> handleDisconnect(peer);
                case CLIENT_HELLO -> LOG.debug("peer {} sent a second ClientHello; ignoring", peerId);
                default -> LOG.debug("peer {} sent {}, which the authority does not consume yet", peerId, type);
            }
        } catch (BitReader.MalformedPacketException e) {
            // Nothing a client sends is trusted (D10-R26), and that includes its framing. One
            // dropped packet, one counted incident, no exception escaping into the schedule.
            malformedPackets++;
            LOG.debug("dropping malformed packet from peer {}: {}", peerId, e.getMessage());
        }
    }

    private void handleClientHello(int peerId) {
        Messages.ClientHello hello = Messages.readClientHello(reader);
        if (hello.protocolVersion() != NetConstants.PROTOCOL_VERSION) {
            reject(
                    peerId,
                    RejectReason.PROTOCOL_MISMATCH,
                    "server " + NetConstants.PROTOCOL_VERSION + " client " + hello.protocolVersion());
            return;
        }
        if (hello.contentHash() != contentHash) {
            reject(
                    peerId,
                    RejectReason.CONTENT_MISMATCH,
                    "server " + Long.toHexString(contentHash) + " client " + Long.toHexString(hello.contentHash()));
            return;
        }
        if (peers.size() >= maxPlayers) {
            reject(peerId, RejectReason.SERVER_FULL, peers.size() + "/" + maxPlayers);
            return;
        }

        PeerSession peer = new PeerSession(peerId);
        peer.handshakeComplete = true;
        peer.lastPacketTick = pollTick;
        peer.inputRateWindowStartTick = pollTick;
        // Staggered so twelve clients do not all receive a snapshot on the same tick (D10-S5.2).
        peer.staggerOffset = peers.size() % TICKS_PER_SNAPSHOT;
        peers.put(peerId, peer);

        writer.reset();
        Messages.writeServerHello(
                writer,
                new Messages.ServerHello(peerId, pollTick, NetConstants.PROTOCOL_VERSION, contentHash, matchSeed));
        transport.send(peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());

        writer.reset();
        Messages.writeMatchConfig(writer, new Messages.MatchConfig(arenaId, matchSeed, 0, 0));
        transport.send(peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
        LOG.info("peer {} accepted as '{}' ({})", peerId, hello.playerName(), hello.clientVersion());
    }

    private void reject(int peerId, RejectReason reason, String detail) {
        writer.reset();
        Messages.writeReject(writer, new Messages.Reject(reason, detail));
        transport.send(peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
        transport.disconnect(peerId, DisconnectReason.KICKED);
        LOG.warn("rejected peer {}: {} ({})", peerId, reason, detail);
    }

    private void handleInput(PeerSession peer) {
        if (!InputValidator.acceptPacket(peer, pollTick)) {
            return;
        }
        long acknowledged = Messages.readInputAcknowledgedTick(reader);
        if (acknowledged != NetConstants.FULL_SNAPSHOT_BASELINE) {
            peer.acknowledge(acknowledged);
        }
        int count = Messages.readInputCount(reader);
        for (int i = 0; i < count; i++) {
            Messages.readOneCommand(reader, scratchCommand);
            if (InputValidator.validate(scratchCommand, peer, pollTick)) {
                peer.inputBuffer.accept(scratchCommand);
            }
        }
    }

    private void handleNack(PeerSession peer) {
        Messages.SnapshotNack nack = Messages.readSnapshotNack(reader);
        peer.nack();
        LOG.debug(
                "peer {} could not decode a delta against tick {}; baseline reset (nack {})",
                peer.peerId,
                nack.missingBaselineTick(),
                peer.consecutiveNacks);
    }

    private void handleDisconnect(PeerSession peer) {
        Messages.Disconnect disconnect = Messages.readDisconnect(reader);
        LOG.info("peer {} is leaving: {} ({})", peer.peerId, disconnect.reason(), disconnect.detail());
        peers.remove(peer.peerId);
        transport.disconnect(peer.peerId, disconnect.reason());
    }

    /** Tells every peer the match is over and releases the transport. */
    public void shutdown(DisconnectReason reason) {
        for (PeerSession peer : peers.values()) {
            writer.reset();
            Messages.writeDisconnect(writer, new Messages.Disconnect(reason, "authority shutting down"));
            transport.send(peer.peerId, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
            transport.disconnect(peer.peerId, reason);
        }
        peers.clear();
        transport.dispose();
    }

    /** Scratch the spawn path needs; kept here so a spawn does not allocate a vector per vehicle. */
    Vector3 scratchPosition() {
        return scratchPosition;
    }

    /** Scratch, as above. */
    Quaternion scratchRotation() {
        return scratchRotation;
    }
}

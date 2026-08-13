/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.NetConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The whole replication loop, authority to client and back, over a loopback pair
 * (docs/10_networking_multiplayer.md#D10-S5.4, #S5.5, #S5.8).
 *
 * <p>The vehicles here are built by hand rather than by {@code VehicleFactory}: what is under test
 * is the protocol, and a synthetic vehicle exercises every path of it — transform, velocity, part
 * health, damage state, spawn, despawn — without a Bullet world on each side. The one property the
 * real factory contributes is that both peers walk their parts in the same order, and that is
 * reproduced exactly here (DEC-059).
 *
 * <p>Covers T-D10-2, T-D10-3, T-D10-4, T-D10-5, T-D10-9, T-D10-10, T-D10-16, T-D10-17, T-D10-20 and
 * T-D10-21.
 */
@Tag("unit")
class LoopbackReplicationTest {

    private static final int CLIENT_PEER_ID = 1;
    private static final long CONTENT_HASH = 0xC0FFEEL;
    private static final long MATCH_SEED = 1337L;
    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");

    private LoopbackTransport.Pair pair;
    private NetworkAuthority authority;
    private NetworkClient client;
    private World authorityWorld;
    private World clientWorld;
    private long tick;

    /** Vehicles spawned on the authority, standing in for slot 18's family. */
    private final java.util.List<Integer> spawned = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        pair = LoopbackTransport.createPair(CLIENT_PEER_ID);
        authorityWorld = new World(MATCH_SEED, true);
        clientWorld = new World(MATCH_SEED, false);
        authority = new NetworkAuthority(pair.authoritySide(), CONTENT_HASH, MATCH_SEED, "arena_scrapyard", 12);
        client = new NetworkClient(
                pair.clientSide(),
                CONTENT_HASH,
                "tester",
                "0.1.0",
                (world, assemblyId, transform, teamId) -> spawnSyntheticVehicle(world, assemblyId, transform));
        tick = 0L;
    }

    // ---- Handshake ------------------------------------------------------------------

    @Test
    void handshake_acceptsAMatchingClientAndNamesItsPeerId() {
        connect();
        assertThat(client.connectionState()).isEqualTo(NetworkClient.ConnectionState.SYNCING);
        assertThat(client.peerId()).isEqualTo(CLIENT_PEER_ID);
        assertThat(client.matchSeed()).isEqualTo(MATCH_SEED);
        assertThat(client.arenaId()).isEqualTo("arena_scrapyard");
        assertThat(authority.peers()).hasSize(1);
    }

    @Test
    void handshake_refusesAContentMismatchAndReportsBothHashes() {
        // T-D10-17 / AC-D10-15. A refusal that does not say which side is out of date leaves both
        // operators guessing, so the detail carries both values.
        NetworkClient mismatched = new NetworkClient(
                pair.clientSide(), CONTENT_HASH + 1, "tester", "0.1.0", (w, a, t, team) -> EntityId.NULL);
        mismatched.connect();
        authority.receive(authorityWorld, tick);

        byte[] rejected = pair.clientSide().peek(Channel.CONTROL);
        assertThat(rejected).isNotNull();
        BitReader reader = new BitReader(rejected);
        assertThat(Messages.readType(reader)).isEqualTo(dev.syndicate.model.net.MessageType.REJECT);
        Messages.Reject reject = Messages.readReject(reader);
        assertThat(reject.reason()).isEqualTo(dev.syndicate.model.net.RejectReason.CONTENT_MISMATCH);
        assertThat(reject.detail()).contains(Long.toHexString(CONTENT_HASH));
        assertThat(reject.detail()).contains(Long.toHexString(CONTENT_HASH + 1));
        assertThat(authority.peers()).isEmpty();
    }

    @Test
    void handshake_refusesAProtocolMismatch() {
        // T-D10-16. Forged by hand: no client this build could construct would send another version.
        BitWriter writer = new BitWriter();
        Messages.writeClientHello(
                writer, new Messages.ClientHello(NetConstants.PROTOCOL_VERSION + 1, CONTENT_HASH, "0.1.0", "tester"));
        pair.clientSide().send(NetConstants.SERVER_PEER_ID, Channel.CONTROL, writer.buffer(), 0, writer.byteLength());
        authority.receive(authorityWorld, tick);

        BitReader reader = new BitReader(pair.clientSide().peek(Channel.CONTROL));
        Messages.readType(reader);
        Messages.Reject reject = Messages.readReject(reader);
        assertThat(reject.reason()).isEqualTo(dev.syndicate.model.net.RejectReason.PROTOCOL_MISMATCH);
        assertThat(reject.detail()).contains(String.valueOf(NetConstants.PROTOCOL_VERSION));
        assertThat(authority.peers()).isEmpty();
    }

    // ---- Spawn and steady state ------------------------------------------------------

    @Test
    void aSpawnedVehicleAppearsOnTheClientWithItsPartsIdenticallyNumbered() {
        connect();
        int vehicle = spawnOnAuthority(3f, 0.5f, -7f);
        advance(4);

        int baseNetworkId = authority.registry().networkIdOf(vehicle);
        assertThat(NetworkId.isValid(baseNetworkId)).isTrue();

        int clientVehicle = client.registry().entityOf(baseNetworkId);
        assertThat(clientVehicle).isNotEqualTo(EntityId.NULL);
        assertThat(client.replicatedEntityCount())
                .isEqualTo(authority.registry().size());

        TransformComponent transform = clientWorld.getComponent(clientVehicle, TransformComponent.class);
        assertThat(transform.position.x).isCloseTo(3f, within(0.02f));
        assertThat(transform.position.y).isCloseTo(0.5f, within(0.02f));
        assertThat(transform.position.z).isCloseTo(-7f, within(0.02f));

        // The part's ids come from the same walk on both sides, so the client's part carries the
        // authority's health without a single part id travelling on the wire.
        int authorityPart = partOf(authorityWorld, vehicle);
        int clientPart = client.registry().entityOf(authority.registry().networkIdOf(authorityPart));
        assertThat(clientPart).isNotEqualTo(EntityId.NULL);
        assertThat(clientWorld.getComponent(clientPart, PartRefComponent.class).slotPath)
                .isEqualTo(authorityWorld.getComponent(authorityPart, PartRefComponent.class).slotPath);
    }

    @Test
    void movementAndDamageReachTheClient() {
        connect();
        int vehicle = spawnOnAuthority(0f, 0f, 0f);
        advance(4);

        moveTo(vehicle, 20f, 1f, 5f);
        velocityOf(vehicle).linear.set(12f, 0f, -3f);
        HealthComponent health = authorityWorld.getComponent(partOf(authorityWorld, vehicle), HealthComponent.class);
        health.healthFraction = 0.5f;
        authorityWorld.getComponent(partOf(authorityWorld, vehicle), DamageStateComponent.class).state =
                DamageState.CRITICAL;
        advance(4);

        int clientVehicle = client.registry().entityOf(authority.registry().networkIdOf(vehicle));
        assertThat(clientWorld.getComponent(clientVehicle, TransformComponent.class).position.x)
                .isCloseTo(20f, within(0.02f));
        assertThat(clientWorld.getComponent(clientVehicle, VelocityComponent.class).linear.x)
                .isCloseTo(12f, within(0.05f));

        int clientPart = client.registry().entityOf(authority.registry().networkIdOf(partOf(authorityWorld, vehicle)));
        assertThat(clientWorld.getComponent(clientPart, HealthComponent.class).healthFraction)
                .isCloseTo(0.5f, within(0.01f));
        assertThat(clientWorld.getComponent(clientPart, DamageStateComponent.class).state)
                .isEqualTo(DamageState.CRITICAL);
    }

    @Test
    void aStationaryWorldProducesEmptyDeltasOnceTheBaselineIsAgreed() {
        connect();
        spawnOnAuthority(0f, 0f, 0f);
        advance(12);

        int bytesWhileStill = 0;
        for (int i = 0; i < 6; i++) {
            authorityTick();
            byte[] packet = pair.clientSide().peek(Channel.STATE);
            if (packet != null) {
                bytesWhileStill = Math.max(bytesWhileStill, packet.length);
            }
            clientTick();
        }
        // Header only: one type byte, the server and baseline ticks, the input ack's sequence and
        // tick, and a zero entity count — 19 bytes. Anything larger means an unmoving car is being
        // re-sent, which is what comparing on the quantisation lattice exists to prevent.
        assertThat(bytesWhileStill).isLessThanOrEqualTo(19);
        assertThat(bytesWhileStill).isGreaterThan(0);
    }

    @Test
    void despawnRemovesTheVehicleFromTheClient() {
        connect();
        int vehicle = spawnOnAuthority(0f, 0f, 0f);
        advance(4);
        int baseNetworkId = authority.registry().networkIdOf(vehicle);
        assertThat(client.registry().entityOf(baseNetworkId)).isNotEqualTo(EntityId.NULL);

        authorityWorld.destroyEntity(vehicle);
        advance(4);

        assertThat(client.registry().entityOf(baseNetworkId)).isEqualTo(EntityId.NULL);
        assertThat(authority.registry().networkIdOf(vehicle)).isEqualTo(NetworkId.NONE);
    }

    // ---- Loss and recovery -------------------------------------------------------------

    @Test
    void outOfOrderSnapshotsAreDiscardedByTick() {
        // T-D10-3 / AC-D10-6. STATE is unordered, so this is a normal event rather than an error.
        connect();
        int vehicle = spawnOnAuthority(0f, 0f, 0f);
        advance(4);

        moveTo(vehicle, 50f, 0f, 0f);
        byte[] older = captureNextStatePacket(vehicle, 50f);
        advance(6);

        int clientVehicle = client.registry().entityOf(authority.registry().networkIdOf(vehicle));
        moveTo(vehicle, 60f, 0f, 0f);
        advance(6);
        float xAfterNewer = clientWorld.getComponent(clientVehicle, TransformComponent.class).position.x;
        assertThat(xAfterNewer).isCloseTo(60f, within(0.02f));
        int discardedBefore = client.discardedStaleSnapshots();

        // Redeliver the older packet by hand, as an unordered channel eventually will.
        pair.authoritySide().send(CLIENT_PEER_ID, Channel.STATE, older, 0, older.length);
        clientTick();

        assertThat(client.discardedStaleSnapshots()).isEqualTo(discardedBefore + 1);
        assertThat(clientWorld.getComponent(clientVehicle, TransformComponent.class).position.x)
                .isEqualTo(xAfterNewer);
    }

    @Test
    void aDeltaWhoseBaselineIsMissingIsNackedAndTheAuthorityRecovers() {
        // T-D10-4 / AC-D10-7. Applying it to a wrong base would corrupt state with nothing to notice.
        connect();
        int vehicle = spawnOnAuthority(0f, 0f, 0f);
        advance(8);

        // Lose one snapshot entirely: the client never sees it, so it cannot serve as a baseline —
        // but the authority has been acknowledged for it by an input packet that crossed with it.
        moveTo(vehicle, 10f, 0f, 0f);
        authorityTick();
        pair.clientSide().dropQueuedState();

        moveTo(vehicle, 11f, 0f, 0f);
        advance(2);

        assertThat(client.nackedSnapshots()).isZero();

        // Force the authority to delta against a frame the client has thrown away.
        SnapshotFrame ghost = new SnapshotFrame();
        ghost.setTick(900_000L);
        SnapshotFrame forged = new SnapshotFrame();
        forged.setTick(900_001L);
        BitWriter writer = new BitWriter();
        Messages.writeType(writer, dev.syndicate.model.net.MessageType.SNAPSHOT);
        new SnapshotCodec().encode(writer, forged, ghost, new InputAck());
        pair.authoritySide().send(CLIENT_PEER_ID, Channel.STATE, writer.buffer(), 0, writer.byteLength());
        clientTick();

        assertThat(client.nackedSnapshots()).isEqualTo(1);
        authority.receive(authorityWorld, tick);
        assertThat(authority.peer(CLIENT_PEER_ID).consecutiveNacks).isEqualTo(1);

        // After enough NACKs with no acknowledgement in between, the authority stops trying to
        // delta at all and its next snapshot is a full one — D10-E5's recovery, with no resync
        // protocol behind it.
        PeerSession peer = authority.peer(CLIENT_PEER_ID);
        for (int i = 0; i < NetConstants.MAX_NACKS_BEFORE_FULL; i++) {
            peer.nack();
        }
        assertThat(peer.baseline()).isNull();

        // And one acknowledgement is enough to put it back on deltas.
        SnapshotFrame acknowledged = peer.borrowFrame();
        acknowledged.setTick(4_242L);
        peer.storeSent(acknowledged);
        peer.acknowledge(4_242L);
        assertThat(peer.baseline()).isSameAs(acknowledged);
    }

    // ---- Input ------------------------------------------------------------------------

    @Test
    void sixLostInputPacketsCostNoInputAtAll() {
        // T-D10-20 / AC-D10-20: the redundancy window of D10-R4 is what buys this.
        connect();
        int vehicle = spawnOwnedVehicle();
        advance(4);

        // Warm-up: the jitter buffer runs the client three ticks in the past, so its first few
        // selections have nothing to reach for. Those misses are the buffer filling, not loss.
        for (int i = 0; i < 10; i++) {
            client.sendInput(clientWorld, tick, 1f, 0.25f, 0f, 0f, 0f, 1);
            authority.receive(authorityWorld, tick);
            authority.applyInputs(authorityWorld, tick);
            tick++;
        }
        int missedBeforeLoss = authority.peer(CLIENT_PEER_ID).missedInputTicks;

        int applied = 0;
        for (int i = 0; i < 20; i++) {
            client.sendInput(clientWorld, tick, 1f, 0.25f, 0f, 0f, 0f, 1);
            if (i < NetConstants.INPUT_REDUNDANCY) {
                // Six consecutive input packets lost — exactly what the redundancy window covers.
                pair.authoritySide().dropQueuedState();
            }
            authority.receive(authorityWorld, tick);
            authority.applyInputs(authorityWorld, tick);
            if (authorityWorld.getComponent(vehicle, PlayerInputComponent.class).throttle > 0.9f) {
                applied++;
            }
            tick++;
        }
        assertThat(authority.peer(CLIENT_PEER_ID).missedInputTicks).isEqualTo(missedBeforeLoss);
        assertThat(applied).isEqualTo(20);
    }

    @Test
    void tenLostInputPacketsRepeatMovementAndZeroTheFireMask() {
        // T-D10-21 / D10-R15. Repeating movement keeps the car plausible; repeating fire would let a
        // lagging client shoot without asking.
        connect();
        int vehicle = spawnOwnedVehicle();
        advance(4);

        for (int i = 0; i < 8; i++) {
            client.sendInput(clientWorld, tick, 1f, 0.5f, 0f, 0f, 0f, 0b11);
            authority.receive(authorityWorld, tick);
            authority.applyInputs(authorityWorld, tick);
            tick++;
        }
        PlayerInputComponent input = authorityWorld.getComponent(vehicle, PlayerInputComponent.class);
        assertThat(input.fireMask).isEqualTo(0b11);

        for (int i = 0; i < 12; i++) {
            client.sendInput(clientWorld, tick, 1f, 0.5f, 0f, 0f, 0f, 0b11);
            pair.authoritySide().dropQueuedState();
            authority.receive(authorityWorld, tick);
            authority.applyInputs(authorityWorld, tick);
            tick++;
        }
        assertThat(input.throttle).isCloseTo(1f, within(0.01f));
        assertThat(input.steer).isCloseTo(0.5f, within(0.01f));
        assertThat(input.fireMask).isZero();
        assertThat(authority.peer(CLIENT_PEER_ID).missedInputTicks).isGreaterThan(0);
    }

    @Test
    void anOutOfRangeAxisIsClampedAndCountedRatherThanRejected() {
        // T-D10-9. A legitimate client never needs a clamp, so it raises suspicion — but dropping the
        // command would make a mildly buggy client undrivable (D10-R27).
        connect();
        PeerSession peer = authority.peer(CLIENT_PEER_ID);
        InputCommand command = new InputCommand();
        command.throttle = 50f;
        command.steer = -9f;
        command.brake = 4f;
        command.commandTick = 0L;

        assertThat(InputValidator.validate(command, peer, 0L)).isTrue();
        assertThat(command.throttle).isEqualTo(1f);
        assertThat(command.steer).isEqualTo(-1f);
        assertThat(command.brake).isEqualTo(1f);
        assertThat(peer.suspicion).isEqualTo(NetConstants.SUSPICION_CLAMPED);
    }

    @Test
    void aCommandFromTheFutureIsDroppedAndRaisesSuspicion() {
        // T-D10-10 / D10-E1: claiming the future is how a speed hack asks to be simulated ahead.
        connect();
        PeerSession peer = authority.peer(CLIENT_PEER_ID);
        InputCommand command = new InputCommand();
        command.commandTick = 600L;

        assertThat(InputValidator.validate(command, peer, 0L)).isFalse();
        assertThat(peer.suspicion).isEqualTo(NetConstants.SUSPICION_FUTURE_TICK);
    }

    // ---- Helpers ----------------------------------------------------------------------

    private void connect() {
        client.connect();
        authority.receive(authorityWorld, tick);
        client.receive(clientWorld, tick);
    }

    /** One authority tick: read input, apply it, replicate. */
    private void authorityTick() {
        authority.receive(authorityWorld, tick);
        authority.applyInputs(authorityWorld, tick);
        int[] vehicles = authorityVehicles();
        authority.replicate(authorityWorld, tick, vehicles, vehicles.length);
    }

    /** One client tick: send this tick's input, then apply whatever arrived. */
    private void clientTick() {
        client.sendInput(clientWorld, tick, 0f, 0f, 0f, 0f, 0f, 0);
        client.receive(clientWorld, tick);
    }

    private void advance(int ticks) {
        for (int i = 0; i < ticks; i++) {
            authorityTick();
            clientTick();
            tick++;
        }
    }

    /** Ticks the authority until it actually emits a snapshot, and returns that packet. */
    private byte[] captureNextStatePacket(int vehicle, float x) {
        moveTo(vehicle, x, 0f, 0f);
        for (int i = 0; i < NetworkAuthority.TICKS_PER_SNAPSHOT + 1; i++) {
            authorityTick();
            byte[] packet = pair.clientSide().peek(Channel.STATE);
            clientTick();
            tick++;
            if (packet != null) {
                return packet;
            }
        }
        throw new AssertionError("the authority sent no snapshot within one snapshot interval");
    }

    /** What slot 18's family would hand the authority: every live vehicle, ascending (G3). */
    private int[] authorityVehicles() {
        return spawned.stream()
                .filter(authorityWorld::isAlive)
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private int spawnOwnedVehicle() {
        int player = authorityWorld.createEntity().id();
        authority.bindPlayer(CLIENT_PEER_ID, player);
        int vehicle = spawnOnAuthority(0f, 0f, 0f);
        OwnerComponent owner = new OwnerComponent();
        owner.ownerEntity = player;
        authorityWorld.addComponent(vehicle, owner);
        return vehicle;
    }

    private int spawnOnAuthority(float x, float y, float z) {
        int vehicle = spawnSyntheticVehicle(authorityWorld, ASSEMBLY, new Matrix4().setToTranslation(x, y, z));
        spawned.add(vehicle);
        return vehicle;
    }

    private void moveTo(int vehicle, float x, float y, float z) {
        authorityWorld.getComponent(vehicle, TransformComponent.class).position.set(x, y, z);
    }

    private VelocityComponent velocityOf(int vehicle) {
        return authorityWorld.getComponent(vehicle, VelocityComponent.class);
    }

    private int partOf(World world, int vehicle) {
        return world.getComponent(vehicle, VehicleChassisComponent.class).chassisPartEntity;
    }

    /**
     * A vehicle with one chassis part and one armour part, built identically on both peers.
     *
     * <p>Identically is the whole point: the network id block covers the vehicle, then the chassis
     * part, then the slot nodes in ascending slot-path order, and a client that builds a different
     * shape would bind the authority's health to the wrong part.
     */
    private static int spawnSyntheticVehicle(World world, AssetId assemblyId, Matrix4 transform) {
        Entity vehicle = world.createEntity();
        int vehicleEntity = vehicle.id();

        TransformComponent vehicleTransform = new TransformComponent();
        transform.getTranslation(vehicleTransform.position);
        transform.getRotation(vehicleTransform.rotation);
        world.addComponent(vehicleEntity, vehicleTransform);
        world.addComponent(vehicleEntity, new VelocityComponent());
        world.addComponent(vehicleEntity, new PlayerInputComponent());

        int chassisPart = addPart(world, vehicleEntity, "root");
        int armourPart = addPart(world, vehicleEntity, "root/armor_front");

        VehicleChassisComponent chassis = new VehicleChassisComponent();
        chassis.assemblyId = assemblyId;
        chassis.chassisPartEntity = chassisPart;
        world.addComponent(vehicleEntity, chassis);

        SlotGraphComponent graph = new SlotGraphComponent();
        SlotNode chassisNode = new SlotNode();
        chassisNode.parentEntity = vehicleEntity;
        chassisNode.childEntity = chassisPart;
        chassisNode.slotPath = "root";
        graph.nodes.add(chassisNode);
        SlotNode armourNode = new SlotNode();
        armourNode.parentEntity = chassisPart;
        armourNode.childEntity = armourPart;
        armourNode.slotPath = "root/armor_front";
        graph.nodes.add(armourNode);
        world.addComponent(vehicleEntity, graph);

        return vehicleEntity;
    }

    private static int addPart(World world, int vehicleEntity, String slotPath) {
        int part = world.createEntity().id();
        world.addComponent(part, new TransformComponent());

        PartRefComponent ref = new PartRefComponent();
        ref.vehicleEntity = vehicleEntity;
        ref.slotPath = slotPath;
        ref.partTypeId = AssetId.of("part_chassis_medium_01");
        world.addComponent(part, ref);

        HealthComponent health = new HealthComponent();
        health.maxHp = 1000f;
        health.currentHp = 1000f;
        health.healthFraction = 1f;
        world.addComponent(part, health);
        world.addComponent(part, new DamageStateComponent());
        return part;
    }
}

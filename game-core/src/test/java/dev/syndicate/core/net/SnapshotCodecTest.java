/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.ProjectileComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.net.NetConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Delta compression, idempotence and relevance
 * (docs/10_networking_multiplayer.md#D10-S4.4, #D10-S5.4, #D10-S5.10).
 *
 * <p>Covers T-D10-2 and AC-D10-5, AC-D10-18.
 */
@Tag("unit")
class SnapshotCodecTest {

    private World world;
    private SnapshotCodec codec;

    @BeforeEach
    void setUp() {
        world = new World(1337L, true);
        codec = new SnapshotCodec();
    }

    @Test
    void applyingTheSameSnapshotTwiceLeavesIdenticalState() {
        // T-D10-2 / AC-D10-5 / G16. Every value is absolute, never an increment, which is what lets
        // a receiver ignore a duplicate instead of tracking one.
        int source = vehicleAt(12.34f, 1.5f, -6.78f);
        world.getComponent(source, VelocityComponent.class).linear.set(3f, 0f, -9f);
        EntityState state = new EntityState();
        codec.capture(world, source, 7, NetConstants.NO_PEER_ID, NetConstants.NO_PEER_ID, state);

        int target = vehicleAt(0f, 0f, 0f);
        codec.apply(world, target, state);
        TransformComponent afterOnce = world.getComponent(target, TransformComponent.class);
        float x = afterOnce.position.x;
        float y = afterOnce.position.y;
        float z = afterOnce.position.z;
        float rotationX = afterOnce.rotation.x;
        float velocityX = world.getComponent(target, VelocityComponent.class).linear.x;

        codec.apply(world, target, state);

        assertThat(afterOnce.position.x).isEqualTo(x);
        assertThat(afterOnce.position.y).isEqualTo(y);
        assertThat(afterOnce.position.z).isEqualTo(z);
        assertThat(afterOnce.rotation.x).isEqualTo(rotationX);
        assertThat(world.getComponent(target, VelocityComponent.class).linear.x).isEqualTo(velocityX);
        assertThat(x).isCloseTo(12.34f, within(0.02f));
    }

    @Test
    void aDeltaCarriesOnlyTheComponentsThatChanged() {
        int vehicle = vehicleAt(5f, 0f, 5f);
        SnapshotFrame baseline = new SnapshotFrame();
        baseline.setTick(10L);
        codec.capture(world, vehicle, 1, NetConstants.NO_PEER_ID, NetConstants.NO_PEER_ID, baseline.getOrCreate(1));

        SnapshotFrame current = new SnapshotFrame();
        current.setTick(13L);
        codec.capture(world, vehicle, 1, NetConstants.NO_PEER_ID, NetConstants.NO_PEER_ID, current.getOrCreate(1));

        BitWriter writer = new BitWriter();
        assertThat(codec.encode(writer, current, baseline, new InputAck()))
                .as("nothing moved, so no entity belongs in the snapshot")
                .isZero();

        // Move it one quantisation step and the transform — and only the transform — comes back.
        world.getComponent(vehicle, TransformComponent.class).position.x = 5.5f;
        current.clear();
        current.setTick(16L);
        codec.capture(world, vehicle, 1, NetConstants.NO_PEER_ID, NetConstants.NO_PEER_ID, current.getOrCreate(1));
        writer.reset();
        assertThat(codec.encode(writer, current, baseline, new InputAck())).isEqualTo(1);

        BitReader reader = new BitReader(writer.toByteArray());
        SnapshotFrame decoded = new SnapshotFrame();
        decoded.copyFrom(baseline);
        SnapshotCodec.Header header = codec.readHeader(reader, new InputAck());
        assertThat(header.serverTick()).isEqualTo(16L);
        assertThat(header.baselineTick()).isEqualTo(10L);
        codec.decodeEntities(reader, decoded, header.serverTick());
        assertThat(decoded.get(1).values[ReplicatedField.POSITION.slot()])
                .isEqualTo(Quantisation.encodePositionAxis(5.5f));
    }

    @Test
    void weaponFieldsGoOnlyToTheOwningPeer() {
        // D10-S4.3 sends cooldown, ammunition and heat to the owner alone — the same reasoning as
        // relevance filtering (D10-R29), applied per field.
        int vehicle = vehicleAt(0f, 0f, 0f);
        WeaponControllerComponent weapon = new WeaponControllerComponent();
        weapon.cooldownRemainingS = 0.4f;
        weapon.ammoRemaining = 30;
        weapon.heat = 0.25f;
        world.addComponent(vehicle, weapon);

        EntityState toOwner = new EntityState();
        codec.capture(world, vehicle, 1, 4, 4, toOwner);
        assertThat(toOwner.has(ReplicatedComponent.WEAPON)).isTrue();

        EntityState toStranger = new EntityState();
        codec.capture(world, vehicle, 1, 4, 9, toStranger);
        assertThat(toStranger.has(ReplicatedComponent.WEAPON)).isFalse();
    }

    @Test
    void debrisIsNeverRelevantToAnybody() {
        // AC-D10-18: zero debris entities in any wire capture. Debris affects nothing (D06-R10/R11),
        // and each peer spawns its own from the replicated fracture event (DEC-005).
        int viewer = vehicleAt(0f, 0f, 0f);
        int debris = world.createEntity().id();
        world.addComponent(debris, new TransformComponent());
        world.addComponent(debris, new DebrisTagComponent());

        assertThat(Relevance.isRelevantTo(world, viewer, debris)).isFalse();
    }

    @Test
    void aDistantVehicleIsCulledAndItsPartsGoWithIt() {
        int viewer = vehicleAt(0f, 0f, 0f);
        int near = vehicleAt(100f, 0f, 0f);
        int far = vehicleAt(NetConstants.VEHICLE_RELEVANCE_M + 50f, 0f, 0f);
        int farPart = partOf(far);

        assertThat(Relevance.isRelevantTo(world, viewer, near)).isTrue();
        assertThat(Relevance.isRelevantTo(world, viewer, far)).isFalse();
        // A part is as relevant as its vehicle (DEC-060): health for a car the peer cannot see is
        // bandwidth, and a visible car whose parts were culled would never appear to take damage.
        assertThat(Relevance.isRelevantTo(world, viewer, farPart)).isFalse();
        assertThat(Relevance.isRelevantTo(world, viewer, partOf(near))).isTrue();
    }

    @Test
    void aProjectileIsCulledAtItsOwnShorterRange() {
        int viewer = vehicleAt(0f, 0f, 0f);
        int projectile = world.createEntity().id();
        TransformComponent transform = new TransformComponent();
        transform.position.set(NetConstants.PROJECTILE_RELEVANCE_M + 10f, 0f, 0f);
        world.addComponent(projectile, transform);
        world.addComponent(projectile, new ProjectileComponent());

        assertThat(Relevance.isRelevantTo(world, viewer, projectile)).isFalse();
        transform.position.set(50f, 0f, 0f);
        assertThat(Relevance.isRelevantTo(world, viewer, projectile)).isTrue();
    }

    private int vehicleAt(float x, float y, float z) {
        int vehicle = world.createEntity().id();
        TransformComponent transform = new TransformComponent();
        transform.position.set(x, y, z);
        world.addComponent(vehicle, transform);
        world.addComponent(vehicle, new VelocityComponent());

        int part = world.createEntity().id();
        world.addComponent(part, new TransformComponent());
        PartRefComponent ref = new PartRefComponent();
        ref.vehicleEntity = vehicle;
        ref.slotPath = "root";
        world.addComponent(part, ref);
        HealthComponent health = new HealthComponent();
        health.maxHp = 1000f;
        health.currentHp = 1000f;
        world.addComponent(part, health);

        VehicleChassisComponent chassis = new VehicleChassisComponent();
        chassis.chassisPartEntity = part;
        world.addComponent(vehicle, chassis);
        return vehicle;
    }

    private int partOf(int vehicle) {
        return world.getComponent(vehicle, VehicleChassisComponent.class).chassisPartEntity;
    }
}

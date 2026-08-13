/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.net.NetConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads world state into a {@link SnapshotFrame}, encodes a frame as a delta, and applies a
 * received frame back to a world (docs/10_networking_multiplayer.md#D10-S4.4, #D10-S5.4).
 *
 * <p>The three operations are one class because they are one contract: the field order
 * {@link #encode} writes is the order {@link #decode} reads, and a change to either that is not a
 * change to both is a protocol break that compiles. Keeping them adjacent is the cheapest defence
 * available.
 *
 * <p>Three properties this implementation is built around, each of them a blueprint requirement:
 *
 * <ul>
 *   <li><b>Self-describing</b> (D10-R9). Every entity carries a component mask and every component
 *       a field mask, so a receiver applies whatever the sender chose to include without needing to
 *       know what that was.
 *   <li><b>Idempotent</b> (D10-R10, G16). Every value is absolute. Applying a snapshot twice is a
 *       no-op, which is what lets a receiver ignore duplicates instead of tracking them.
 *   <li><b>Exact baselines</b> (D10-R18). A delta is meaningful only against the frame it was built
 *       from; {@link #decode} is handed that frame by the caller, and a caller that does not have it
 *       must NACK rather than substitute another.
 * </ul>
 */
public final class SnapshotCodec {

    /** Bits for the entity count. {@code uint16} per D10-S4.4. */
    private static final int ENTITY_COUNT_BITS = 16;

    /** Bits for a component or field mask. */
    private static final int MASK_BITS = 8;

    /** Bits for a component type id, which is a {@link dev.syndicate.core.component.ComponentCatalogue} index. */
    private static final int COMPONENT_TYPE_BITS = 8;

    /** Seconds of weapon cooldown the 8-bit field covers, at 0.01 s resolution. */
    private static final float COOLDOWN_RANGE_S = 2.55f;

    /** Ammunition count that means "unlimited" on the wire. */
    private static final int AMMO_UNLIMITED = 255;

    private final Vector3 scratchVector = new Vector3();
    private final Matrix4 scratchMatrix = new Matrix4();
    private final List<EntityState> changed = new ArrayList<>();
    private final List<Integer> changedMasks = new ArrayList<>();

    // ---- Capture -------------------------------------------------------------------

    /**
     * Reads one entity's replicated components into {@code out}.
     *
     * @param toPeerId the peer this capture is for, which decides whether owner-only fields are
     *     included; pass {@code NetConstants.NO_PEER_ID} to include everything
     * @param ownerPeerId the entity's owner, from its {@code NetworkReplicatedComponent}
     */
    public void capture(World world, int entityId, int networkId, int ownerPeerId, int toPeerId, EntityState out) {
        out.reset();
        out.networkId = networkId;
        out.tick = world.currentTick();

        TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
        if (transform != null) {
            out.mark(ReplicatedComponent.TRANSFORM);
            int slot = ReplicatedField.POSITION.slot();
            out.values[slot] = Quantisation.encodePositionAxis(transform.position.x);
            out.values[slot + 1] = Quantisation.encodePositionAxis(transform.position.y);
            out.values[slot + 2] = Quantisation.encodePositionAxis(transform.position.z);
            out.values[ReplicatedField.ROTATION.slot()] = Quantisation.packRotation(transform.rotation);
        }

        VelocityComponent velocity = world.getComponent(entityId, VelocityComponent.class);
        if (velocity != null) {
            out.mark(ReplicatedComponent.VELOCITY);
            int linear = ReplicatedField.LINEAR_VELOCITY.slot();
            out.values[linear] = Quantisation.encodeLinearVelocityAxis(velocity.linear.x);
            out.values[linear + 1] = Quantisation.encodeLinearVelocityAxis(velocity.linear.y);
            out.values[linear + 2] = Quantisation.encodeLinearVelocityAxis(velocity.linear.z);
            int angular = ReplicatedField.ANGULAR_VELOCITY.slot();
            out.values[angular] = Quantisation.encodeAngularVelocityAxis(velocity.angular.x);
            out.values[angular + 1] = Quantisation.encodeAngularVelocityAxis(velocity.angular.y);
            out.values[angular + 2] = Quantisation.encodeAngularVelocityAxis(velocity.angular.z);
        }

        HealthComponent health = world.getComponent(entityId, HealthComponent.class);
        if (health != null) {
            out.mark(ReplicatedComponent.HEALTH);
            out.values[ReplicatedField.HEALTH_FRACTION.slot()] =
                    Quantisation.encodeHealthFraction(health.healthFraction);
        }

        DamageStateComponent damage = world.getComponent(entityId, DamageStateComponent.class);
        if (damage != null) {
            out.mark(ReplicatedComponent.DAMAGE_STATE);
            out.values[ReplicatedField.DAMAGE_STATE.slot()] = damage.state.ordinal();
        }

        WeaponControllerComponent weapon = world.getComponent(entityId, WeaponControllerComponent.class);
        boolean sendOwnerOnly = toPeerId == NetConstants.NO_PEER_ID || toPeerId == ownerPeerId;
        if (weapon != null && sendOwnerOnly) {
            out.mark(ReplicatedComponent.WEAPON);
            out.values[ReplicatedField.WEAPON_COOLDOWN.slot()] = Quantisation.encodeUnit(
                    weapon.cooldownRemainingS / COOLDOWN_RANGE_S, NetConstants.WEAPON_FIELD_BITS);
            out.values[ReplicatedField.WEAPON_AMMO.slot()] =
                    weapon.ammoRemaining < 0 ? AMMO_UNLIMITED : Math.min(weapon.ammoRemaining, AMMO_UNLIMITED - 1);
            out.values[ReplicatedField.WEAPON_HEAT.slot()] =
                    Quantisation.encodeUnit(weapon.heat, NetConstants.WEAPON_FIELD_BITS);
        }
    }

    // ---- Encode --------------------------------------------------------------------

    /**
     * Writes {@code current} as a delta against {@code baseline}.
     *
     * @param baseline the frame the recipient acknowledged, or null for a full snapshot
     * @return how many entities the snapshot names; zero is legal and means nothing moved
     */
    public int encode(BitWriter writer, SnapshotFrame current, SnapshotFrame baseline, InputAck ack) {
        changed.clear();
        changedMasks.clear();
        for (Map.Entry<Integer, EntityState> entry : current.entries()) {
            EntityState state = entry.getValue();
            EntityState base = baseline == null ? null : baseline.get(state.networkId);
            int mask = changedComponentMask(state, base);
            if (mask != 0) {
                changed.add(state);
                changedMasks.add(mask);
            }
        }

        writer.writeTick(current.tick());
        writer.writeTick(baseline == null ? NetConstants.FULL_SNAPSHOT_BASELINE : baseline.tick());
        writer.writeInt(ack.lastProcessedSequence);
        writer.writeTick(ack.lastProcessedTick);
        writer.writeBits(Math.min(changed.size(), (1 << ENTITY_COUNT_BITS) - 1), ENTITY_COUNT_BITS);

        for (int i = 0; i < changed.size(); i++) {
            EntityState state = changed.get(i);
            int componentMask = changedMasks.get(i);
            EntityState base = baseline == null ? null : baseline.get(state.networkId);
            writer.writeInt(state.networkId);
            writer.writeBits(componentMask, MASK_BITS);
            for (ReplicatedComponent component : ReplicatedComponent.values()) {
                if ((componentMask & component.maskBit()) == 0) {
                    continue;
                }
                writer.writeBits(component.wireTypeId(), COMPONENT_TYPE_BITS);
                int fieldMask = changedFieldMask(state, base, component);
                writer.writeBits(fieldMask, MASK_BITS);
                List<ReplicatedField> fields = component.fields();
                for (int f = 0; f < fields.size(); f++) {
                    if ((fieldMask & (1 << f)) == 0) {
                        continue;
                    }
                    ReplicatedField field = fields.get(f);
                    int slot = field.slot();
                    for (int v = 0; v < field.valueCount(); v++) {
                        writer.writeBits(state.values[slot + v], field.bitsPerValue());
                    }
                }
            }
        }
        return changed.size();
    }

    private int changedComponentMask(EntityState state, EntityState base) {
        int mask = 0;
        for (ReplicatedComponent component : ReplicatedComponent.values()) {
            if (state.has(component) && changedFieldMask(state, base, component) != 0) {
                mask |= component.maskBit();
            }
        }
        return mask;
    }

    /**
     * Which of a component's fields differ from the baseline.
     *
     * <p>With no baseline every field the entity has is "changed", which is what makes a full
     * snapshot a delta against nothing rather than a second code path.
     */
    private int changedFieldMask(EntityState state, EntityState base, ReplicatedComponent component) {
        if (!state.has(component)) {
            return 0;
        }
        boolean baseHasComponent = base != null && base.has(component);
        int mask = 0;
        List<ReplicatedField> fields = component.fields();
        for (int f = 0; f < fields.size(); f++) {
            if (!baseHasComponent || state.differs(base, fields.get(f))) {
                mask |= 1 << f;
            }
        }
        return mask;
    }

    // ---- Decode --------------------------------------------------------------------

    /** What a decoded snapshot header said, before its entities are read. */
    public record Header(long serverTick, long baselineTick) {}

    /** Reads a snapshot's header without consuming its entities. */
    public Header readHeader(BitReader reader, InputAck ackOut) {
        long serverTick = reader.readTick();
        long baselineTick = reader.readTick();
        ackOut.set(reader.readInt(), reader.readTick());
        return new Header(serverTick, baselineTick);
    }

    /**
     * Applies a snapshot's entity deltas onto {@code target}, which the caller has already seeded
     * with the baseline the header named.
     *
     * <p>Call {@link #readHeader} first: the split exists because a receiver has to check that it
     * <em>has</em> the baseline before it starts mutating anything (D10-R18), and a single decode
     * would have consumed half the packet by the time it found out.
     *
     * @return how many entities the snapshot carried
     */
    public int decodeEntities(BitReader reader, SnapshotFrame target, long serverTick) {
        int entityCount = reader.readBits(ENTITY_COUNT_BITS);
        for (int i = 0; i < entityCount; i++) {
            int networkId = reader.readInt();
            int componentMask = reader.readBits(MASK_BITS);
            EntityState state = target.getOrCreate(networkId);
            state.tick = serverTick;
            for (int bit = 0; bit < ReplicatedComponent.values().length; bit++) {
                if ((componentMask & (1 << bit)) == 0) {
                    continue;
                }
                int wireTypeId = reader.readBits(COMPONENT_TYPE_BITS);
                ReplicatedComponent component = ReplicatedComponent.byWireTypeId(wireTypeId);
                if (component == null) {
                    throw new BitReader.MalformedPacketException(
                            "snapshot names component type id " + wireTypeId + ", which is not replicated");
                }
                state.mark(component);
                int fieldMask = reader.readBits(MASK_BITS);
                List<ReplicatedField> fields = component.fields();
                for (int f = 0; f < fields.size(); f++) {
                    if ((fieldMask & (1 << f)) == 0) {
                        continue;
                    }
                    ReplicatedField field = fields.get(f);
                    int slot = field.slot();
                    for (int v = 0; v < field.valueCount(); v++) {
                        state.values[slot + v] = reader.readBits(field.bitsPerValue());
                    }
                }
            }
        }
        target.setTick(serverTick);
        return entityCount;
    }

    // ---- Apply ---------------------------------------------------------------------

    /**
     * Writes one decoded entity state onto the components of a live entity.
     *
     * <p>The physics body is set from the same values as the components, not left to catch up:
     * D10-R22 requires a remote vehicle's body to hold the authority's current state so collisions
     * and ray tests resolve against where it is, while <em>rendering</em> shows it interpolated
     * 100 ms in the past. Mixing those two is what makes a remote car collide with where it used to
     * be.
     */
    public void apply(World world, int entityId, EntityState state) {
        if (state.has(ReplicatedComponent.TRANSFORM)) {
            TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
            if (transform != null) {
                int slot = ReplicatedField.POSITION.slot();
                transform.position.set(
                        Quantisation.decodePositionAxis(state.values[slot]),
                        Quantisation.decodePositionAxis(state.values[slot + 1]),
                        Quantisation.decodePositionAxis(state.values[slot + 2]));
                Quantisation.unpackRotation(state.values[ReplicatedField.ROTATION.slot()], transform.rotation);
                transform.rotation.nor();
                transform.dirty = true;
            }
        }

        if (state.has(ReplicatedComponent.VELOCITY)) {
            VelocityComponent velocity = world.getComponent(entityId, VelocityComponent.class);
            if (velocity != null) {
                int linear = ReplicatedField.LINEAR_VELOCITY.slot();
                velocity.linear.set(
                        Quantisation.decodeLinearVelocityAxis(state.values[linear]),
                        Quantisation.decodeLinearVelocityAxis(state.values[linear + 1]),
                        Quantisation.decodeLinearVelocityAxis(state.values[linear + 2]));
                int angular = ReplicatedField.ANGULAR_VELOCITY.slot();
                velocity.angular.set(
                        Quantisation.decodeAngularVelocityAxis(state.values[angular]),
                        Quantisation.decodeAngularVelocityAxis(state.values[angular + 1]),
                        Quantisation.decodeAngularVelocityAxis(state.values[angular + 2]));
            }
        }

        if (state.has(ReplicatedComponent.HEALTH)) {
            HealthComponent health = world.getComponent(entityId, HealthComponent.class);
            if (health != null) {
                // The fraction is authoritative and the hit points are derived from it, not the other
                // way round: max hp comes from the part type and is identical on both peers, so
                // deriving keeps the two fields consistent without sending a second number.
                health.healthFraction =
                        Quantisation.decodeHealthFraction(state.values[ReplicatedField.HEALTH_FRACTION.slot()]);
                health.currentHp = health.healthFraction * health.maxHp;
            }
        }

        if (state.has(ReplicatedComponent.DAMAGE_STATE)) {
            DamageStateComponent damage = world.getComponent(entityId, DamageStateComponent.class);
            if (damage != null) {
                DamageState[] states = DamageState.values();
                int ordinal = Math.min(state.values[ReplicatedField.DAMAGE_STATE.slot()], states.length - 1);
                DamageState received = states[ordinal];
                if (received != damage.state) {
                    damage.state = received;
                    damage.stateEnteredTick = state.tick;
                    damage.stateVersion++;
                }
            }
        }

        if (state.has(ReplicatedComponent.WEAPON)) {
            WeaponControllerComponent weapon = world.getComponent(entityId, WeaponControllerComponent.class);
            if (weapon != null) {
                weapon.cooldownRemainingS = Quantisation.decodeUnit(
                                state.values[ReplicatedField.WEAPON_COOLDOWN.slot()], NetConstants.WEAPON_FIELD_BITS)
                        * COOLDOWN_RANGE_S;
                int ammo = state.values[ReplicatedField.WEAPON_AMMO.slot()];
                weapon.ammoRemaining = ammo == AMMO_UNLIMITED ? -1 : ammo;
                weapon.heat = Quantisation.decodeUnit(
                        state.values[ReplicatedField.WEAPON_HEAT.slot()], NetConstants.WEAPON_FIELD_BITS);
            }
        }

        syncBodyFromComponents(world, entityId);
    }

    /**
     * Pushes an entity's replicated transform and velocity into its Bullet body, if it has one.
     *
     * <p>Also used by reconciliation after a rewind (D10-S5.5 step 3), which is the other place a
     * component-side transform has to become the body's.
     */
    public void syncBodyFromComponents(World world, int entityId) {
        RigidBodyComponent rigid = world.getComponent(entityId, RigidBodyComponent.class);
        if (rigid == null || rigid.body == null) {
            return;
        }
        TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
        if (transform != null) {
            scratchMatrix.idt();
            scratchMatrix.set(transform.position, transform.rotation);
            rigid.body.setWorldTransform(scratchMatrix);
        }
        VelocityComponent velocity = world.getComponent(entityId, VelocityComponent.class);
        if (velocity != null) {
            rigid.body.setLinearVelocity(scratchVector.set(velocity.linear));
            rigid.body.setAngularVelocity(scratchVector.set(velocity.angular));
        }
        // A body that Bullet has put to sleep would ignore the new transform until something touched
        // it, which for a remote vehicle is "never" — it is driven entirely from the wire.
        rigid.body.activate();
    }
}

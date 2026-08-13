/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.component.ComponentCatalogue;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.ecs.Component;
import java.util.List;

/**
 * The five component types that go on the wire, and the fields each contributes
 * (docs/10_networking_multiplayer.md#D10-S4.3, #D10-S4.4).
 *
 * <p>{@link #wireTypeId()} is the component's index in {@link ComponentCatalogue}, which D04-R22
 * makes append-only for exactly this purpose: the id is written into every snapshot, and a
 * renumbering would leave two builds decoding each other's fields into the wrong components. The
 * handshake's content hash covers the catalogue, so a mismatch is refused at connect rather than
 * discovered as a physics glitch (D10-R11).
 *
 * <p>{@link #ownerOnly()} marks the weapon fields, which D10-S4.3 sends only to the peer that owns
 * the vehicle. Cooldown, ammunition and heat are HUD numbers for their owner and would be a
 * wallhack for anyone else — the same reasoning as relevance filtering (D10-R29), applied per field
 * instead of per entity.
 */
public enum ReplicatedComponent {

    /** Position and rotation of a replicated root (D10-S4.3). */
    TRANSFORM(TransformComponent.class, false, ReplicatedField.POSITION, ReplicatedField.ROTATION),

    /** Linear and angular velocity, which a client needs to extrapolate between snapshots. */
    VELOCITY(VelocityComponent.class, false, ReplicatedField.LINEAR_VELOCITY, ReplicatedField.ANGULAR_VELOCITY),

    /** A part's health fraction. Delta only: usually unchanged, so usually absent. */
    HEALTH(HealthComponent.class, false, ReplicatedField.HEALTH_FRACTION),

    /** A part's damage state. Three bits, delta only. */
    DAMAGE_STATE(DamageStateComponent.class, false, ReplicatedField.DAMAGE_STATE),

    /** Weapon cooldown, ammunition and heat — to the owning peer only. */
    WEAPON(
            WeaponControllerComponent.class,
            true,
            ReplicatedField.WEAPON_COOLDOWN,
            ReplicatedField.WEAPON_AMMO,
            ReplicatedField.WEAPON_HEAT);

    private static final ReplicatedComponent[] VALUES = values();

    private final Class<? extends Component> componentType;
    private final boolean ownerOnly;
    private final List<ReplicatedField> fields;
    private final int wireTypeId;

    ReplicatedComponent(Class<? extends Component> componentType, boolean ownerOnly, ReplicatedField... fields) {
        this.componentType = componentType;
        this.ownerOnly = ownerOnly;
        this.fields = List.of(fields);
        int index = ComponentCatalogue.TYPES.indexOf(componentType);
        if (index < 0) {
            throw new IllegalStateException(
                    componentType.getSimpleName() + " is replicated but absent from ComponentCatalogue (D04-R22)");
        }
        this.wireTypeId = index;
    }

    /** The component class this reads from and writes to. */
    public Class<? extends Component> componentType() {
        return componentType;
    }

    /** True when this component is sent only to the peer that owns the entity. */
    public boolean ownerOnly() {
        return ownerOnly;
    }

    /** The fields it contributes, in wire order. Mask bit {@code n} is {@code fields().get(n)}. */
    public List<ReplicatedField> fields() {
        return fields;
    }

    /** The {@link ComponentCatalogue} index, written as {@code componentTypeId} (D10-S4.4). */
    public int wireTypeId() {
        return wireTypeId;
    }

    /** The bit this component occupies in an entity's {@code changedComponentMask}. */
    public int maskBit() {
        return 1 << ordinal();
    }

    /** The component with this wire type id, or null. */
    public static ReplicatedComponent byWireTypeId(int wireTypeId) {
        for (ReplicatedComponent component : VALUES) {
            if (component.wireTypeId == wireTypeId) {
                return component;
            }
        }
        return null;
    }
}

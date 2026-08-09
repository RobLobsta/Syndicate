/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One part type's authored data, as the simulation reads it (docs/08_asset_pipeline.md#D08-S4.2).
 *
 * <p>This is the record {@code part.json} becomes, and the thing a spawned part copies itself out
 * of: {@code VehicleFactory} reads a type once and writes its fields across the components that
 * carry them at runtime — mass onto {@code RigidBodyComponent}, hit points onto
 * {@code HealthComponent}, the break impulse onto {@code SlotAttachmentComponent}. The type stays
 * the authority for the authored value; the components hold the live one, which damage changes and
 * the type never does.
 *
 * <p>Immutable and shared: every instance of a part type in a match points at one of these, so
 * nothing here may be mutated once it is in the index (the same reason {@link MeshData} copies its
 * array).
 *
 * <p><b>Not the whole of D08-R5.</b> {@code displayName}, {@code tags}, the visual mesh and the
 * morph target names are absent because nothing in {@code game-core} reads them — presentation and
 * the asset pipeline do, and a field declared before anything reads it has no test that would
 * notice it being loaded wrong. {@code degradationOverrides} is absent for the same reason until
 * {@code VehicleStatsSystem} (slot 6) exists to apply the curve.
 */
public final class PartType {

    private final AssetId partTypeId;
    private final PartCategory category;
    private final AssetId materialId;
    private final SlotType slotTypeRequired;
    private final float massKg;
    private final float maxHp;
    private final float armorValue;
    private final float breakImpulseN;
    private final float powerCost;
    private final boolean hangsBeforeFalling;
    private final StatBlock stats;
    private final Map<String, SlotDefinition> slots;
    private final AssetId fractureManifestRef;
    private final MeshData collisionMesh;

    private PartType(Builder builder) {
        this.partTypeId = Objects.requireNonNull(builder.partTypeId, "partTypeId");
        this.category = Objects.requireNonNull(builder.category, "category");
        this.materialId = builder.materialId;
        this.slotTypeRequired = Objects.requireNonNull(builder.slotTypeRequired, "slotTypeRequired");
        this.massKg = builder.massKg;
        this.maxHp = builder.maxHp;
        this.armorValue = builder.armorValue;
        this.breakImpulseN = builder.breakImpulseN;
        this.powerCost = builder.powerCost;
        this.hangsBeforeFalling = builder.hangsBeforeFalling;
        this.stats = new StatBlock().set(builder.stats);
        this.slots = Collections.unmodifiableMap(new TreeMap<>(builder.slots));
        this.fractureManifestRef = builder.fractureManifestRef;
        this.collisionMesh = Objects.requireNonNull(builder.collisionMesh, "collisionMesh");
    }

    /** Which part type this describes (D00-R19). */
    public AssetId partTypeId() {
        return partTypeId;
    }

    /** Drives slot compatibility, the degradation curve, and whether the part is compound geometry. */
    public PartCategory category() {
        return category;
    }

    /** Resolves in the material table; drives density and the damage-type modifiers of D07-S4.3. */
    public AssetId materialId() {
        return materialId;
    }

    /** The slot type this part must occupy (D08-R6). */
    public SlotType slotTypeRequired() {
        return slotTypeRequired;
    }

    /** Kilograms. Authoritative: the vehicle's total mass is the sum of its parts' (D06-S5.7). */
    public float massKg() {
        return massKg;
    }

    /** Hit points at full health, before the assembly's utility multipliers (D05-S5.2). */
    public float maxHp() {
        return maxHp;
    }

    /** Flat mitigation subtracted before hit points are removed (D07-S5.2). */
    public float armorValue() {
        return armorValue;
    }

    /** Newton-seconds. The impulse at which this part's attachment breaks (D06-R22, D07-S5.7 T2). */
    public float breakImpulseN() {
        return breakImpulseN;
    }

    /** This part's contribution to its assembly's balance budget (D05-S5.7). */
    public float powerCost() {
        return powerCost;
    }

    /**
     * Whether a destroyed part of this type hangs by a thread before it detaches (D07-S5.7 T1).
     *
     * <p>A door that swings half off before falling, rather than vanishing the instant it dies.
     */
    public boolean hangsBeforeFalling() {
        return hangsBeforeFalling;
    }

    /** The part's authored stat contributions (D05-S4.5). The returned block is shared; do not mutate. */
    public StatBlock stats() {
        return stats;
    }

    /** The slots this part offers, by ascending slot id (G3). */
    public Map<String, SlotDefinition> slots() {
        return slots;
    }

    /** One slot by id, or null. */
    public SlotDefinition slot(String slotId) {
        return slots.get(slotId);
    }

    /** Which fracture manifest describes this part's shards, or null — it detaches whole (D07-E5). */
    public AssetId fractureManifestRef() {
        return fractureManifestRef;
    }

    /**
     * The mesh its convex hull is built from.
     *
     * <p>The vehicle compound builds hulls for the parts it contains (D06-S5.3), but a wheel is a
     * ray cast and contributes no compound geometry (D06-R6) — so this is the only source of a
     * wheel's hull when it detaches and becomes a debris body of its own.
     */
    public MeshData collisionMesh() {
        return collisionMesh;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PartType other && partTypeId.equals(other.partTypeId);
    }

    @Override
    public int hashCode() {
        return partTypeId.hashCode();
    }

    @Override
    public String toString() {
        return "PartType[" + partTypeId.value() + ", " + category + ", " + massKg + " kg]";
    }

    /** Starts a part type. {@code massKg}, {@code maxHp} and {@code breakImpulseN} default sanely. */
    public static Builder builder(AssetId partTypeId, PartCategory category, MeshData collisionMesh) {
        return new Builder(partTypeId, category, collisionMesh);
    }

    /**
     * Assembles a {@link PartType}.
     *
     * <p>A builder rather than a record constructor because a part type has fourteen fields of which
     * a caller usually sets five, and a fourteen-argument constructor is a call site where two
     * transposed floats compile silently.
     */
    public static final class Builder {

        private final AssetId partTypeId;
        private final PartCategory category;
        private final MeshData collisionMesh;

        private AssetId materialId;
        private SlotType slotTypeRequired;
        private float massKg = 1f;
        private float maxHp = 100f;
        private float armorValue;
        private float breakImpulseN = 1000f;
        private float powerCost;
        private boolean hangsBeforeFalling;
        private final StatBlock stats = new StatBlock();
        private final Map<String, SlotDefinition> slots = new TreeMap<>();
        private AssetId fractureManifestRef;

        private Builder(AssetId partTypeId, PartCategory category, MeshData collisionMesh) {
            this.partTypeId = partTypeId;
            this.category = category;
            this.collisionMesh = collisionMesh;
            this.slotTypeRequired = defaultSlotTypeFor(category);
        }

        public Builder materialId(AssetId value) {
            this.materialId = value;
            return this;
        }

        public Builder slotTypeRequired(SlotType value) {
            this.slotTypeRequired = value;
            return this;
        }

        public Builder massKg(float value) {
            this.massKg = value;
            return this;
        }

        public Builder maxHp(float value) {
            this.maxHp = value;
            return this;
        }

        public Builder armorValue(float value) {
            this.armorValue = value;
            return this;
        }

        public Builder breakImpulseN(float value) {
            this.breakImpulseN = value;
            return this;
        }

        public Builder powerCost(float value) {
            this.powerCost = value;
            return this;
        }

        public Builder hangsBeforeFalling(boolean value) {
            this.hangsBeforeFalling = value;
            return this;
        }

        public Builder stat(StatBlock.Stat stat, float addTerm, float mulFactor) {
            stats.setAdd(stat, addTerm);
            stats.setMul(stat, mulFactor);
            return this;
        }

        public Builder stats(StatBlock value) {
            stats.set(value);
            return this;
        }

        /** Adds a slot. A duplicate {@code slotId} is rejected here rather than reported as A207. */
        public Builder slot(SlotDefinition slot) {
            if (slots.putIfAbsent(slot.slotId(), slot) != null) {
                throw new IllegalArgumentException(
                        "part " + partTypeId.value() + " declares slot " + slot.slotId() + " twice (A207)");
            }
            return this;
        }

        public Builder fractureManifestRef(AssetId value) {
            this.fractureManifestRef = value;
            return this;
        }

        public PartType build() {
            return new PartType(this);
        }

        /**
         * The slot type a category occupies unless the part says otherwise.
         *
         * <p>D08-R6 makes {@code slotTypeRequired} an authored field, and the categories that can go
         * in more than one kind of slot — a weapon in a hardpoint or on a turret mount — must author
         * it. This default exists so that the majority which cannot do not have to.
         */
        private static SlotType defaultSlotTypeFor(PartCategory category) {
            return switch (category) {
                case CHASSIS -> SlotType.ROOT;
                case WHEEL -> SlotType.WHEEL;
                case ARMOR -> SlotType.ARMOR_PANEL;
                case WEAPON, UTILITY -> SlotType.HARDPOINT;
                case DECORATIVE -> SlotType.ACCESSORY;
            };
        }
    }
}

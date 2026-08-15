/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.DegradationRule;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DestructionClass;
import dev.syndicate.model.PartCategory;
import java.util.Collections;
import java.util.EnumMap;
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
 * notice it being loaded wrong.
 */
public final class PartType {

    private final AssetId partTypeId;
    private final PartCategory category;
    private final AssetId materialId;
    private final DestructionClass destructionClass;
    private final SlotType slotTypeRequired;
    private final float massKg;
    private final float maxHp;
    private final float armorValue;
    private final float breakImpulseN;
    private final float powerCost;
    private final boolean hangsBeforeFalling;
    private final StatBlock stats;
    private final HandlingBlock handling;
    private final WeaponBlock weapon;
    private final ModuleBlock module;
    private final Map<StatBlock.Stat, DegradationRule> degradationOverrides;
    private final Map<String, SlotDefinition> slots;
    private final AssetId fractureManifestRef;
    private final MeshData collisionMesh;

    private PartType(Builder builder) {
        this.partTypeId = Objects.requireNonNull(builder.partTypeId, "partTypeId");
        this.category = Objects.requireNonNull(builder.category, "category");
        this.materialId = builder.materialId;
        this.destructionClass = builder.destructionClass == null
                ? DestructionClass.forCategory(builder.category)
                : builder.destructionClass;
        this.slotTypeRequired = Objects.requireNonNull(builder.slotTypeRequired, "slotTypeRequired");
        this.massKg = builder.massKg;
        this.maxHp = builder.maxHp;
        this.armorValue = builder.armorValue;
        this.breakImpulseN = builder.breakImpulseN;
        this.powerCost = builder.powerCost;
        this.hangsBeforeFalling = builder.hangsBeforeFalling;
        this.stats = new StatBlock().set(builder.stats);
        this.handling = builder.handling;
        this.weapon = builder.weapon;
        this.module = builder.module;
        this.degradationOverrides = builder.degradationOverrides.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(builder.degradationOverrides));
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

    /**
     * How this part fails, and therefore what the preparation pipeline authors for it
     * (docs/15_vehicle_preparation_pipeline.md#D15-S5.7, D15-R32).
     *
     * <p>On the part rather than on the material, because it follows from what a part <em>is</em>:
     * a chassis rail and a door skin can be the same steel and fail completely differently. Defaults
     * from {@link PartCategory} when a part authors none, so every part has a treatment without
     * every {@code part.json} having to name one.
     */
    public DestructionClass destructionClass() {
        return destructionClass;
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

    /**
     * The physical parameters a stat block cannot carry: drag and rolling resistance on a chassis,
     * damping and roll influence on a wheel (D08-R5, DEC-031).
     *
     * @return never null — a part that authors no {@code handling} block gets
     *     {@link HandlingBlock#REFERENCE}, D06-S4.5's reference chassis
     */
    public HandlingBlock handling() {
        return handling;
    }

    /**
     * What kind of weapon this part is, or null for a part that is not one (D08-R5, D01-R8).
     *
     * <p>Null rather than a neutral default: {@code WeaponSystem} (8) uses its presence to decide
     * whether a part can fire at all, and a non-weapon carrying a default family would be a gun
     * bolted to every armour plate.
     */
    public WeaponBlock weapon() {
        return weapon;
    }

    /**
     * What kind of utility module this part is, or null for a part that is not one (D08-R6).
     *
     * <p>Null for the same reason {@link #weapon()} is: presence is what says the part has the
     * capability at all, and a default family would make every accessory a radar.
     */
    public ModuleBlock module() {
        return module;
    }

    /**
     * Per-stat degradation rules this part authors, overriding the D05-S5.4 table (D08-R5).
     *
     * <p>Usually empty: the table is the answer for almost every part, and an override exists so a
     * particular plate can be authored to fail harder or a particular gun to keep working longer.
     * A stat absent from the map falls back to {@code Degradation.ruleFor(category, stat)}.
     *
     * @return an immutable map, never null
     */
    public Map<StatBlock.Stat, DegradationRule> degradationOverrides() {
        return degradationOverrides;
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

    /**
     * Where this part's mass acts, in part-local metres — the centre of its collision mesh's extent.
     *
     * <p>D08-R5 gives a part a mass but no centre for it, and the obvious reading of that silence is
     * that the mass acts at the part's origin. For a wheel that reading is right: the dissection
     * centres a wheel on its axle, so origin and centroid coincide to within a millimetre. For a
     * chassis it is badly wrong. The chassis mesh's origin is on the road at the centreline — the
     * space slot positions are authored in (D08-S4.2) — and a car's body does not sit at road level;
     * it sits about half a metre above it. Taking the origin as the centre of mass puts three
     * quarters of a tonne under the tarmac, and suspension pushing up from 0.59 m onto a mass at
     * 0.0 m applies a couple to the body every time a spring extends. That rings instead of damping:
     * the corners of a settled car were still 10 cm apart after four seconds, and the Stampede lost
     * half a second off its 0–100 to wheels that kept unloading.
     *
     * <p>The AABB centre rather than a volume integral because a convex hull's AABB centre is within
     * a few centimetres of its centroid for shapes as boxy as a car body, and because the alternative
     * is authoring a {@code comLocal} per part — a field no artist would get right and no test could
     * check. When a part's real centre matters more than that, D08-R5 is where it should be authored.
     */
    public Vector3 centerOfMassLocal(Vector3 out) {
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        collisionMesh.bounds(min, max);
        return out.set(min).add(max).scl(0.5f);
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
     * <p>A builder rather than a record constructor because a part type has fifteen fields of which
     * a caller usually sets five, and a fourteen-argument constructor is a call site where two
     * transposed floats compile silently.
     */
    public static final class Builder {

        private final AssetId partTypeId;
        private final PartCategory category;
        private final MeshData collisionMesh;

        private AssetId materialId;
        private DestructionClass destructionClass;
        private SlotType slotTypeRequired;
        private float massKg = 1f;
        private float maxHp = 100f;
        private float armorValue;
        private float breakImpulseN = 1000f;
        private float powerCost;
        private boolean hangsBeforeFalling;
        private final StatBlock stats = new StatBlock();
        private HandlingBlock handling = HandlingBlock.REFERENCE;
        private WeaponBlock weapon;
        private ModuleBlock module;
        private final Map<StatBlock.Stat, DegradationRule> degradationOverrides = new EnumMap<>(StatBlock.Stat.class);
        private final Map<String, SlotDefinition> slots = new TreeMap<>();
        private AssetId fractureManifestRef;

        private Builder(AssetId partTypeId, PartCategory category, MeshData collisionMesh) {
            this.partTypeId = partTypeId;
            this.category = category;
            this.collisionMesh = collisionMesh;
            this.slotTypeRequired = defaultSlotTypeFor(category);
        }

        /** Overrides the class {@link PartCategory} would give this part (D15-R32). */
        public Builder destructionClass(DestructionClass value) {
            this.destructionClass = value;
            return this;
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

        /** Replaces D06-S4.5's reference chassis figures for this part (D08-R5). */
        public Builder handling(HandlingBlock value) {
            this.handling = value == null ? HandlingBlock.REFERENCE : value;
            return this;
        }

        /** Declares this part a weapon of the given family and configuration (D08-R5, D01-R8). */
        public Builder weapon(WeaponBlock value) {
            this.weapon = value;
            return this;
        }

        /** Declares this part a utility module of the given family (D08-R6). */
        public Builder module(ModuleBlock value) {
            this.module = value;
            return this;
        }

        /** Overrides the D05-S5.4 table for one stat on this part type (D08-R5). */
        public Builder degradationOverride(StatBlock.Stat stat, DegradationRule rule) {
            degradationOverrides.put(stat, rule);
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
                case PANEL -> SlotType.PANEL;
                case WEAPON, UTILITY -> SlotType.HARDPOINT;
                case DECORATIVE -> SlotType.ACCESSORY;
            };
        }
    }
}

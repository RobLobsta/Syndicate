/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SizeClass;
import java.util.ArrayList;
import java.util.List;

/**
 * A four-wheeled chassis with one weapon hardpoint, whose two size classes the caller chooses.
 *
 * <p>Exists so {@link SizeClassGatingTest} exercises the real {@link AssemblyValidator} rather than a
 * re-implementation of D17-R7 — the failure mode a hand-rolled assertion has is agreeing with a bug.
 */
final class SizeClassFixtures {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_size_class_probe");
    private static final AssetId CHASSIS = AssetId.of("chassis_probe_01");
    private static final AssetId WHEEL = AssetId.of("wheel_probe_01");
    private static final AssetId WEAPON = AssetId.of("weapon_probe_01");

    /** A one-metre box, the smallest mesh whose hull encloses a volume. */
    private static MeshData box() {
        return new MeshData(new float[] {
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f,
            -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
        });
    }

    private static Transform at(float x, float y, float z) {
        Transform transform = new Transform();
        transform.position.set(x, y, z);
        return transform;
    }

    /**
     * The catalogue, with the weapon at {@code weaponClass} and the hardpoint at {@code slotClass}.
     *
     * <p>Masses are well inside the slot's cap so that A306 cannot fire and confuse the reading: this
     * fixture is about bulk, and the mass gate is tested separately.
     */
    InMemoryAssetIndex index(SizeClass weaponClass, SizeClass slotClass) {
        PartType.Builder chassis =
                PartType.builder(CHASSIS, PartCategory.CHASSIS, box()).massKg(1000f);
        chassis.slot(SlotDefinition.of("wheel_fl", SlotType.WHEEL, at(-1f, -0.4f, 1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_fr", SlotType.WHEEL, at(1f, -0.4f, 1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_rl", SlotType.WHEEL, at(-1f, -0.4f, -1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_rr", SlotType.WHEEL, at(1f, -0.4f, -1.4f), 200f));
        chassis.slot(new SlotDefinition(
                "hardpoint_probe", SlotType.HARDPOINT, at(0f, 1f, 0f), 500f, slotClass, List.of(), true));

        return new InMemoryAssetIndex()
                .put(chassis.build())
                .put(PartType.builder(WHEEL, PartCategory.WHEEL, box())
                        .massKg(50f)
                        .build())
                .put(PartType.builder(WEAPON, PartCategory.WEAPON, box())
                        .massKg(60f)
                        .sizeClass(weaponClass)
                        .slotTypeRequired(SlotType.HARDPOINT)
                        .build());
    }

    /** Four wheels and the weapon on the hardpoint. */
    AssemblyDef assembly() {
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>();
        for (String slot : new String[] {"wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"}) {
            parts.add(AssemblyDef.PartPlacement.of("root", slot, WHEEL));
        }
        parts.add(AssemblyDef.PartPlacement.of("root", "hardpoint_probe", WEAPON));
        parts.sort(java.util.Comparator.comparing(AssemblyDef.PartPlacement::slotPath));
        // Null `expected` so A310/A311/A312 stay silent: this fixture is about A316 and nothing else.
        return new AssemblyDef(ASSEMBLY, "medium", CHASSIS, parts, null);
    }
}

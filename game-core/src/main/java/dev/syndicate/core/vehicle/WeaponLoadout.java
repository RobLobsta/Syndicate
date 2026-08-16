/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.asset.WeaponDef;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Which weapon sits on which of a vehicle's hardpoints
 * (docs/01_product_game_design.md#D01-S2.2 NG1, docs/17_weapon_system.md#D17-S4.3).
 *
 * <p><b>A loadout, not an editor.</b> D01-NG1 still rules out assembling a vehicle part by part:
 * everything except the weapons is the vehicle the artist authored, and this changes only what
 * occupies the hardpoints that exist on it. That boundary is the whole design — a hardpoint is
 * already authored with a position, a size class and a mass ceiling, so a loadout can be checked
 * against rules that exist rather than needing new ones.
 *
 * <p>Immutable, and every operation returns a new instance. That is what lets the garage show a
 * candidate loadout beside the fitted one without either being half-applied, and it is what makes
 * {@link #applyTo} a pure function of an assembly and an index — so the same loadout produces the
 * same vehicle on a headless server as in the client (G17, G3).
 */
public final class WeaponLoadout {

    /**
     * The suffix a configured assembly's id carries.
     *
     * <p>Visible in a match report and in a log line, which is the point: "the player drove
     * {@code vehicle_eclipse_01} as configured" is a different fact from "the player drove the
     * shipped {@code vehicle_eclipse_01}", and a debug session that cannot tell them apart wastes
     * its first ten minutes.
     */
    public static final String CONFIGURED_SUFFIX = "_fitted";

    /** Sorted by slot id, so two peers building the same loadout iterate it identically (G3). */
    private final Map<String, AssetId> bySlot;

    private WeaponLoadout(Map<String, AssetId> bySlot) {
        this.bySlot = Collections.unmodifiableMap(new TreeMap<>(bySlot));
    }

    /** Nothing on any hardpoint. */
    public static WeaponLoadout empty() {
        return new WeaponLoadout(Map.of());
    }

    /**
     * The loadout an assembly already carries, read back out of its part list.
     *
     * <p>Reading it back rather than storing it separately is what makes a shipped vehicle and a
     * configured one the same kind of thing: the garage opens on what the artist fitted, and the
     * player edits from there.
     */
    public static WeaponLoadout of(AssemblyDef assembly, InMemoryAssetIndex assets) {
        Map<String, AssetId> fitted = new TreeMap<>();
        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            if (!SlotChain.ROOT_SLOT_PATH.equals(placement.parentSlotPath())) {
                continue;
            }
            AssetId weaponId = weaponRootedAt(placement.partTypeId(), assets);
            if (weaponId != null) {
                fitted.put(placement.parentSlotId(), weaponId);
            }
        }
        return new WeaponLoadout(fitted);
    }

    /** The weapon on this hardpoint, or null when it is empty. */
    public AssetId on(String slotId) {
        return bySlot.get(slotId);
    }

    /** Every occupied hardpoint, by ascending slot id. */
    public Map<String, AssetId> fitted() {
        return bySlot;
    }

    /** The same loadout with {@code weaponId} on {@code slotId}; a null weapon clears it. */
    public WeaponLoadout with(String slotId, AssetId weaponId) {
        Map<String, AssetId> next = new TreeMap<>(bySlot);
        if (weaponId == null) {
            next.remove(slotId);
        } else {
            next.put(slotId, weaponId);
        }
        return new WeaponLoadout(next);
    }

    /**
     * The vehicle this loadout describes: {@code base} with every hardpoint's weapon replaced.
     *
     * <p>Two halves, and the first is the one that is easy to get wrong. Removing a weapon means
     * removing its <em>whole subtree</em> — a cannon is a mount, two gears, a receiver, a barrel, a
     * muzzle and a breech, and dropping only the mount leaves six orphans whose parents no longer
     * exist. Slot paths make that cheap: every sub-part's path is prefixed by its mount's, so one
     * prefix test removes the lot.
     *
     * <p>The {@code expected} block is dropped rather than recomputed. It is the artist's assertion
     * about the vehicle they authored (D08-R10), and a configured vehicle is not that vehicle;
     * carrying it forward would assert a mass that is no longer true, and recomputing it would turn
     * a check into a tautology.
     *
     * @return a new assembly whose id carries {@link #CONFIGURED_SUFFIX}, or {@code base} itself
     *     when this loadout is exactly what the base already carries
     */
    public AssemblyDef applyTo(AssemblyDef base, InMemoryAssetIndex assets) {
        if (equals(of(base, assets))) {
            return base;
        }
        List<AssemblyDef.PartPlacement> kept = new ArrayList<>();
        List<String> strippedPrefixes = new ArrayList<>();
        for (AssemblyDef.PartPlacement placement : base.parts()) {
            if (SlotChain.ROOT_SLOT_PATH.equals(placement.parentSlotPath())
                    && weaponRootedAt(placement.partTypeId(), assets) != null) {
                strippedPrefixes.add(placement.slotPath() + "/");
                continue;
            }
            kept.add(placement);
        }
        kept.removeIf(placement -> strippedPrefixes.stream().anyMatch(placement.slotPath()::startsWith));

        for (Map.Entry<String, AssetId> entry : bySlot.entrySet()) {
            WeaponDef weapon = assets.weapon(entry.getValue());
            if (weapon == null) {
                continue;
            }
            kept.addAll(weapon.placements(SlotChain.ROOT_SLOT_PATH, entry.getKey()));
        }
        return new AssemblyDef(
                AssetId.of(base.assemblyId().value() + CONFIGURED_SUFFIX),
                base.displayName(),
                base.vehicleClass(),
                base.chassisPartTypeId(),
                kept,
                null);
    }

    /**
     * Every weapon that may be fitted to one of a chassis's hardpoints, by ascending id.
     *
     * <p>The gate is {@link SlotDefinition#accepts} and nothing else — the same three questions the
     * assembly validator asks of a placement, so a weapon this offers is a weapon that loads
     * (A305, A306, A316). Writing a second, stricter rule here is a live temptation and a mistake:
     * the first draft required the weapon's {@code slotTypeRequired} to equal the slot's type, which
     * offered nothing at all, because every weapon mount the tool produces declares
     * {@code TURRET_MOUNT} while a {@code HARDPOINT} accepts weapons perfectly happily. A garage
     * that hides something the game would accept is as wrong as one that offers something it would
     * reject.
     *
     * <p>Weighed by the <em>whole</em> weapon rather than by its mount. A305's ceiling is per-part
     * and a 0.6 kg bracket passes any slot in the game; what a hardpoint is really rated for is the
     * gun hanging off it, and a garage that let a 178 kg cannon onto a 120 kg mounting because the
     * bracket was light would be measuring the wrong thing.
     */
    public static List<AssetId> fittableOn(SlotDefinition slot, InMemoryAssetIndex assets) {
        List<AssetId> out = new ArrayList<>();
        for (WeaponDef weapon : assets.weapons().values()) {
            PartType root = assets.partType(weapon.rootPartTypeId());
            if (root == null) {
                continue;
            }
            if (!slot.accepts(root.category(), weapon.totalMassKg(), weapon.sizeClass())) {
                continue;
            }
            out.add(weapon.weaponId());
        }
        return List.copyOf(out);
    }

    /**
     * The mountings on a vehicle that a weapon may be put on, in slot-id order.
     *
     * <p>Two filters, and the second is not optional. A slot must accept the {@code WEAPON}
     * category — {@code HARDPOINT} or {@code TURRET_MOUNT} — <b>and</b> must not already be
     * occupied by something that is not a weapon. Type alone is not enough because
     * {@code HARDPOINT} also accepts {@code UTILITY}, and a prepared vehicle's four brake hubs sit
     * in exactly such slots: without the occupancy test the garage offered to bolt a machine gun
     * to each wheel hub, which the 22 kg mass ceiling would even have allowed.
     *
     * <p>Derived from the slot table rather than from a list of known slot ids, so a chassis that
     * gains a sixth mounting appears in the garage without a code change.
     */
    public static List<SlotDefinition> mountingsOf(AssemblyDef assembly, InMemoryAssetIndex assets) {
        PartType chassis = assembly == null ? null : assets.partType(assembly.chassisPartTypeId());
        if (chassis == null) {
            return List.of();
        }
        Map<String, AssetId> occupants = new LinkedHashMap<>();
        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            if (SlotChain.ROOT_SLOT_PATH.equals(placement.parentSlotPath())) {
                occupants.put(placement.parentSlotId(), placement.partTypeId());
            }
        }
        List<SlotDefinition> out = new ArrayList<>();
        for (SlotDefinition slot : chassis.slots().values()) {
            if (slot.slotType() != SlotType.HARDPOINT && slot.slotType() != SlotType.TURRET_MOUNT) {
                continue;
            }
            AssetId occupant = occupants.get(slot.slotId());
            if (occupant != null && weaponRootedAt(occupant, assets) == null) {
                continue;
            }
            out.add(slot);
        }
        out.sort((a, b) -> a.slotId().compareTo(b.slotId()));
        return List.copyOf(out);
    }

    /** The total mass of the fitted weapons, for the garage's stat block. */
    public float totalMassKg(InMemoryAssetIndex assets) {
        float total = 0f;
        for (AssetId weaponId : bySlot.values()) {
            WeaponDef weapon = assets.weapon(weaponId);
            if (weapon != null) {
                total += weapon.totalMassKg();
            }
        }
        return total;
    }

    /** The weapon whose root part this is, or null when the part is not a weapon's mount. */
    private static AssetId weaponRootedAt(AssetId partTypeId, InMemoryAssetIndex assets) {
        for (WeaponDef weapon : assets.weapons().values()) {
            if (weapon.rootPartTypeId().equals(partTypeId)) {
                return weapon.weaponId();
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WeaponLoadout loadout && bySlot.equals(loadout.bySlot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bySlot);
    }

    @Override
    public String toString() {
        return "WeaponLoadout" + bySlot;
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which live armour part shields which slot (docs/05_vehicle_part_system.md#D05-S5.8).
 *
 * <p>Two questions, and they are the whole of the destruction system's design payoff (D01-R12).
 * <em>Interception</em>: a hit aimed at a slot an armour plate covers damages the plate instead of
 * what is behind it. <em>Exposure</em>: once that plate is gone, the slot it covered takes ×1.5
 * damage, so stripping armour is rewarded and the reward is visible, because the exposed frame is
 * literally showing.
 *
 * <p><b>Rebuilt rather than cached.</b> D05-S5.8 rebuilds the map on a {@code structuralVersion}
 * change. This class rebuilds it into a caller-owned instance whenever a vehicle is about to take a
 * hit, which is strictly more often and always correct — a plate destroyed earlier in the same tick
 * has already stopped covering by the time the next hit resolves. The cost is a walk over one
 * vehicle's parts, paid only on a tick where that vehicle was actually struck, against a cached map
 * that would have to be invalidated by every one of the four detach triggers.
 *
 * <p>Armour parts are visited in ascending slot path order so that "last writer wins" is a
 * deterministic writer (G3). Assets are validated to avoid two plates covering one slot
 * (D08-S5.4), so the tie-break should never decide anything; if it ever does, it decides the same
 * way on every peer.
 */
public final class CoverageMap {

    /** Covered slot path → the live armour part entity shielding it. Sorted for determinism (G3). */
    private final Map<String, Integer> coveringPartBySlotPath = new TreeMap<>();

    /** Slot paths that some part type declares coverable, whether or not anything covers them now. */
    private final List<String> coverableSlotPaths = new ArrayList<>();

    /** An empty map, for a vehicle with no armour at all. */
    public CoverageMap() {}

    /**
     * Rebuilds this map for one vehicle (D05-S5.8 {@code rebuildCoverageMap}).
     *
     * <p>Clears first, so one instance can be reused across vehicles as a system's scratch.
     */
    public void rebuild(World world, AssetIndex assets, int vehicleEntity) {
        coveringPartBySlotPath.clear();
        coverableSlotPaths.clear();

        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (graph == null) {
            return;
        }
        List<SlotNode> nodes = new ArrayList<>(graph.nodes);
        nodes.sort(Comparator.comparing(node -> node.slotPath));

        for (SlotNode node : nodes) {
            int partEntity = node.childEntity;
            if (!world.isAlive(partEntity)) {
                continue;
            }
            PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
            if (partRef == null) {
                continue;
            }
            PartType partType = assets.partType(partRef.partTypeId);
            if (partType == null) {
                continue;
            }
            // Every slot a part offers that names something it covers is a coverable slot, whatever
            // occupies it. This is the "authored as coverable" half of D05-S5.8's isExposed: a part
            // in a slot nobody was ever meant to shield is not exposed, it is simply outside.
            String parentPath = node.slotPath;
            for (SlotDefinition slot : partType.slots().values()) {
                for (String coveredSlotId : slot.covers()) {
                    coverableSlotPaths.add(parentPath + "/" + coveredSlotId);
                }
            }
            if (partType.category() != PartCategory.PANEL) {
                continue;
            }
            if (isDeadOrGone(world, partEntity)) {
                continue;
            }
            // The armour plate shields slots on the part it is bolted to, named by the slot
            // definition it occupies (D08-R6: covers entries are slot ids on the same part).
            SlotDefinition occupied = occupiedSlot(world, assets, graph, node);
            if (occupied == null) {
                continue;
            }
            String ownerPath = SlotChain.parentPathOf(node.slotPath);
            for (String coveredSlotId : occupied.covers()) {
                coveringPartBySlotPath.put(ownerPath + "/" + coveredSlotId, partEntity);
            }
        }
    }

    /**
     * The live armour part shielding a slot path, or {@link EntityId#NULL}.
     *
     * <p>This is D07-S5.1's interception test: a hit that resolved to the covered part is redirected
     * to the armour while the armour lives.
     */
    public int coveringPartOf(String slotPath) {
        return coveringPartBySlotPath.getOrDefault(slotPath, EntityId.NULL);
    }

    /**
     * Whether a slot is authored as coverable and has nothing covering it now (D05-S5.8).
     *
     * <p>A part in an uncoverable slot is never {@code EXPOSED}: there was no armour for it to lose,
     * so multiplying its damage by 1.5 would reward the attacker for nothing.
     */
    public boolean isExposed(String slotPath) {
        return !coveringPartBySlotPath.containsKey(slotPath) && coverableSlotPaths.contains(slotPath);
    }

    /** How many slots are currently shielded. For assertions and diagnostics. */
    public int coveredCount() {
        return coveringPartBySlotPath.size();
    }

    /** The slot definition a part occupies on its parent, or null when it cannot be resolved. */
    private static SlotDefinition occupiedSlot(
            World world, AssetIndex assets, SlotGraphComponent graph, SlotNode node) {
        int parentEntity = node.parentEntity;
        if (parentEntity == EntityId.NULL || !world.isAlive(parentEntity)) {
            return null;
        }
        PartRefComponent parentRef = world.getComponent(parentEntity, PartRefComponent.class);
        if (parentRef == null) {
            return null;
        }
        PartType parentType = assets.partType(parentRef.partTypeId);
        return parentType == null ? null : parentType.slot(node.slotId);
    }

    private static boolean isDeadOrGone(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        return damageState == null
                || damageState.state == DamageState.DESTROYED
                || damageState.state == DamageState.DETACHED;
    }
}

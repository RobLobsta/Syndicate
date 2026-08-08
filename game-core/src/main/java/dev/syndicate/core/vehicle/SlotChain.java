/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulated slot transforms: where each part sits in chassis-local space
 * (docs/05_vehicle_part_system.md#D05-S4.3, docs/06_physics_simulation.md#D06-S5.3).
 *
 * <p>A part's placement is the product of the slot offsets from the chassis down to it, so a turret
 * barrel moves when the turret's slot offset does without anyone re-authoring the barrel. Both the
 * compound shape (D06-S5.3) and the mass-property sum (D06-S5.7) need that product, so it is
 * computed once, here, rather than twice with two chances to disagree about multiplication order.
 *
 * <p><b>Why a single pass works.</b> Slot paths are {@code /}-joined and a parent's path is a prefix
 * of its children's, and a prefix always sorts before the strings that extend it. Walking the graph
 * in ascending slot path order therefore visits every parent before any of its children, so one pass
 * suffices — no topological sort, no recursion, and the same order on every peer (G3).
 */
public final class SlotChain {

    /** The slot path of the chassis, the root of every chain (D05-R11). */
    public static final String ROOT_SLOT_PATH = "root";

    private final Map<String, Matrix4> transformBySlotPath = new TreeMap<>();
    private final Map<String, Integer> entityBySlotPath = new TreeMap<>();
    private final Map<Integer, String> slotPathByEntity = new TreeMap<>();

    private SlotChain() {}

    /**
     * Computes every live part's chassis-local transform.
     *
     * <p>The chassis itself is included at {@link #ROOT_SLOT_PATH} with the identity transform: it
     * is the space everything else is expressed in, and leaving it out would make every caller
     * special-case the one part that is always present.
     *
     * <p>A node whose parent is not in the graph is skipped rather than treated as a root. That
     * happens transiently while a subtree is being detached, and silently reparenting an orphan to
     * the chassis would place it at the wrong point in the world in the same tick it leaves.
     */
    public static SlotChain of(SlotGraphComponent graph, VehicleChassisComponent chassis) {
        SlotChain chain = new SlotChain();
        chain.transformBySlotPath.put(ROOT_SLOT_PATH, new Matrix4());
        if (chassis != null && chassis.chassisPartEntity != EntityId.NULL) {
            chain.slotPathByEntity.put(chassis.chassisPartEntity, ROOT_SLOT_PATH);
            chain.entityBySlotPath.put(ROOT_SLOT_PATH, chassis.chassisPartEntity);
        }

        List<SlotNode> nodes = new ArrayList<>(graph.nodes);
        nodes.sort((a, b) -> a.slotPath.compareTo(b.slotPath));

        Matrix4 local = new Matrix4();
        for (int i = 0; i < nodes.size(); i++) {
            SlotNode node = nodes.get(i);
            String parentPath = parentPathOf(node.slotPath);
            Matrix4 parent = chain.transformBySlotPath.get(parentPath);
            if (parent == null) {
                continue;
            }
            node.localTransform.toMatrix(local);
            chain.transformBySlotPath.put(node.slotPath, new Matrix4(parent).mul(local));
            chain.slotPathByEntity.put(node.childEntity, node.slotPath);
            chain.entityBySlotPath.put(node.slotPath, node.childEntity);
        }
        return chain;
    }

    /** The chassis-local transform of a slot path, or null if that path is not occupied. */
    public Matrix4 transformOf(String slotPath) {
        return transformBySlotPath.get(slotPath);
    }

    /** The slot path a part entity occupies, or null if it is not in this graph. */
    public String slotPathOf(int partEntity) {
        return slotPathByEntity.get(partEntity);
    }

    /** The part entity at a slot path, or null if that path is not occupied. */
    public Integer entityAt(String slotPath) {
        return entityBySlotPath.get(slotPath);
    }

    /** Every occupied slot path including the chassis root, in ascending order (G3). */
    public Iterable<Map.Entry<String, Matrix4>> entries() {
        return transformBySlotPath.entrySet();
    }

    /** Every part entity by slot path, in ascending slot path order (G3). */
    public Iterable<Map.Entry<String, Integer>> partEntities() {
        return entityBySlotPath.entrySet();
    }

    /** How many parts, including the chassis, have a transform. */
    public int size() {
        return transformBySlotPath.size();
    }

    /** The parent of a slot path: everything before the final {@code /}. */
    public static String parentPathOf(String slotPath) {
        int cut = slotPath.lastIndexOf('/');
        return cut < 0 ? ROOT_SLOT_PATH : slotPath.substring(0, cut);
    }

    /** True when {@code candidate} is {@code ancestor} or sits beneath it in the slot tree. */
    public static boolean isAtOrBeneath(String candidate, String ancestor) {
        return candidate.equals(ancestor) || candidate.startsWith(ancestor + "/");
    }
}

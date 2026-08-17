/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What a weapon loses when its sub-parts are shot off (docs/17_weapon_system.md#D17-S5.13).
 *
 * <p>A shared operation over components in the mould of {@link PartDetachment} and {@code
 * DamageApplication} (DEC-016, DEC-038): {@code VehicleStatsSystem} (6) owns the schedule slot and
 * this class owns the table. D17-R61 is explicit that this is <b>not</b> a special case in the
 * weapon system — a weapon's effectiveness is a function of its own sub-parts' damage states,
 * evaluated through the ordinary degradation model, and slot 8 is left reading effective numbers
 * without knowing why they are what they are.
 *
 * <p><b>Why the penalties are multipliers rather than curves.</b> D05-S5.4's profiles describe a
 * stat falling off as one part's health falls. A sub-part is a different question: it is present or
 * it is not, and the row in D17-S5.13 is the step. Folding them in as {@code mul} terms on the
 * mount's effective stats is the same mechanism {@code VehicleStatsSystem} already uses to let a
 * utility module modify another part's numbers, so nothing new arrives in the schedule.
 *
 * <p><b>What counts as lost</b> is deliberately wider than "an entity whose state is DESTROYED".
 * A destroyed sub-part usually detaches in the same tick and leaves the slot graph entirely
 * (D07-S5.7), so a walk that only inspected present entities would see a gun regain its accuracy
 * the moment its barrel finished falling off. The walk therefore starts from what the mount's
 * {@link PartType} <em>declares</em> and treats an unoccupied declared sub-slot as a loss.
 */
public final class WeaponSubPartDegradation {

    /** Spread multiplier when the barrel is gone (D17-R61). */
    public static final float BARREL_LOST_SPREAD_MUL = 4.0f;

    /** Range multiplier when the barrel is gone (D17-R61). */
    public static final float BARREL_LOST_RANGE_MUL = 0.5f;

    /** Fire-interval multiplier when the breech is gone — half the rate is twice the interval. */
    public static final float BREECH_LOST_INTERVAL_MUL = 2.0f;

    /**
     * The fraction of its capacity a weapon keeps chambered when its feed is gone.
     *
     * <p>D17-R61 says the capacity goes to zero and "the weapon runs on what is chambered", which
     * names a quantity no document authors. Five per cent, floored at one round, is the reading
     * taken: it is a belt-fed gun's receiver rather than its drum, it leaves the row meaning what it
     * says — a burst, then nothing — and it is never zero, because a feed hit that silenced a gun
     * instantly would make the sub-part system a second health pool, which D17-R62 exists to
     * prevent. Recorded as DEC-085.
     */
    public static final float FEED_LOST_CHAMBERED_FRACTION = 0.05f;

    /** The floor under {@link #FEED_LOST_CHAMBERED_FRACTION}: a gun always has one round left. */
    public static final int FEED_LOST_MIN_CHAMBERED = 1;

    private WeaponSubPartDegradation() {
        throw new AssertionError("no instances");
    }

    /**
     * What a weapon's remaining sub-parts leave it able to do.
     *
     * @param disabled true when the weapon cannot fire at all — its receiver is gone (D17-R61)
     * @param spreadMul multiplies the mount's {@code SPREAD_RAD}
     * @param rangeMul multiplies the mount's effective range
     * @param fireIntervalMul multiplies the mount's effective fire interval
     * @param feedLost true when ammunition capacity is gone and only the chambered rounds remain
     */
    public record Penalties(
            boolean disabled, float spreadMul, float rangeMul, float fireIntervalMul, boolean feedLost) {

        /** An intact weapon: everything at identity, nothing disabled. */
        public static final Penalties NONE = new Penalties(false, 1f, 1f, 1f, false);
    }

    /**
     * Evaluates D17-S5.13 for one weapon.
     *
     * <p>{@code lost} is the set of labels the caller found missing or destroyed beneath the mount.
     * Passing the set rather than walking the graph here keeps this class free of {@code World}: the
     * table is the part worth testing on its own, and a test that wants to ask "what does a gun with
     * no barrel do" should not have to build a vehicle to ask it.
     *
     * <p>{@code MOUNT} is absent from the table on purpose. Losing the mount takes the whole weapon
     * with it through ordinary subtree detachment (D07-S5.7, D17-R55), so by the time it could
     * matter here there is no weapon left to degrade.
     */
    public static Penalties evaluate(Set<WeaponSubPart> lost) {
        if (lost == null || lost.isEmpty()) {
            return Penalties.NONE;
        }
        boolean disabled = lost.contains(WeaponSubPart.RECEIVER);
        float spreadMul = lost.contains(WeaponSubPart.BARREL) ? BARREL_LOST_SPREAD_MUL : 1f;
        float rangeMul = lost.contains(WeaponSubPart.BARREL) ? BARREL_LOST_RANGE_MUL : 1f;
        float intervalMul = lost.contains(WeaponSubPart.BREECH) ? BREECH_LOST_INTERVAL_MUL : 1f;
        boolean feedLost = lost.contains(WeaponSubPart.FEED);
        // MUZZLE, GEAR, SIGHT and FURNITURE reach here and change nothing, which is the table's
        // answer for them. GEAR does freeze the ELEVATE articulation, but that is cosmetic and the
        // client reads the damage state for itself (D17-R15, G6).
        return new Penalties(disabled, spreadMul, rangeMul, intervalMul, feedLost);
    }

    /** How many rounds a weapon of this capacity keeps when its feed is gone (D17-R61). */
    public static int chamberedRounds(int ammoCapacity) {
        if (ammoCapacity < 0) {
            // Unlimited stays unlimited: a weapon with no ammunition model has no feed to lose.
            return -1;
        }
        return Math.max(FEED_LOST_MIN_CHAMBERED, Math.round(ammoCapacity * FEED_LOST_CHAMBERED_FRACTION));
    }

    /**
     * Collects the sub-part labels missing from beneath a mount, walking what the parts declare.
     *
     * <p>{@code occupancy} answers, for a slot path, whether a live part is sitting there — where
     * "live" excludes {@code DESTROYED} and {@code DETACHED}, exactly as {@code VehicleStatsSystem}
     * judges it everywhere else. {@code partTypes} resolves the part type at a slot path so the walk
     * can descend: a barrel declares {@code sub_muzzle}, and the muzzle is only reachable through it.
     *
     * <p><b>A lost sub-part's own children are not separately reported.</b> If the receiver is gone
     * then the barrel it carried is gone with it, and counting both would apply the barrel's
     * accuracy penalty to a weapon that has already stopped firing. The walk simply does not descend
     * past a loss — the subtree rule of D07-S5.7 expressed as a traversal.
     */
    public static Set<WeaponSubPart> lostBeneath(
            String mountSlotPath, PartType mountType, SlotOccupancy occupancy, SlotPartTypes partTypes) {

        Set<WeaponSubPart> lost = EnumSet.noneOf(WeaponSubPart.class);
        if (mountSlotPath == null || mountType == null) {
            return lost;
        }
        collect(mountSlotPath, mountType, occupancy, partTypes, lost);
        return lost;
    }

    private static void collect(
            String parentPath,
            PartType parentType,
            SlotOccupancy occupancy,
            SlotPartTypes partTypes,
            Set<WeaponSubPart> lost) {

        // Ascending slot id: PartType.slots() is already sorted, and the result is a set, but the
        // order still has to be fixed because two peers must agree on it byte for byte (G3).
        for (Map.Entry<String, SlotDefinition> entry : parentType.slots().entrySet()) {
            String slotId = entry.getKey();
            WeaponSubPart label = WeaponSubPart.fromSlotId(slotId);
            if (label == null) {
                continue;
            }
            String childPath = parentPath + "/" + slotId;
            if (!occupancy.isLive(childPath)) {
                lost.add(label);
                continue;
            }
            PartType childType = partTypes.at(childPath);
            if (childType != null) {
                collect(childPath, childType, occupancy, partTypes, lost);
            }
        }
    }

    /** Whether a live, undestroyed part occupies a slot path. */
    @FunctionalInterface
    public interface SlotOccupancy {
        boolean isLive(String slotPath);
    }

    /** The part type at a slot path, or null when nothing occupies it. */
    @FunctionalInterface
    public interface SlotPartTypes {
        PartType at(String slotPath);
    }
}

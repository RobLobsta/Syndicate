/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;

/**
 * How much of a vehicle is left, as one number
 * (docs/07_damage_destruction_model.md#D07-S5.3, docs/11_ai_bots_and_match_simulation.md#D11-S4.3).
 *
 * <p>Three callers want the same walk and would otherwise each write it: the win condition's "most
 * intact vehicle" tie-break (D01-S5.5), a bot's own {@code selfIntegrity}, and the {@code integrity}
 * it perceives on a target. Having one definition matters more than the six lines it saves — a bot
 * that measured integrity differently from the rules would retreat at a health level the match does
 * not agree exists.
 *
 * <p><b>It is a sum over live parts, not over the assembly.</b> A vehicle that has lost a wheel has
 * lost that wheel's hit points from both the numerator and the denominator, so integrity measures
 * "how damaged is what I still have" rather than "how much of the original car is left". The second
 * reading would put a stripped-but-healthy vehicle permanently in retreat.
 */
public final class VehicleIntegrity {

    private VehicleIntegrity() {
        throw new AssertionError("no instances");
    }

    /**
     * The vehicle's aggregate health fraction in {@code [0,1]}.
     *
     * @return 0 for a vehicle that does not exist or has no parts with hit points
     */
    public static float fraction(World world, int vehicleEntity) {
        if (vehicleEntity == EntityId.NULL || !world.isAlive(vehicleEntity)) {
            return 0f;
        }
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        float current = 0f;
        float max = 0f;
        if (chassis != null) {
            HealthComponent health = healthOf(world, chassis.chassisPartEntity);
            if (health != null) {
                current += Math.max(0f, health.currentHp);
                max += health.maxHp;
            }
        }
        if (graph != null) {
            for (SlotNode node : graph.nodes) {
                HealthComponent health = healthOf(world, node.childEntity);
                if (health != null) {
                    current += Math.max(0f, health.currentHp);
                    max += health.maxHp;
                }
            }
        }
        return max <= 0f ? 0f : Math.min(1f, current / max);
    }

    /** The sum of the vehicle's live parts' current hit points. */
    public static float remainingHp(World world, int vehicleEntity) {
        if (vehicleEntity == EntityId.NULL || !world.isAlive(vehicleEntity)) {
            return 0f;
        }
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        float total = 0f;
        if (chassis != null) {
            HealthComponent health = healthOf(world, chassis.chassisPartEntity);
            total += health == null ? 0f : Math.max(0f, health.currentHp);
        }
        if (graph != null) {
            for (SlotNode node : graph.nodes) {
                HealthComponent health = healthOf(world, node.childEntity);
                total += health == null ? 0f : Math.max(0f, health.currentHp);
            }
        }
        return total;
    }

    private static HealthComponent healthOf(World world, int partEntity) {
        if (partEntity == EntityId.NULL || !world.isAlive(partEntity)) {
            return null;
        }
        return world.getComponent(partEntity, HealthComponent.class);
    }
}

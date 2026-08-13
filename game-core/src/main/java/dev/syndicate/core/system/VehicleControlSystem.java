/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.VehicleControl;

/**
 * Schedule slot 7: turns a driver's intent into forces on the ray-cast vehicle
 * (docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>This is the system that makes a vehicle <em>move</em>. Everything before it describes a vehicle;
 * this one drives it. It reads {@code PlayerInputComponent} — written by a human's client, by the
 * authority's input receiver, or by a bot, and indistinguishable downstream, which is what makes G17
 * hold for AI — and the aggregated {@code VehicleStatsComponent} that slot 6 produced earlier in the
 * same tick.
 *
 * <p>It runs in SIM, before {@code PhysicsSystem} (10). Bullet clears accumulated forces at the end
 * of each {@code stepSimulation}, so the engine force, brake and downforce applied here are consumed
 * by exactly one step and never carried into a second (G2).
 *
 * <p>The arithmetic itself lives in {@link VehicleControl}, a shared operation, because slot 20
 * replays it during reconciliation and a system may not call a system (D04-R13, DEC-061). This class
 * is the schedule slot, the family, and nothing else.
 */
public final class VehicleControlSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 7;

    /** @see VehicleControl#MAX_VEHICLE_SPEED_MPS */
    public static final float MAX_VEHICLE_SPEED_MPS = VehicleControl.MAX_VEHICLE_SPEED_MPS;

    /** @see VehicleControl#DOWNFORCE_COEFFICIENT */
    public static final float DOWNFORCE_COEFFICIENT = VehicleControl.DOWNFORCE_COEFFICIENT;

    /** @see VehicleControl#MIN_POWER_LIMIT_SPEED_MPS */
    public static final float MIN_POWER_LIMIT_SPEED_MPS = VehicleControl.MIN_POWER_LIMIT_SPEED_MPS;

    private final VehicleControl control = new VehicleControl();

    private Family vehicles;

    @Override
    public Phase phase() {
        return Phase.SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        vehicles = world.family(ComponentQuery.all(
                VehicleChassisComponent.class, PlayerInputComponent.class, VehicleStatsComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            control.drive(world, entityIds[i], dtSeconds);
        }
    }

    /** @see VehicleControl#availableTractiveForceN */
    public static float availableTractiveForceN(VehicleStatsComponent stats, float speedMps) {
        return VehicleControl.availableTractiveForceN(stats, speedMps);
    }

    /** @see VehicleControl#moveToward */
    static float moveToward(float current, float target, float maxDelta) {
        return VehicleControl.moveToward(current, target, maxDelta);
    }
}

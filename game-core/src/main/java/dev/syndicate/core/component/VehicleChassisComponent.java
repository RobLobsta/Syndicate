/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.SimulationConstants;
import java.util.Arrays;

/**
 * The root of a vehicle (docs/04_entity_component_model.md#D04-S4.3.2).
 *
 * <p>A vehicle is one rigid body with a compound shape, not one body per part (DEC-004), so this
 * component is where the vehicle-wide mass properties live: {@link #totalMassKg} and
 * {@link #comLocal} are recomputed by {@code MassPropertySystem} in the same tick as any attach or
 * detach, which is G10.
 *
 * <p><b>Native ownership (G19).</b> {@link #vehicleController} is owned by {@code PhysicsWorld},
 * not by this component — the ray-cast vehicle must be removed from the world before the chassis
 * body it wraps is disposed, and only the world knows that ordering (D02-S5.7 rule 5).
 */
public final class VehicleChassisComponent implements Component {

    /** Which assembly was spawned. */
    public AssetId assemblyId;

    /** The root part entity — the chassis part itself, which never detaches. */
    public int chassisPartEntity = EntityId.NULL;

    /** Kilograms, summed over every attached part. Recomputed on any structural change (G10). */
    public float totalMassKg;

    /** Centre of mass in chassis-local space, metres. Recomputed alongside {@link #totalMassKg}. */
    public final Vector3 comLocal = new Vector3();

    /**
     * Wheel part entities, ordered so that the array index equals the Bullet wheel index. The
     * ordering is load-bearing: {@code VehicleControlSystem} applies engine force and steering by
     * Bullet index, so a reordering silently steers the wrong wheels.
     */
    public final int[] wheelEntities = new int[MAX_WHEELS];

    /** How many entries of {@link #wheelEntities} are live. */
    public int wheelCount;

    /**
     * Radians. The steering angle currently applied to the steered wheels.
     *
     * <p>D06-S5.5's control loop rate-limits steering toward its target, which needs the angle it
     * reached last tick. That state cannot live in {@code VehicleControlSystem}: D04-R3 keeps
     * systems stateless with respect to gameplay, and a field on the system would be invisible to
     * replication and to a client rewinding across a reconciliation — the vehicle would come out of
     * the rewind steering wherever the un-rewound field happened to be pointing (DEC-026).
     *
     * <p>It is here rather than on {@code VehicleStatsComponent} because stat aggregation rebuilds
     * that component from the parts every time it runs (D05-R27), and control state is not derived
     * from parts.
     */
    public float currentSteerRad;

    /** OWNER: {@code PhysicsWorld} (D06-S5.5). Never disposed from here. */
    public btRaycastVehicle vehicleController;

    /**
     * Upper bound on wheels per vehicle. Sized from the parts-per-vehicle limit rather than the
     * 4–6 of the D05-S4.3 table, so an exotic chassis cannot overflow the array.
     */
    public static final int MAX_WHEELS = SimulationConstants.MAX_PARTS_PER_VEHICLE;

    @Override
    public void reset() {
        assemblyId = null;
        chassisPartEntity = EntityId.NULL;
        totalMassKg = 0f;
        comLocal.set(0f, 0f, 0f);
        Arrays.fill(wheelEntities, EntityId.NULL);
        wheelCount = 0;
        currentSteerRad = 0f;
        vehicleController = null;
    }
}

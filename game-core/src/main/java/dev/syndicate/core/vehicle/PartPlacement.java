/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.World;

/**
 * Where a vehicle's parts are in the world, and how fast they are moving
 * (docs/06_physics_simulation.md#D06-S5.7, docs/07_damage_destruction_model.md#D07-S5.7).
 *
 * <p>An attached part has no body of its own — it is geometry inside the vehicle's compound
 * (DEC-004) — so both of those are derived, and both are derived the same way by every system that
 * turns a part into a world object. They live here so the derivation exists once.
 *
 * <p><b>The recentring term is the part that is easy to omit.</b> {@code MassPropertySystem} moves a
 * vehicle's compound so its local origin sits on the centre of mass (D06-S5.7 step 2), which means
 * the body's transform maps <em>centre-of-mass-local</em> space to the world, not chassis-local
 * space. Composing a slot chain straight onto the body transform therefore places every part off by
 * the vehicle's COM: subtle on an intact vehicle, obvious on a badly damaged one, and never obvious
 * in the code that omitted it.
 */
public final class PartPlacement {

    private PartPlacement() {
        throw new AssertionError("no instances");
    }

    /**
     * Writes the chassis-local → world transform of a vehicle, and its world centre of mass.
     *
     * <p>The world COM is the body's world translation with no second derivation: the body's origin
     * <em>is</em> its centre of mass, which is exactly what the recentring of D06-S5.7 arranges. A
     * separately computed COM would be free to fall out of step with the transform it belongs to.
     *
     * @param outChassisToWorld receives the transform that maps a part's chassis-local placement
     *     (its accumulated {@link SlotChain} transform) into world space
     * @param outComWorld receives the vehicle's centre of mass in world space
     * @return false when the vehicle has no body to derive either from, in which case neither output
     *     is written
     */
    public static boolean chassisToWorld(
            World world, int vehicleEntity, Matrix4 outChassisToWorld, Vector3 outComWorld) {
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (rigidBody == null || rigidBody.body == null || chassis == null) {
            return false;
        }
        rigidBody.body.getWorldTransform(outChassisToWorld);
        outChassisToWorld.getTranslation(outComWorld);
        // Post-multiplying by the inverse of the COM offset undoes the recentring, so what is left
        // maps chassis-local space — the space slot chains are expressed in — to the world.
        outChassisToWorld.translate(-chassis.comLocal.x, -chassis.comLocal.y, -chassis.comLocal.z);
        return true;
    }

    /**
     * The velocity of a point rigidly attached to a body: {@code v + ω × (p − com)}.
     *
     * <p>This is what a detaching part or a fracturing shard leaves with (D05-R23, D07-S5.7). Using
     * the body's linear velocity alone would give every part of a spinning vehicle the same
     * velocity and throw away the rotational component — which is the component that makes debris
     * off a spinning wreck look right.
     *
     * @param out receives the result; may alias neither {@code linear} nor {@code angular}
     * @return {@code out}
     */
    public static Vector3 velocityAt(
            Vector3 linear, Vector3 angular, Vector3 comWorld, Vector3 pointWorld, Vector3 out) {
        float rx = pointWorld.x - comWorld.x;
        float ry = pointWorld.y - comWorld.y;
        float rz = pointWorld.z - comWorld.z;
        return out.set(
                linear.x + angular.y * rz - angular.z * ry,
                linear.y + angular.z * rx - angular.x * rz,
                linear.z + angular.x * ry - angular.y * rx);
    }
}

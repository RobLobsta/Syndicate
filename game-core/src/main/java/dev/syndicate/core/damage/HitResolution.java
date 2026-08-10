/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.VehicleCompound;
import dev.syndicate.core.vehicle.PartPlacement;
import dev.syndicate.core.vehicle.SlotChain;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns "something was hit here" into "this part was hit"
 * (docs/07_damage_destruction_model.md#D07-S5.1).
 *
 * <p>There are no abstract hit zones in this game: <b>the hit zone is the part that was hit</b>
 * (D01-R10). A vehicle is one rigid body whose shape is a compound of its parts' hulls (DEC-004),
 * so a contact already carries the answer as a compound child index, and this class is the mapping
 * from that index through {@link VehicleCompound} to a part entity — plus the two corrections that
 * the raw geometry does not give you.
 *
 * <p><b>Correction one: wheels are not in the compound.</b> A wheel is a ray cast and contributes no
 * collision geometry (D06-R6), so nothing can ever report a compound child index for one. D07-R11
 * makes that the single explicit geometric special case: a hit within
 * {@link #WHEEL_HIT_MARGIN_M} of a wheel's own position resolves to the wheel. It is checked before
 * the compound, because a hit near a wheel arch is far more likely to be the wheel than the arch.
 *
 * <p><b>Correction two: armour intercepts.</b> A live plate that covers the struck slot takes the
 * damage instead, and once it is gone the slot behind it is {@code EXPOSED} (D05-S5.8). Both come
 * from a {@link CoverageMap} the caller supplies, so the map is rebuilt once per struck vehicle
 * rather than once per contact point.
 *
 * <p>Instance rather than static because it carries scratch: a contact is resolved several times per
 * tick in a busy collision, and allocating three matrices per contact point is exactly the garbage a
 * fixed-timestep simulation should not be making.
 */
public final class HitResolution {

    private static final Logger LOG = LoggerFactory.getLogger(HitResolution.class);

    /**
     * Metres added to a wheel's radius to decide whether a hit is on that wheel (D07-R11).
     *
     * <p>{@code WHEEL_HIT_RADIUS = wheelRadius + 0.1 m} in D07-S5.1; this is the {@code 0.1}.
     */
    public static final float WHEEL_HIT_MARGIN_M = 0.1f;

    /** Degrees within which a contact normal counts as a rear hit — ×1.35 (D01-R11). */
    public static final float REAR_CONE_DEG = 60f;

    /** Degrees within which a contact normal counts as a top hit — ×1.20 (D01-R11). */
    public static final float TOP_CONE_DEG = 45f;

    /** The rear positional multiplier (D07-R9). */
    public static final float REAR_MULTIPLIER = 1.35f;

    /** The top positional multiplier (D07-R9). */
    public static final float TOP_MULTIPLIER = 1.20f;

    /** The exposed positional multiplier (D07-R9), and the design payoff of armour (D01-R12). */
    public static final float EXPOSED_MULTIPLIER = 1.50f;

    /**
     * Where a hit landed and on what.
     *
     * @param partEntity the part that takes the damage, after coverage interception
     * @param exposed whether that part's slot has lost its covering armour (D05-S5.8)
     * @param intercepted true when armour took a hit aimed at something behind it
     */
    public record Resolved(int partEntity, boolean exposed, boolean intercepted) {

        /** Nothing was hit — no part could be resolved and the hit is dropped. */
        public static final Resolved NONE = new Resolved(EntityId.NULL, false, false);

        /** Whether a part was found at all. */
        public boolean hasPart() {
            return partEntity != EntityId.NULL;
        }
    }

    private final ShapeCache shapes;

    private final Matrix4 scratchChassisToWorld = new Matrix4();
    private final Matrix4 scratchPartToWorld = new Matrix4();
    private final Vector3 scratchComWorld = new Vector3();
    private final Vector3 scratchPartWorld = new Vector3();
    private final Vector3 scratchForward = new Vector3();

    public HitResolution(ShapeCache shapes) {
        this.shapes = Objects.requireNonNull(shapes, "shapes");
    }

    /**
     * Resolves a contact on a vehicle to the part that takes the damage (D07-S5.1).
     *
     * @param vehicleEntity the struck vehicle
     * @param childIndex the compound child index the contact carried, or {@code -1} when the caller
     *     has none — a ray hit reports the object, not the child
     * @param hitPointWorld where the contact is, world space
     * @param coverage the struck vehicle's coverage map, already rebuilt for this tick
     */
    public Resolved resolve(
            World world, int vehicleEntity, int childIndex, Vector3 hitPointWorld, CoverageMap coverage) {

        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (chassis == null || graph == null) {
            // Not a vehicle: a prop, or debris. The struck object is itself the target (D07-S5.1).
            return new Resolved(vehicleEntity, false, false);
        }
        SlotChain chain = SlotChain.of(graph, chassis);

        int wheelEntity = wheelAt(world, chassis, chain, vehicleEntity, hitPointWorld);
        if (wheelEntity != EntityId.NULL) {
            return intercept(world, wheelEntity, coverage);
        }

        String slotPath = slotPathAt(vehicleEntity, childIndex);
        Integer struckPart = slotPath == null ? null : chain.entityAt(slotPath);
        if (struckPart == null) {
            struckPart = nearestPartByCentroid(world, vehicleEntity, chassis, chain, hitPointWorld);
            if (struckPart == null) {
                return Resolved.NONE;
            }
            if (childIndex >= 0) {
                // The caller had an index and it addressed nothing: the map is stale, which
                // D06-R14 says cannot happen. The hit is still never dropped (D07-E13), but the
                // discrepancy is worth an error. A caller with no index — a ray test, which
                // gdx-bullet does not expose a child shape index for — is the ordinary case and
                // reaches the same fallback silently.
                LOG.error(
                        "no part at compound child index {} on vehicle {}; used the centroid fallback of D07-E13",
                        childIndex,
                        EntityId.toString(vehicleEntity));
            }
        }
        return intercept(world, struckPart, coverage);
    }

    /**
     * The positional damage multiplier for a hit (D07-S5.1 {@code positionalModifiers}, D01-R11).
     *
     * <p>Rear and top are read off the contact normal against the vehicle's own axes rather than
     * against the attacker's position, so a shot that skims round a corner and lands on the back
     * plate is a rear hit whoever fired it. The three multiply: a rear top hit on an exposed part is
     * ×2.43 (AC-D07-4).
     *
     * @param hitNormalWorld the contact normal, world space; need not be normalised
     * @param exposed whether the struck slot has lost its covering armour
     */
    public float positionalMultiplier(World world, int vehicleEntity, Vector3 hitNormalWorld, boolean exposed) {
        float multiplier = 1f;
        if (hitNormalWorld.len2() > 0f) {
            scratchForward.set(hitNormalWorld).nor();
            if (vehicleForward(world, vehicleEntity)) {
                // The contact normal on a rear hit points forward along the vehicle: into the back
                // panel is out of the front. Comparing against +forward is therefore the rear test.
                float cosForward = scratchForward.dot(scratchPartWorld);
                if (cosForward >= (float) Math.cos(Math.toRadians(REAR_CONE_DEG))) {
                    multiplier *= REAR_MULTIPLIER;
                }
            }
            if (scratchForward.dot(Vector3.Y) >= (float) Math.cos(Math.toRadians(TOP_CONE_DEG))) {
                multiplier *= TOP_MULTIPLIER;
            }
        }
        if (exposed) {
            multiplier *= EXPOSED_MULTIPLIER;
        }
        return multiplier;
    }

    // ---- Internals -------------------------------------------------------------------

    /** Applies the coverage interception and exposure tests of D05-S5.8 to a struck part. */
    private Resolved intercept(World world, int struckPart, CoverageMap coverage) {
        PartRefComponent partRef = world.getComponent(struckPart, PartRefComponent.class);
        if (partRef == null) {
            return new Resolved(struckPart, false, false);
        }
        int covering = coverage.coveringPartOf(partRef.slotPath);
        if (covering != EntityId.NULL && covering != struckPart && world.isAlive(covering)) {
            // The plate is alive, so it eats the hit; it cannot itself be exposed, because something
            // covering something else is by definition the outermost layer.
            return new Resolved(covering, false, true);
        }
        return new Resolved(struckPart, coverage.isExposed(partRef.slotPath), false);
    }

    /** The slot path at a compound child index, or null when there is no compound or no such child. */
    private String slotPathAt(int vehicleEntity, int childIndex) {
        if (childIndex < 0) {
            return null;
        }
        VehicleCompound compound = shapes.vehicleCompound(vehicleEntity);
        if (compound == null || childIndex >= compound.childCount()) {
            return null;
        }
        return compound.slotPathAt(childIndex);
    }

    /**
     * The wheel a hit landed on, or {@link EntityId#NULL} (D07-R11).
     *
     * <p>A wheel's world position is its slot-chain placement on the chassis, not its contact patch:
     * the ray cast decides where the tyre <em>touches</em>, while the part sits where the assembly
     * put it. Using the placement keeps the test independent of whether the wheel is currently on
     * the ground, which a wheel taking fire in mid-air very much is not.
     */
    private int wheelAt(
            World world, VehicleChassisComponent chassis, SlotChain chain, int vehicleEntity, Vector3 hitPointWorld) {

        if (chassis.wheelCount == 0
                || !PartPlacement.chassisToWorld(world, vehicleEntity, scratchChassisToWorld, scratchComWorld)) {
            return EntityId.NULL;
        }
        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            if (wheelEntity == EntityId.NULL || !world.isAlive(wheelEntity)) {
                continue;
            }
            WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
            String slotPath = chain.slotPathOf(wheelEntity);
            if (wheel == null || slotPath == null) {
                continue;
            }
            Matrix4 chainTransform = chain.transformOf(slotPath);
            if (chainTransform == null) {
                continue;
            }
            scratchPartToWorld.set(scratchChassisToWorld).mul(chainTransform);
            scratchPartToWorld.getTranslation(scratchPartWorld);
            float radius = wheel.radiusM + WHEEL_HIT_MARGIN_M;
            if (scratchPartWorld.dst2(hitPointWorld) <= radius * radius) {
                return wheelEntity;
            }
        }
        return EntityId.NULL;
    }

    /**
     * The part whose placement is closest to the hit (D07-E13).
     *
     * <p>The defensive path: the compound's index map should never be stale (D06-R14), and a hit is
     * never dropped when it is. Dropping one would make a vehicle invulnerable from an angle for a
     * reason no player could see.
     */
    private Integer nearestPartByCentroid(
            World world, int vehicleEntity, VehicleChassisComponent chassis, SlotChain chain, Vector3 hitPointWorld) {

        if (!PartPlacement.chassisToWorld(world, vehicleEntity, scratchChassisToWorld, scratchComWorld)) {
            return chassis.chassisPartEntity == EntityId.NULL ? null : chassis.chassisPartEntity;
        }
        Integer nearest = null;
        float nearestDistance2 = Float.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            Matrix4 chainTransform = chain.transformOf(entry.getKey());
            if (chainTransform == null || !world.isAlive(entry.getValue())) {
                continue;
            }
            scratchPartToWorld.set(scratchChassisToWorld).mul(chainTransform);
            scratchPartToWorld.getTranslation(scratchPartWorld);
            float distance2 = scratchPartWorld.dst2(hitPointWorld);
            // Ties break toward the lower slot path, which is the iteration order of a SlotChain (G3).
            if (distance2 < nearestDistance2) {
                nearestDistance2 = distance2;
                nearest = entry.getValue();
            }
        }
        return nearest;
    }

    /**
     * Writes the vehicle's world forward axis into {@code scratchPartWorld}.
     *
     * <p>Forward is {@code -Z} in local space (D00-R15), so the world forward is the negated third
     * column of the body transform.
     *
     * @return false when the vehicle has no body to read an orientation from
     */
    private boolean vehicleForward(World world, int vehicleEntity) {
        if (!PartPlacement.chassisToWorld(world, vehicleEntity, scratchChassisToWorld, scratchComWorld)) {
            return false;
        }
        float[] m = scratchChassisToWorld.val;
        scratchPartWorld.set(-m[Matrix4.M02], -m[Matrix4.M12], -m[Matrix4.M22]).nor();
        return true;
    }
}

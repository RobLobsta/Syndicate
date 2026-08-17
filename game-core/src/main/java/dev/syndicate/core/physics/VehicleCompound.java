/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCompoundShape;
import dev.syndicate.core.asset.MeshData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A vehicle's compound collision shape and the map from slot path to child index
 * (docs/06_physics_simulation.md#D06-S5.3).
 *
 * <p>A vehicle is one rigid body whose shape is a compound of its parts' hulls (DEC-004), so "which
 * part was hit" is answered by a child index, and "remove this part" is a child removal. Both of
 * those need a mapping between the compound's positional child indices and the slot paths that are
 * a part's stable identity (D05-R11) — that mapping is this class.
 *
 * <p><b>Child indices are positional and shift on removal (D06-R14).</b>
 * {@code removeChildShapeByIndex} moves the <em>last</em> child into the removed slot rather than
 * shifting the tail down, exactly like {@code btRaycastVehicle}'s wheel indices (D05-R24). Any index
 * cached across a structural change therefore addresses a different part, silently. This class
 * mirrors that swap in its own list inside {@link #removeChild}, so the map is rebuilt as part of
 * the removal and there is no window in which it is stale (AC-D06-7); nothing outside may hold a
 * child index across a call to it.
 *
 * <p><b>Native ownership (G19).</b> {@link ShapeCache} owns the {@code btCompoundShape} and the
 * child hulls. This object is a handle over them and disposes nothing.
 */
public final class VehicleCompound {

    /**
     * One part's contribution to the compound.
     *
     * @param slotPath the part's stable identity within the assembly (D05-R11)
     * @param hullKey which cached hull the part uses
     * @param hullMesh the mesh to build that hull from, if it is not cached yet
     * @param localTransform the part's placement in chassis-local space, accumulated down the slot
     *     chain
     */
    public record Child(String slotPath, ShapeCacheKey hullKey, MeshData hullMesh, Matrix4 localTransform) {
        public Child {
            Objects.requireNonNull(slotPath, "slotPath");
            Objects.requireNonNull(hullKey, "hullKey");
            Objects.requireNonNull(hullMesh, "hullMesh");
            localTransform = new Matrix4(localTransform);
        }
    }

    private final ShapeCacheKey key;
    private final btCompoundShape compound;

    /**
     * Slot path per child index, parallel to the compound's children. A list rather than a map from
     * index to path because it <em>is</em> the positional structure: mirroring Bullet's swap is one
     * {@code set} plus one {@code remove} of the tail, which is hard to get wrong.
     */
    private final List<String> slotPathByChildIndex = new ArrayList<>();

    private final Matrix4 scratchTransform = new Matrix4();

    private VehicleCompound(ShapeCacheKey key, btCompoundShape compound) {
        this.key = key;
        this.compound = compound;
    }

    /**
     * Builds the compound of D06-S5.3.
     *
     * <p>Children are added in ascending slot path order so that the index assignment is a function
     * of the assembly alone, not of the order parts happened to be attached in (G3). Two peers
     * spawning the same assembly therefore agree on which child index means which part, which is
     * what lets a hit be replicated as a child index at all (D07-S5.1).
     *
     * <p>Wheels are excluded by the caller: they are ray casts, not bodies, and contribute no
     * collision geometry (D06-R6).
     */
    public static VehicleCompound build(ShapeCache cache, ShapeCacheKey compoundKey, List<Child> children) {
        VehicleCompound vehicleCompound = new VehicleCompound(compoundKey, cache.newCompound(compoundKey));
        List<Child> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparing(Child::slotPath));
        for (int i = 0; i < sorted.size(); i++) {
            Child child = sorted.get(i);
            if (i > 0 && child.slotPath().equals(sorted.get(i - 1).slotPath())) {
                throw new IllegalArgumentException("two parts claim slot path " + child.slotPath());
            }
            vehicleCompound.compound.addChildShape(
                    child.localTransform(), cache.hullFor(child.hullKey(), child.hullMesh()));
            vehicleCompound.slotPathByChildIndex.add(child.slotPath());
        }
        vehicleCompound.compound.recalculateLocalAabb();
        return vehicleCompound;
    }

    /** The key this compound is cached under. */
    public ShapeCacheKey key() {
        return key;
    }

    /** The Bullet shape. OWNER: {@link ShapeCache}. */
    public btCompoundShape compound() {
        return compound;
    }

    /** How many parts contribute geometry. */
    public int childCount() {
        return slotPathByChildIndex.size();
    }

    /** The child index of a slot path, or {@code -1} if that part is not in the compound. */
    public int childIndexOf(String slotPath) {
        return slotPathByChildIndex.indexOf(slotPath);
    }

    /** The slot path at a child index. */
    public String slotPathAt(int childIndex) {
        return slotPathByChildIndex.get(childIndex);
    }

    /** The slot paths in current child-index order. For assertions and diagnostics. */
    public List<String> slotPaths() {
        return List.copyOf(slotPathByChildIndex);
    }

    /**
     * Removes a part's geometry and rebuilds the index map in the same operation (D06-R14).
     *
     * <p>The rebuild is not a separate step a caller could forget: Bullet swaps its last child into
     * the freed index, and this mirrors that swap before returning, so the map is never observably
     * out of date. The compound's local AABB is recalculated too — a stale AABB leaves the vehicle
     * with a broadphase proxy covering armour it no longer has, and the phantom contacts that
     * produces look like a physics bug rather than a bookkeeping one.
     *
     * @return true if the part was present
     */
    public boolean removeChild(String slotPath) {
        int index = slotPathByChildIndex.indexOf(slotPath);
        if (index < 0) {
            return false;
        }
        compound.removeChildShapeByIndex(index);

        int last = slotPathByChildIndex.size() - 1;
        if (index != last) {
            slotPathByChildIndex.set(index, slotPathByChildIndex.get(last));
        }
        slotPathByChildIndex.remove(last);

        compound.recalculateLocalAabb();
        return true;
    }

    /**
     * Translates every child by {@code -delta}, moving the compound's origin onto a new centre of
     * mass (D06-S5.7 step 2).
     *
     * <p>Bullet treats a compound's local origin as the body's centre of mass. A vehicle whose
     * compound is not recentred rotates about its mesh origin instead — the classic "car pivots
     * around its nose" bug — and the further the COM is from the origin, the more wrong it looks.
     *
     * <p>The local AABB is recalculated once at the end rather than per child, which is why each
     * {@code updateChildTransform} is told not to.
     */
    public void recentre(float deltaX, float deltaY, float deltaZ) {
        for (int i = 0; i < compound.getNumChildShapes(); i++) {
            // getChildTransform hands back a shared temporary; copying it before mutating is not
            // optional in gdx-bullet.
            scratchTransform.set(compound.getChildTransform(i));
            scratchTransform.val[Matrix4.M03] -= deltaX;
            scratchTransform.val[Matrix4.M13] -= deltaY;
            scratchTransform.val[Matrix4.M23] -= deltaZ;
            compound.updateChildTransform(i, scratchTransform, false);
        }
        compound.recalculateLocalAabb();
    }

    /**
     * The lowest point of the whole compound, in the compound's own local space.
     *
     * <p>Called after {@link #recentre}, so the compound's origin is the vehicle's centre of mass
     * and this is a signed offset from it — negative for every vehicle, because a centre of mass is
     * above the ground. It is what {@code VehicleFactory} needs to know how far a spawning vehicle
     * has to be raised to stand on the terrain rather than inside it.
     */
    public float lowestPointY() {
        localAabb(scratchAabbMin, scratchAabbMax);
        return scratchAabbMin.y;
    }

    /**
     * The compound's own axis-aligned bounds, in its local space.
     *
     * <p>Called after {@link #recentre}, so these are relative to the vehicle's centre of mass.
     */
    public void localAabb(Vector3 outMin, Vector3 outMax) {
        compound.getAabb(IDENTITY, outMin, outMax);
    }

    private final Vector3 scratchAabbMin = new Vector3();
    private final Vector3 scratchAabbMax = new Vector3();

    /** Reused for the local-space AABB query above; never mutated. */
    private static final Matrix4 IDENTITY = new Matrix4();

    @Override
    public String toString() {
        return "VehicleCompound[" + key + ", " + slotPathByChildIndex + "]";
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.component.RenderTransformComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;

/**
 * Schedule slot 22: places every drawn entity between the last two ticks
 * (docs/04_entity_component_model.md#D04-S4.4 row 22, docs/03_runtime_modes.md#D03-S5.3).
 *
 * <p>The simulation advances in whole {@code TICK_DT} steps (G2) and frames do not land on tick
 * boundaries, so drawing {@code TransformComponent.worldMatrix} directly shows a world that stands
 * still for a frame and then jumps. This system samples that matrix once per tick and rebuilds a
 * render transform {@code alpha} of the way from the previous sample to the current one, every
 * frame. Sixty ticks a second become as many distinct positions as the display can show.
 *
 * <p><b>This is not the snapshot interpolation of D10-S5.6.</b> That one buffers <em>remote</em>
 * transforms arriving at the snapshot rate and plays them back behind a delay, and it needs a
 * transport to have anything to buffer. This one interpolates locally simulated state between the
 * ticks this process itself ran, which is what a single-player or listen-server client needs and
 * what D03-S5.3's {@code alpha} is defined for. {@code InterpolationComponent}'s ring buffer stays
 * empty and unused until slot 19 exists to fill it; the two are complementary rather than
 * alternatives, and this is recorded in DEC-050.
 *
 * <p>Every entity carrying a {@link TransformComponent} is interpolated, parts included, rather than
 * only the physics roots. A part's world matrix is recomposed from its parent every tick by slot 21,
 * so interpolating it independently lands it in the same place as interpolating its parent and
 * recomposing — without this system needing to know the slot graph.
 */
public final class InterpolationSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 22;

    /**
     * Metres of movement in one tick past which the entity is snapped rather than interpolated.
     *
     * <p>A respawn, a teleport to a spawn point, or a body being repositioned is a discontinuity,
     * not motion, and smearing an entity across the arena over one frame reads as a rendering fault.
     * At 60 Hz this is 240 m/s, well above anything the simulation produces by driving.
     */
    public static final float SNAP_DISTANCE_M = 4f;

    private final Vector3 scratchPosition = new Vector3();
    private final Quaternion scratchRotation = new Quaternion();
    private final Vector3 scratchScale = new Vector3();

    private Family transforms;
    private Family unsampled;

    @Override
    public Phase phase() {
        return Phase.PRESENT;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        unsampled = world.family(ComponentQuery.all(TransformComponent.class).exclude(RenderTransformComponent.class));
        transforms = world.family(ComponentQuery.all(TransformComponent.class, RenderTransformComponent.class));
    }

    /**
     * @param dtSeconds the frame delta, unused here; this system's clock is {@code world.renderAlpha}
     */
    @Override
    public void update(World world, float dtSeconds, long tick) {
        attachToNewEntities(world);

        float alpha = world.renderAlpha();
        int[] entityIds = transforms.snapshot();
        int count = transforms.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
            RenderTransformComponent render = world.getComponent(entityId, RenderTransformComponent.class);
            if (transform == null || render == null) {
                continue;
            }
            sample(transform, render, tick);
            interpolate(render, alpha);
        }
    }

    /**
     * Gives a {@link RenderTransformComponent} to anything that has appeared since the last frame.
     *
     * <p>The client attaches its own cosmetic components rather than having the factories in
     * {@code game-core} attach them: {@code VehicleFactory} and {@code DebrisFactory} run on a
     * dedicated server too, and a server that built render state would be carrying G17's violation
     * in its own spawn path.
     */
    private void attachToNewEntities(World world) {
        int[] entityIds = unsampled.snapshot();
        int count = unsampled.size();
        for (int i = 0; i < count; i++) {
            world.addComponent(entityIds[i], new RenderTransformComponent());
        }
    }

    /**
     * Takes this tick's world transform, once.
     *
     * <p>Guarded on the tick number rather than run unconditionally, because this is a per-frame
     * system: sampling every frame would make the previous sample one frame old rather than one tick
     * old, and the interpolation would collapse to no interpolation at all.
     */
    private void sample(TransformComponent transform, RenderTransformComponent render, long tick) {
        if (render.sampledTick == tick) {
            return;
        }
        transform.worldMatrix.getTranslation(scratchPosition);
        transform.worldMatrix.getRotation(scratchRotation, true);
        transform.worldMatrix.getScale(scratchScale);

        if (render.sampledTick == Long.MIN_VALUE) {
            // First sighting: the entity has one position, so it is drawn at it.
            render.previousPosition.set(scratchPosition);
            render.previousRotation.set(scratchRotation);
            render.previousScale.set(scratchScale);
            render.hasPrevious = false;
        } else {
            render.previousPosition.set(render.currentPosition);
            render.previousRotation.set(render.currentRotation);
            render.previousScale.set(render.currentScale);
            render.hasPrevious = render.previousPosition.dst2(scratchPosition) <= SNAP_DISTANCE_M * SNAP_DISTANCE_M;
        }
        render.currentPosition.set(scratchPosition);
        render.currentRotation.set(scratchRotation);
        render.currentScale.set(scratchScale);
        render.sampledTick = tick;
    }

    private void interpolate(RenderTransformComponent render, float alpha) {
        if (!render.hasPrevious) {
            render.renderMatrix.set(render.currentPosition, render.currentRotation, render.currentScale);
            return;
        }
        scratchPosition.set(render.previousPosition).lerp(render.currentPosition, alpha);
        scratchRotation.set(render.previousRotation).slerp(render.currentRotation, alpha);
        scratchScale.set(render.previousScale).lerp(render.currentScale, alpha);
        render.renderMatrix.set(scratchPosition, scratchRotation, scratchScale);
    }
}

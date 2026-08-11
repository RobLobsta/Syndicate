/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.component;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;

/**
 * Where an entity is <em>drawn</em>, as opposed to where it is
 * (docs/03_runtime_modes.md#D03-S5.3).
 *
 * <p>Classified {@code C}: cosmetic, client-local, never replicated, and never read by a gameplay
 * system. The distinction it exists for is the whole of D03-R11. The simulation advances in whole
 * {@code TICK_DT} steps and a frame almost never lands on one, so a renderer reading
 * {@code TransformComponent.worldMatrix} directly shows the world at the last completed tick and
 * judders whenever the frame rate is not an exact multiple of 60 Hz. This component holds the two
 * samples between which the frame actually sits.
 *
 * <p>The samples are decomposed into position, rotation and scale at the moment they are taken
 * rather than kept as matrices, because a matrix cannot be interpolated: lerping sixteen components
 * of two rotations shears the result. Decomposing once per tick and slerping once per frame is both
 * correct and cheaper than the reverse.
 *
 * <p>It lives in {@code game-client} rather than {@code game-core} for the same reason
 * {@link RenderModelComponent} does — nothing without a renderer has any use for it, and a dedicated
 * server never loads the module (G17).
 */
public final class RenderTransformComponent implements Component {

    /** World-space translation at the end of the previous tick. */
    public final Vector3 previousPosition = new Vector3();

    /** World-space rotation at the end of the previous tick. */
    public final Quaternion previousRotation = new Quaternion();

    /** Scale at the end of the previous tick. */
    public final Vector3 previousScale = new Vector3(1f, 1f, 1f);

    /** World-space translation at the end of the current tick. */
    public final Vector3 currentPosition = new Vector3();

    /** World-space rotation at the end of the current tick. */
    public final Quaternion currentRotation = new Quaternion();

    /** Scale at the end of the current tick. */
    public final Vector3 currentScale = new Vector3(1f, 1f, 1f);

    /** The matrix the renderer draws with, rebuilt every frame from the pair above. */
    public final Matrix4 renderMatrix = new Matrix4();

    /** The tick {@link #currentPosition} was sampled on, so a frame samples at most once. */
    public long sampledTick = Long.MIN_VALUE;

    /** False until two samples exist; one sample can only be drawn at itself. */
    public boolean hasPrevious;

    @Override
    public void reset() {
        previousPosition.set(0f, 0f, 0f);
        previousRotation.idt();
        previousScale.set(1f, 1f, 1f);
        currentPosition.set(0f, 0f, 0f);
        currentRotation.idt();
        currentScale.set(1f, 1f, 1f);
        renderMatrix.idt();
        sampledTick = Long.MIN_VALUE;
        hasPrevious = false;
    }
}

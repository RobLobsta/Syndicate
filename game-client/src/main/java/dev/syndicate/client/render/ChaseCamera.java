/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * The camera that follows the car the player is driving (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>It trails the vehicle's <em>heading</em> rather than being rigidly bolted to it. A camera fixed
 * to the body rotates with every twitch of the chassis and makes a car that is merely lively look
 * uncontrollable; one that lags behind the heading and eases into place shows the car rotating
 * underneath it, which is what a driver reads as oversteer. The lag is a first-order ease with a
 * half-life, so it is frame-rate independent — a 144 Hz display and a 30 Hz one produce the same
 * camera, which matters because this camera is how the handling will be judged.
 *
 * <p>It also pulls back with speed. Widening the shot as the car accelerates is the cheapest
 * available cue for velocity and costs nothing in a scene with no speedometer in the driver's
 * eyeline.
 *
 * <p>Purely cosmetic (G6): nothing here is read by anything, and a headless process never
 * constructs it.
 */
public final class ChaseCamera {

    /** Metres behind the vehicle at rest. */
    public static final float DISTANCE_M = 8.5f;

    /** Extra metres of trail at top speed. */
    public static final float SPEED_DISTANCE_M = 4.0f;

    /** Metres above the vehicle's origin the camera sits. */
    public static final float HEIGHT_M = 3.2f;

    /** Metres above the vehicle's origin the camera looks at — its roof, not its floor. */
    public static final float LOOK_AT_HEIGHT_M = 1.1f;

    /** Seconds for the camera to close half the gap to where it should be. */
    public static final float FOLLOW_HALF_LIFE_S = 0.12f;

    /** Seconds for the camera's yaw to close half the gap to the vehicle's heading. */
    public static final float YAW_HALF_LIFE_S = 0.22f;

    /** Vertical field of view in degrees at rest. */
    public static final float FOV_DEG = 60f;

    /** Degrees of extra field of view at top speed, which reads as the world rushing past. */
    public static final float SPEED_FOV_DEG = 12f;

    private final PerspectiveCamera camera;
    private final Vector3 position = new Vector3();
    private final Vector3 target = new Vector3();
    private final Vector3 desired = new Vector3();

    private float yawRad;
    private boolean placed;
    private boolean hasPosition;

    public ChaseCamera(int viewportWidth, int viewportHeight) {
        camera = new PerspectiveCamera(FOV_DEG, viewportWidth, viewportHeight);
        camera.near = 0.15f;
        camera.far = 600f;
        camera.position.set(0f, HEIGHT_M, -DISTANCE_M);
        camera.lookAt(0f, LOOK_AT_HEIGHT_M, 0f);
        camera.update();
    }

    public PerspectiveCamera camera() {
        return camera;
    }

    /** Rebuilds the projection for a resized window. */
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    /**
     * Eases the camera toward a vehicle.
     *
     * @param vehiclePosition where the vehicle is this frame, already interpolated (slot 22)
     * @param headingRad the vehicle's yaw, which the camera trails rather than matches
     * @param speedFraction road speed over top speed, in {@code [0,1]}
     * @param dtSeconds real frame time
     */
    public void follow(Vector3 vehiclePosition, float headingRad, float speedFraction, float dtSeconds) {
        float speed = MathUtils.clamp(speedFraction, 0f, 1f);
        if (!placed) {
            yawRad = headingRad;
            placed = true;
        } else {
            // Shortest-way-round, so a car crossing from +179 to -179 degrees does not send the
            // camera the long way about the arena.
            float delta = wrapPi(headingRad - yawRad);
            yawRad = wrapPi(yawRad + delta * ease(YAW_HALF_LIFE_S, dtSeconds));
        }

        float distance = DISTANCE_M + SPEED_DISTANCE_M * speed;
        desired.set(vehiclePosition)
                .add(-MathUtils.sin(yawRad) * distance, HEIGHT_M, -MathUtils.cos(yawRad) * distance);

        if (!hasPosition) {
            // The first frame has nothing to ease from, and easing from the origin would fly the
            // camera in across the arena while the match is already running.
            position.set(desired);
            hasPosition = true;
        } else {
            position.lerp(desired, ease(FOLLOW_HALF_LIFE_S, dtSeconds));
        }
        target.set(vehiclePosition).add(0f, LOOK_AT_HEIGHT_M, 0f);

        camera.fieldOfView = FOV_DEG + SPEED_FOV_DEG * speed;
        camera.position.set(position);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(target);
        camera.update();
    }

    /** Places the camera exactly, with no ease — for the first frame after a respawn. */
    public void snapTo(Vector3 vehiclePosition, float headingRad) {
        yawRad = headingRad;
        placed = true;
        hasPosition = true;
        position.set(vehiclePosition)
                .add(-MathUtils.sin(yawRad) * DISTANCE_M, HEIGHT_M, -MathUtils.cos(yawRad) * DISTANCE_M);
        target.set(vehiclePosition).add(0f, LOOK_AT_HEIGHT_M, 0f);
        camera.position.set(position);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(target);
        camera.update();
    }

    /**
     * Frames the whole arena from above, for when there is no vehicle to follow — the lobby, or the
     * moment between dying and respawning.
     */
    public void overview(Vector3 centre, float radius, float dtSeconds) {
        desired.set(centre).add(0f, radius * 0.85f, -radius * 0.9f);
        if (!hasPosition) {
            position.set(desired);
            hasPosition = true;
        } else {
            position.lerp(desired, ease(FOLLOW_HALF_LIFE_S * 4f, dtSeconds));
        }
        camera.fieldOfView = FOV_DEG;
        camera.position.set(position);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(centre);
        camera.update();
    }

    /**
     * The fraction of the remaining gap to close this frame for a given half-life.
     *
     * <p>{@code 1 - 2^(-dt/halfLife)}. The naive {@code lerp(x, y, k * dt)} is frame-rate dependent
     * and unstable above {@code dt = 1/k}; this is neither, and it is the same shape.
     */
    private static float ease(float halfLifeSeconds, float dtSeconds) {
        if (halfLifeSeconds <= 0f) {
            return 1f;
        }
        return 1f - (float) Math.pow(2.0, -dtSeconds / halfLifeSeconds);
    }

    private static float wrapPi(float angleRad) {
        float wrapped = angleRad;
        while (wrapped > MathUtils.PI) {
            wrapped -= MathUtils.PI2;
        }
        while (wrapped < -MathUtils.PI) {
            wrapped += MathUtils.PI2;
        }
        return wrapped;
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.PredictionComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.net.EntityState;
import dev.syndicate.core.net.InputCommand;
import dev.syndicate.core.net.NetworkClient;
import dev.syndicate.core.net.Quantisation;
import dev.syndicate.core.net.ReplicatedComponent;
import dev.syndicate.core.net.ReplicatedField;
import dev.syndicate.core.net.SnapshotCodec;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.vehicle.VehicleControl;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.net.NetConstants;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 20: reconciling what this client predicted with what the authority decided
 * (docs/04_entity_component_model.md#D04-S4.4, docs/10_networking_multiplayer.md#D10-S5.5).
 *
 * <p>Prediction exists so that a driver's own steering feels instant; reconciliation is what keeps
 * that from becoming a lie. When the authority's state for the tick it last processed agrees with
 * what this client predicted for that tick — within 5 cm and 0.02 rad — nothing happens at all,
 * which is the common case on a healthy connection. When it does not, the local vehicle is snapped
 * to the authority's truth and every unacknowledged input is replayed on top of it, so the driver
 * ends up where the authority says while still holding the inputs they have given since.
 *
 * <p><b>Replay steps the whole physics world.</b> D10-S5.5's pseudocode replays "only the local
 * vehicle's body", which a per-body integrator would allow and Bullet does not: DEC-004 puts every
 * vehicle in one {@code btDiscreteDynamicsWorld} and there is no API to advance one body in it. So a
 * replay advances everything, and the remote entities are then put back where the newest snapshot
 * says they are — which is where they were going to be set from anyway (D10-R19, DEV-017).
 *
 * <p><b>The correction the player sees is smoothed</b> (D10-S5.5 step 4). Snapping the simulation is
 * correct; snapping the camera is not. The offset between where the client thought it was and where
 * it now is decays by 15% a tick, so a correction reads as a nudge rather than a jump. It is
 * exposed for the renderer to add and is never read by anything in the simulation (G6).
 */
public final class ReconciliationSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 20;

    private final NetworkClient client;
    private final PhysicsWorld physics;
    private final SnapshotCodec codec = new SnapshotCodec();
    private final VehicleControl control;

    private final Vector3 predictedPosition = new Vector3();
    private final Quaternion predictedRotation = new Quaternion();
    private final Vector3 authoritativePosition = new Vector3();
    private final Quaternion authoritativeRotation = new Quaternion();

    /** The decaying visual offset of D10-S5.5 step 4. Cosmetic; never read by a gameplay system. */
    private final Vector3 visualOffset = new Vector3();

    private int reconcileCount;
    private int consecutiveReconciles;

    public ReconciliationSystem(NetworkClient client, PhysicsWorld physics) {
        this.client = Objects.requireNonNull(client, "client");
        this.physics = Objects.requireNonNull(physics, "physics");
        // The same terrain the live control operation reads (D16-R54): a replay that gripped
        // differently from the tick it is replaying would reconcile a correction into existence.
        this.control = new VehicleControl(physics);
    }

    @Override
    public Phase phase() {
        return Phase.NET;
    }

    @Override
    public int order() {
        return ORDER;
    }

    /** How many corrections have been applied. A rate near one per tick is a bug signal (D10-E6). */
    public int reconcileCount() {
        return reconcileCount;
    }

    /** The visual correction the renderer should still be easing out, in metres. */
    public Vector3 visualOffset(Vector3 out) {
        return out.set(visualOffset);
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        decayVisualOffset();
        if (!client.hasPendingCorrection()) {
            consecutiveReconciles = 0;
            return;
        }
        client.clearPendingCorrection();

        int vehicle = client.localVehicleEntity();
        if (vehicle == EntityId.NULL) {
            return;
        }
        EntityState authoritative = client.authoritativeLocal();
        if (!authoritative.has(ReplicatedComponent.TRANSFORM)) {
            return;
        }
        decode(authoritative);

        PredictionComponent prediction = world.getComponent(vehicle, PredictionComponent.class);
        long ackedTick = prediction == null ? client.authoritativeTick() : prediction.lastAckedTick;
        if (!client.predictionAt(client.authoritativeTick(), predictedPosition, predictedRotation)) {
            // No prediction survives for that tick: the client has been running behind for longer
            // than the buffer holds, so the authority's state is simply accepted (D10-S5.5).
            applyAuthoritative(world, vehicle);
            return;
        }

        float positionError = predictedPosition.dst(authoritativePosition);
        float rotationError = angleBetween(predictedRotation, authoritativeRotation);
        if (positionError <= NetConstants.RECONCILE_POS_THRESHOLD_M
                && rotationError <= NetConstants.RECONCILE_ROT_THRESHOLD_RAD) {
            consecutiveReconciles = 0;
            return;
        }

        reconcileCount++;
        consecutiveReconciles++;
        if (consecutiveReconciles == NetConstants.RECONCILE_PERSISTENT_TICKS) {
            // D10-E6: persistent divergence is a bug signal and it must be visible. WARN once at the
            // threshold rather than every tick, so the log stays readable while it is happening.
            LOG.warn(
                    "reconciliation has fired {} ticks running, error {} m — this is a divergence, not latency",
                    consecutiveReconciles,
                    positionError);
        }

        applyAuthoritative(world, vehicle);
        replayPendingInputs(world, vehicle, prediction, ackedTick);
        client.reapplyLatestFrame(world);

        // What the player sees moves from where they thought they were toward where they are.
        visualOffset.set(predictedPosition).sub(authoritativePosition);
    }

    private void decode(EntityState authoritative) {
        int slot = ReplicatedField.POSITION.slot();
        authoritativePosition.set(
                Quantisation.decodePositionAxis(authoritative.values[slot]),
                Quantisation.decodePositionAxis(authoritative.values[slot + 1]),
                Quantisation.decodePositionAxis(authoritative.values[slot + 2]));
        Quantisation.unpackRotation(authoritative.values[ReplicatedField.ROTATION.slot()], authoritativeRotation);
        authoritativeRotation.nor();
    }

    /** Snaps the local vehicle's components and its Bullet body to the authority's state. */
    private void applyAuthoritative(World world, int vehicleEntity) {
        codec.apply(world, vehicleEntity, client.authoritativeLocal());
        TransformComponent transform = world.getComponent(vehicleEntity, TransformComponent.class);
        if (transform != null) {
            transform.position.set(authoritativePosition);
            transform.rotation.set(authoritativeRotation);
            transform.dirty = true;
        }
        codec.syncBodyFromComponents(world, vehicleEntity);
    }

    /**
     * Re-applies every input the authority has not yet acknowledged (D10-S5.5 step 3).
     *
     * <p>Oldest first, each one driven through the same {@link VehicleControl} the authority used
     * and followed by one fixed step. Replaying through different arithmetic would turn a transient
     * prediction error into a permanent offset, which is why the control logic is a shared operation
     * rather than a system's private method (DEC-061).
     */
    private void replayPendingInputs(World world, int vehicleEntity, PredictionComponent prediction, long ackedTick) {
        if (prediction == null) {
            return;
        }
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        if (input == null) {
            return;
        }
        for (int age = prediction.pendingInputs.size() - 1; age >= 0; age--) {
            InputCommand command = prediction.pendingInputs.get(age);
            if (command.commandTick <= ackedTick) {
                continue;
            }
            input.throttle = command.throttle;
            input.steer = command.steer;
            input.brake = command.brake;
            input.aimYawRad = command.aimYawRad;
            input.aimPitchRad = command.aimPitchRad;
            input.fireMask = command.fireMask;
            input.commandTick = command.commandTick;
            input.sequence = command.sequence;

            control.drive(world, vehicleEntity, SimulationConstants.TICK_DT);
            physics.step();
        }
    }

    private void decayVisualOffset() {
        if (visualOffset.isZero()) {
            return;
        }
        visualOffset.scl(NetConstants.VISUAL_OFFSET_DECAY_PER_TICK);
        if (visualOffset.len2() <= NetConstants.VISUAL_OFFSET_EPSILON_M * NetConstants.VISUAL_OFFSET_EPSILON_M) {
            visualOffset.setZero();
        }
    }

    /** The angle between two orientations, radians. */
    static float angleBetween(Quaternion a, Quaternion b) {
        float dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
        return (float) (2.0 * Math.acos(Math.min(1f, dot)));
    }
}

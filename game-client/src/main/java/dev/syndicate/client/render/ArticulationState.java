/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.damage.WeaponFiredEvent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.DamageState;
import java.util.HashMap;
import java.util.Map;

/**
 * How far through its motion each articulated part is, this frame
 * (docs/17_weapon_system.md#D17-S5.9).
 *
 * <p>The whole of the "what drives the movement" half of articulation. {@link PartArticulation}
 * says <em>what</em> a part does and this says <em>when</em>, and the split is what makes the pose
 * itself a pure function of a phase in {@code [0,1]} — directly testable, and identical on two
 * clients handed the same phase.
 *
 * <p><b>It is a listener, not a poller.</b> Recoil and drum indexing are keyed off
 * {@link WeaponFiredEvent}, which arrives on the deferred bus that PRESENT systems subscribe to
 * (DISC-022 is the standing reason a cosmetic system must not use the same-tick queue).
 *
 * <p><b>Sub-parts inherit their weapon's fire time.</b> A barrel is not the entity that fires — the
 * mount is, because the mount is the part that occupies the vehicle's hardpoint and carries the
 * {@code weapon} block. So a shot is recorded against the firing part's <em>slot path</em>, and any
 * part whose own slot path is a descendant of it recoils with it. That is one string prefix test and
 * it works at any sub-part depth, which matters because D17-S5.8's tree is three deep for a cannon
 * and two for a machine gun.
 */
public final class ArticulationState {

    /** Seconds a spin decays to a stop over once a weapon stops firing. */
    private static final float SPIN_COAST_SECONDS = 0.6f;

    /** Key: which weapon on which vehicle fired, and when. */
    private record WeaponKey(int vehicleEntity, String slotPath) {}

    private final Map<WeaponKey, Float> lastFiredAt = new HashMap<>();
    private float now;

    /** Subscribes to the fire events that drive {@code FIRE} and {@code CONTINUOUS} motions. */
    public void initialize(World world) {
        world.events().subscribe(WeaponFiredEvent.class, this::onWeaponFired);
    }

    /** Advances the client clock. Called once per frame with real frame time, never with a tick. */
    public void advance(float frameDtSeconds) {
        now += frameDtSeconds;
    }

    /** Client seconds since start; the clock every phase below is measured against. */
    public float now() {
        return now;
    }

    private void onWeaponFired(WeaponFiredEvent event) {
        // The event carries the entity; the slot path is what survives a part being rebuilt, and is
        // what a sub-part can be matched against. Looked up lazily in phaseFor, because the world is
        // not a field here and an event handler that needed one would have to hold a stale reference.
        pendingEntity = event.weaponPartEntity();
        pendingVehicle = event.vehicleEntity();
        pendingAt = now;
        pending = true;
    }

    private boolean pending;
    private int pendingEntity = EntityId.NULL;
    private int pendingVehicle = EntityId.NULL;
    private float pendingAt;

    /**
     * Resolves any fire event received since the last frame into a slot-path key.
     *
     * <p>Deferred out of the handler because resolving a path needs the world, and a subscriber that
     * captured one would keep a reference across a world rebuild.
     */
    public void resolvePending(World world) {
        if (!pending) {
            return;
        }
        pending = false;
        if (pendingEntity == EntityId.NULL || !world.isAlive(pendingEntity)) {
            return;
        }
        PartRefComponent part = world.getComponent(pendingEntity, PartRefComponent.class);
        if (part == null || part.slotPath == null) {
            return;
        }
        lastFiredAt.put(new WeaponKey(pendingVehicle, part.slotPath), pendingAt);
    }

    /**
     * The phase to draw {@code entityId} at: {@code [0,1]} for every motion but {@code ELEVATE},
     * which is signed {@code [-1,1]} about its rest pose.
     *
     * <p>Returns 0 — the rest pose — for a part that is {@code DESTROYED} or {@code DETACHED}
     * (D17-R15). This is the one legal direction of the G6 split: cosmetic state reading
     * authoritative state, never the reverse. A barrel that has been shot off does not recoil.
     */
    public float phaseFor(World world, int entityId, PartArticulation.Articulation articulation) {
        if (articulation == null) {
            return 0f;
        }
        DamageStateComponent damage = world.getComponent(entityId, DamageStateComponent.class);
        if (damage != null && (damage.state == DamageState.DESTROYED || damage.state == DamageState.DETACHED)) {
            return 0f;
        }
        PartRefComponent part = world.getComponent(entityId, PartRefComponent.class);
        if (part == null) {
            return 0f;
        }
        return switch (articulation.driver()) {
            case FIRE -> firePhase(part, articulation);
            case CONTINUOUS -> continuousPhase(part, articulation);
            case AIM -> aimPhase(world, part, articulation);
            case OPEN -> 0f;
        };
    }

    /**
     * One over the settle time since the shot, clamped: 1 at the instant of firing, easing to 0.
     *
     * <p>Squared on the way back so the barrel snaps rearward and returns softly, which is what a
     * recoil spring does and what distinguishes it from a sine wave.
     */
    private float firePhase(PartRefComponent part, PartArticulation.Articulation articulation) {
        float since = secondsSinceFire(part);
        if (since < 0f) {
            return 0f;
        }
        float settle = Math.max(1e-3f, articulation.returnSeconds());
        if (since >= settle) {
            return 0f;
        }
        float remaining = 1f - since / settle;
        return remaining * remaining;
    }

    /**
     * A spin that runs while the weapon is firing and coasts to a stop when it stops.
     *
     * <p>Wrapped into {@code [0,1]} so a full revolution is one phase sweep, and scaled by the
     * articulation's own rate rather than by a global constant — a drum tied to the fire rate is the
     * whole point of R47, and one that turns at a rate unrelated to the shots leaving the gun looks
     * worse than one that does not turn at all.
     */
    private float continuousPhase(PartRefComponent part, PartArticulation.Articulation articulation) {
        float since = secondsSinceFire(part);
        float coast = since < 0f ? 0f : Math.max(0f, 1f - since / SPIN_COAST_SECONDS);
        if (coast <= 0f) {
            return 0f;
        }
        float degrees = articulation.rateDegPerSec() * now * coast;
        float turns = degrees / 360f;
        return turns - (float) Math.floor(turns);
    }

    /** Commanded pitch as a signed fraction of the articulation's travel limit. */
    private static float aimPhase(World world, PartRefComponent part, PartArticulation.Articulation articulation) {
        if (part.vehicleEntity == EntityId.NULL || !world.isAlive(part.vehicleEntity)) {
            return 0f;
        }
        PlayerInputComponent input = world.getComponent(part.vehicleEntity, PlayerInputComponent.class);
        if (input == null) {
            return 0f;
        }
        float limitDeg = Math.max(1e-3f, articulation.travelDeg());
        float pitchDeg = (float) Math.toDegrees(input.aimPitchRad);
        return Math.max(-1f, Math.min(1f, pitchDeg / limitDeg));
    }

    /** Seconds since the weapon this part belongs to last fired, or -1 when it never has. */
    private float secondsSinceFire(PartRefComponent part) {
        if (part.slotPath == null) {
            return -1f;
        }
        float best = -1f;
        for (Map.Entry<WeaponKey, Float> entry : lastFiredAt.entrySet()) {
            WeaponKey key = entry.getKey();
            if (key.vehicleEntity() != part.vehicleEntity) {
                continue;
            }
            // The firing part itself, or any descendant of it in the slot graph.
            if (!part.slotPath.equals(key.slotPath()) && !part.slotPath.startsWith(key.slotPath() + "/")) {
                continue;
            }
            float since = now - entry.getValue();
            if (best < 0f || since < best) {
                best = since;
            }
        }
        return best;
    }

    /** Drops everything remembered. Called when the world is rebuilt between matches. */
    public void reset() {
        lastFiredAt.clear();
        pending = false;
        now = 0f;
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.EntityId;

/**
 * What the behaviour tree decided, before the solvers turn it into an input command
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.1, D11-R7).
 *
 * <p>D11-R7 is the reason this type exists: no leaf action writes throttle or steer. A leaf says
 * "go there", "shoot that", and the steering and aim solvers convert that into controls the same way
 * for every behaviour. Without the separation, "retreat" and "patrol" would each grow their own
 * driving model and the two would diverge on the first bug fix.
 */
public final class BotBlackboard {

    /** Where the tree wants the vehicle to go, in world space. */
    public final Vector3 destination = new Vector3();

    /** Whether {@link #destination} has been set this tick. */
    public boolean hasDestination;

    /** The entity the tree wants engaged, or {@link EntityId#NULL}. */
    public int target = EntityId.NULL;

    /** Where on that target to shoot, in world space. Meaningless when {@link #target} is null. */
    public final Vector3 aimPoint = new Vector3();

    /** Whether the tree wants to fire at all this tick. The solver still applies range and discipline. */
    public boolean wantsToFire;

    /** How much of the available throttle the behaviour asks for, before difficulty scaling. */
    public float aggression = 1f;

    /** Clears the board for a fresh decision. Called at the top of every decision tick. */
    public void clear() {
        destination.set(0f, 0f, 0f);
        hasDestination = false;
        target = EntityId.NULL;
        aimPoint.set(0f, 0f, 0f);
        wantsToFire = false;
        aggression = 1f;
    }

    /** Records a destination. */
    public void driveTo(float x, float y, float z) {
        destination.set(x, y, z);
        hasDestination = true;
    }

    /** Records a destination. */
    public void driveTo(Vector3 point) {
        destination.set(point);
        hasDestination = true;
    }
}

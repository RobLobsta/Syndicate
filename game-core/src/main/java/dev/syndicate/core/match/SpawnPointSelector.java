/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.util.StreamId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses where a vehicle enters the arena
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.7 {@code chooseSpawnPoint}, D11-E14,
 * docs/06_physics_simulation.md#D06-E7).
 *
 * <p>Two rules, and both exist because of the same failure: a respawning player materialising on top
 * of the fight that just killed them.
 *
 * <ol>
 *   <li><b>Occupancy.</b> A point with any vehicle inside {@link ArenaDef#MIN_SPAWN_SEPARATION_M} is
 *       skipped. Spawning inside another vehicle's collision shape is not a near miss — Bullet
 *       resolves the overlap by firing both bodies apart at whatever speed the penetration depth
 *       implies.
 *   <li><b>Enemy proximity.</b> A point within {@link #AVOID_ENEMIES_WITHIN_M} of a vehicle on
 *       another team is deprioritised but not forbidden. Forbidding it would leave a small arena
 *       with no legal point at all in the last thirty seconds of a match.
 * </ol>
 *
 * <p><b>The scan starts at a seeded offset, not at index 0.</b> Otherwise every respawn in a match
 * takes the first free point, which puts each player back where they died and turns one spawn into a
 * shooting gallery. The offset comes from the {@code SPAWN_SELECT} stream, so it is random to a
 * player and identical on every peer replaying the tick (G4, D06-E7).
 */
public final class SpawnPointSelector {

    /** How close an enemy has to be for a spawn point to be treated as contested (D11-S5.7). */
    public static final float AVOID_ENEMIES_WITHIN_M = 40.0f;

    private final Vector3 scratch = new Vector3();
    private final Vector3 candidate = new Vector3();

    /**
     * Points already handed out for spawns that have been queued but not yet created.
     *
     * <p>Slot 5 creates vehicles one slot after slot 4 chooses their spawn points, so during a
     * starting grid — every player queued in the same tick — the occupancy scan sees an empty world
     * and every point looks clear. Six bots then take whichever point their seeded offset lands on,
     * with nothing stopping two from landing on the same one. They spawn inside each other, Bullet
     * resolves the overlap by throwing them apart, and one of them ends up somewhere it cannot
     * drive out of. Claims are the memory that scan does not have.
     */
    private final Set<String> claimed = new HashSet<>();

    private long claimTick = Long.MIN_VALUE;

    private Family vehicles;

    /**
     * Picks a spawn transform for a team, avoiding occupied and contested points.
     *
     * @param out receives the transform; also the return value
     * @return {@code out}, or null when the arena declares no point this team may use
     */
    public Matrix4 choose(World world, ArenaDef arena, int teamId, Matrix4 out) {
        if (arena == null) {
            return null;
        }
        List<ArenaDef.SpawnPoint> points = arena.spawnPointsFor(teamId);
        if (points.isEmpty()) {
            return null;
        }
        if (vehicles == null) {
            vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class, TransformComponent.class));
        }
        expireClaims(world.currentTick());

        int start = world.random().stream(StreamId.SPAWN_SELECT).nextInt(points.size());
        int firstContested = -1;
        for (int step = 0; step < points.size(); step++) {
            int index = (start + step) % points.size();
            ArenaDef.SpawnPoint point = points.get(index);
            if (claimed.contains(point.id())) {
                continue;
            }
            Occupancy occupancy = classify(world, point, teamId);
            if (occupancy == Occupancy.CLEAR) {
                return claim(point, out);
            }
            if (occupancy == Occupancy.CONTESTED && firstContested < 0) {
                firstContested = index;
            }
        }
        if (firstContested >= 0) {
            // Contested beats occupied: arriving next to an enemy is a fight, arriving inside
            // another vehicle is a physics explosion.
            return claim(points.get(firstContested), out);
        }
        // Nothing is free. More players than the arena has points is a content problem the arena
        // should be fixed for; in the meantime, offsetting around the point is a great deal better
        // than stacking two vehicles on it. The offset is derived from how many claims are already
        // out, so a batch fans out rather than piling onto one displacement.
        ArenaDef.SpawnPoint point = points.get(start);
        return claim(point, out).trn(offsetFor(claimed.size()));
    }

    /** Records a point as taken for this tick and returns its transform. */
    private Matrix4 claim(ArenaDef.SpawnPoint point, Matrix4 out) {
        claimed.add(point.id());
        return transformOf(point, out);
    }

    /**
     * Drops claims from earlier ticks.
     *
     * <p>A claim only has to outlive the gap between slot 4 choosing and slot 5 creating, which is
     * one slot in the same tick. Holding them longer would make a respawn thirty seconds later
     * refuse a point that is now empty.
     */
    private void expireClaims(long tick) {
        if (tick != claimTick) {
            claimed.clear();
            claimTick = tick;
        }
    }

    /** A ring of displacements around a point, one separation radius out. */
    private Vector3 offsetFor(int index) {
        double angle = index * 2.0 * Math.PI / OVERFLOW_RING_SIZE;
        float radius = ArenaDef.MIN_SPAWN_SEPARATION_M;
        return scratch.set((float) (Math.cos(angle) * radius), 0f, (float) (Math.sin(angle) * radius));
    }

    /** How many displacements the overflow ring has before it repeats. */
    public static final int OVERFLOW_RING_SIZE = 8;

    private enum Occupancy {
        /** Nothing within the separation radius and no enemy nearby. */
        CLEAR,
        /** An enemy within {@link #AVOID_ENEMIES_WITHIN_M}, but nothing inside the separation radius. */
        CONTESTED,
        /** A vehicle inside the separation radius. */
        OCCUPIED
    }

    private Occupancy classify(World world, ArenaDef.SpawnPoint point, int teamId) {
        candidate.set(point.position());
        float separation = Math.max(point.clearanceRadiusM(), ArenaDef.MIN_SPAWN_SEPARATION_M);
        Occupancy worst = Occupancy.CLEAR;
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < vehicles.size(); i++) {
            int vehicle = entityIds[i];
            TransformComponent transform = world.getComponent(vehicle, TransformComponent.class);
            if (transform == null) {
                continue;
            }
            float distance = scratch.set(transform.position).dst(candidate);
            if (distance <= separation) {
                return Occupancy.OCCUPIED;
            }
            if (distance <= AVOID_ENEMIES_WITHIN_M && isEnemy(world, vehicle, teamId)) {
                worst = Occupancy.CONTESTED;
            }
        }
        return worst;
    }

    /** In free-for-all every other vehicle is an enemy, which is exactly what makes it free-for-all. */
    private static boolean isEnemy(World world, int vehicleEntity, int teamId) {
        TeamComponent team = world.getComponent(vehicleEntity, TeamComponent.class);
        if (teamId == TeamComponent.FREE_FOR_ALL || team == null) {
            return true;
        }
        return team.teamId != teamId;
    }

    private static Matrix4 transformOf(ArenaDef.SpawnPoint point, Matrix4 out) {
        return out.setToRotation(Vector3.Y, point.yawDeg()).setTranslation(point.position());
    }
}

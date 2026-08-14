/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.arena.TerrainParams;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.GameMode;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A place to fight in (docs/08_asset_pipeline.md#D08-S4.7).
 *
 * <p>The smallest thing that is a world rather than an empty universe: a floor, a boundary, a height
 * below which a vehicle is dead, and somewhere for each side to start. Everything else an arena will
 * eventually have — cover, hazards, a payload path, a navmesh — is content layered on top of these
 * four, and none of it changes what a vehicle needs in order to drive somewhere and be shot at.
 *
 * <p>Immutable and shared, like {@link PartType}: one instance per arena per process, pointed at by
 * whatever spawns into it.
 *
 * @param arenaId the asset id, matching its directory name (D08-R6)
 * @param displayName the name a player sees
 * @param boundsMin the arena's axis-aligned lower corner, metres
 * @param boundsMax its upper corner
 * @param killPlaneY the height below which a vehicle is destroyed, metres (D01-E3)
 * @param groundY the height of the drivable floor, metres
 * @param spawnPoints where vehicles start, sorted by id so selection is deterministic (G3)
 * @param modes the game modes this arena supports (D01-S4.2)
 * @param collisionMeshRef the arena's collision geometry file, or null when its collision is
 *     generated from {@link #boundsMin} and {@link #boundsMax} (DEV-014)
 * @param terrain the generated-ground parameters, or null for a flat arena (D16-R3, R4)
 */
public record ArenaDef(
        AssetId arenaId,
        String displayName,
        Vector3 boundsMin,
        Vector3 boundsMax,
        float killPlaneY,
        float groundY,
        List<SpawnPoint> spawnPoints,
        Set<GameMode> modes,
        String collisionMeshRef,
        TerrainParams terrain) {

    /**
     * Metres. The minimum clearance a spawn point must have (D06-E7, D08-R15).
     *
     * <p>Two vehicles that spawn inside each other are resolved by the solver pushing them apart
     * violently, which reads as a bug on the first frame of every match. Eight metres is comfortably
     * more than the longest vehicle in the roster.
     */
    public static final float MIN_SPAWN_SEPARATION_M = 8.0f;

    /**
     * One place a vehicle can start.
     *
     * @param id the spawn point's id, unique within the arena
     * @param team which team may use it, or {@code -1} for any (D08-S4.7)
     * @param position where the chassis body starts, world space, metres
     * @param yawDeg which way it faces, degrees about Y (D00-R17)
     * @param clearanceRadiusM how much room it guarantees, metres
     */
    public record SpawnPoint(String id, int team, Vector3 position, float yawDeg, float clearanceRadiusM) {

        /** A {@link #team} value meaning any team may spawn here. */
        public static final int ANY_TEAM = -1;

        public SpawnPoint {
            Objects.requireNonNull(id, "id");
            position = new Vector3(position == null ? Vector3.Zero : position);
        }
    }

    public ArenaDef {
        Objects.requireNonNull(arenaId, "arenaId");
        displayName = displayName == null ? arenaId.value() : displayName;
        boundsMin = new Vector3(boundsMin == null ? Vector3.Zero : boundsMin);
        boundsMax = new Vector3(boundsMax == null ? Vector3.Zero : boundsMax);
        // Sorted by id rather than left in file order: spawn selection walks this list, and a list
        // whose order depended on how the JSON happened to be written would make two peers put the
        // same player in different places (G3).
        List<SpawnPoint> sorted = spawnPoints == null ? List.of() : new java.util.ArrayList<>(spawnPoints);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        spawnPoints = Collections.unmodifiableList(sorted);
        modes = modes == null || modes.isEmpty() ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(modes));
    }

    /**
     * Whether this arena's ground is generated rather than flat (D16-R4).
     *
     * <p>An arena that declares no {@code terrain} block is the flat floor and box walls that shipped
     * before D16, and that stays a legal arena rather than a deprecated one — every physics
     * regression fixture is one, and a test measuring a braking distance wants a floor, not a
     * landform.
     */
    public boolean hasTerrain() {
        return terrain != null;
    }

    /** Whether a point is inside the arena's bounds. */
    public boolean contains(Vector3 pointWorld) {
        return pointWorld.x >= boundsMin.x
                && pointWorld.x <= boundsMax.x
                && pointWorld.y >= boundsMin.y
                && pointWorld.y <= boundsMax.y
                && pointWorld.z >= boundsMin.z
                && pointWorld.z <= boundsMax.z;
    }

    /** Whether this arena is playable in a mode. An arena that names none is playable in all. */
    public boolean supports(GameMode mode) {
        return modes.isEmpty() || modes.contains(mode);
    }

    /**
     * The spawn points a team may use, in id order. Falls back to every point when a team has none.
     *
     * <p><b>A free-for-all uses every point.</b> {@link SpawnPoint#ANY_TEAM} and
     * {@code TeamComponent.FREE_FOR_ALL} are both {@code -1}, so filtering on equality used to give a
     * free-for-all only the points explicitly marked as neutral — two of the shipped arena's six.
     * Six bots then spawned on two points, interpenetrated, and one of them spent the match wedged
     * where Bullet had shoved it. A mode with no teams has no reason to reserve a team's grid.
     */
    public List<SpawnPoint> spawnPointsFor(int teamId) {
        if (teamId == SpawnPoint.ANY_TEAM) {
            return spawnPoints;
        }
        List<SpawnPoint> forTeam = spawnPoints.stream()
                .filter(point -> point.team() == teamId || point.team() == SpawnPoint.ANY_TEAM)
                .toList();
        return forTeam.isEmpty() ? spawnPoints : forTeam;
    }

    @Override
    public String toString() {
        return "ArenaDef[" + arenaId.value() + ", " + spawnPoints.size() + " spawns]";
    }
}

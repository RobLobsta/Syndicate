/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.AssetId;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-part meshes produced by {@code syndicate_dissect}, read back by the game's own reader.
 *
 * <p>A round trip is the only check worth making on an asset pipeline stage. The dissection tool
 * measures the wheels in Blender and reports what it found; this reads the file it wrote, through
 * the same {@code GltfReader} the server uses, and asserts the geometry is the size the source art
 * was independently measured to be, in each vehicle's {@code SOURCE.md}. If the two agree, the
 * export's axis conversion, the origin placement and the reader all agree as well — and if any one
 * of them were wrong, no amount of agreement inside Blender would show it.
 */
@Tag("unit")
class SplitVehiclePartsTest {

    private static final Path PARTS = Path.of("..", "assets", "parts");

    /** Tolerance in metres for a value that should be exact — an origin, a symmetry. */
    private static final float TOLERANCE_M = 0.005f;

    /**
     * How far past the bare tyre a wheel's hull may reach.
     *
     * <p>The hull covers everything that turns with the wheel, and a brake caliper sticks out
     * past the tyre's silhouette by a few centimetres. It must never be *smaller* than the
     * tyre — that is what {@code VehicleFactory} reads the wheel radius from (DEC-022), and a
     * hull short of the tyre parks the car in the road.
     */
    private static final float WHEEL_FURNITURE_MARGIN_M = 0.05f;

    private final GltfCollisionMeshSource meshes = new GltfCollisionMeshSource();

    // ---- Wheels ----------------------------------------------------------------------

    /**
     * The Eclipse's front tyre is 0.7068 m across, measured off the source art in SESS-014 — and
     * its hull is a disc of that diameter centred on its own origin.
     *
     * <p>Centred is the load-bearing half. {@code btRaycastVehicle} spins a wheel about its part
     * origin, so a wheel whose geometry is offset from that origin orbits the vehicle's centreline
     * instead of turning — the most visible art bug a driving game can ship, and one that no
     * measurement inside Blender would catch, because inside Blender the wheel is where it belongs.
     */
    @Test
    void eclipseFrontWheelIsADiscAboutItsAxle() {
        assertWheel("wheel_eclipse_front_01", 0.7068f, 0.2687f);
    }

    /** And its rear is 0.7189 m, on a wider rim. */
    @Test
    void eclipseRearWheelIsADiscAboutItsAxle() {
        assertWheel("wheel_eclipse_rear_01", 0.7189f, 0.3225f);
    }

    @Test
    void stampedeFrontWheelIsADiscAboutItsAxle() {
        assertWheel("wheel_stampede_front_01", 0.6834f, 0.3527f);
    }

    @Test
    void stampedeRearWheelIsADiscAboutItsAxle() {
        assertWheel("wheel_stampede_rear_01", 0.6994f, 0.3777f);
    }

    // ---- Chassis ---------------------------------------------------------------------

    /**
     * The chassis keeps the whole car's footprint and loses the wheels.
     *
     * <p>The length and width are the source art's, measured two sessions before this tool existed.
     * What the dissection changes is the <em>height</em> of the lowest geometry: with the wheels
     * gone, the lowest point of a chassis is its floor pan rather than a contact patch, so a chassis
     * whose minimum Y is still zero has kept a wheel.
     */
    @Test
    void theEclipseChassisKeepsTheBodyAndLosesTheWheels() {
        MeshData mesh = load("chassis_eclipse_01");
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        mesh.bounds(min, max);

        assertThat(max.z - min.z).as("length").isCloseTo(4.6682f, within(0.02f));
        assertThat(max.x - min.x).as("width").isCloseTo(2.1776f, within(0.02f));
        assertThat(min.y)
                .as("the floor pan clears the road once the tyres are gone")
                .isGreaterThan(0.02f);
    }

    @Test
    void theStampedeChassisKeepsTheBodyAndLosesTheWheels() {
        MeshData mesh = load("chassis_stampede_01");
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        mesh.bounds(min, max);

        assertThat(max.z - min.z).as("length").isGreaterThan(4.0f);
        assertThat(max.x - min.x).as("width").isGreaterThan(1.8f);
        assertThat(min.y)
                .as("the floor pan clears the road once the tyres are gone")
                .isGreaterThan(0.02f);
    }

    // ---- The collision node --------------------------------------------------------

    /**
     * Every part resolves the {@code <partTypeId>_col} node its {@code part.json} names (D08-R3).
     *
     * <p>{@code GltfCollisionMeshSource} falls back to the whole file when the node is missing, and
     * that fallback is what makes this worth asserting: without it, a mistyped node name would
     * produce a hull built from the visual mesh — larger, slower, and correct-looking.
     */
    @Test
    void everyPartCarriesItsOwnCollisionNode() {
        for (String partId : new String[] {
            "chassis_eclipse_01",
            "chassis_stampede_01",
            "wheel_eclipse_front_01",
            "wheel_eclipse_rear_01",
            "wheel_stampede_front_01",
            "wheel_stampede_rear_01"
        }) {
            MeshData whole = meshes.meshFor(AssetId.of(partId), "mesh.glb", PARTS.resolve(partId));
            MeshData hull = load(partId);
            assertThat(hull).as("%s has a collision node", partId).isNotNull();
            assertThat(hull.vertexCount())
                    .as("%s collision hull is within MAX_HULL_VERTICES", partId)
                    .isLessThanOrEqualTo(64);
            assertThat(hull.vertexCount())
                    .as("%s hull is the _col node, not the whole visual mesh", partId)
                    .isLessThan(whole.vertexCount());
        }
    }

    // ---- Helpers ---------------------------------------------------------------------

    /**
     * @param tyreDiameterM the bare tyre's diameter, from the source art's own measurements
     * @param widthM the wheel's width across the axle
     */
    private void assertWheel(String partId, float tyreDiameterM, float widthM) {
        MeshData mesh = load(partId);
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        mesh.bounds(min, max);

        // The hull covers the tyre and does not run far past it (see WHEEL_FURNITURE_MARGIN_M).
        // The lower bound carries the same slack as any other equality here: on both Eclipse
        // wheels the tyre *is* the outermost surface, so the hull equals the recorded diameter and
        // the only difference is the glTF's float32 rounding.
        assertThat(max.y - min.y)
                .as("%s diameter (Y)", partId)
                .isBetween(tyreDiameterM - TOLERANCE_M, tyreDiameterM + WHEEL_FURNITURE_MARGIN_M);
        assertThat(max.z - min.z)
                .as("%s diameter (Z)", partId)
                .isBetween(tyreDiameterM - TOLERANCE_M, tyreDiameterM + WHEEL_FURNITURE_MARGIN_M);
        // Round: a wheel is as tall as it is long, whichever way round it was exported. This is
        // what an axis mix-up in the export would break, and nothing else here would.
        assertThat(Math.abs((max.y - min.y) - (max.z - min.z)))
                .as("%s is round", partId)
                .isLessThan(0.02f);
        assertThat(max.x - min.x).as("%s width", partId).isCloseTo(widthM, within(TOLERANCE_M));

        // The axle is the origin: the hull straddles zero on both of the rotating axes.
        assertThat((max.y + min.y) / 2f)
                .as("%s is centred on its axle in Y", partId)
                .isCloseTo(0f, within(TOLERANCE_M));
        assertThat((max.z + min.z) / 2f)
                .as("%s is centred on its axle in Z", partId)
                .isCloseTo(0f, within(TOLERANCE_M));
    }

    private MeshData load(String partId) {
        return meshes.meshFor(AssetId.of(partId), "mesh.glb#node=" + partId + "_col", PARTS.resolve(partId));
    }
}

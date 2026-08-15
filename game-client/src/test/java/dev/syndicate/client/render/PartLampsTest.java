/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.model.AssetPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code light} block, read from a part directory (D08-R6, D15-R51).
 *
 * <p>No GL context anywhere here, which is the point of {@link PartLamps} being a separate class
 * from {@link VehicleLights}: what a lamp <em>is</em> can be asserted headlessly, and only what a
 * lamp <em>draws</em> needs a window.
 */
@Tag("unit")
class PartLampsTest {

    @Test
    void aHeadLampIsReadWithItsConeRangeAndDirection(@TempDir Path root) throws IOException {
        write(
                root,
                "light_eclipse_head_l_01",
                """
                ,"light":{"colorRgb":{"r":1.0,"g":0.96,"b":0.88},"intensity":34.0,
                "coneOuterDeg":34.0,"coneInnerDeg":13.0,"rangeM":55.0,"castsBeam":true,
                "directionLocal":{"x":0.0,"y":-0.061,"z":0.9981},
                "originLocal":{"x":0.0,"y":0.0,"z":0.2396}}""");

        PartLamps.Lamp lamp = new PartLamps(root).lampFor("light_eclipse_head_l_01");

        assertThat(lamp).isNotNull();
        assertThat(lamp.castsBeam()).isTrue();
        assertThat(lamp.rangeM()).isEqualTo(55f);
        assertThat(lamp.coneInnerDeg()).isLessThan(lamp.coneOuterDeg());
        // Normalised on read, because the shader takes a direction and not a scaled one.
        assertThat(lamp.direction().len()).isEqualTo(1f, within(1e-5f));
        // Pointing forward (+z is the vehicle's front) and a little down, which is a beam cut-off.
        assertThat(lamp.direction().z).isGreaterThan(0.99f);
        assertThat(lamp.direction().y).isNegative();
        // The lens face, not the middle of the lamp: a beam from the centre starts inside the body.
        assertThat(lamp.origin().z).isGreaterThan(0f);
    }

    /** A tail light glows and lights nothing — the difference is one flag and 40 lamps of budget. */
    @Test
    void aTailLampDoesNotCast(@TempDir Path root) throws IOException {
        write(
                root,
                "light_eclipse_tail_r_01",
                ",\"light\":{\"castsBeam\":false,\"directionLocal\":{\"x\":0,\"y\":0,\"z\":-1}}");

        PartLamps.Lamp lamp = new PartLamps(root).lampFor("light_eclipse_tail_r_01");

        assertThat(lamp).isNotNull();
        assertThat(lamp.castsBeam()).isFalse();
        assertThat(lamp.direction().z).isEqualTo(-1f, within(1e-5f));
    }

    @Test
    void aPartWithNoLightBlockHasNoLamp(@TempDir Path root) throws IOException {
        write(root, "panel_eclipse_door_l_01", "");

        assertThat(new PartLamps(root).lampFor("panel_eclipse_door_l_01")).isNull();
    }

    /** G18: a part that is not there is a car with its lights off, not a client that will not start. */
    @Test
    void anAbsentPartIsNullRatherThanAFailure(@TempDir Path root) {
        assertThat(new PartLamps(root).lampFor("never_authored_01")).isNull();
    }

    /** Every part is inspected once; a miss is cached so it is not a file read every frame. */
    @Test
    void aMissIsCachedAsFirmlyAsAHit(@TempDir Path root) throws IOException {
        write(root, "panel_eclipse_door_l_01", "");
        PartLamps lamps = new PartLamps(root);

        lamps.lampFor("panel_eclipse_door_l_01");
        lamps.lampFor("panel_eclipse_door_l_01");
        lamps.lampFor("never_authored_01");

        assertThat(lamps.inspectedCount()).isEqualTo(2);
    }

    private static void write(Path root, String partTypeId, String light) throws IOException {
        Path directory = AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve(partTypeId);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("part.json"),
                "{\"schemaVersion\":\"1.0.0\",\"partTypeId\":\"" + partTypeId
                        + "\",\"category\":\"DECORATIVE\",\"massKg\":4.0,\"maxHp\":40.0,\"breakImpulseN\":300.0"
                        + light + "}");
    }
}

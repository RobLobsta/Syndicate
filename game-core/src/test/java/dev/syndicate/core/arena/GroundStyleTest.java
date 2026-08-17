/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The ground held in the vehicles' house style (docs/15_vehicle_preparation_pipeline.md#D15-S4.5). */
@Tag("unit")
class GroundStyleTest {

    private static Path styleFile() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("assets/materials/style.json"))) {
            here = here.getParent();
        }
        return here == null ? null : here.resolve("assets/materials/style.json");
    }

    /**
     * The constants here and {@code style.json} are the same style, not two.
     *
     * <p>This is the whole reason it is safe for {@code game-core} to carry the numbers rather than
     * load the file: the moment somebody retunes the palette in the style table, this fails and says
     * so, instead of the cars moving and the ground staying where it was.
     */
    @Test
    void theConstantsMatchTheStyleTable() throws Exception {
        Path file = styleFile();
        assumeTrue(file != null && Files.exists(file), "style.json is not present");
        JsonNode style = new ObjectMapper().readTree(Files.readString(file));

        JsonNode hues = style.path("palette").path("hues");
        assertThat(hues.size())
                .as("the palette is six hues and no more")
                .isEqualTo(GroundStyle.PALETTE_HUES_DEG.length);
        for (int i = 0; i < hues.size(); i++) {
            assertThat((float) hues.get(i).path("hueDeg").asDouble())
                    .as("palette hue %s", hues.get(i).path("name").asText())
                    .isEqualTo(GroundStyle.PALETTE_HUES_DEG[i]);
        }
        assertThat((float) style.path("palette").path("pull").asDouble()).isEqualTo(GroundStyle.PALETTE_PULL);
        assertThat((float) style.path("tone").path("luminanceMin").asDouble()).isEqualTo(GroundStyle.LUMINANCE_MIN);
        assertThat((float) style.path("tone").path("luminanceMax").asDouble()).isEqualTo(GroundStyle.LUMINANCE_MAX);
    }

    /** Every theme's ground ends inside the band the vehicles are held in. */
    @Test
    void everyThemeGroundSitsInsideTheToneBand() {
        float ceiling = GroundStyle.LUMINANCE_MAX * GroundStyle.GROUND_CEILING_FRACTION;
        for (ArenaTheme theme : ArenaTheme.values()) {
            float l = GroundStyle.luma(theme.albedoR(), theme.albedoG(), theme.albedoB());
            assertThat(l).as("%s ground luma", theme.name()).isBetween(GroundStyle.LUMINANCE_MIN, ceiling + 1e-4f);
        }
    }

    /** The desert is what this was for: it was the theme sitting above the band. */
    @Test
    void theDesertIsDarkenedIntoTheBandAndTheOthersAreLeftAlone() {
        // Authored 0.66/0.55/0.38, whose Rec. 709 luma is 0.561 — well above the 0.409 a ground is
        // allowed. The scrapyard's 0.323 and the proving ground's 0.281 were already under it.
        float desert = GroundStyle.luma(
                ArenaTheme.DESERT_HIGHWAY.albedoR(),
                ArenaTheme.DESERT_HIGHWAY.albedoG(),
                ArenaTheme.DESERT_HIGHWAY.albedoB());
        assertThat(desert).isLessThan(0.561f);
        assertThat(GroundStyle.luma(
                        ArenaTheme.SCRAPYARD.albedoR(), ArenaTheme.SCRAPYARD.albedoG(), ArenaTheme.SCRAPYARD.albedoB()))
                .isCloseTo(0.323f, org.assertj.core.data.Offset.offset(0.01f));
    }

    /** A hue is pulled toward the palette, not snapped hard onto it: the pull is 0.75, not 1. */
    @Test
    void aHueIsPulledRatherThanSnapped() {
        // The desert's authored 36.4 degrees has three palette hues near it — rust at 22, sodium at
        // 33 and sand at 41 — and the nearest is sodium, 3.4 away. A hard snap would land on 33; the
        // pull leaves it at 33.85, which is the point: the palette bends a colour, it does not
        // replace it.
        float moved = GroundStyle.snapHue(36.4f);
        assertThat(moved).isBetween(33.0f, 36.4f);
        assertThat(moved).isCloseTo(36.4f - 3.4f * 0.75f, org.assertj.core.data.Offset.offset(0.05f));
    }

    /** Brightness is corrected by scaling, so the hue survives the clamp. */
    @Test
    void clampingLumaKeepsTheHue() {
        float[] before = {0.66f, 0.55f, 0.38f};
        float[] after = GroundStyle.clampLuma(before, 0.03f, 0.409f);
        assertThat(GroundStyle.toHsv(after[0], after[1], after[2])[0])
                .isCloseTo(
                        GroundStyle.toHsv(before[0], before[1], before[2])[0],
                        org.assertj.core.data.Offset.offset(0.5f));
    }
}

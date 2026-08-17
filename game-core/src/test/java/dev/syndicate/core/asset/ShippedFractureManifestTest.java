/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.ShippedContent;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shipped glass, read through the real reader (docs/08_asset_pipeline.md#D08-S5.3 step 2).
 *
 * <p>{@link FractureManifestLoadingTest} covers the reader's rules against fixtures it writes
 * itself. This covers the thing those fixtures stand for: eight real manifests written by the
 * Blender tool over real car glass, with real {@code shards.glb} files beside them. The whole
 * authored destruction path — the tool, the shards, the harness that verifies them — had never once
 * been read by the game, so "it parses our own fixtures" was not evidence of anything.
 */
@Tag("integration")
class ShippedFractureManifestTest {

    /** Every part in the shipped tree that was authored with shards; D15-S5.7 gives them to glass. */
    private static final AssetId ECLIPSE_WINDSCREEN = AssetId.of("glass_eclipse_windscreen_01");

    private static InMemoryAssetIndex index;
    private static AssetLoader loader;

    @BeforeAll
    static void loadShippedTree() {
        assumeTrue(ShippedContent.isPresent(), "the shipped asset tree is not present");
        loader = ShippedContent.loader();
        index = loader.loadFrom(ShippedContent.ASSET_ROOT);
    }

    @Test
    void everyPartThatDeclaresAManifest_hasOneInTheIndex() {
        List<AssetId> declared = index.partTypes().values().stream()
                .filter(part -> part.fractureManifestRef() != null)
                .map(PartType::partTypeId)
                .toList();

        // Eight panes of glass across the two cars. If this is zero the reader silently did
        // nothing, which is the state the game shipped in until this landed.
        assertThat(declared).isNotEmpty();
        for (AssetId partTypeId : declared) {
            assertThat(index.fractureManifest(partTypeId))
                    .as("manifest for %s", partTypeId)
                    .isNotNull();
        }
    }

    @Test
    void theShippedTreeStillLoadsWithNoBlockingFindings() {
        // The manifests are now read as part of a normal load, so a bad one blocks a strict start
        // (D03-S4.4). This is what says the eight that ship are not bad ones.
        assertThat(loader.blockingIssues()).isEmpty();
    }

    @Test
    void aWindscreenBreaksIntoShardsThatWeighWhatTheWindscreenWeighs() {
        FractureManifest manifest = index.fractureManifest(ECLIPSE_WINDSCREEN);
        PartType part = index.partType(ECLIPSE_WINDSCREEN);

        assertThat(manifest.shardCount()).isGreaterThan(1);
        assertThat(manifest.shardCount()).isLessThanOrEqualTo(SimulationConstants.MAX_SHARDS_PER_PART);
        // G7, over the numbers a match actually spends rather than over the tool's self-report.
        assertThat(manifest.declaredShardMassKg())
                .isCloseTo(part.massKg(), within(SimulationConstants.MASS_TOLERANCE_FRAC * part.massKg()));
    }

    @Test
    void everyShardCarriesGeometryOnItsOwnOrigin() {
        FractureManifest manifest = index.fractureManifest(ECLIPSE_WINDSCREEN);
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();

        for (ShardDefinition shard : manifest.shards()) {
            assertThat(shard.massKg()).isGreaterThan(SimulationConstants.MIN_BODY_MASS_KG);
            assertThat(shard.hullMesh().vertexCount()).isGreaterThanOrEqualTo(4);
            shard.hullMesh().bounds(min, max);

            // A shard of a 1.8 m windscreen is a few centimetres across. Measured about its own
            // origin it stays inside its own extent; measured about the *part's* origin — which is
            // what the file holds and what a reader that forgot to recentre would produce — a
            // corner pane sits the better part of a metre away.
            float reach = Math.max(max.len(), min.len());
            assertThat(reach)
                    .as("shard %s reaches %s m from its own origin", shard.shardId(), reach)
                    .isLessThan(0.5f);
        }
    }

    @Test
    void aShardsPlacementPutsItBackWhereItsPartHadIt() {
        FractureManifest manifest = index.fractureManifest(ECLIPSE_WINDSCREEN);
        PartType part = index.partType(ECLIPSE_WINDSCREEN);
        Vector3 partMin = new Vector3();
        Vector3 partMax = new Vector3();
        part.collisionMesh().bounds(partMin, partMax);

        // Every shard's centroid is inside the intact part's own bounds, with a collision margin of
        // slack. This is the check that would have caught a manifest read in the wrong units or
        // against the wrong file: the shards would reassemble into something that is not the part.
        Vector3 centroid = new Vector3();
        for (ShardDefinition shard : manifest.shards()) {
            shard.centroidLocal(centroid);
            assertThat(centroid.x).isBetween(partMin.x - 0.05f, partMax.x + 0.05f);
            assertThat(centroid.y).isBetween(partMin.y - 0.05f, partMax.y + 0.05f);
            assertThat(centroid.z).isBetween(partMin.z - 0.05f, partMax.z + 0.05f);
        }
    }
}

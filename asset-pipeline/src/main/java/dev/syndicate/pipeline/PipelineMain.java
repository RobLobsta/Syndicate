/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the asset validation CLI (docs/08_asset_pipeline.md#D08-S5.4,
 * docs/02_technical_architecture.md#D02-S5.2 step 5).
 *
 * <p>Validates {@code assets/} against the schemas in {@code schemas/}, resolves references, and
 * emits {@code asset-index.json}. It runs before the Blender tool stage in CI so that a broken
 * fixture is attributed to the fixture rather than blamed on the tool (D12-S5.3, D12-E18).
 *
 * <p>The schema catalogue of D08-S6.1 is a prerequisite and does not exist yet; see
 * {@code .agent-memory/progress/} for the current state.
 */
public final class PipelineMain {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineMain.class);

    private PipelineMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        LOG.error("asset-pipeline is not implemented yet (docs/08_asset_pipeline.md#D08-S5.4); "
                + "the schema catalogue of D08-S6.1 is its prerequisite");
        System.exit(70);
    }
}

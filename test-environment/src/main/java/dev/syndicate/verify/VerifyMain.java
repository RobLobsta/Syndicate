/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the verification harness (docs/14_test_environment.md#D14-S5.1).
 *
 * <p>Runs the asset, physics, destruction-progression, and vehicle-integration checks against
 * processed assets in a real Bullet world, and emits the JSON report of D14-S4.4. It has two modes:
 * visual inspection (D14-S5.11), which is manual and never runs in CI, and headless (D14-S5.13),
 * which is CI stage 5.
 *
 * <p>Its exit codes are the harness's own (D14-S4.2), deliberately distinct from the game's
 * (D03-S4.4) and the Blender tool's (D09-S4.3). Blocked on the Blender tool's fixture output; see
 * {@code .agent-memory/progress/}.
 */
public final class VerifyMain {

    private static final Logger LOG = LoggerFactory.getLogger(VerifyMain.class);

    private VerifyMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        LOG.error("test-environment is not implemented yet (docs/14_test_environment.md#D14-S5.1); "
                + "it is blocked on blender-tool fixture output (D14-S7.3)");
        System.exit(70);
    }
}

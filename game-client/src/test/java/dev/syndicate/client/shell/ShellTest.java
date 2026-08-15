/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.client.LocalPlayerFactory;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.GameMode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shell's decisions that do not need a GL context (T-D03-4).
 *
 * <p>The screens themselves draw, and drawing is verified by capturing the real client under a
 * virtual display — which is a different kind of check and lives outside the unit suite. What is
 * asserted here is the part a screenshot cannot show: that the vehicle the garage selected is the
 * vehicle that ends up on the player's entity. A preview showing the Stampede while the match spawns
 * the Eclipse would look completely correct in both captures.
 */
@Tag("unit")
class ShellTest {

    private static final AssetId ECLIPSE = AssetId.of("vehicle_eclipse_01");
    private static final AssetId STAMPEDE = AssetId.of("vehicle_stampede_01");
    private static final AssetId CHASSIS = AssetId.of("chassis_test_01");

    /** The garage's choice reaches the player entity, rather than the catalogue's first row. */
    @Test
    void theSelectedVehicleIsTheOneJoined() {
        World world = new World(7L, true);
        InMemoryAssetIndex assets = rosterOfTwo();

        int player = LocalPlayerFactory.join(world, assets, GameMode.DEATHMATCH, "Player", STAMPEDE);

        assertThat(world.getComponent(player, PlayerIdentityComponent.class).selectedAssemblyId)
                .isEqualTo(STAMPEDE);
    }

    /**
     * With no selection, the lowest id wins — the same car on every machine and every run (G3).
     *
     * <p>This is the path {@code --auto-start} and every CI capture take, so a change that made it
     * depend on map iteration order would make those captures compare against a different car
     * without anything failing.
     */
    @Test
    void noSelectionTakesTheLowestIdNotTheInsertionOrder() {
        World world = new World(7L, true);
        InMemoryAssetIndex assets = new InMemoryAssetIndex();
        assets.put(new AssemblyDef(STAMPEDE, "heavy", CHASSIS, List.of(), null));
        assets.put(new AssemblyDef(ECLIPSE, "medium", CHASSIS, List.of(), null));

        int player = LocalPlayerFactory.join(world, assets, GameMode.DEATHMATCH, "Player", null);

        assertThat(world.getComponent(player, PlayerIdentityComponent.class).selectedAssemblyId)
                .isEqualTo(ECLIPSE);
    }

    /** Content that went missing under a saved choice costs a different car, not the match (G18). */
    @Test
    void anUnloadedSelectionFallsBackRatherThanFailing() {
        World world = new World(7L, true);

        int player = LocalPlayerFactory.join(
                world, rosterOfTwo(), GameMode.DEATHMATCH, "Player", AssetId.of("vehicle_deleted_01"));

        assertThat(world.getComponent(player, PlayerIdentityComponent.class).selectedAssemblyId)
                .isEqualTo(ECLIPSE);
    }

    /** An assembly with no {@code displayName} is named after its id rather than shown as null. */
    @Test
    void anUnnamedAssemblyFallsBackToItsId() {
        assertThat(new AssemblyDef(ECLIPSE, null, "medium", CHASSIS, List.of(), null).displayName())
                .isEqualTo("vehicle_eclipse_01");
    }

    /** {@code --start-screen} accepts a screen name in any case, and rejects anything else. */
    @Test
    void startScreenParsesCaseInsensitivelyAndRejectsNonsense() {
        assertThat(ScreenId.parse("garage")).isEqualTo(ScreenId.GARAGE);
        assertThat(ScreenId.parse("MAIN_MENU")).isEqualTo(ScreenId.MAIN_MENU);
        assertThatThrownBy(() -> ScreenId.parse("lobby")).isInstanceOf(IllegalArgumentException.class);
    }

    private static InMemoryAssetIndex rosterOfTwo() {
        InMemoryAssetIndex assets = new InMemoryAssetIndex();
        assets.put(new AssemblyDef(ECLIPSE, "Eclipse", "medium", CHASSIS, List.of(), null));
        assets.put(new AssemblyDef(STAMPEDE, "Stampede", "heavy", CHASSIS, List.of(), null));
        return assets;
    }
}

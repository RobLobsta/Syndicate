/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.asset.WeaponDef;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.SizeClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Choosing a weapon in the garage, against the shipped content
 * (docs/01_product_game_design.md#D01-S2.2 NG1, docs/17_weapon_system.md#D17-S4.3).
 *
 * <p>Run against the real asset tree rather than a synthetic one on purpose. The thing that can
 * actually break here is a weapon manifest and a part tree disagreeing — a sub-part renamed, a seam
 * dropped, a mount that is not labelled {@code mount} — and a fixture built in this file would
 * agree with itself no matter what shipped.
 */
@Tag("integration")
class WeaponLoadoutTest {

    private static final Path ASSET_ROOT = repositoryRoot().resolve("assets");
    private static final AssetId ECLIPSE = AssetId.of("vehicle_eclipse_01");
    private static final AssetId STAMPEDE = AssetId.of("vehicle_stampede_01");
    private static final AssetId CANNON = AssetId.of("weapon_cannon_01");
    private static final AssetId MACHINE_GUN = AssetId.of("weapon_machinegun_01");

    private static InMemoryAssetIndex assets;

    @BeforeAll
    static void load() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        assets = ShippedContent.load();
    }

    /** T-D17-24: every shipped weapon's manifest resolves to a tree of parts that exist. */
    @Test
    void everyWeaponManifestNamesPartsThatLoaded() {
        assertThat(assets.weapons()).isNotEmpty();
        assertThat(assets.weapons().values()).allSatisfy(weapon -> {
            assertThat(assets.partType(weapon.rootPartTypeId()))
                    .as("%s root part", weapon.weaponId().value())
                    .isNotNull();
            assertThat(weapon.partTypeIds()).allSatisfy(id -> assertThat(assets.partType(id))
                    .as("%s sub-part %s", weapon.weaponId().value(), id.value())
                    .isNotNull());
        });
    }

    /** The cannon is HEAVY, which is what confines it to the one HEAVY mounting (D17-R10). */
    @Test
    void theCannonIsHeavyAndTheMachineGunIsLight() {
        assertThat(assets.weapon(CANNON).sizeClass()).isEqualTo(SizeClass.HEAVY);
        assertThat(assets.weapon(MACHINE_GUN).sizeClass()).isEqualTo(SizeClass.LIGHT);
    }

    /** The loadout the garage opens on is the one the artist fitted. */
    @Test
    void theFittedLoadoutIsReadBackOutOfTheAssembly() {
        WeaponLoadout fitted = WeaponLoadout.of(assets.assembly(STAMPEDE), assets);

        assertThat(fitted.fitted()).containsEntry("turret_main", CANNON);
        assertThat(WeaponLoadout.of(assets.assembly(ECLIPSE), assets).fitted())
                .containsKeys("hardpoint_flank_l", "hardpoint_flank_r");
    }

    /** T-D17-25: only weapons the slot's size class and mass ceiling accept are offered. */
    @Test
    void aLightFlankOffersTheMachineGunAndNotTheCannon() {
        PartType chassis = assets.partType(assets.assembly(ECLIPSE).chassisPartTypeId());
        SlotDefinition flank = slot(chassis, "hardpoint_flank_r");
        SlotDefinition turret = slot(chassis, "turret_main");

        assertThat(WeaponLoadout.fittableOn(flank, assets))
                .contains(MACHINE_GUN)
                .doesNotContain(CANNON);
        assertThat(WeaponLoadout.fittableOn(turret, assets)).contains(CANNON);
    }

    /**
     * T-D17-26: a slot already holding something that is not a weapon is not a mounting.
     *
     * <p>The four brake hubs sit in {@code HARDPOINT} slots, because a hardpoint accepts utility
     * parts as well as weapons. Without the occupancy filter the garage offered to bolt a machine
     * gun to each wheel — and the 22 kg ceiling on a hub would have let it.
     */
    @Test
    void anOccupiedUtilitySlotIsNotOfferedAsAMounting() {
        List<String> mountings = WeaponLoadout.mountingsOf(assets.assembly(STAMPEDE), assets).stream()
                .map(SlotDefinition::slotId)
                .toList();

        assertThat(mountings).contains("turret_main", "hardpoint_bonnet", "hardpoint_flank_l");
        assertThat(mountings).noneMatch(id -> id.startsWith("hub_"));
    }

    /** Stripping a weapon takes its whole subtree, not just the mount that named it. */
    @Test
    void clearingAHardpointRemovesEverySubPartOfTheWeapon() {
        AssemblyDef base = assets.assembly(STAMPEDE);
        WeaponDef cannon = assets.weapon(CANNON);

        AssemblyDef stripped = WeaponLoadout.empty().applyTo(base, assets);

        assertThat(stripped.parts())
                .hasSize(base.parts().size() - cannon.partTypeIds().size());
        assertThat(stripped.parts())
                .extracting(AssemblyDef.PartPlacement::partTypeId)
                .doesNotContainAnyElementsOf(cannon.partTypeIds());
        assertThat(stripped.assemblyId().value()).endsWith(WeaponLoadout.CONFIGURED_SUFFIX);
    }

    /** Fitting one is the inverse: every sub-part comes back, under the hardpoint it was put on. */
    @Test
    void fittingAWeaponGraftsItsWholeSubtreeUnderTheHardpoint() {
        AssemblyDef base = assets.assembly(STAMPEDE);

        AssemblyDef refitted = WeaponLoadout.empty().with("turret_main", CANNON).applyTo(base, assets);

        assertThat(refitted.parts())
                .extracting(AssemblyDef.PartPlacement::partTypeId)
                .containsAll(assets.weapon(CANNON).partTypeIds());
        assertThat(refitted.parts())
                .filteredOn(p -> p.slotPath().startsWith("root/turret_main"))
                .hasSize(assets.weapon(CANNON).partTypeIds().size());
        // Every child's parent is in the list before it, which is what the spawn path relies on.
        List<String> paths = refitted.parts().stream()
                .map(AssemblyDef.PartPlacement::slotPath)
                .toList();
        assertThat(paths).isSorted();
    }

    /** A loadout equal to what is already fitted returns the shipped assembly untouched. */
    @Test
    void anUnchangedLoadoutIsNotACopy() {
        AssemblyDef base = assets.assembly(STAMPEDE);

        assertThat(WeaponLoadout.of(base, assets).applyTo(base, assets)).isSameAs(base);
    }

    /** Swapping a flank gun for the other flank's mirror leaves the part count alone. */
    @Test
    void swappingOneHardpointLeavesTheOthersAlone() {
        AssemblyDef base = assets.assembly(ECLIPSE);
        WeaponLoadout fitted = WeaponLoadout.of(base, assets);

        AssemblyDef swapped = fitted.with("hardpoint_flank_l", MACHINE_GUN).applyTo(base, assets);

        assertThat(swapped.parts()).hasSameSizeAs(base.parts());
        assertThat(WeaponLoadout.of(swapped, assets).on("hardpoint_flank_r")).isEqualTo(fitted.on("hardpoint_flank_r"));
    }

    /** G3: the same loadout produces byte-identical placements, in the same order, every time. */
    @Test
    void applyingIsDeterministic() {
        AssemblyDef base = assets.assembly(ECLIPSE);
        WeaponLoadout loadout = WeaponLoadout.empty().with("hardpoint_bonnet", MACHINE_GUN);

        assertThat(loadout.applyTo(base, assets).parts())
                .isEqualTo(loadout.applyTo(base, assets).parts());
    }

    /** A configured assembly resolves through the index without joining the catalogue. */
    @Test
    void aConfiguredAssemblyIsResolvableAndNotInTheRoster() {
        AssemblyDef configured = WeaponLoadout.empty().applyTo(assets.assembly(STAMPEDE), assets);

        assets.putConfigured(configured);

        assertThat(assets.assembly(configured.assemblyId())).isSameAs(configured);
        assertThat(assets.assemblyIds()).doesNotContain(configured.assemblyId());
        assertThat(assets.assemblies()).doesNotContainKey(configured.assemblyId());
    }

    private static SlotDefinition slot(PartType chassis, String slotId) {
        SlotDefinition found = chassis.slot(slotId);
        assertThat(found)
                .as("%s has no slot %s", chassis.partTypeId().value(), slotId)
                .isNotNull();
        return found;
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs"))) {
            path = path.getParent();
        }
        return path;
    }
}

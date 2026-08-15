/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.AssetPaths;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.ModuleFamily;
import dev.syndicate.model.WeaponFamily;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared library: modular weapons and modules, loaded from {@code assets/parts/} (D08-R14b).
 *
 * <p>Two things are new enough to be worth their own class. The first is that a part can be in
 * either bucket at all — a vehicle's own parts live under it now, and both must load. The second is
 * that the {@code weapon} block is <em>read</em>: D08-R6 has specified it since the beginning and
 * nothing parsed it, so a part declaring {@code "category": "WEAPON"} loaded as an inert lump and
 * {@code WeaponSystem} could only ever fire a weapon a test had constructed in Java.
 */
@Tag("unit")
class ModularPartLoadingTest {

    /** A unit box in place of a real hull: what the D08-S5.3 mesh seam is for. */
    private static final AssetLoader.CollisionMeshSource BOXES =
            (partTypeId, ref, directory) -> new MeshData(new float[] {
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
            });

    @Test
    void aSharedWeaponCarriesItsFamilyRangeAndMuzzle(@TempDir Path root) throws IOException {
        shared(
                root,
                "weapon_autocannon_01",
                "WEAPON",
                """
                ,"weapon":{"family":"AUTOCANNON","ammoCapacity":400,"blastRadiusM":0.0,
                "rangeM":0.0,"muzzleLocal":{"x":0.0,"y":0.2,"z":0.9}}""");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("weapon_autocannon_01"));

        assertThat(part).isNotNull();
        WeaponBlock weapon = part.weapon();
        assertThat(weapon).isNotNull();
        assertThat(weapon.family()).isEqualTo(WeaponFamily.AUTOCANNON);
        assertThat(weapon.ammoCapacity()).isEqualTo(400);
        assertThat(weapon.muzzleLocal().z).isEqualTo(0.9f, within(1e-6f));
        // rangeM 0 means "the family's", not "zero metres" — a weapon that expired at the muzzle.
        assertThat(weapon.effectiveRangeM()).isEqualTo(WeaponFamily.AUTOCANNON.defaultRangeM());
        assertThat(weapon.damageType()).isEqualTo(WeaponFamily.AUTOCANNON.damageType());
    }

    /** An override exists so an incendiary autocannon needs no ninth family (D01-R9). */
    @Test
    void aWeaponMayOverrideItsFamilysDamageType(@TempDir Path root) throws IOException {
        shared(
                root,
                "weapon_incendiary_01",
                "WEAPON",
                ",\"weapon\":{\"family\":\"AUTOCANNON\",\"damageType\":\"INCENDIARY\"}");

        PartType part = new AssetLoader(BOXES).loadFrom(root).partType(AssetId.of("weapon_incendiary_01"));

        assertThat(part.weapon().damageType()).isEqualTo(DamageType.INCENDIARY);
    }

    /** A216: a weapon block belongs on a weapon, and a weapon without one can never fire. */
    @Test
    void aWeaponBlockOnANonWeaponIsA216(@TempDir Path root) throws IOException {
        shared(root, "panel_plate_01", "PANEL", ",\"weapon\":{\"family\":\"CANNON\"}");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("panel_plate_01"));

        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A216");
        assertThat(part.weapon()).as("the block is discarded, not half-applied").isNull();
    }

    @Test
    void aWeaponPartWithNoBlockIsWarnedAboutRatherThanRefused(@TempDir Path root) throws IOException {
        shared(root, "weapon_silent_01", "WEAPON", "");

        AssetLoader loader = new AssetLoader(BOXES);
        loader.loadFrom(root);

        // A warning, not a refusal: an unarmed weapon part is content that will not fire, which
        // is a thing to say loudly and not a thing to refuse to start over.
        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A216");
        assertThat(loader.blockingIssues()).extracting(ValidationIssue::code).doesNotContain("A216");
    }

    /** A217: ramming is a chassis property, so a part claiming it is a gun that never fires. */
    @Test
    void aRamWeaponIsA217(@TempDir Path root) throws IOException {
        shared(root, "weapon_ram_01", "WEAPON", ",\"weapon\":{\"family\":\"RAM\"}");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("weapon_ram_01"));

        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A217");
        assertThat(part.weapon()).isNull();
    }

    @Test
    void anActiveModuleCarriesItsFamilyAndCharges(@TempDir Path root) throws IOException {
        shared(root, "module_cloak_01", "UTILITY", ",\"module\":{\"family\":\"CLOAK\",\"charges\":3}");

        PartType part = new AssetLoader(BOXES).loadFrom(root).partType(AssetId.of("module_cloak_01"));

        assertThat(part.module()).isNotNull();
        assertThat(part.module().family()).isEqualTo(ModuleFamily.CLOAK);
        assertThat(part.module().charges()).isEqualTo(3);
        assertThat(part.module().isActive()).isTrue();
    }

    /**
     * A passive module needs no block, and that is not an oversight.
     *
     * <p>A radiator is its {@code heatPerShot} stat and nothing else. The block exists because an
     * <em>active</em> module has a state — idle, running, recharging — that no stat represents.
     */
    @Test
    void aPassiveUtilityWithNoBlockIsNotAFinding(@TempDir Path root) throws IOException {
        shared(root, "module_radiator_01", "UTILITY", ",\"stats\":{\"heatPerShot\":{\"mul\":0.7}}");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("module_radiator_01"));

        assertThat(part.module()).isNull();
        assertThat(loader.issues()).extracting(ValidationIssue::code).doesNotContain("A218", "A219");
    }

    @Test
    void aModuleBlockOnANonUtilityIsA218(@TempDir Path root) throws IOException {
        shared(root, "panel_smart_01", "PANEL", ",\"module\":{\"family\":\"RADAR\"}");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("panel_smart_01"));

        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A218");
        assertThat(part.module()).isNull();
    }

    @Test
    void anUnknownModuleFamilyIsA219(@TempDir Path root) throws IOException {
        shared(root, "module_teapot_01", "UTILITY", ",\"module\":{\"family\":\"TELEPORTER\"}");

        AssetLoader loader = new AssetLoader(BOXES);
        PartType part = loader.loadFrom(root).partType(AssetId.of("module_teapot_01"));

        assertThat(loader.issues()).extracting(ValidationIssue::code).contains("A219");
        assertThat(part.module()).isNull();
    }

    /** Both buckets load in one pass, which is the whole of D08-R14b from the loader's side. */
    @Test
    void aVehiclesOwnPartAndASharedPartBothLoad(@TempDir Path root) throws IOException {
        shared(root, "weapon_autocannon_01", "WEAPON", ",\"weapon\":{\"family\":\"AUTOCANNON\"}");
        write(
                AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve("panel_eclipse_door_l_01"),
                "panel_eclipse_door_l_01",
                "PANEL",
                "");

        InMemoryAssetIndex index = new AssetLoader(BOXES).loadFrom(root);

        assertThat(index.partTypes().keySet())
                .extracting(AssetId::value)
                .containsExactlyInAnyOrder("weapon_autocannon_01", "panel_eclipse_door_l_01");
    }

    private static void shared(Path root, String partTypeId, String category, String extra) throws IOException {
        write(AssetPaths.sharedPartsRoot(root).resolve(partTypeId), partTypeId, category, extra);
    }

    private static void write(Path directory, String partTypeId, String category, String extra) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("part.json"),
                "{\"schemaVersion\":\"1.0.0\",\"partTypeId\":\"" + partTypeId + "\",\"category\":\"" + category
                        + "\",\"massKg\":60.0,\"maxHp\":400.0,\"breakImpulseN\":3000.0" + extra + "}");
    }
}

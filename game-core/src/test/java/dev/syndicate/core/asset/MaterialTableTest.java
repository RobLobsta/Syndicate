/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.AudioMaterial;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.DestructionClass;
import dev.syndicate.model.PartCategory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared material table (docs/08_asset_pipeline.md#D08-S4.3,
 * docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>The table is the one place four tools agree about what a part is made of, so what is worth
 * asserting is the agreement rather than any single number: that every shipped part resolves to a
 * row, that every row is usable by the damage formula and by the audio bank, and that the
 * destruction class a part gets is a function of what the part <em>is</em> — which is where D15-R32
 * puts it and where this project first put it wrongly.
 */
@Tag("unit")
class MaterialTableTest {

    private static final Path ASSET_ROOT = Path.of("..", "assets");

    /** A unit box in place of a real hull: this is exactly what the D08-S5.3 mesh seam is for. */
    private static final AssetLoader.CollisionMeshSource BOXES =
            (partTypeId, ref, directory) -> new MeshData(new float[] {
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
            });

    private static InMemoryAssetIndex load() {
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        new AssetLoader((partTypeId, ref, dir) -> null)
                .loadMaterials(ASSET_ROOT.resolve("materials").resolve("materials.json"), index);
        return index;
    }

    @Test
    void everyMaterialHasADensityAResistanceAndAVoice() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        InMemoryAssetIndex index = load();

        assertThat(index.materials()).isNotEmpty();
        index.materials().forEach((id, material) -> {
            assertThat(material.densityKgPerM3()).as("%s density", id.value()).isPositive();
            assertThat(material.audioMaterial()).as("%s voice", id.value()).isNotNull();
            for (DamageType damageType : DamageType.values()) {
                assertThat(material.resistanceTo(damageType))
                        .as("%s resistance to %s", id.value(), damageType)
                        .isPositive();
            }
        });
    }

    /** D15-R37: the audio axis is coarser than the material axis, and every family is reachable. */
    @Test
    void everyAudioFamilyIsUsedBySomeMaterial() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        InMemoryAssetIndex index = load();

        for (AudioMaterial family : AudioMaterial.values()) {
            assertThat(index.materials().values())
                    .as("no material sounds like %s", family)
                    .anyMatch(material -> material.audioMaterial() == family);
        }
        assertThat(index.materials())
                .as("more materials than voices, by design")
                .hasSizeGreaterThan(AudioMaterial.values().length);
    }

    /** Every part the game ships names a material the table actually has. */
    @Test
    void everyShippedPartResolvesToAMaterial() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        AssetLoader loader = new AssetLoader(BOXES);
        InMemoryAssetIndex index = loader.loadFrom(ASSET_ROOT);

        assumeTrue(!index.partTypes().isEmpty(), "no parts are loaded");
        index.partTypes().forEach((id, part) -> {
            AssetId materialId = part.materialId();
            assertThat(materialId).as("%s names no material", id.value()).isNotNull();
            assertThat(index.material(materialId))
                    .as("%s names material %s, which is not in the table", id.value(), materialId.value())
                    .isNotNull();
        });
    }

    /**
     * D15-R32: the class follows from what a part is, and defaults from its category.
     *
     * <p>Asserted on the projection rather than on the shipped parts, because the shipped parts are
     * two chassis and four wheels and would exercise two of the five branches.
     */
    @Test
    void destructionClassDefaultsFromCategory() {
        assertThat(DestructionClass.forCategory(PartCategory.CHASSIS)).isEqualTo(DestructionClass.STRUCTURAL);
        assertThat(DestructionClass.forCategory(PartCategory.ARMOR)).isEqualTo(DestructionClass.SHEET_METAL);
        assertThat(DestructionClass.forCategory(PartCategory.WHEEL)).isEqualTo(DestructionClass.RIGID);
        assertThat(DestructionClass.forCategory(PartCategory.WEAPON)).isEqualTo(DestructionClass.RIGID);
        assertThat(DestructionClass.forCategory(PartCategory.DECORATIVE)).isEqualTo(DestructionClass.NONE);
        assertThat(DestructionClass.forCategory(null)).isEqualTo(DestructionClass.RIGID);
    }

    /** AC-D15-10: no glass part carries damage shape keys, and a panel does. */
    @Test
    void onlyDentingClassesCarryShapeKeys() {
        assertThat(DestructionClass.GLASS.hasDamageShapeKeys()).isFalse();
        assertThat(DestructionClass.RIGID.hasDamageShapeKeys()).isFalse();
        assertThat(DestructionClass.NONE.hasDamageShapeKeys()).isFalse();
        assertThat(DestructionClass.SHEET_METAL.hasDamageShapeKeys()).isTrue();
        assertThat(DestructionClass.STRUCTURAL.hasDamageShapeKeys()).isTrue();
    }

    /** Every shipped part gets a class, whether or not it authored one. */
    @Test
    void everyShippedPartHasADestructionClass() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        AssetLoader loader = new AssetLoader(BOXES);
        InMemoryAssetIndex index = loader.loadFrom(ASSET_ROOT);

        assumeTrue(!index.partTypes().isEmpty(), "no parts are loaded");
        index.partTypes().forEach((id, part) -> assertThat(part.destructionClass())
                .as("%s", id.value())
                .isEqualTo(DestructionClass.forCategory(part.category())));
    }
}

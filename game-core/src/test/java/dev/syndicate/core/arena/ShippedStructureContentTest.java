/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.StructureDef;
import dev.syndicate.core.asset.StructurePlacementRule;
import dev.syndicate.core.component.StructureComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.physics.ArenaFactory;
import dev.syndicate.core.physics.ShippedContentScene;
import dev.syndicate.core.vehicle.ShippedContent;
import dev.syndicate.model.AssetId;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The five shipped structures, loaded and placed into the arenas that declare them (D16-S7, D16-S4.7).
 *
 * <p>Everything else about structures is checked on synthetic content, which is the right way round:
 * a rule should be tested against the case it describes, not against whatever art happens to exist.
 * This is the test for the other question — whether the art that <em>does</em> exist actually loads
 * and actually gets placed — and it is the one that would have caught a structure whose part meshes
 * were written to a directory nothing scans.
 */
final class ShippedStructureContentTest {

    @Test
    @Tag("integration")
    void everyShippedStructureLoadsAndNamesPartsThatExist() {
        InMemoryAssetIndex assets = ShippedContent.load();
        assertThat(assets.structureIds())
                .containsExactly(
                        AssetId.of("str_city_block_low_01"),
                        AssetId.of("str_city_block_tall_01"),
                        AssetId.of("str_rocket_turret_01"),
                        AssetId.of("str_street_bench_01"),
                        AssetId.of("str_street_tree_01"));

        for (AssetId id : assets.structureIds()) {
            StructureDef structure = assets.structure(id);
            assertThat(assets.partType(structure.rootPartTypeId()))
                    .as("%s's root part is loaded", id.value())
                    .isNotNull();
            structure.assembly().parts().forEach(placement -> assertThat(assets.partType(placement.partTypeId()))
                    .as(
                            "%s names loaded part %s",
                            id.value(), placement.partTypeId().value())
                    .isNotNull());
            assertThat(structure.footprintRadiusM())
                    .as("%s has a footprint placement can space by (D16-R20)", id.value())
                    .isGreaterThan(StructureDef.MIN_FOOTPRINT_RADIUS_M);
            assertThat(structure.staticRoot())
                    .as("%s is bolted to the ground (D16-R20)", id.value())
                    .isTrue();
        }
    }

    @Test
    @Tag("integration")
    void theRocketTurretCarriesItsBuiltInWeapon() {
        InMemoryAssetIndex assets = ShippedContent.load();
        StructureDef turret = assets.structure(AssetId.of("str_rocket_turret_01"));
        // DEC-077: a built-in weapon is derived from the model's own geometry rather than fitted.
        // The pods are the top of the support chain, so the weapon is on the last part in it.
        AssetId topPart = turret.assembly()
                .parts()
                .get(turret.assembly().parts().size() - 1)
                .partTypeId();
        assertThat(assets.partType(topPart).weapon())
                .as("the turret's pods carry a weapon block")
                .isNotNull();
        assertThat(assets.partType(topPart).weapon().family().name()).isEqualTo("ROCKET");
    }

    @Test
    @Tag("integration")
    void bothShippedArenasDeclareStructuresAndActuallyGetThem() {
        InMemoryAssetIndex assets = ShippedContent.load();
        for (String arenaId : List.of("arena_desert_01", "arena_scrapyard_01")) {
            ArenaDef arena = assets.arena(AssetId.of(arenaId));
            assertThat(arena.structures())
                    .as("%s declares placement rules (D16-R21)", arenaId)
                    .isNotEmpty();
            for (StructurePlacementRule rule : arena.structures()) {
                assertThat(assets.structure(rule.structureId()))
                        .as(
                                "%s places %s, which is loaded",
                                arenaId, rule.structureId().value())
                        .isNotNull();
            }
        }
    }

    @Test
    @Tag("integration")
    void loadingTheScrapyardStandsStructuresOnIt() {
        try (ShippedContentScene scene = new ShippedContentScene(20260818L)) {
            ArenaDef arena = scene.assets().arena(AssetId.of("arena_scrapyard_01"));
            ArenaFactory.LoadedArena loaded =
                    ArenaFactory.load(scene.world(), scene.physics(), scene.shapes(), arena, scene.assets());
            assertThat(loaded.entities()).isNotEmpty();

            Family structures = scene.world().family(ComponentQuery.all(StructureComponent.class));
            assertThat(structures.size())
                    .as("the scrapyard's rules produced structures")
                    .isGreaterThanOrEqualTo(4);

            // Nothing overlaps anything else: D16-R23's footprint test, checked on the output rather
            // than trusted from the code that applies it.
            int[] ids = structures.snapshot();
            for (int i = 0; i < structures.size(); i++) {
                StructureComponent a = scene.world().getComponent(ids[i], StructureComponent.class);
                assertThat(a.partCount).as("a placed structure has parts").isGreaterThan(0);
                for (int j = i + 1; j < structures.size(); j++) {
                    StructureComponent b = scene.world().getComponent(ids[j], StructureComponent.class);
                    float separation = distanceBetween(scene, ids[i], ids[j]);
                    assertThat(separation)
                            .as("%s and %s do not overlap", a.structureId, b.structureId)
                            .isGreaterThanOrEqualTo(a.footprintRadiusM + b.footprintRadiusM - 0.01f);
                }
            }
        }
    }

    private static float distanceBetween(ShippedContentScene scene, int first, int second) {
        var a = scene.world().getComponent(first, dev.syndicate.core.component.TransformComponent.class).position;
        var b = scene.world().getComponent(second, dev.syndicate.core.component.TransformComponent.class).position;
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}

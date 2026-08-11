/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.physics.ArenaFactory;
import net.mgsx.gltf.scene3d.attributes.PBRColorAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute;

/**
 * The arena, drawn from the same numbers its collision is built from
 * (docs/08_asset_pipeline.md#D08-S4.7, DEV-014).
 *
 * <p>The shipped arena declares no visual geometry, and {@code ArenaFactory} generates its collision
 * from {@code boundsMin}, {@code boundsMax} and {@code groundY}. This builds the picture from the
 * same three numbers, so what a player sees and what a car collides with cannot drift apart — the
 * failure this class exists to prevent is a wall you can see and drive through, or one you cannot
 * see and stop dead against.
 *
 * <p>A grid is drawn into the floor rather than left flat. On a featureless plane a car at speed
 * looks stationary, which makes the handling impossible to judge — and judging the handling is what
 * the window is for.
 *
 * <p><b>Owner of one {@link Model}</b> (G19), disposed by {@link RenderContext}.
 */
public final class ArenaModel implements Disposable {

    /** Metres between floor grid lines. Roughly a car length, so speed reads at a glance. */
    public static final float GRID_SPACING_M = 5f;

    /** Metres. How wide a grid line is drawn. */
    public static final float GRID_LINE_WIDTH_M = 0.12f;

    /** Metres. How far above the floor the grid sits, to stay out of the depth fight with it. */
    public static final float GRID_LIFT_M = 0.01f;

    private final Model model;
    private final ModelInstance instance;

    public ArenaModel(ArenaDef arena) {
        Vector3 min = arena.boundsMin();
        Vector3 max = arena.boundsMax();
        float spanX = max.x - min.x;
        float spanZ = max.z - min.z;
        float centreX = (min.x + max.x) * 0.5f;
        float centreZ = (min.z + max.z) * 0.5f;
        float wallHeight = Math.max(2f, max.y - arena.groundY());
        float thickness = ArenaFactory.SURFACE_THICKNESS_M;

        ModelBuilder builder = new ModelBuilder();
        builder.begin();

        // The floor is a thin box rather than the infinite plane the physics uses: a plane has no
        // extent to draw, and the walls are what actually bound play (DEV-014's floor note).
        box(
                builder,
                "floor",
                surface(new Color(0.30f, 0.31f, 0.33f, 1f), 0.95f),
                spanX,
                thickness,
                spanZ,
                centreX,
                arena.groundY() - thickness * 0.5f,
                centreZ);

        Material gridMaterial = surface(new Color(0.44f, 0.46f, 0.50f, 1f), 0.8f);
        for (float x = gridStart(min.x); x <= max.x; x += GRID_SPACING_M) {
            box(
                    builder,
                    "grid_x_" + Math.round(x),
                    gridMaterial,
                    GRID_LINE_WIDTH_M,
                    GRID_LIFT_M,
                    spanZ,
                    x,
                    arena.groundY() + GRID_LIFT_M * 0.5f,
                    centreZ);
        }
        for (float z = gridStart(min.z); z <= max.z; z += GRID_SPACING_M) {
            box(
                    builder,
                    "grid_z_" + Math.round(z),
                    gridMaterial,
                    spanX,
                    GRID_LIFT_M,
                    GRID_LINE_WIDTH_M,
                    centreX,
                    arena.groundY() + GRID_LIFT_M * 0.5f,
                    z);
        }

        Material wallMaterial = surface(new Color(0.52f, 0.50f, 0.47f, 1f), 0.9f);
        float wallCentreY = arena.groundY() + wallHeight * 0.5f;
        box(builder, "wall_xmin", wallMaterial, thickness, wallHeight, spanZ, min.x, wallCentreY, centreZ);
        box(builder, "wall_xmax", wallMaterial, thickness, wallHeight, spanZ, max.x, wallCentreY, centreZ);
        box(builder, "wall_zmin", wallMaterial, spanX, wallHeight, thickness, centreX, wallCentreY, min.z);
        box(builder, "wall_zmax", wallMaterial, spanX, wallHeight, thickness, centreX, wallCentreY, max.z);

        model = builder.end();
        instance = new ModelInstance(model);
    }

    /** The drawable arena. One instance exists; it never moves. */
    public ModelInstance instance() {
        return instance;
    }

    /** The first grid line at or beyond a bound, so the grid is aligned to world axes, not to bounds. */
    private static float gridStart(float bound) {
        return (float) Math.ceil(bound / GRID_SPACING_M) * GRID_SPACING_M;
    }

    private static Material surface(Color colour, float roughness) {
        return new Material(
                PBRColorAttribute.createBaseColorFactor(colour),
                PBRFloatAttribute.createRoughness(roughness),
                PBRFloatAttribute.createMetallic(0f));
    }

    private static void box(
            ModelBuilder builder,
            String id,
            Material material,
            float width,
            float height,
            float depth,
            float x,
            float y,
            float z) {

        // node() both starts a new node and returns it, so it is called exactly once per box:
        // calling it twice would leave an empty node between every pair of surfaces.
        Node node = builder.node();
        node.id = id;
        node.translation.set(x, y, z);
        BoxShapeBuilder.build(
                builder.part(id, GL20.GL_TRIANGLES, Usage.Position | Usage.Normal, material), width, height, depth);
    }

    @Override
    public void dispose() {
        model.dispose();
    }
}

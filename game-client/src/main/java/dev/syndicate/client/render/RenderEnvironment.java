/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute;
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx;
import net.mgsx.gltf.scene3d.utils.IBLBuilder;

/**
 * The lighting every drawn thing shares (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>One sun and an image-based ambient. The IBL maps are not decoration: a glTF PBR material with
 * no environment to reflect renders as flat, dark plastic, so a car built from real art would look
 * worse in the game than it does in the tool that produced it. They are generated procedurally
 * rather than loaded, which keeps the client free of a texture asset the pipeline does not author.
 *
 * <p><b>Owner of three cubemaps and a BRDF lookup</b> (G19). Disposed by {@link RenderContext},
 * before the models that reference them are dropped.
 */
public final class RenderEnvironment implements Disposable {

    /** How bright the sun is. Above 1 the PBR shader's tonemapping starts to clip highlights. */
    public static final float SUN_INTENSITY = 3.2f;

    /** Ambient term, so a surface facing away from the sun is readable rather than black. */
    public static final float AMBIENT = 0.55f;

    /** gdx-gltf ships the split-sum BRDF integration its PBR shader samples. */
    private static final String BRDF_LUT_RESOURCE = "net/mgsx/gltf/shaders/brdfLUT.png";

    private final Environment environment = new Environment();
    private final DirectionalLightEx sun = new DirectionalLightEx();
    private final Cubemap diffuse;
    private final Cubemap specular;
    private final com.badlogic.gdx.graphics.Texture brdf;

    public RenderEnvironment() {
        // Angled rather than overhead: a light straight down flattens a car's shoulders, which are
        // the shapes that make one readable as a car at a distance.
        sun.direction.set(-0.45f, -0.8f, -0.4f).nor();
        sun.baseColor.set(Color.WHITE);
        sun.intensity = SUN_INTENSITY;
        sun.updateColor();
        environment.add(sun);
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, AMBIENT, AMBIENT, AMBIENT, 1f));

        IBLBuilder ibl = IBLBuilder.createOutdoor(sun);
        diffuse = ibl.buildIrradianceMap(32);
        specular = ibl.buildRadianceMap(8);
        ibl.dispose();

        // Shipped inside gdx-gltf rather than authored here; without it the specular response of
        // every metal in the scene is wrong at grazing angles, which is most of a car's silhouette.
        brdf = new com.badlogic.gdx.graphics.Texture(Gdx.files.classpath(BRDF_LUT_RESOURCE));
        environment.set(new PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdf));
        environment.set(new PBRCubemapAttribute(PBRCubemapAttribute.DiffuseEnv, diffuse));
        environment.set(new PBRCubemapAttribute(PBRCubemapAttribute.SpecularEnv, specular));
    }

    public Environment environment() {
        return environment;
    }

    /** The sun's direction, for anything that wants to shade consistently with it. */
    public Vector3 sunDirection() {
        return sun.direction;
    }

    @Override
    public void dispose() {
        diffuse.dispose();
        specular.dispose();
        brdf.dispose();
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

/**
 * One glTF material, reduced to the metallic-roughness core
 * (docs/08_asset_pipeline.md#D08-S4.5).
 *
 * <p>References to image data, never image data: {@code baseColorTextureUri} is the path the file
 * names and {@code baseColorImageIndex} is its slot in {@link GltfModel#images()}. Decoding a PNG
 * means a graphics library, and {@code game-core} is the module the dedicated server shares (D02-R9,
 * G17) — so the module that has a GL context does the decoding, and this one says where to look.
 *
 * <p>The material extensions the supplied art carries — {@code KHR_materials_clearcoat},
 * {@code KHR_materials_specular}, {@code KHR_materials_transmission},
 * {@code KHR_materials_emissive_strength} — are all declared in {@code extensionsUsed} and none in
 * {@code extensionsRequired}, which glTF 2.0 §3.12 defines as "ignore me safely". They are ignored.
 *
 * @param name the material's name, or {@code "material_<index>"} when it declares none
 * @param baseColorFactor linear RGBA, defaulting to opaque white as the specification requires
 * @param baseColorTextureUri the URI of the base colour image, or null
 * @param baseColorImageIndex index into {@link GltfModel#images()}, or -1
 * @param metallicFactor 0..1, default 1
 * @param roughnessFactor 0..1, default 1
 * @param emissiveFactor linear RGB, default black
 * @param alphaMode {@code OPAQUE}, {@code MASK} or {@code BLEND}
 * @param isDoubleSided whether back faces are drawn
 */
public record GltfMaterial(
        String name,
        float[] baseColorFactor,
        String baseColorTextureUri,
        int baseColorImageIndex,
        float metallicFactor,
        float roughnessFactor,
        float[] emissiveFactor,
        String alphaMode,
        boolean isDoubleSided) {

    /** The material glTF 2.0 §3.9.2 says a primitive with no {@code material} is drawn with. */
    public static GltfMaterial defaultMaterial() {
        return new GltfMaterial(
                "default", new float[] {1f, 1f, 1f, 1f}, null, -1, 1f, 1f, new float[] {0f, 0f, 0f}, "OPAQUE", false);
    }

    /** True when the material declares a base colour texture. */
    public boolean hasBaseColorTexture() {
        return baseColorImageIndex >= 0;
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

/**
 * Where one glTF image lives — never what is in it (docs/08_asset_pipeline.md#D08-S4.5).
 *
 * <p>glTF stores an image either as a URI beside the document or as a slice of a buffer inside it;
 * a {@code .glb} normally does the second and the Sketchfab exports in {@code art-source/} do the
 * first. Both arrive here as bytes-or-a-path and neither is decoded: a PNG becomes a texture only
 * in a module that has a GL context (D02-R9, G17).
 *
 * @param name the image's name, or {@code "image_<index>"} when it declares none
 * @param uri the URI relative to the glTF document, or null for an embedded image
 * @param mimeType the declared MIME type, or null
 * @param embedded the bytes for a buffer-view image, or null when {@code uri} is set
 */
public record GltfImage(String name, String uri, String mimeType, byte[] embedded) {

    /** True when the bytes are inside the document rather than beside it. */
    public boolean isEmbedded() {
        return embedded != null;
    }
}

/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * An {@link AssetIndex} built by hand rather than loaded from disk
 * (docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p>This is what the asset loader will populate once D08's pipeline exists, and what tests and the
 * verification harness populate today. Keeping it in main rather than in test sources is
 * deliberate: the loader's job then becomes parsing plus a call to {@link #put}, with no second
 * implementation of the index itself for the two to disagree about.
 *
 * <p>Backed by a {@code TreeMap} so any future iteration is by ascending asset id rather than hash
 * order (G3).
 */
public final class InMemoryAssetIndex implements AssetIndex {

    private final Map<AssetId, FractureManifest> manifests = new TreeMap<>();

    private final Map<AssetId, PartType> partTypes = new TreeMap<>();

    private final Map<AssetId, AssemblyDef> assemblies = new TreeMap<>();

    private final Map<AssetId, MaterialDef> materials = new TreeMap<>();

    /** Registers a manifest under its own {@link FractureManifest#manifestId()}. */
    public InMemoryAssetIndex put(FractureManifest manifest) {
        manifests.put(manifest.manifestId(), manifest);
        return this;
    }

    /** Registers a part type under its own {@link PartType#partTypeId()}. */
    public InMemoryAssetIndex put(PartType partType) {
        partTypes.put(partType.partTypeId(), partType);
        return this;
    }

    /** Registers an assembly under its own {@link AssemblyDef#assemblyId()}. */
    public InMemoryAssetIndex put(AssemblyDef assembly) {
        assemblies.put(assembly.assemblyId(), assembly);
        return this;
    }

    /** Registers a material under its own {@link MaterialDef#materialId()}. */
    public InMemoryAssetIndex put(MaterialDef material) {
        materials.put(material.materialId(), material);
        return this;
    }

    @Override
    public FractureManifest fractureManifest(AssetId manifestId) {
        return manifestId == null ? null : manifests.get(manifestId);
    }

    @Override
    public PartType partType(AssetId partTypeId) {
        return partTypeId == null ? null : partTypes.get(partTypeId);
    }

    @Override
    public AssemblyDef assembly(AssetId assemblyId) {
        return assemblyId == null ? null : assemblies.get(assemblyId);
    }

    @Override
    public MaterialDef material(AssetId materialId) {
        return materialId == null ? null : materials.get(materialId);
    }

    /** Every loaded manifest, by ascending id. */
    public Map<AssetId, FractureManifest> manifests() {
        return Collections.unmodifiableMap(manifests);
    }

    /** Every loaded part type, by ascending id. */
    public Map<AssetId, PartType> partTypes() {
        return Collections.unmodifiableMap(partTypes);
    }

    /** Every loaded assembly, by ascending id. */
    public Map<AssetId, AssemblyDef> assemblies() {
        return Collections.unmodifiableMap(assemblies);
    }

    /** Every loaded material, by ascending id. */
    public Map<AssetId, MaterialDef> materials() {
        return Collections.unmodifiableMap(materials);
    }
}

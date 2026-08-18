/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.core.ai.BotDifficultyTable;
import dev.syndicate.model.AssetId;
import java.util.Collections;
import java.util.List;
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

    private final Map<AssetId, ArenaDef> arenas = new TreeMap<>();

    private final Map<AssetId, StructureDef> structures = new TreeMap<>();

    private final Map<AssetId, WeaponDef> weapons = new TreeMap<>();

    /**
     * Assemblies a player configured, kept apart from the shipped catalogue on purpose.
     *
     * <p>A garage loadout produces a real {@link AssemblyDef} that the spawn path has to be able to
     * resolve, and it must not become a vehicle anyone else can be given: it is one player's
     * configuration of one shipped vehicle, not a new entry in the roster. Holding it in a second
     * map is what keeps that structural — {@link #assemblyIds()} and {@link #assemblies()} are the
     * catalogue and never see it, so a bot cannot draw it (D11-R12) and the garage cannot list it,
     * while {@link #assembly(AssetId)} still finds it.
     */
    private final Map<AssetId, AssemblyDef> configured = new TreeMap<>();

    private BotDifficultyTable botDifficulties = BotDifficultyTable.defaults();

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

    /** Registers a structure under its own {@link StructureDef#structureId()}. */
    public InMemoryAssetIndex put(StructureDef structure) {
        structures.put(structure.structureId(), structure);
        return this;
    }

    /** Registers an arena under its own {@link ArenaDef#arenaId()}. */
    public InMemoryAssetIndex put(ArenaDef arena) {
        arenas.put(arena.arenaId(), arena);
        return this;
    }

    /** Registers a weapon under its own {@link WeaponDef#weaponId()}. */
    public InMemoryAssetIndex put(WeaponDef weapon) {
        weapons.put(weapon.weaponId(), weapon);
        return this;
    }

    /**
     * Registers an assembly a player configured, resolvable but outside the catalogue.
     *
     * <p>Replaces any previous configuration under the same id, so reconfiguring in the garage
     * between matches does not accumulate assemblies for the length of the session.
     */
    public InMemoryAssetIndex putConfigured(AssemblyDef assembly) {
        configured.put(assembly.assemblyId(), assembly);
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
        if (assemblyId == null) {
            return null;
        }
        AssemblyDef shipped = assemblies.get(assemblyId);
        return shipped != null ? shipped : configured.get(assemblyId);
    }

    /** The weapon with this id, or null. */
    public WeaponDef weapon(AssetId weaponId) {
        return weaponId == null ? null : weapons.get(weaponId);
    }

    @Override
    public MaterialDef material(AssetId materialId) {
        return materialId == null ? null : materials.get(materialId);
    }

    @Override
    public StructureDef structure(AssetId structureId) {
        return structureId == null ? null : structures.get(structureId);
    }

    @Override
    public List<AssetId> structureIds() {
        return List.copyOf(structures.keySet());
    }

    @Override
    public ArenaDef arena(AssetId arenaId) {
        return arenaId == null ? null : arenas.get(arenaId);
    }

    /** Replaces the bot difficulty table. */
    public InMemoryAssetIndex put(BotDifficultyTable table) {
        botDifficulties = table == null ? BotDifficultyTable.defaults() : table;
        return this;
    }

    @Override
    public BotDifficultyTable botDifficulties() {
        return botDifficulties;
    }

    @Override
    public List<AssetId> assemblyIds() {
        // The backing map is sorted by id, so the key order is already the order D11-R12's seeded
        // choice needs; the copy is what stops a caller holding a view that changes under it.
        return List.copyOf(assemblies.keySet());
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

    /** Every loaded structure, by ascending id. */
    public Map<AssetId, StructureDef> structures() {
        return Collections.unmodifiableMap(structures);
    }

    /** Every loaded arena, by ascending id. */
    public Map<AssetId, ArenaDef> arenas() {
        return Collections.unmodifiableMap(arenas);
    }

    /** Every loaded modular weapon, by ascending id. */
    public Map<AssetId, WeaponDef> weapons() {
        return Collections.unmodifiableMap(weapons);
    }
}

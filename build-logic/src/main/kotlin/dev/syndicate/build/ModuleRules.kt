package dev.syndicate.build

/**
 * The module catalogue of docs/02_technical_architecture.md#D02-S4.5, as data.
 *
 * The table in D02 is prose; the checks need something to compare against, so it is
 * duplicated here deliberately (DEC-006). Changing the blueprint table without changing
 * this object is the drift this file exists to make expensive — `:validateDocs` cannot
 * detect it, but the first module that violates the stale rule will.
 */
object ModuleRules {

    /** D02-R13: the single root package each module's sources must sit under. */
    val rootPackages: Map<String, String> = mapOf(
        "shared-models" to "dev.syndicate.model",
        "game-core" to "dev.syndicate.core",
        "game-client" to "dev.syndicate.client",
        "game-server-headless" to "dev.syndicate.server",
        "asset-pipeline" to "dev.syndicate.pipeline",
        "test-environment" to "dev.syndicate.verify",
        "memory-system" to "dev.syndicate.memory",
    )

    /**
     * Layer numbers from D02-S5.6. An edge `a -> b` is legal only when
     * `layers[a] > layers[b]`, which makes cycles unrepresentable rather than merely
     * forbidden — two modules cannot both be strictly above the other.
     */
    val layers: Map<String, Int> = mapOf(
        "shared-models" to 0,
        "memory-system" to 0,
        "game-core" to 1,
        "asset-pipeline" to 1,
        "game-client" to 2,
        "game-server-headless" to 2,
        "test-environment" to 2,
    )

    /**
     * The "MUST NOT depend on" column of D02-S4.5, restricted to internal modules.
     * The library bans in that column are enforced by [HeadlessSafetyCheckTask] and by
     * the dependency declarations themselves, not here.
     */
    val forbiddenEdges: Map<String, Set<String>> = mapOf(
        "game-client" to setOf("game-server-headless", "test-environment"),
        "game-server-headless" to setOf("game-client", "test-environment"),
        "asset-pipeline" to setOf("game-client"),
        "test-environment" to setOf("game-server-headless"),
        "memory-system" to setOf(
            "game-core",
            "game-client",
            "game-server-headless",
            "asset-pipeline",
            "test-environment",
        ),
        "shared-models" to setOf(
            "game-core",
            "game-client",
            "game-server-headless",
            "asset-pipeline",
            "test-environment",
            "memory-system",
        ),
    )

    /**
     * Packages no `game-core` class may import (D02-R9). These are the backends, the UI
     * toolkit, and the rendering half of `g3d` — everything whose presence would mean the
     * dedicated server needs a GL context to run (G17).
     */
    val bannedCorePackages: List<String> = listOf(
        "com.badlogic.gdx.backends",
        "com.badlogic.gdx.scenes.scene2d",
        "com.badlogic.gdx.graphics.g3d.shaders",
        "com.badlogic.gdx.graphics.g3d.ModelBatch",
        "com.badlogic.gdx.graphics.g3d.Environment",
        "com.badlogic.gdx.graphics.g2d",
        "com.badlogic.gdx.graphics.glutils",
        "java.awt",
        "javax.swing",
    )

    /**
     * The narrow allowlist D02-R9 requires: mesh *data* types that collision shape
     * construction genuinely needs, which happen to live under a banned prefix. Each entry
     * is a full type name, never a package, so widening the hole is a visible edit.
     */
    val allowedGraphicsTypes: List<String> = listOf(
        "com.badlogic.gdx.graphics.g3d.model.MeshPart",
        "com.badlogic.gdx.graphics.VertexAttributes",
        "com.badlogic.gdx.graphics.VertexAttribute",
        "com.badlogic.gdx.graphics.Mesh",
    )

    /**
     * Component types classified `C` in D04-S4.3 that live in `game-core` and must be read
     * by nothing there (D07-R18, AC-D07-10, G6). They live in `game-core` because they are
     * plain data with a wire-stable index (D04-R22), not because anything in it uses them.
     */
    val cosmeticOnlyTypes: List<String> = listOf(
        "DamageVisualComponent",
    )

    /**
     * The two files allowed to name a type in [cosmeticOnlyTypes]: its own declaration, and
     * the append-only catalogue that has to list every component to number it. Named by file
     * so a third exemption is a visible edit rather than a widened pattern.
     */
    val cosmeticReferenceExemptions: Set<String> = setOf(
        "DamageVisualComponent.java",
        "ComponentCatalogue.java",
    )
}

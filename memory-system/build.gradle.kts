plugins {
    id("syndicate.application-conventions")
}

description = ".agent-memory tooling: index regeneration and entry lint (D13-S5.5, D13-S5.8)."

// Deliberately no internal dependencies. D02-S4.5 lists `shared-models` as optional, but
// both modules sit at layer 0, so the strict rule of D02-S5.6 forbids the edge; the more
// restrictive reading wins (D00-R22). This module needs nothing from it anyway.

application {
    mainClass.set("dev.syndicate.memory.MemoryToolMain")
    applicationName = "syndicate-memory"
}

val memoryRoot = rootProject.layout.projectDirectory.dir(".agent-memory")
val docsRoot = rootProject.layout.projectDirectory.dir("docs")

/**
 * Regenerates `.agent-memory/INDEX.md` from the entry files (D13-S5.5, D13-R19).
 * `INDEX.md` is generated, never hand-edited; lint rule L13 enforces that.
 */
val regenerateIndex = tasks.register<JavaExec>("regenerateIndex") {
    group = "documentation"
    description = "Regenerates .agent-memory/INDEX.md from the entry files (D13-S5.5)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.syndicate.memory.MemoryToolMain")
    // Entry titles and blueprint prose contain em-dashes; without this they are mangled
    // in the build log on a platform whose default encoding is not UTF-8.
    defaultCharacterEncoding = "UTF-8"
    args("regenerate", memoryRoot.asFile.absolutePath, docsRoot.asFile.absolutePath)
}

/** Lint rules L1–L15 of D13-S5.8. CI stage 0 gate (D12-S5.4). */
val lintMemory = tasks.register<JavaExec>("lintMemory") {
    group = "verification"
    description = "Lints .agent-memory entries against D13-S5.8 rules L1-L15."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.syndicate.memory.MemoryToolMain")
    defaultCharacterEncoding = "UTF-8"
    args("lint", memoryRoot.asFile.absolutePath, docsRoot.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(lintMemory)
}

// `regenerateIndex` writes the file that `lintMemory` L13 verifies; declaring the order
// keeps a combined invocation from checking a stale index.
lintMemory.configure { mustRunAfter(regenerateIndex) }

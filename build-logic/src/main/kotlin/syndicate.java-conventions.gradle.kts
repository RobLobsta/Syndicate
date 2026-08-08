import dev.syndicate.build.LayeringCheckTask
import dev.syndicate.build.ModuleRules
import dev.syndicate.build.PackageRootCheckTask

/**
 * Conventions shared by every JVM module (docs/02_technical_architecture.md#D02-S5.5).
 *
 * Everything a module needs to conform to D02 lives here, so an individual module's
 * `build.gradle.kts` declares only its dependencies. A convention that lives in one
 * place cannot drift between modules.
 */

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = rootProject.group
version = rootProject.version

java {
    toolchain {
        // D02-R1: pinned to 17 so bytecode is reproducible regardless of the JDK a
        // developer happens to have (D02-E2).
        languageVersion.set(JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt()))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(libs.findVersion("java").get().requiredVersion.toInt())
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}

tasks.withType<Jar>().configureEach {
    manifest {
        // D02-R17 / AC-D02-7: every artifact carries the version from gradle.properties.
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Syndicate",
        )
    }
}

dependencies {
    // Libraries take the SLF4J API only; bindings belong to applications (D02-S4.1).
    "implementation"(libs.findLibrary("slf4j-api").get())

    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findBundle("testing").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    defaultCharacterEncoding = "UTF-8"
    useJUnitPlatform {
        // Test level selection (D12-S4.1): `./gradlew test -Ptags=unit,integration`.
        // With no -Ptags, every level except the explicitly opt-in ones runs.
        val requested = providers.gradleProperty("tags").orNull
        if (requested != null) {
            includeTags(*requested.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
        } else {
            // Quarantined tests never gate (D12-S5.8 rule 2). Benchmarks are nightly.
            excludeTags("quarantined")
        }
    }

    // G4/G5: tests are seeded and must never read wall-clock time. G17: no display, ever.
    systemProperty("syndicate.seed", "1337")
    systemProperty("java.awt.headless", "true")

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }

    // D12-S5.8 rule 1: there is no retry mechanism, deliberately. Do not add one.
    failFast = false
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        licenseHeaderFile(rootProject.file("gradle/HEADER"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---- Guardrails (D02-S5.6, D02-R13) --------------------------------------------------

// Not named `moduleName`: inside the LayeringCheckTask configure block that identifier
// resolves to the task's own property, which makes `set(moduleName)` self-referential.
val thisModule = project.name

val checkPackageRoots = tasks.register<PackageRootCheckTask>("checkPackageRoots") {
    rootPackage.set(
        ModuleRules.rootPackages[thisModule]
            ?: error("no root package declared for '$thisModule' in ModuleRules (D02-R13)"),
    )
    sources.from(project.the<SourceSetContainer>()["main"].allJava.srcDirs)
}

val checkLayering = tasks.register<LayeringCheckTask>("checkLayering") {
    moduleName.set(thisModule)
    // Captured at configuration time so the task stays configuration-cache compatible.
    projectDependencies.set(
        provider {
            configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .flatMap { it.dependencies.withType<ProjectDependency>() }
                .map { it.path.removePrefix(":") }
        },
    )
}

tasks.named("check") {
    dependsOn(checkPackageRoots, checkLayering)
}

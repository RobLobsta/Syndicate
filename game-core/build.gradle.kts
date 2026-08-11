import dev.syndicate.build.CosmeticIsolationCheckTask
import dev.syndicate.build.HeadlessSafetyCheckTask

plugins {
    id("syndicate.java-conventions")
}

description = "Simulation: ECS, physics, vehicle, damage, net, AI, match. Headless-safe (D02-S4.5)."

dependencies {
    api(projects.sharedModels)

    // gdx core supplies the math types (Vector3/Matrix4/Quaternion) so the project has
    // exactly one math type system (D02-S4.1). Bullet is `api` because component fields
    // hold native handles (D04-S4.3.1).
    api(libs.gdx.core)
    api(libs.gdx.bullet)

    // Natives for the host platform. `runtimeOnly` keeps them off the compile classpath,
    // so a missing native surfaces as E1 at bootstrap rather than as a compile error.
    runtimeOnly(variantOf(libs.gdx.bullet.platform) { classifier("natives-desktop") })

    implementation(libs.kryonet)
    implementation(libs.bundles.jackson)
}

/**
 * G17 is structural here, not aspirational: `game-core` is the module every runtime mode
 * shares, so anything graphical that lands in it silently breaks the dedicated server
 * (D02-R9, AC-D02-4).
 */
val checkHeadlessSafety = tasks.register<HeadlessSafetyCheckTask>("checkHeadlessSafety") {
    sources.from(sourceSets["main"].allJava.srcDirs)
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
    reportRoot.set(rootProject.layout.projectDirectory)
}

/**
 * G6 is structural here too: `game-core` holds the cosmetic components because they need a
 * wire-stable index (D04-R22), and nothing in it may read one (D07-R18, AC-D07-10).
 */
val checkCosmeticIsolation = tasks.register<CosmeticIsolationCheckTask>("checkCosmeticIsolation") {
    sources.from(sourceSets["main"].allJava.srcDirs)
    reportRoot.set(rootProject.layout.projectDirectory)
}

tasks.named("check") {
    dependsOn(checkHeadlessSafety, checkCosmeticIsolation)
}

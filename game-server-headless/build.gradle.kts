plugins {
    id("syndicate.application-conventions")
}

description = "Dedicated authoritative server: mode boot, tick loop, connections, admin console (D02-S4.5)."

dependencies {
    implementation(projects.gameCore)
    implementation(projects.sharedModels)

    // The headless backend gives the libGDX Application lifecycle and Gdx.files with no
    // GL context, which is what makes G17 achievable rather than merely intended.
    implementation(libs.gdx.backend.headless)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.syndicate.server.ServerMain")
    applicationName = "syndicate-server"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("syndicate-server")
    archiveClassifier.set("")
}

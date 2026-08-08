plugins {
    id("syndicate.application-conventions")
}

description = "Window, GL context, render systems, camera, HUD, input, audio (D02-S4.5)."

dependencies {
    implementation(projects.gameCore)
    implementation(projects.sharedModels)

    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.gdx.gltf)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.syndicate.client.ClientMain")
    applicationName = "syndicate-client"
}

// D02-S4.8 / D02-E12: the distribution enumerates `assets/` explicitly. `art-source/`
// and `fixtures/` are never shipped, and a CI check greps the zip to prove it.
distributions {
    named("main") {
        contents {
            from(rootProject.layout.projectDirectory.dir("assets")) {
                into("assets")
            }
        }
    }
}

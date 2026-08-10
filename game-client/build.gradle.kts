plugins {
    id("syndicate.application-conventions")
}

description = "Window, GL context, render systems, camera, HUD, input, audio (D02-S4.5)."

dependencies {
    implementation(projects.gameCore)
    implementation(projects.sharedModels)

    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.gdx.gltf)

    // A gamepad is a first-class input device here, not a fallback: see
    // `dev.syndicate.client.input`. `core` is the API the systems compile against; `desktop`
    // is the backend and is a runtime concern, so nothing in this module can accidentally
    // import a backend type.
    implementation(libs.gdx.controllers.core)
    runtimeOnly(libs.gdx.controllers.desktop)
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

plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin marker artifacts, so build-logic's plugin versions also come from the
    // version catalog and never drift from the main build (AC-D02-5).
    implementation(libs.spotless.gradle)
    implementation(libs.shadow.gradle)
}

// build-logic compiles against the Gradle daemon's JVM deliberately: pinning a
// toolchain here would force a second JDK download before the main build's
// toolchain can even be configured.

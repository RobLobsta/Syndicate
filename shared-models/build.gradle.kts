plugins {
    id("syndicate.java-conventions")
}

description = "Immutable DTOs, enums, IDs, and launch configuration. No behaviour beyond validation (D02-S4.5)."

dependencies {
    // Jackson is `api` because the DTO annotations are part of this module's surface.
    api(libs.bundles.jackson)
}

/**
 * Root build (docs/02_technical_architecture.md#D02-S5.5).
 *
 * Per-module configuration lives in the `syndicate.*-conventions` plugins in
 * `build-logic`, never in a `subprojects { }` block: cross-project configuration from
 * the root is what makes a multi-project build slow and impossible to reason about one
 * module at a time.
 */

plugins {
    id("syndicate.root-conventions")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

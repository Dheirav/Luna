plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Refreshes the vendored copy of the golden fixture from ../spec.
 *
 * The fixture lives at the repo root so it stays language-neutral, but the Android project is
 * transferred to Windows on its own for builds — where ../spec may not exist. A copy is therefore
 * committed under core/src/test/resources, and this task keeps it current.
 *
 * Run `./gradlew syncSpec` after editing anything in spec/. FixtureTest fails on drift when the
 * source directory is present, so a stale copy cannot pass silently.
 */
val specDir = rootDir.resolve("../spec")

val syncFixtures by tasks.registering(Copy::class) {
    onlyIf { specDir.exists() }
    from(specDir) { include("cycle_fixtures.json") }
    into(rootDir.resolve("core/src/test/resources"))
}

val syncSeed by tasks.registering(Copy::class) {
    onlyIf { specDir.exists() }
    from(specDir) { include("seed_periods.json") }
    into(rootDir.resolve("app/src/main/assets"))
}

tasks.register("syncSpec") {
    group = "spec"
    description = "Refreshes the vendored fixture and seed from ../spec."
    dependsOn(syncFixtures, syncSeed)
}

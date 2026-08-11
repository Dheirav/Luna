plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Plain Kotlin/JVM, deliberately. The cycle engine touches no Android API, so its tests run on
 * the JVM in under a second with no emulator, no device and no SDK — which is what makes the
 * golden fixture cheap enough to run on every change.
 */

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

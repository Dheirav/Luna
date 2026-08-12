import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing, loaded from `keystore/keystore.properties` — which is gitignored, along with the
 * keystore itself.
 *
 * **Absent by design on any machine but the author's**, so this must degrade rather than fail: CI has
 * no keystore and must still build, and a fresh clone must still compile. When the file is missing,
 * `signingConfigs` is simply not created and `assembleRelease` produces an unsigned APK exactly as it
 * did before — which cannot be installed, but that is honest and is the pre-existing behaviour.
 *
 * The alternative, hardcoding a password or reading an environment variable that is usually unset,
 * either commits a credential or fails confusingly. This fails only at the point where signing is
 * actually attempted.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val canSignRelease = keystoreProperties.getProperty("storeFile") != null &&
    rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}").exists()

android {
    namespace = "com.dheirav.cycletracker"
    compileSdk = 35

    defaultConfig {
        // The app is called Luna; the applicationId deliberately still says cycletracker.
        //
        // Android identifies an installed app by this string. Changing it does not rename the
        // app — it creates a second, unrelated one, and strands the original's database where
        // nothing can reach it. For a tracker whose entire value is a multi-year history, a
        // cosmetic rename is not worth that. The package name is invisible to the user anyway.
        applicationId = "com.dheirav.cycletracker"
        // 31 rather than 34: java.time native with no desugaring, Material You, modern widget
        // APIs. Targeting the A35's shipping level would buy nothing and lock out a spare device.
        minSdk = 31
        targetSdk = 35
        // Needed by the Room migration test in src/androidTest. That test needs a device or
        // emulator, so it is not part of CI — see .github/workflows/tests.yml.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"

    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when no keystore is present, which leaves the APK unsigned rather than failing.
            signingConfig = signingConfigs.findByName("release")
            ndk {
                // One known phone, so the shipped APK carries one ABI.
                //
                // **Release only, and it stopped being free.** This used to sit in `defaultConfig`
                // with a comment saying it "costs nothing while there are no native libs" — no longer
                // true. Compose pulls in `libandroidx.graphics.path.so`, so the filter now genuinely
                // excludes ABIs, and an arm64-only debug APK **cannot install on an x86_64 emulator**.
                // AGP reports that as "Found 1 connected device(s), 0 of which were compatible",
                // which reads like a broken emulator and is not.
                //
                // That is what blocked the migration test in CI. Debug is unfiltered so it installs
                // anywhere; release keeps the single ABI, where the size actually matters.
                abiFilters += "arm64-v8a"
            }

            // The difference between ~12 MB and ~35 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"

            // Opt-in shrinking for the debug build: `./gradlew :app:installDebug -PminifyDebug`
            //
            // The plain debug APK is ~25 MB and takes one to three minutes to push over wireless
            // debugging. The Redmi's adb port cycles faster than that, so installs were routinely
            // dying mid-transfer — the shrunk build lands in seconds instead.
            //
            // Screenshots still work: FLAG_SECURE is gated on ApplicationInfo.FLAG_DEBUGGABLE
            // (see MainActivity), which this build still sets. It is the *release* build that
            // blocks screencap, not the minified one.
            //
            // Off by default because R8 adds a slow pass to every build, and most builds never
            // reach a phone. Turning it on also exercises the release ProGuard rules earlier,
            // which is a side benefit rather than the point.
            // `hasProperty` rather than `providers.gradleProperty(...).isPresent`: a bare
            // `-PminifyDebug` sets the value to an empty string, which the provider API reports
            // as absent, so the flag silently did nothing.
            val shrink = project.hasProperty("minifyDebug")
            isMinifyEnabled = shrink
            isShrinkResources = shrink
            if (shrink) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }
    }

    // `MigrationTestHelper` reads the exported schema from the **test APK's assets**, not from disk.
    // Without this the instrumented migration test compiles, installs, and then throws at runtime
    // looking for a schema that was never packaged — which is precisely how it failed on its first CI
    // run. `room.schemaLocation` above only writes the JSON; this is what ships it.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // Exported schemas make Room migrations reviewable in the diff rather than guessed at.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    // Instrumented tests exist for one reason: the Room migration. `MigrationTestHelper` replays a
    // real schema against a real SQLite, which cannot be done on the JVM, and a broken migration
    // does not lose data quietly — there is no fallbackToDestructiveMigration, so it stops the app
    // opening at all with the user's history stranded inside.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

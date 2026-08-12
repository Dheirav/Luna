plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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

        ndk {
            // One known phone. Kept even though SQLCipher was dropped (see HANDOVER) — costs
            // nothing while there are no native libs, and is already correct if any arrive.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
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

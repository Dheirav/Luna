package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The golden fixture lives at the repo root in spec/ so it stays language-neutral, but a copy is
 * vendored into test resources because the android/ directory is transferred to Windows on its own
 * for builds, where ../spec does not exist.
 *
 * Two copies means drift. This catches it whenever the source directory is reachable — so editing
 * the spec in WSL and forgetting `./gradlew syncSpec` fails loudly instead of testing stale rules.
 * On Windows the source is absent and the check simply skips.
 */
class SpecDriftTest {

    @Test
    fun `vendored fixture matches spec directory when it is present`() {
        assertMatchesSource("cycle_fixtures.json", "src/test/resources/cycle_fixtures.json")
    }


    private fun assertMatchesSource(name: String, vendoredPath: String) {
        // Gradle runs tests with the module directory as the working directory: android/core.
        val source = File("../../spec/$name")
        assumeTrue("spec/ not reachable — standalone build, skipping drift check", source.exists())

        val vendored = File(vendoredPath)
        assertEquals(
            "$name is stale. Run ./gradlew syncSpec from android/ to refresh the vendored copy.",
            source.readText().trim(),
            vendored.readText().trim(),
        )
    }
}

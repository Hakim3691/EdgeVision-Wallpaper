package com.edgevision.wallpaper.bdd

/**
 * Minimal Given/When/Then step recorder for executable specifications.
 *
 * Specs are ordinary JUnit 4 tests, so they run in the same `testDebugUnitTest`
 * task as the rest of the suite and are therefore gated by CI. The steps exist to
 * make a failure read like the scenario it came from:
 *
 * ```
 * Then the newest frame is the only frame retained failed:
 *   expected:<frame-3> but was:<frame-1>
 * ```
 *
 * The suite deliberately avoids a Gherkin runner dependency: adding one would
 * mean a second test engine alongside the Robolectric/JUnit 4 UI tests.
 */
object Given {
    operator fun invoke(description: String, block: () -> Unit) = Steps.run("Given", description, block)
}

object When {
    operator fun invoke(description: String, block: () -> Unit) = Steps.run("When", description, block)
}

object Then {
    operator fun invoke(description: String, block: () -> Unit) = Steps.run("Then", description, block)
}

private object Steps {
    fun run(keyword: String, description: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            throw AssertionError("$keyword $description failed: ${throwable.message}", throwable)
        }
    }
}

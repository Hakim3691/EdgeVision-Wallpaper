package com.edgevision.wallpaper.bdd

/**
 * Minimal Given/When/Then harness shared by the executable specifications.
 *
 * A failing step reports the step text that failed, so a red build names the
 * behaviour that broke instead of only a line number. The DSL is deliberately
 * tiny: specifications are ordinary JUnit tests, so they run in the same
 * `:app:testDebugUnitTest` task that CI already gates every push on.
 */
private fun step(prefix: String, description: String, block: () -> Unit) {
    try {
        block()
    } catch (throwable: Throwable) {
        throw AssertionError("$prefix \"$description\" failed: ${throwable.message}", throwable)
    }
}

fun Given(description: String, block: () -> Unit) = step("Given", description, block)

fun When(description: String, block: () -> Unit) = step("When", description, block)

fun Then(description: String, block: () -> Unit) = step("Then", description, block)

fun And(description: String, block: () -> Unit) = step("And", description, block)

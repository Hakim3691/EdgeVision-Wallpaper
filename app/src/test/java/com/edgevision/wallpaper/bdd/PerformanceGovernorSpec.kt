package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.diagnostics.PerformanceGovernor
import com.edgevision.wallpaper.diagnostics.PerformanceInputs
import com.edgevision.wallpaper.diagnostics.PerformanceMode
import com.edgevision.wallpaper.diagnostics.ThermalPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: battery- and thermal-aware performance modes.
 *
 * Heat and a low battery may slow the pipeline, but detection must never be
 * switched off: a late alert is still useful, a missing one is not.
 */
class PerformanceGovernorSpec {

    @Test
    fun `explicit modes set the pipeline cadence`() {
        Then("each mode maps to its documented interval") {
            assertEquals(
                66L,
                PerformanceGovernor.profile(PerformanceInputs(mode = PerformanceMode.PERFORMANCE)).inferenceIntervalMs,
            )
            assertEquals(
                100L,
                PerformanceGovernor.profile(PerformanceInputs(mode = PerformanceMode.BALANCED)).inferenceIntervalMs,
            )
            assertEquals(
                250L,
                PerformanceGovernor.profile(PerformanceInputs(mode = PerformanceMode.BATTERY_SAVER)).inferenceIntervalMs,
            )
        }
    }

    @Test
    fun `automatic mode follows the battery`() {
        Given("a half-full phone that is not charging") {
            val low = PerformanceGovernor.profile(
                PerformanceInputs(mode = PerformanceMode.AUTOMATIC, batteryPercent = 30, charging = false),
            )

            Then("battery saver is chosen") {
                assertEquals(PerformanceMode.BATTERY_SAVER, low.mode)
                assertEquals(250L, low.inferenceIntervalMs)
            }
        }

        And("the same phone charging runs balanced") {
            val charging = PerformanceGovernor.profile(
                PerformanceInputs(mode = PerformanceMode.AUTOMATIC, batteryPercent = 30, charging = true),
            )
            assertEquals(PerformanceMode.BALANCED, charging.mode)
            assertEquals(100L, charging.inferenceIntervalMs)
        }
    }

    @Test
    fun `thermal pressure overrides a performance profile`() {
        Given("performance mode on a severely hot device") {
            val profile = PerformanceGovernor.profile(
                PerformanceInputs(mode = PerformanceMode.PERFORMANCE, thermal = ThermalPressure.SEVERE),
            )

            Then("the cadence is widened to the critical interval") {
                assertEquals(500L, profile.inferenceIntervalMs)
            }
        }
    }

    @Test
    fun `battery saver and low battery widen the interval without disabling detection`() {
        Given("a performance build in system battery saver") {
            val saver = PerformanceGovernor.profile(
                PerformanceInputs(mode = PerformanceMode.PERFORMANCE, powerSaveMode = true),
            )

            Then("the interval widens and detection stays on") {
                assertEquals(250L, saver.inferenceIntervalMs)
                assertTrue(saver.detectionEnabled)
            }
        }

        And("a low battery that is not charging also slows the pipeline") {
            val low = PerformanceGovernor.profile(
                PerformanceInputs(mode = PerformanceMode.BALANCED, batteryPercent = 10, charging = false),
            )
            assertEquals(250L, low.inferenceIntervalMs)
        }
    }

    @Test
    fun `detection is enabled in every profile`() {
        Then("no combination disables the detector") {
            PerformanceMode.entries.forEach { mode ->
                ThermalPressure.entries.forEach { thermal ->
                    val profile = PerformanceGovernor.profile(
                        PerformanceInputs(
                            mode = mode,
                            thermal = thermal,
                            batteryPercent = 5,
                            charging = false,
                            powerSaveMode = true,
                        ),
                    )
                    assertTrue("detection disabled for $mode/$thermal", profile.detectionEnabled)
                }
            }
        }
    }
}

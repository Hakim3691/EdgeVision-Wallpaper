package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.diagnostics.LoadPolicy
import com.edgevision.wallpaper.diagnostics.ThermalPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: the wallpaper slows down before the phone does.
 *
 * Camera preview plus inference runs for as long as the home screen is visible, so
 * a wall charger is not the issue: sustained load on a warm device is. The pipeline
 * now trades preview smoothness for temperature, and never trades away detection.
 */
class AdaptiveLoadSpec {

    @Test
    fun `a cool phone converts frames at the normal cadence`() {
        Given("thermal status NONE and battery saver off") {
            val profile = LoadPolicy.profile(LoadPolicy.pressureFor(0), powerSaveMode = false)

            Then("frames convert roughly fifteen times per second") {
                assertEquals(66L, profile.frameIntervalMs)
                assertEquals("normal", profile.label)
                assertTrue("detection keeps running", profile.detectionEnabled)
            }
        }
    }

    @Test
    fun `thermal pressure slows the preview monotonically`() {
        Given("the four reported thermal pressures") {
            val intervals = listOf(
                ThermalPressure.NONE,
                ThermalPressure.LIGHT,
                ThermalPressure.MODERATE,
                ThermalPressure.SEVERE,
                ThermalPressure.CRITICAL,
            ).map { LoadPolicy.profile(it, powerSaveMode = false) }

            Then("each step is at least as slow as the one before it") {
                intervals.zipWithNext().forEach { (cooler, hotter) ->
                    assertTrue(
                        "${hotter.label} must not run faster than ${cooler.label}",
                        hotter.frameIntervalMs >= cooler.frameIntervalMs,
                    )
                }
                assertEquals(66L, intervals[0].frameIntervalMs)
                assertEquals(66L, intervals[1].frameIntervalMs)
                assertEquals(150L, intervals[2].frameIntervalMs)
                assertEquals(250L, intervals[3].frameIntervalMs)
                assertEquals(500L, intervals[4].frameIntervalMs)
            }
        }
    }

    @Test
    fun `the hottest state still reports hazards`() {
        Given("a device reporting the shutdown thermal status") {
            val pressure = LoadPolicy.pressureFor(6)
            val profile = LoadPolicy.profile(pressure, powerSaveMode = false)

            Then("detection stays on and only the conversion cadence collapses") {
                assertEquals(ThermalPressure.CRITICAL, pressure)
                assertTrue("hazard alerts are never disabled", profile.detectionEnabled)
                assertEquals(500L, profile.frameIntervalMs)
                assertEquals("thermal-critical", profile.label)
            }
        }
    }

    @Test
    fun `battery saver slows conversion without silencing detection`() {
        Given("a cool phone in battery saver") {
            val profile = LoadPolicy.profile(ThermalPressure.NONE, powerSaveMode = true)

            Then("frames convert at the battery-saver cadence and detection continues") {
                assertEquals(100L, profile.frameIntervalMs)
                assertEquals("battery-saver", profile.label)
                assertTrue(profile.detectionEnabled)
            }
        }
    }

    @Test
    fun `battery saver and heat combine to the slower cadence`() {
        Given("a hot phone in battery saver") {
            val severe = LoadPolicy.profile(ThermalPressure.SEVERE, powerSaveMode = true)

            Then("the strictest constraint wins instead of averaging out") {
                assertEquals(250L, severe.frameIntervalMs)
                assertEquals("thermal-severe+battery-saver", severe.label)
            }
        }
    }

    @Test
    fun `an unreported thermal status is treated as normal`() {
        Given("a device that does not report a thermal status") {
            Then("the pipeline runs at full cadence rather than guessing at heat") {
                assertEquals(ThermalPressure.NONE, LoadPolicy.pressureFor(null))
                assertEquals(66L, LoadPolicy.profile(LoadPolicy.pressureFor(null), false).frameIntervalMs)
            }
        }
    }
}

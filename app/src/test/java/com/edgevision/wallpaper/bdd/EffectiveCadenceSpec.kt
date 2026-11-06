package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.diagnostics.PerformanceController
import com.edgevision.wallpaper.diagnostics.PerformanceMode
import com.edgevision.wallpaper.diagnostics.ThermalPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: the performance governor changes the interval the pipeline uses.
 *
 * The controller pushes every changed profile through the same seam the
 * wallpaper service uses to call `CameraController.setDetectionIntervalMs`, so
 * this proves the effective cadence, not just a calculation.
 */
class EffectiveCadenceSpec {

    @Test
    fun `changing mode changes the applied detection interval`() {
        val applied = mutableListOf<Long>()
        var detectionAlwaysOn = true
        val controller = PerformanceController { profile ->
            applied += profile.inferenceIntervalMs
            detectionAlwaysOn = detectionAlwaysOn && profile.detectionEnabled
        }

        Given("performance mode is selected") {
            controller.mode = PerformanceMode.PERFORMANCE
            controller.refresh()

            Then("the pipeline receives the 66ms interval") {
                assertEquals(listOf(66L), applied)
                assertEquals(66L, controller.current?.inferenceIntervalMs)
            }

            And("switching to battery saver widens the interval") {
                controller.mode = PerformanceMode.BATTERY_SAVER
                controller.refresh()
                assertEquals(250L, applied.last())
            }
        }

        And("detection is never disabled") {
            assertTrue(detectionAlwaysOn)
        }
    }

    @Test
    fun `thermal pressure overrides the mode on the applied cadence`() {
        val applied = mutableListOf<Long>()
        val controller = PerformanceController { applied += it.inferenceIntervalMs }
        controller.mode = PerformanceMode.PERFORMANCE
        controller.refresh()

        Given("a performance-mode device that becomes severely hot") {
            controller.thermal = ThermalPressure.SEVERE
            controller.refresh()

            Then("the applied interval jumps to 500ms") {
                assertEquals(500L, applied.last())
            }

            And("critical heat widens it further") {
                controller.thermal = ThermalPressure.CRITICAL
                controller.refresh()
                assertEquals(1_000L, applied.last())
            }

            And("cooling back down restores the mode interval") {
                controller.thermal = ThermalPressure.NONE
                controller.refresh()
                assertEquals(66L, applied.last())
            }
        }
    }

    @Test
    fun `low battery and system saver widen the applied interval`() {
        val applied = mutableListOf<Long>()
        val controller = PerformanceController { applied += it.inferenceIntervalMs }
        controller.mode = PerformanceMode.PERFORMANCE
        controller.refresh()

        Given("the battery drops below the floor while unplugged") {
            controller.batteryPercent = 10
            controller.charging = false
            controller.refresh()

            Then("the applied interval slows to the saver cadence") {
                assertEquals(250L, applied.last())
            }

            And("plugging in restores performance") {
                controller.charging = true
                controller.refresh()
                assertEquals(66L, applied.last())
            }

            And("system battery saver also slows the pipeline") {
                controller.charging = false
                controller.batteryPercent = 90
                controller.powerSaveMode = true
                controller.refresh()
                assertEquals(250L, applied.last())
            }
        }
    }

    @Test
    fun `unchanged state does not reapply a profile`() {
        val applied = mutableListOf<Long>()
        val controller = PerformanceController { applied += it.inferenceIntervalMs }
        controller.mode = PerformanceMode.BALANCED

        Given("the same state is refreshed three times") {
            repeat(3) { controller.refresh() }

            Then("the pipeline is configured once") {
                assertEquals(listOf(100L), applied)
            }
        }
    }
}

package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.inference.DetectionParser
import com.edgevision.wallpaper.inference.HazardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: the Python detector and the Kotlin tracker agree on one contract.
 *
 * Python owns preprocessing, decoding and the hazard taxonomy; Kotlin owns
 * tracking, thresholds and alerts. Anything the two disagree about used to fail
 * silently, so the contract is specified explicitly.
 */
class DetectionContractSpec {

    @Test
    fun `a decoded vehicle detection keeps its box, score and hazard kind`() {
        val payload = """
            {
              "backend": "tflite_java",
              "input_size": 320,
              "latency_ms": 42.5,
              "detections": [
                {"label":"car","hazard":"vehicle","score":0.71,"box":[0.2,0.4,0.6,0.9]}
              ]
            }
        """.trimIndent()
        var kind: HazardKind? = null

        Given("Python reports one vehicle at 0.71 confidence") { }

        When("Kotlin parses the detection payload") {
            val result = DetectionParser.parse(payload)
            Then("the vehicle survives with its normalised box") {
                assertEquals(1, result.detections.size)
                kind = result.detections.single().hazard
                assertEquals(HazardKind.VEHICLE, kind)
                assertEquals(0.71f, result.detections.single().confidence, 0.001f)
                assertEquals(0.2f, result.detections.single().box.left, 0.001f)
                assertEquals(42L, result.latencyMs)
            }
        }
    }

    @Test
    fun `obstacle wire values map onto the pole hazard`() {
        val payload = """{"detections":[{"label":"pole","hazard":"obstacle","score":0.6,"box":[0,0,0.2,1]}]}"""

        Given("Python reports the legacy obstacle taxonomy") { }

        When("Kotlin parses it") {
            val result = DetectionParser.parse(payload)

            Then("it is treated as a pole hazard so it still alerts") {
                assertEquals(HazardKind.POLE, result.detections.single().hazard)
            }
        }
    }

    @Test
    fun `a malformed box is dropped instead of crashing the wallpaper`() {
        val payload = """
            {"detections":[
              {"label":"car","hazard":"vehicle","score":0.9,"box":[0.1,0.2]},
              {"label":"truck","hazard":"vehicle","score":0.8,"box":[0.1,0.2,0.4,0.6]}
            ]}
        """.trimIndent()

        Given("one detection has a truncated box") { }

        When("Kotlin parses the payload") {
            val result = DetectionParser.parse(payload)

            Then("only the complete detection is kept") {
                assertEquals(1, result.detections.size)
                assertEquals("truck", result.detections.single().label)
            }
        }
    }

    @Test
    fun `a backend error is surfaced rather than treated as an empty frame`() {
        Given("Python reports that the model is missing") { }

        When("Kotlin parses the payload") {
            val failure = runCatching { DetectionParser.parse("""{"error":"model missing"}""") }

            Then("the error reaches the caller") {
                assertNotNull(failure.exceptionOrNull())
                assertTrue(failure.exceptionOrNull()!!.message!!.contains("model missing"))
            }
        }
    }
}

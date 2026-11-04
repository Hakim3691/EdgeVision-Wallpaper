package com.edgevision.wallpaper.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionParserTest {

    @Test
    fun parsesHazardDetectionsAndMetadata() {
        val result = DetectionParser.parse(
            """
            {
              "backend": "tflite_java",
              "input_size": 320,
              "latency_ms": 41,
              "detections": [
                {"label":"car","hazard":"vehicle","score":0.82,"box":[0.1,0.2,0.5,0.7]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("tflite_java", result.backend)
        assertEquals(320, result.inputSize)
        assertEquals(41, result.latencyMs)
        assertEquals(1, result.detections.size)
        assertEquals("car", result.detections[0].label)
        assertEquals(HazardKind.VEHICLE, result.detections[0].hazard)
        assertEquals(0.82f, result.detections[0].confidence, 0.001f)
    }

    @Test
    fun clampsAndOrdersBoxCoordinates() {
        val result = DetectionParser.parse(
            """
            {"backend":"fake","detections":[
              {"label":"pole","hazard":"pole","score":0.7,"box":[1.4,-0.2,0.3,0.8]}
            ]}
            """.trimIndent(),
        )

        val box = result.detections.single().box
        assertEquals(0.3f, box.left, 0.001f)
        assertEquals(0.0f, box.top, 0.001f)
        assertEquals(1.0f, box.right, 0.001f)
        assertEquals(0.8f, box.bottom, 0.001f)
    }

    @Test
    fun ignoresNonHazardEntries() {
        val result = DetectionParser.parse(
            """
            {"detections":[{"label":"person","hazard":"unknown","score":0.9,"box":[0,0,1,1]}]}
            """.trimIndent(),
        )
        assertTrue(result.detections.isEmpty())
    }

    @Test
    fun surfacesBackendErrors() {
        val error = runCatching {
            DetectionParser.parse("""{"error":"model missing"}""")
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message!!.contains("model missing"))
    }
}

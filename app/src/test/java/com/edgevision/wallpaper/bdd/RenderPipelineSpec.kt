package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.camera.FrameGeometry
import com.edgevision.wallpaper.diagnostics.RuntimeMetrics
import com.edgevision.wallpaper.wallpaper.RenderMailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: the live preview stays live.
 *
 * The wallpaper used to post every camera frame to the main thread. When a
 * full-surface draw took longer than the capture interval the queue grew without
 * bound: the preview drifted seconds behind reality and every queued frame
 * retained a preview bitmap until the process was killed.
 */
class RenderPipelineSpec {

    @Test
    fun `a busy renderer always draws the newest camera frame`() {
        val released = mutableListOf<String>()
        val mailbox = RenderMailbox<String> { released += it }

        Given("the wallpaper main thread is still drawing the previous frame") {
            assertTrue("the first frame asks for a drain", mailbox.offer("frame-1"))
        }

        When("two more camera frames arrive before that drain runs") {
            assertFalse("a drain is already scheduled", mailbox.offer("frame-2"))
            assertFalse("a drain is already scheduled", mailbox.offer("frame-3"))
        }

        Then("superseded frames are released immediately instead of being queued") {
            assertEquals(listOf("frame-1", "frame-2"), released)
            assertEquals(2L, mailbox.droppedCount)
            assertTrue("only the newest frame is still held", mailbox.hasPending)
        }

        Then("the drain renders that newest frame and leaves nothing behind") {
            assertEquals("frame-3", mailbox.take())
            assertNull(mailbox.take())
            assertEquals(1L, mailbox.deliveredCount)
        }
    }

    @Test
    fun `the newest frame after a drain requests a new drain`() {
        val mailbox = RenderMailbox<String> { }

        Given("a mailbox that already delivered one frame") {
            mailbox.offer("frame-1")
            mailbox.take()
        }

        When("the next camera frame arrives") {
            val scheduled = mailbox.offer("frame-2")
            Then("the caller is told to post exactly one drain") { assertTrue(scheduled) }
        }

        Then("no frames were dropped because the renderer kept up") {
            assertEquals(0L, mailbox.droppedCount)
            assertEquals("frame-2", mailbox.take())
        }
    }

    @Test
    fun `hiding the wallpaper releases the frame that was waiting`() {
        val released = mutableListOf<String>()
        val mailbox = RenderMailbox<String> { released += it }

        Given("a camera frame is waiting for the main thread") {
            mailbox.offer("frame-1")
        }

        When("the wallpaper becomes invisible") {
            mailbox.discard()
        }

        Then("the waiting frame is released and no stale draw survives") {
            assertEquals(listOf("frame-1"), released)
            assertFalse(mailbox.hasPending)
            assertNull(mailbox.take())
        }
    }

    @Test
    fun `a portrait preview receives an upright detection buffer`() {
        val sourceWidth = 1280
        val sourceHeight = 720
        val rotation = 90

        Given("the sensor delivers a landscape 1280x720 buffer while the phone is upright") {
            val displayWidth = FrameGeometry.displayWidth(sourceWidth, sourceHeight, rotation)
            val displayHeight = FrameGeometry.displayHeight(sourceWidth, sourceHeight, rotation)
            assertEquals(720, displayWidth)
            assertEquals(1280, displayHeight)

            When("the detector samples the corners of the upright view") {
                val topLeft = sourceOf(rotation, 0, 0, sourceWidth, sourceHeight)
                val topRight = sourceOf(rotation, displayWidth - 1, 0, sourceWidth, sourceHeight)
                val bottomRight = sourceOf(
                    rotation,
                    displayWidth - 1,
                    displayHeight - 1,
                    sourceWidth,
                    sourceHeight,
                )

                Then("the upright view is the sensor buffer rotated 90 degrees clockwise") {
                    // The buffer's bottom-left corner becomes the upright top-left.
                    assertEquals(0, topLeft.first)
                    assertEquals(719, topLeft.second)
                    // Its top-left corner becomes the upright top-right.
                    assertEquals(0, topRight.first)
                    assertEquals(0, topRight.second)
                    // Its top-right corner becomes the upright bottom-right.
                    assertEquals(1279, bottomRight.first)
                    assertEquals(0, bottomRight.second)
                }
            }
        }
    }

    @Test
    fun `rotation covers the whole frame instead of a centre crop`() {
        val sourceWidth = 1280
        val sourceHeight = 720

        Given("a 320 pixel detector input over a 1280x720 sensor buffer") {
            assertEquals(320, DETECTION_SIZE)
        }

        When("the detection grid is mapped back to sensor coordinates for every rotation") {
            Then("both axes span the full sensor frame, not the old centre crop") {
                // A 320 wide grid over 1280 columns can only land on every 4th
                // column, so coverage is asserted to within one sampling step.
                val stepX = sourceWidth / DETECTION_SIZE + 1
                val stepY = sourceHeight / DETECTION_SIZE + 1
                for (rotation in listOf(0, 90, 180, 270)) {
                    val coverage = sampledCoverage(rotation, sourceWidth, sourceHeight)
                    assertTrue(
                        "rotation $rotation must sample near the left edge, got ${coverage.minX}",
                        coverage.minX <= stepX,
                    )
                    assertTrue(
                        "rotation $rotation must sample near the right edge, got ${coverage.maxX}",
                        coverage.maxX >= sourceWidth - 1 - stepX,
                    )
                    assertTrue(
                        "rotation $rotation must sample near the top edge, got ${coverage.minY}",
                        coverage.minY <= stepY,
                    )
                    assertTrue(
                        "rotation $rotation must sample near the bottom edge, got ${coverage.maxY}",
                        coverage.maxY >= sourceHeight - 1 - stepY,
                    )
                }
            }
        }
    }

    @Test
    fun `the diagnostics window reports real frame and draw numbers`() {
        val metrics = RuntimeMetrics()

        Given("a fresh metrics window that starts at 1000ms") {
            metrics.start(1_000L)
        }

        When("three frames are drawn in two seconds with 40ms inference each") {
            repeat(3) {
                metrics.onFrame()
                metrics.onDraw(10L)
                metrics.onInference(40L)
            }
            metrics.onHazard()
            metrics.onDroppedFrames(2L)
        }

        Then("the snapshot describes what the device actually did") {
            val snapshot = metrics.snapshot(3_000L)
            assertEquals(3L, snapshot.frames)
            assertEquals(3L, snapshot.draws)
            assertEquals(1.5, snapshot.framesPerSecond, 0.001)
            assertEquals(10.0, snapshot.drawAverageMs, 0.001)
            assertEquals(10L, snapshot.drawMaxMs)
            assertEquals(40.0, snapshot.inferenceAverageMs, 0.001)
            assertEquals(1L, snapshot.hazards)
            assertEquals(2L, snapshot.droppedFrames)
        }

        Then("the window restarts so the next log line covers only new work") {
            val next = metrics.snapshot(4_000L)
            assertEquals(0L, next.frames)
            assertEquals(0L, next.draws)
        }
    }

    private fun sourceOf(
        rotationDegrees: Int,
        displayX: Int,
        displayY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Int, Int> = Pair(
        FrameGeometry.sourceX(rotationDegrees, displayX, displayY, sourceWidth, sourceHeight),
        FrameGeometry.sourceY(rotationDegrees, displayX, displayY, sourceWidth, sourceHeight),
    )

    private data class Coverage(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    /** Mirrors the production sampling grid so coverage is asserted, not assumed. */
    private fun sampledCoverage(
        rotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Coverage {
        val displayWidth = FrameGeometry.displayWidth(sourceWidth, sourceHeight, rotationDegrees)
        val displayHeight = FrameGeometry.displayHeight(sourceWidth, sourceHeight, rotationDegrees)
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        for (outY in 0 until DETECTION_SIZE) {
            val displayY = (outY.toLong() * displayHeight / DETECTION_SIZE).toInt()
                .coerceIn(0, displayHeight - 1)
            for (outX in 0 until DETECTION_SIZE) {
                val displayX = (outX.toLong() * displayWidth / DETECTION_SIZE).toInt()
                    .coerceIn(0, displayWidth - 1)
                val sourceX = FrameGeometry.sourceX(
                    rotationDegrees,
                    displayX,
                    displayY,
                    sourceWidth,
                    sourceHeight,
                )
                val sourceY = FrameGeometry.sourceY(
                    rotationDegrees,
                    displayX,
                    displayY,
                    sourceWidth,
                    sourceHeight,
                )
                if (sourceX < minX) minX = sourceX
                if (sourceX > maxX) maxX = sourceX
                if (sourceY < minY) minY = sourceY
                if (sourceY > maxY) maxY = sourceY
            }
        }
        return Coverage(minX, maxX, minY, maxY)
    }

    private companion object {
        const val DETECTION_SIZE = 320
    }
}

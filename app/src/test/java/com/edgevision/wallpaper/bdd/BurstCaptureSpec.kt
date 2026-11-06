package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.capture.BurstCaptureRunner
import com.edgevision.wallpaper.capture.BurstConfig
import com.edgevision.wallpaper.capture.BurstScheduler
import com.edgevision.wallpaper.capture.CaptureController
import com.edgevision.wallpaper.capture.CaptureDecision
import com.edgevision.wallpaper.capture.CaptureImageSink
import com.edgevision.wallpaper.capture.CaptureTarget
import com.edgevision.wallpaper.capture.CaptureTrigger
import com.edgevision.wallpaper.capture.JpegSource
import com.edgevision.wallpaper.capture.SaveRejection
import com.edgevision.wallpaper.capture.SaveResult
import com.edgevision.wallpaper.vision.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: a threat burst produces saved JPEGs.
 *
 * These specs drive the real [BurstCaptureRunner] and [CaptureController] with a
 * fake camera, a fake clock and a fake sink, so the assertions are about the
 * files that would be created, not about a mock returning true.
 */
class BurstCaptureSpec {

    private class FakeSink(private val deny: Boolean = false) : CaptureImageSink {
        val saved = mutableListOf<String>()
        val rejected = mutableListOf<SaveRejection>()
        var lastJpegSize = 0

        override fun save(
            jpeg: ByteArray,
            trigger: CaptureTrigger,
            capturedAtMs: Long,
            shotIndex: Int,
        ): SaveResult {
            if (deny) {
                rejected += SaveRejection.PERMISSION_DENIED
                return SaveResult.Rejected(SaveRejection.PERMISSION_DENIED)
            }
            val name = CaptureTarget.displayName(trigger, capturedAtMs, shotIndex)
            saved += name
            lastJpegSize = jpeg.size
            return SaveResult.Saved(name, "content://test/$name")
        }
    }

    private class ImmediateScheduler : BurstScheduler {
        val delays = mutableListOf<Long>()

        override fun schedule(delayMs: Long, action: () -> Unit) {
            delays += delayMs
            action()
        }
    }

    private class ImmediateSource(private val jpeg: ByteArray?) : JpegSource {
        var calls = 0

        override fun capture(onCaptured: (ByteArray?) -> Unit) {
            calls += 1
            onCaptured(jpeg)
        }
    }

    private class ManualSource : JpegSource {
        val pending = mutableListOf<(ByteArray?) -> Unit>()
        var calls = 0

        override fun capture(onCaptured: (ByteArray?) -> Unit) {
            calls += 1
            pending += onCaptured
        }
    }

    private var now = 1_000L

    private fun runner(
        sink: CaptureImageSink,
        source: JpegSource,
        scheduler: BurstScheduler = ImmediateScheduler(),
        controller: CaptureController = CaptureController(),
    ) = BurstCaptureRunner(controller, source, sink, scheduler) { now }

    @Test
    fun `armed high threat writes three JPEGs into the Android Clear folder`() {
        val sink = FakeSink()
        val source = ImmediateSource(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
        val scheduler = ImmediateScheduler()
        val controller = CaptureController()
        val runner = runner(sink, source, scheduler, controller)
        controller.setArmed(true)

        Given("capture is armed and a high threat is graded") {
            val decision = runner.onThreat(ThreatLevel.HIGH, nowMs = now)

            Then("a burst was admitted and completed") {
                assertTrue(decision is CaptureDecision.Burst)
                assertEquals(3, sink.saved.size)
                assertEquals("every shot carries the camera bytes", 5, sink.lastJpegSize)
                assertEquals("two intervals separate three shots", listOf(350L, 350L), scheduler.delays)
                assertEquals(3, controller.completedShots)
                assertFalse(controller.isBurstRunning)
                assertEquals(BurstCaptureRunner.Outcome.Completed(3), runner.lastOutcome)
            }

            And("the files are named for the product folder and capture order") {
                assertEquals("Pictures/Android Clear/Threat Captures/", CaptureTarget.RELATIVE_PATH)
                sink.saved.forEachIndexed { index, name ->
                    assertTrue("$name should be an automatic capture", name.contains("-auto-"))
                    assertTrue("$name should end with shot ${index + 1}", name.endsWith("-${index + 1}.jpg"))
                }
            }
        }
    }

    @Test
    fun `cooldown after a finished burst rejects the next threat`() {
        val sink = FakeSink()
        val controller = CaptureController(BurstConfig(cooldownMs = 15_000L))
        val runner = runner(sink, ImmediateSource(byteArrayOf(1)), controller = controller)
        controller.setArmed(true)

        Given("a burst completed at t = 1000") {
            runner.onThreat(ThreatLevel.HIGH, nowMs = now)

            Then("a threat one second later is refused by cooldown") {
                now = 2_000
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.COOLDOWN),
                    runner.onThreat(ThreatLevel.HIGH, nowMs = now),
                )
                assertEquals("only the first burst was written", 3, sink.saved.size)
            }

            And("capture resumes once the cooldown has passed") {
                now = 16_500
                assertTrue(runner.onThreat(ThreatLevel.HIGH, nowMs = now) is CaptureDecision.Burst)
            }
        }
    }

    @Test
    fun `a second trigger during a burst is refused as busy`() {
        val source = ManualSource()
        val controller = CaptureController()
        val runner = runner(FakeSink(), source, controller = controller)
        controller.setArmed(true)

        Given("the first shot has not returned yet") {
            assertTrue(runner.onThreat(ThreatLevel.CRITICAL, nowMs = now) is CaptureDecision.Burst)

            Then("another trigger is refused and no second shutter fires") {
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.BUSY),
                    runner.onThreat(ThreatLevel.CRITICAL, nowMs = now + 50),
                )
                assertEquals(1, source.calls)
            }
        }
    }

    @Test
    fun `a denied storage permission aborts the burst without saving anything`() {
        val sink = FakeSink(deny = true)
        val source = ImmediateSource(byteArrayOf(9, 9))
        val controller = CaptureController()
        val runner = runner(sink, source, controller = controller)
        controller.setArmed(true)

        Given("capture is armed but the sink rejects the write") {
            runner.onThreat(ThreatLevel.HIGH, nowMs = now)

            Then("nothing is saved and the burst stops after one attempt") {
                assertTrue(sink.saved.isEmpty())
                assertEquals(listOf(SaveRejection.PERMISSION_DENIED), sink.rejected)
                assertEquals("no further shutter presses", 1, source.calls)
                assertFalse(controller.isBurstRunning)
                assertEquals(
                    BurstCaptureRunner.Outcome.Aborted(SaveRejection.PERMISSION_DENIED),
                    runner.lastOutcome,
                )
            }
        }
    }

    @Test
    fun `manual capture writes a manual burst while automatic capture is disarmed`() {
        val sink = FakeSink()
        val runner = runner(sink, ImmediateSource(byteArrayOf(4, 5, 6)))

        Given("capture is disarmed") {
            Then("a manual request still produces three manual files") {
                assertTrue(runner.onManualRequest(nowMs = now) is CaptureDecision.Burst)
                assertEquals(3, sink.saved.size)
                assertTrue(sink.saved.all { it.contains("-manual-") })
            }
        }
    }

    @Test
    fun `an empty camera frame is rejected instead of writing a corrupt file`() {
        val sink = FakeSink()
        val controller = CaptureController()
        val runner = runner(sink, ImmediateSource(null), controller = controller)
        controller.setArmed(true)

        Given("the camera returns no bytes") {
            runner.onThreat(ThreatLevel.HIGH, nowMs = now)

            Then("the write is rejected as an empty image") {
                assertTrue(sink.saved.isEmpty())
                assertEquals(
                    BurstCaptureRunner.Outcome.Aborted(SaveRejection.EMPTY_IMAGE),
                    runner.lastOutcome,
                )
            }
        }
    }
}

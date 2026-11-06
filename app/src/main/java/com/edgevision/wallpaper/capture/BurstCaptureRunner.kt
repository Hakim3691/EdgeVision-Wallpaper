package com.edgevision.wallpaper.capture

import com.edgevision.wallpaper.vision.ThreatLevel

/** Produces one JPEG. The wallpaper supplies a Camera2 implementation. */
fun interface JpegSource {
    fun capture(onCaptured: (ByteArray?) -> Unit)
}

/** Schedules the next shot. The service uses a Handler; specs run immediately. */
fun interface BurstScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}

/**
 * Drives a burst end to end: admission through [CaptureController], one JPEG per
 * shot, storage through [CaptureImageSink], then cooldown bookkeeping.
 *
 * It deliberately does not re-implement the arming, BUSY or cooldown rules; it
 * asks the controller and obeys the answer, so there is one authority on whether
 * the shutter may fire.
 *
 * A rejected write aborts the remaining shots. That matters for permission loss
 * and storage failure: continuing would burn battery producing images that can
 * never be saved.
 */
class BurstCaptureRunner(
    private val controller: CaptureController,
    private val source: JpegSource,
    private val sink: CaptureImageSink,
    private val scheduler: BurstScheduler,
    private val clock: () -> Long,
) {

    sealed interface Outcome {
        data class Completed(val shots: Int) : Outcome

        data class Aborted(val reason: SaveRejection) : Outcome
    }

    var lastOutcome: Outcome? = null
        private set

    var shotsRequested: Int = 0
        private set

    fun onThreat(level: ThreatLevel, nowMs: Long): CaptureDecision {
        val decision = controller.onThreat(level, nowMs)
        if (decision is CaptureDecision.Burst) execute(decision.request)
        return decision
    }

    fun onManualRequest(nowMs: Long): CaptureDecision {
        val decision = controller.onManualRequest(nowMs)
        if (decision is CaptureDecision.Burst) execute(decision.request)
        return decision
    }

    private fun execute(request: BurstRequest) {
        shotsRequested = request.shots
        lastOutcome = null
        captureNext(request, shotIndex = 0)
    }

    private fun captureNext(request: BurstRequest, shotIndex: Int) {
        if (!controller.isBurstRunning) return
        source.capture { jpeg -> onCaptured(request, shotIndex, jpeg) }
    }

    private fun onCaptured(request: BurstRequest, shotIndex: Int, jpeg: ByteArray?) {
        // The session can end between the request and the shutter (wallpaper
        // hidden, camera disconnected); drop the result instead of storing it.
        if (!controller.isBurstRunning) return

        val result = if (jpeg == null) {
            SaveResult.Rejected(SaveRejection.EMPTY_IMAGE)
        } else {
            sink.save(
                jpeg = jpeg,
                trigger = request.trigger,
                capturedAtMs = clock(),
                shotIndex = shotIndex,
            )
        }

        when (result) {
            is SaveResult.Saved -> {
                controller.onShotCaptured()
                val nextIndex = shotIndex + 1
                if (nextIndex >= request.shots) {
                    controller.onBurstFinished(clock())
                    lastOutcome = Outcome.Completed(request.shots)
                } else {
                    scheduler.schedule(request.intervalMs) { captureNext(request, nextIndex) }
                }
            }

            is SaveResult.Rejected -> {
                controller.onBurstFailed(clock())
                lastOutcome = Outcome.Aborted(result.reason)
            }
        }
    }
}

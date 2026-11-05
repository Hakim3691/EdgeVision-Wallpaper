package com.edgevision.wallpaper.wallpaper

/**
 * Single-slot mailbox between the camera thread and the wallpaper's main thread.
 *
 * Exactly one frame can be waiting to be drawn. Offering a newer frame while the
 * previous one is still pending releases the stale frame immediately, so the
 * on-screen image is always the newest capture and the main thread can never
 * accumulate a backlog. Without this bound a renderer that falls behind the
 * capture cadence grows an unbounded queue, which shows a stale picture (the
 * multi-second lag) and retains one full preview bitmap per queued frame (the
 * out-of-memory crash when another app needs memory).
 *
 * [offer] returns `true` when the caller must schedule a drain; [take] consumes
 * the pending frame and clears the scheduled flag.
 */
class RenderMailbox<T>(private val release: (T) -> Unit) {

    private val lock = Any()
    private var pending: T? = null
    private var scheduled = false

    /** Frames superseded before they were drawn. */
    var droppedCount: Long = 0L
        private set

    /** Frames handed to the renderer. */
    var deliveredCount: Long = 0L
        private set

    val hasPending: Boolean
        get() = synchronized(lock) { pending != null }

    /**
     * Publishes [value] as the newest frame. Returns `true` when the caller must
     * post a drain; returns `false` when a drain is already scheduled (the
     * scheduled drain will pick up [value]).
     */
    fun offer(value: T): Boolean {
        var stale: T? = null
        var mustSchedule: Boolean
        synchronized(lock) {
            stale = pending
            pending = value
            if (stale != null) {
                droppedCount += 1
            }
            mustSchedule = !scheduled
            scheduled = true
        }
        stale?.let { release(it) }
        return mustSchedule
    }

    /** Consumes the pending frame, if any, and re-arms [offer]. */
    fun take(): T? {
        synchronized(lock) {
            val value = pending
            pending = null
            scheduled = false
            if (value != null) {
                deliveredCount += 1
            }
            return value
        }
    }

    /** Releases any frame still waiting, e.g. when the wallpaper is hidden. */
    fun discard(): T? {
        var stale: T? = null
        synchronized(lock) {
            stale = pending
            pending = null
            scheduled = false
        }
        stale?.let { release(it) }
        return stale
    }
}

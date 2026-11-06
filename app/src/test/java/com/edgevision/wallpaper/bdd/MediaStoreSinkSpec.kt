package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.capture.CaptureTarget
import com.edgevision.wallpaper.capture.CaptureTrigger
import com.edgevision.wallpaper.capture.MediaStoreGateway
import com.edgevision.wallpaper.capture.MediaStoreImageSink
import com.edgevision.wallpaper.capture.SaveRejection
import com.edgevision.wallpaper.capture.SaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: MediaStore capture rows are pending until the JPEG is complete.
 *
 * A gallery must never show a half-written file, and a failed write must not
 * leave an orphaned pending row. The Android gateway is thin; the orchestration
 * that decides insert/write/publish/delete is specified here against a fake.
 */
class MediaStoreSinkSpec {

    private class FakeGateway(
        var insertResult: String? = "content://media/1",
        var writeResult: Boolean = true,
        var publishResult: Boolean = true,
    ) : MediaStoreGateway {
        data class Insert(
            val relativePath: String,
            val displayName: String,
            val mimeType: String,
            val dateTakenMs: Long,
            val pending: Boolean,
        )

        val inserts = mutableListOf<Insert>()
        val writes = mutableListOf<Pair<String, Int>>()
        val publishes = mutableListOf<String>()
        val deletes = mutableListOf<String>()

        override fun insert(
            relativePath: String,
            displayName: String,
            mimeType: String,
            dateTakenMs: Long,
            pending: Boolean,
        ): String? {
            inserts += Insert(relativePath, displayName, mimeType, dateTakenMs, pending)
            return insertResult
        }

        override fun write(uri: String, bytes: ByteArray): Boolean {
            writes += uri to bytes.size
            return writeResult
        }

        override fun publish(uri: String): Boolean {
            publishes += uri
            return publishResult
        }

        override fun delete(uri: String) {
            deletes += uri
        }
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    private val capturedAt = 1_700_000_000_000L

    @Test
    fun `a successful capture inserts pending, writes, then publishes`() {
        val gateway = FakeGateway()
        val sink = MediaStoreImageSink(gateway)

        Given("a JPEG burst shot") {
            val result = sink.save(jpeg, CaptureTrigger.THREAT_AUTO, capturedAt, shotIndex = 0)

            Then("the row carries the product folder and JPEG metadata") {
                assertEquals(1, gateway.inserts.size)
                val insert = gateway.inserts.single()
                assertEquals("Pictures/Android Clear/Threat Captures/", insert.relativePath)
                assertEquals(CaptureTarget.MIME_TYPE, insert.mimeType)
                assertEquals(capturedAt, insert.dateTakenMs)
                assertTrue("a new row must be hidden until written", insert.pending)
                assertTrue(insert.displayName.startsWith("android-clear-auto-"))
                assertTrue(insert.displayName.endsWith("-1.jpg"))
            }

            And("the bytes are written and the row published exactly once") {
                assertEquals(listOf("content://media/1" to jpeg.size), gateway.writes)
                assertEquals(listOf("content://media/1"), gateway.publishes)
                assertTrue(gateway.deletes.isEmpty())
                assertTrue(result is SaveResult.Saved)
            }
        }
    }

    @Test
    fun `a failed write deletes the pending row instead of leaving it behind`() {
        val gateway = FakeGateway(writeResult = false)
        val sink = MediaStoreImageSink(gateway)

        Given("the storage rejects the bytes") {
            val result = sink.save(jpeg, CaptureTrigger.MANUAL, capturedAt, shotIndex = 0)

            Then("the pending row is deleted and never published") {
                assertEquals(SaveResult.Rejected(SaveRejection.WRITE_FAILED), result)
                assertEquals(listOf("content://media/1"), gateway.deletes)
                assertTrue(gateway.publishes.isEmpty())
            }
        }
    }

    @Test
    fun `a failed publish deletes the row rather than exposing a broken file`() {
        val gateway = FakeGateway(publishResult = false)
        val sink = MediaStoreImageSink(gateway)

        Then("publishing failure cleans up the row") {
            assertEquals(
                SaveResult.Rejected(SaveRejection.PUBLISH_FAILED),
                sink.save(jpeg, CaptureTrigger.THREAT_AUTO, capturedAt, 1),
            )
            assertEquals(listOf("content://media/1"), gateway.deletes)
        }
    }

    @Test
    fun `a denied permission never touches MediaStore`() {
        val gateway = FakeGateway()
        val sink = MediaStoreImageSink(gateway, canWrite = { false })

        Then("the capture is rejected before any row is created") {
            assertEquals(
                SaveResult.Rejected(SaveRejection.PERMISSION_DENIED),
                sink.save(jpeg, CaptureTrigger.MANUAL, capturedAt, 0),
            )
            assertTrue(gateway.inserts.isEmpty())
            assertTrue(gateway.writes.isEmpty())
            assertTrue(gateway.deletes.isEmpty())
        }
    }

    @Test
    fun `an unavailable MediaStore is reported without trying to write`() {
        val gateway = FakeGateway(insertResult = null)
        val sink = MediaStoreImageSink(gateway)

        Then("storage unavailability is explicit") {
            assertEquals(
                SaveResult.Rejected(SaveRejection.STORAGE_UNAVAILABLE),
                sink.save(jpeg, CaptureTrigger.THREAT_AUTO, capturedAt, 0),
            )
            assertTrue(gateway.writes.isEmpty())
            assertFalse(gateway.inserts.isEmpty())
        }
    }
}

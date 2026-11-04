package com.edgevision.wallpaper.inference

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the pieces that cannot run under Robolectric: the Chaquopy
 * interpreter, the TensorFlow Lite AAR for the test ABI, the model asset, and the
 * Python decode/JSON contract.
 */
@RunWith(AndroidJUnit4::class)
class PythonTfliteIntegrationTest {

    @Test
    fun bundledModelRunsThroughPythonAndReturnsDetectionsArray() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
        }
        val module = Python.getInstance().getModule("hazard_detector")
        val model = ModelAssets.ensureModel(context)

        try {
            val status = JSONObject(
                module.callAttr("initialize", model.absolutePath, 320).toString(),
            )
            assertEquals(320, status.getInt("input_size"))

            val payload = JSONObject(
                module.callAttr(
                    "detect",
                    ByteArray(320 * 320 * 3),
                    320,
                    320,
                    0.35f,
                ).toString(),
            )
            assertTrue(payload.has("backend"))
            assertTrue(payload.has("detections"))
            assertTrue(payload.getJSONArray("detections").length() >= 0)
        } finally {
            module.callAttr("shutdown")
        }
    }
}

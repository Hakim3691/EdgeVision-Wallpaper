package com.edgevision.wallpaper.bdd

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.edgevision.wallpaper.AndroidClearApp
import com.edgevision.wallpaper.MainActivity
import com.edgevision.wallpaper.R
import com.edgevision.wallpaper.legal.LegalDocument
import com.edgevision.wallpaper.settings.AssistSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature: legal consent.
 *
 * Play policy and the product's own safety copy require the AI disclosure before
 * first use and an explicit safety acknowledgement before monitoring is enabled.
 * Consent must be persisted, and enabling capture must not be possible by
 * flipping the switch alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AndroidClearApp::class)
class LegalConsentSpec {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences(AssistSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `consent starts unaccepted on a fresh install`() {
        val settings = AssistSettings(context)

        assertFalse(settings.aiDisclosureSeen)
        assertFalse(settings.safetyNoticeAccepted)
        assertFalse(settings.captureEnabled)
    }

    @Test
    fun `acceptance is persisted independently of feature settings`() {
        AssistSettings(context).apply {
            aiDisclosureSeen = true
            safetyNoticeAccepted = true
        }

        // A fresh instance reads the same store, exactly as the wallpaper does.
        val reloaded = AssistSettings(context)
        assertTrue(reloaded.aiDisclosureSeen)
        assertTrue(reloaded.safetyNoticeAccepted)
        assertFalse("consent must not enable capture by itself", reloaded.captureEnabled)
    }

    @Test
    fun `the capture switch cannot stay on before the safety notice is accepted`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.switch_capture_armed)).perform(scrollTo(), click())

            Then("the preference stays off until the user accepts") {
                assertFalse(AssistSettings(context).captureEnabled)
            }
        }
    }

    @Test
    fun `every legal document ships in the apk and is readable`() {
        LegalDocument.entries.forEach { document ->
            val text = document.read(context)
            assertTrue(
                "${document.assetName} must not be empty",
                text.isNotBlank(),
            )
            assertTrue(
                "${document.assetName} must carry the product name",
                text.uppercase().contains("ANDROID CLEAR"),
            )
        }
    }

    private fun Then(description: String, block: () -> Unit) {
        block()
    }
}

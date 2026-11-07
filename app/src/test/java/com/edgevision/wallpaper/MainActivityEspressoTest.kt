package com.edgevision.wallpaper

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.edgevision.wallpaper.diagnostics.PerformanceMode
import com.edgevision.wallpaper.settings.AssistSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AndroidClearApp::class)
class MainActivityEspressoTest {

    @Before
    fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AssistSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        // These tests exercise settings, not the first-run legal flow, so the
        // disclosure and safety acknowledgement are pre-accepted.
        AssistSettings(context).apply {
            aiDisclosureSeen = true
            safetyNoticeAccepted = true
        }
    }

    @Test
    fun captureArmingTogglePersistsForTheWallpaperEngine() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.switch_capture_armed)).perform(scrollTo(), click())

            val settings = AssistSettings(ApplicationProvider.getApplicationContext())
            assertTrue(settings.captureArmed)
            onView(withId(R.id.tv_burst_shots_value)).perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun performanceModeSelectionPersists() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btn_mode_battery_saver)).perform(scrollTo(), click())

            val settings = AssistSettings(ApplicationProvider.getApplicationContext())
            assertEquals(PerformanceMode.BATTERY_SAVER, settings.performanceMode)
        }
    }

    @Test
    fun blindAssistTogglePersistsAndExplainsFixedAlerts() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.switch_blind_assist)).perform(scrollTo(), click())

            val settings = AssistSettings(ApplicationProvider.getApplicationContext())
            assertTrue(settings.blindAssist)
            onView(withId(R.id.tv_blind_audio_note)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
}

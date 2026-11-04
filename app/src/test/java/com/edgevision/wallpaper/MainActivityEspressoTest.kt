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
import com.edgevision.wallpaper.settings.AssistSettings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = EdgeVisionApp::class)
class MainActivityEspressoTest {

    @Before
    fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AssistSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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

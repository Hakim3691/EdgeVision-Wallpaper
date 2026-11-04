package com.edgevision.wallpaper

import android.Manifest
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.edgevision.wallpaper.settings.AssistSettings
import com.google.android.material.switchmaterial.SwitchMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Before
    fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AssistSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun blindAssistTogglePersists() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Espresso's root picker can time out on a headless emulator whose
            // activity window never receives focus. Exercise the real view and
            // listener directly; persistence and visibility are still asserted.
            scenario.onActivity { activity ->
                activity.findViewById<SwitchMaterial>(R.id.switch_blind_assist).performClick()
                assertTrue(AssistSettings(activity).blindAssist)
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.tv_blind_audio_note).visibility,
                )
            }
        }
    }
}

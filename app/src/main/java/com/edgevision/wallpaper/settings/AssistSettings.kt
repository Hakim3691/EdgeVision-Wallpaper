package com.edgevision.wallpaper.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.NonNull
import androidx.annotation.WorkerThread

object AssistSettings {
    private const val PREFS_NAME = "android_clear_settings"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_VIBRATION = "vibration_enabled"
    private const val KEY_BLIND_ASSIST = "blind_assist_enabled"

    @WorkerThread
    fun from(@NonNull context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

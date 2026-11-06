package com.edgevision.wallpaper.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.edgevision.wallpaper.inference.HazardKind
import com.edgevision.wallpaper.vision.HazardEvent
import java.util.Locale

/**
 * Plays low-latency hazard alerts. Audio, haptics, and speech are prepared when
 * the wallpaper becomes visible and released when it is hidden, so the wallpaper
 * does not hold audio resources while the launcher is covered.
 */
class AlertCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var prepared = false

    @Volatile
    private var generation = 0

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var lastToneAtMs = 0L
    private var lastHapticAtMs = 0L
    private var lastSpeechAtMs = 0L

    fun prepare() {
        if (prepared) return
        prepared = true
        generation += 1
        handler.post {
            if (!prepared) return@post
            if (toneGenerator == null) {
                toneGenerator = runCatching {
                    ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
                }.onFailure { Log.w(TAG, "ToneGenerator unavailable", it) }.getOrNull()
            }
            if (vibrator == null) {
                vibrator = resolveVibrator()
            }
        }
    }

    fun stop() {
        prepared = false
        generation += 1
        handler.removeCallbacksAndMessages(null)
        handler.post { releaseAudio() }
    }

    fun close() {
        stop()
    }

    fun dispatch(event: HazardEvent, decision: AlertDecision) {
        if (!prepared) return
        if (!decision.audio && !decision.haptic && !decision.spoken) return
        val expectedGeneration = generation

        handler.post {
            if (!prepared || expectedGeneration != generation) return@post
            val now = SystemClock.elapsedRealtime()

            if (decision.audio && now - lastToneAtMs >= TONE_COOLDOWN_MS) {
                lastToneAtMs = now
                toneGenerator?.let { tone ->
                    val duration = if (decision.spoken) 220 else 140
                    runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, duration) }
                }
            }

            if (decision.haptic && now - lastHapticAtMs >= HAPTIC_COOLDOWN_MS) {
                lastHapticAtMs = now
                vibrate(blindPattern = decision.spoken)
            }

            if (decision.spoken && now - lastSpeechAtMs >= SPEECH_COOLDOWN_MS) {
                lastSpeechAtMs = now
                speak(event)
            }
        }
    }

    private fun speak(event: HazardEvent) {
        ensureTextToSpeech()
        if (!ttsReady) return
        val text = when (event.kind) {
            HazardKind.VEHICLE -> "Vehicle approaching"
            HazardKind.POLE -> "Obstacle ahead"
        }
        textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "edgevision-hazard",
        )
    }

    private fun ensureTextToSpeech() {
        if (textToSpeech != null) return
        textToSpeech = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching {
                    textToSpeech?.language = Locale.getDefault()
                    textToSpeech?.setSpeechRate(0.95f)
                }
            } else {
                Log.w(TAG, "TextToSpeech initialization failed: $status")
            }
        }
    }

    private fun vibrate(blindPattern: Boolean) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val pattern = if (blindPattern) {
            longArrayOf(0L, 120L, 80L, 180L)
        } else {
            longArrayOf(0L, 90L)
        }
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Log.w(TAG, "Vibration failed", it) }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun releaseAudio() {
        runCatching { toneGenerator?.stopTone() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null

        textToSpeech?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        textToSpeech = null
        ttsReady = false
        vibrator = null

        lastToneAtMs = 0L
        lastHapticAtMs = 0L
        lastSpeechAtMs = 0L
    }

    private companion object {
        const val TAG = "AlertCoordinator"
        const val TONE_VOLUME = 100
        const val TONE_COOLDOWN_MS = 900L
        const val HAPTIC_COOLDOWN_MS = 700L
        const val SPEECH_COOLDOWN_MS = 2_500L
    }
}

package io.github.hafeee44lab.solargrid.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The looping outage alarm. It is owned by the service, not by the alarm screen: when the phone
 * is in use Android shows a full-screen intent as a banner and never opens the activity, and an
 * alarm that only rang from the activity stayed silent in exactly that case.
 */
object AlarmPlayer {

    const val DEFAULT_DURATION_MS = 90_000L
    private const val FADE_STEPS = 16
    private const val FADE_STEP_MS = 500L
    private val PATTERN = longArrayOf(0L, 700L, 350L, 700L, 1300L)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing

    /** How long the current alarm rings for; 0 means until stopped. Shown on the alarm screen. */
    private val _durationMs = MutableStateFlow(DEFAULT_DURATION_MS)
    val durationMs: StateFlow<Long> = _durationMs

    fun start(context: Context, ringInSilentMode: Boolean, durationMs: Long = DEFAULT_DURATION_MS) {
        main.post { startOnMain(context.applicationContext, ringInSilentMode, durationMs) }
    }

    fun stop() {
        main.post { stopOnMain() }
    }

    private fun startOnMain(context: Context, ringInSilentMode: Boolean, durationMs: Long) {
        stopOnMain()

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (ringInSilentMode) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(0.15f, 0.15f)
                prepare()
                start()
            }
            fadeIn(1)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator = vibratorFor(context)?.also {
                it.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _durationMs.value = durationMs
        _ringing.value = true
        if (durationMs > 0) main.postDelayed(::stopOnMain, durationMs)
    }

    /** A gentle ramp: loud enough to wake you, not a jump-scare at full volume. */
    private fun fadeIn(step: Int) {
        val current = player ?: return
        if (step > FADE_STEPS) return
        val volume = 0.15f + 0.85f * step / FADE_STEPS
        try {
            current.setVolume(volume, volume)
        } catch (e: Exception) {
            return
        }
        main.postDelayed({ fadeIn(step + 1) }, FADE_STEP_MS)
    }

    private fun stopOnMain() {
        main.removeCallbacksAndMessages(null)

        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            it.release()
        }
        player = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vibrator = null
        _ringing.value = false
    }

    private fun vibratorFor(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}

package com.example.zubflix.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.zubflix.util.AudioSettings

/**
 * Manages the hardware DSP LoudnessEnhancer and speech intelligibility for ExoPlayer.
 * Provides safe decibel boost (+0dB to +16dB) with anti-clipping headroom protection.
 */
@OptIn(UnstableApi::class)
class AudioEffectManager(private val context: Context) {

    private val TAG = "AudioEffectManager"
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentSessionId: Int = 0

    // Current active boost in millibels (0 to 1600 mB)
    var currentBoostMb: Int = AudioSettings.getDefaultBoostLevel(context)
        private set

    /**
     * Attaches LoudnessEnhancer to the player's AudioSession ID.
     */
    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (currentSessionId == audioSessionId && loudnessEnhancer != null) return

        release()
        currentSessionId = audioSessionId

        try {
            val enhancer = LoudnessEnhancer(audioSessionId)
            loudnessEnhancer = enhancer
            applyBoost(currentBoostMb)
            Log.d(TAG, "LoudnessEnhancer attached to audioSession: $audioSessionId with initial boost: $currentBoostMb mB")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize LoudnessEnhancer for session $audioSessionId: ${e.message}")
            loudnessEnhancer = null
        }
    }

    /**
     * Applies target gain in millibels.
     * @param targetGainMb Target gain (0 mB to 1600 mB / +0dB to +16dB)
     */
    fun applyBoost(targetGainMb: Int) {
        currentBoostMb = targetGainMb.coerceIn(AudioSettings.BOOST_OFF, AudioSettings.BOOST_MAX)
        try {
            loudnessEnhancer?.let { enhancer ->
                if (currentBoostMb > 0) {
                    enhancer.setTargetGain(currentBoostMb)
                    if (!enhancer.enabled) {
                        enhancer.enabled = true
                    }
                } else {
                    enhancer.setTargetGain(0)
                    if (enhancer.enabled) {
                        enhancer.enabled = false
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set target gain: ${e.message}")
        }
    }

    /**
     * Cycles to the next boost preset level for quick toggle buttons.
     */
    fun cycleNextBoost(): Int {
        val next = when (currentBoostMb) {
            AudioSettings.BOOST_OFF -> AudioSettings.BOOST_LOW
            AudioSettings.BOOST_LOW -> AudioSettings.BOOST_MEDIUM
            AudioSettings.BOOST_MEDIUM -> AudioSettings.BOOST_HIGH
            AudioSettings.BOOST_HIGH -> AudioSettings.BOOST_MAX
            else -> AudioSettings.BOOST_OFF
        }
        applyBoost(next)
        if (AudioSettings.isRememberSessionBoostEnabled(context)) {
            AudioSettings.setDefaultBoostLevel(context, next)
        }
        return next
    }

    /**
     * Sets boost directly by percentage from 100% to 200%.
     */
    fun setBoostFromPercentage(percent: Int) {
        val clampedPercent = percent.coerceIn(100, 200)
        val normalized = (clampedPercent - 100) / 100.0f
        val targetMb = (normalized * AudioSettings.BOOST_MAX).toInt()
        applyBoost(targetMb)
    }

    /**
     * Releases audio effect safely to avoid native DSP memory leaks.
     */
    fun release() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing LoudnessEnhancer: ${e.message}")
        } finally {
            loudnessEnhancer = null
            currentSessionId = 0
        }
    }
}

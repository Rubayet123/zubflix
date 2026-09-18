package com.example.zubflix.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.example.R
import com.example.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Formatter
import java.util.Locale

/**
 * Controller for gestures in Zubflix player:
 * - Horizontal swipe for scrub/seek preview
 * - Vertical left swipe for screen brightness
 * - Vertical right swipe for volume level
 * - Double tap left/right for 10s seek, center for play/pause
 */
@UnstableApi
class PlayerGestureController(
    private val activity: Activity,
    private val binding: ActivityPlayerBinding,
    private val playerProvider: () -> ExoPlayer?,
    private val audioEffectManager: AudioEffectManager? = null,
    private val timeFormatter: (Long) -> String
) {
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var isVolumeGesture = false
    private var isBrightnessGesture = false
    private var isScrubbing = false
    private var scrubStartPos = 0L

    // 0.0f to 1.0f (0-100% system volume) and 1.0f to 2.0f (100-200% audio boost)
    private var currentVolumeFloat = 0.5f
    private var currentBrightnessFloat = 0.5f
    private var gestureOverlayJob: Job? = null

    init {
        val initialSysVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val initialBoostMb = audioEffectManager?.currentBoostMb ?: 0
        if (initialBoostMb > 0) {
            val boostFraction = initialBoostMb.toFloat() / com.example.zubflix.util.AudioSettings.BOOST_MAX.toFloat()
            currentVolumeFloat = 1.0f + boostFraction
        } else {
            currentVolumeFloat = initialSysVolume.toFloat() / maxVolume.toFloat()
        }

        val lp = activity.window.attributes
        currentBrightnessFloat = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
    }

    fun setupGestures(lifecycleOwner: LifecycleOwner) {
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.playerView.isControllerFullyVisible) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = activity.resources.displayMetrics.widthPixels
                playerProvider()?.let { p ->
                    if (e.x < screenWidth / 3) {
                        p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                        showDoubleTapIndicator("-10s", lifecycleOwner)
                    } else if (e.x > 2 * screenWidth / 3) {
                        p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                        showDoubleTapIndicator("+10s", lifecycleOwner)
                    } else {
                        if (p.isPlaying) {
                            p.pause()
                            showDoubleTapIndicator("PAUSE", lifecycleOwner)
                        } else {
                            p.play()
                            showDoubleTapIndicator("PLAY", lifecycleOwner)
                        }
                    }
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            val screenWidth = activity.resources.displayMetrics.widthPixels
            val screenHeight = activity.resources.displayMetrics.heightPixels

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartY = event.y
                    gestureStartX = event.x
                    isVolumeGesture = false
                    isBrightnessGesture = false
                    isScrubbing = false

                    // Resync current volume with AudioManager and active DSP Boost
                    val sysVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val activeBoostMb = audioEffectManager?.currentBoostMb ?: 0
                    if (activeBoostMb > 0) {
                        val boostFraction = activeBoostMb.toFloat() / com.example.zubflix.util.AudioSettings.BOOST_MAX.toFloat()
                        currentVolumeFloat = 1.0f + boostFraction
                    } else {
                        currentVolumeFloat = sysVolume.toFloat() / maxVolume.toFloat()
                    }

                    val lp = activity.window.attributes
                    currentBrightnessFloat = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaY = gestureStartY - event.y
                    val deltaX = event.x - gestureStartX

                    // Priority 1: Horizontal Scrubbing (Seek)
                    if (!isVolumeGesture && !isBrightnessGesture && (kotlin.math.abs(deltaX) > 80 || isScrubbing)) {
                        if (!isScrubbing && kotlin.math.abs(deltaY) < 100) {
                            isScrubbing = true
                            scrubStartPos = playerProvider()?.currentPosition ?: 0L
                        }

                        if (isScrubbing) {
                            val totalDuration = playerProvider()?.duration ?: 0L
                            if (totalDuration > 0) {
                                val seekDelta = (deltaX / screenWidth * 120_000).toLong()
                                val newPos = (scrubStartPos + seekDelta).coerceIn(0, totalDuration)
                                binding.centralFeedbackContainer.visibility = View.VISIBLE
                                binding.tvCentralFeedback.text = timeFormatter(newPos)
                            }
                        }
                    }
                    // Priority 2: Vertical Gestures (Volume/Brightness)
                    else if (!isScrubbing && (kotlin.math.abs(deltaY) > 50 || isVolumeGesture || isBrightnessGesture)) {
                        if (!isVolumeGesture && !isBrightnessGesture) {
                            isVolumeGesture = gestureStartX > screenWidth / 2
                            isBrightnessGesture = gestureStartX <= screenWidth / 2
                        }

                        if (isVolumeGesture) {
                            val allowExtendedBoost = com.example.zubflix.util.AudioSettings.isGestureExtendedBoostEnabled(activity)
                            val maxAllowedVolume = if (allowExtendedBoost && audioEffectManager != null) 2.0f else 1.0f
                            val volumeChange = (deltaY / screenHeight) * 1.2f
                            currentVolumeFloat = (currentVolumeFloat + volumeChange).coerceIn(0f, maxAllowedVolume)

                            if (currentVolumeFloat <= 1.0f) {
                                // Standard System Volume (0% - 100%)
                                playerProvider()?.volume = 1.0f
                                audioEffectManager?.applyBoost(0)
                                val targetSysVolume = (currentVolumeFloat * maxVolume).toInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVolume, 0)
                            } else {
                                // Extended Audio Boost (100% - 200%) via DSP LoudnessEnhancer
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                                playerProvider()?.volume = 1.0f
                                val boostPercent = (currentVolumeFloat * 100).toInt()
                                audioEffectManager?.setBoostFromPercentage(boostPercent)
                            }

                            showVolumeOverlay(currentVolumeFloat, lifecycleOwner)
                        } else if (isBrightnessGesture) {
                            val brightnessChange = (deltaY / screenHeight) * 1.2f
                            currentBrightnessFloat = (currentBrightnessFloat + brightnessChange).coerceIn(0.01f, 1.0f)

                            val lpAttr = activity.window.attributes
                            lpAttr.screenBrightness = currentBrightnessFloat
                            activity.window.attributes = lpAttr

                            showBrightnessOverlay(currentBrightnessFloat, lifecycleOwner)
                        }
                        gestureStartY = event.y
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isScrubbing) {
                        val totalDuration = playerProvider()?.duration ?: 0L
                        val deltaX = event.x - gestureStartX
                        val seekDelta = (deltaX / screenWidth * 120_000).toLong()
                        val newPos = (scrubStartPos + seekDelta).coerceIn(0, totalDuration)
                        playerProvider()?.seekTo(newPos)
                        isScrubbing = false
                        binding.centralFeedbackContainer.visibility = View.GONE
                    }
                }
            }
            true
        }
    }

    private fun showVolumeOverlay(volumeFloat: Float, lifecycleOwner: LifecycleOwner) {
        binding.volumeSliderContainer.visibility = View.VISIBLE
        val totalPercent = (volumeFloat * 100).toInt()
        
        binding.volumeSlider.max = 100
        if (totalPercent > 100) {
            // Phase 2: Audio Boost (101% - 200%)
            // Progress represents the boost depth from 0% to 100% over the full bar
            val boostProgress = (totalPercent - 100).coerceIn(1, 100)
            binding.volumeSlider.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.vertical_progress_boost)
            binding.volumeSlider.progress = boostProgress
            
            binding.volumeIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))
            binding.volumePercentage.text = "BOOST\n$totalPercent%"
            binding.volumePercentage.setTextColor(android.graphics.Color.parseColor("#FF9800"))
        } else {
            // Phase 1: Normal System Volume (0% - 100%)
            // Full bar height represents 0% to 100% system volume
            binding.volumeSlider.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.vertical_progress_red)
            binding.volumeSlider.progress = totalPercent.coerceIn(0, 100)
            
            binding.volumeIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.volumePercentage.text = "$totalPercent%"
            binding.volumePercentage.setTextColor(android.graphics.Color.WHITE)
        }
        resetGestureOverlayTimer(lifecycleOwner)
    }

    private fun showBrightnessOverlay(brightness: Float, lifecycleOwner: LifecycleOwner) {
        binding.brightnessSliderContainer.visibility = View.VISIBLE
        val progressPercent = (brightness * 100).toInt()
        binding.brightnessSlider.progress = progressPercent
        binding.brightnessPercentage.text = "$progressPercent%"
        resetGestureOverlayTimer(lifecycleOwner)
    }

    private fun resetGestureOverlayTimer(lifecycleOwner: LifecycleOwner) {
        gestureOverlayJob?.cancel()
        gestureOverlayJob = lifecycleOwner.lifecycleScope.launch {
            delay(1500)
            binding.volumeSliderContainer.visibility = View.GONE
            binding.brightnessSliderContainer.visibility = View.GONE
        }
    }

    private var indicatorJob: Job? = null

    fun showDoubleTapIndicator(text: String, lifecycleOwner: LifecycleOwner) {
        binding.centralFeedbackContainer.visibility = View.VISIBLE
        binding.tvCentralFeedback.text = text
        indicatorJob?.cancel()
        indicatorJob = lifecycleOwner.lifecycleScope.launch {
            delay(1200)
            binding.centralFeedbackContainer.visibility = View.GONE
        }
    }
}

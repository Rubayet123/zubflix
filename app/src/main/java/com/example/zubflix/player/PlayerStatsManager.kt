package com.example.zubflix.player

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.example.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Diagnostics and real-time telemetry overlay manager ("Stats for Nerds") for Zubflix player.
 */
@UnstableApi
class PlayerStatsManager(
    private val context: Context,
    private val binding: ActivityPlayerBinding,
    private val playerProvider: () -> ExoPlayer?
) {
    private var statsEnabled = false
    private var statsPollerJob: Job? = null

    fun isStatsEnabled(): Boolean = statsEnabled

    fun toggleStats(lifecycleOwner: LifecycleOwner) {
        statsEnabled = !statsEnabled
        binding.statsContainer.visibility = if (statsEnabled) View.VISIBLE else View.GONE

        if (statsEnabled) {
            startStatsPoller(lifecycleOwner)
        } else {
            stopStatsPoller()
        }
    }

    fun startStatsPoller(lifecycleOwner: LifecycleOwner) {
        statsPollerJob?.cancel()
        statsPollerJob = lifecycleOwner.lifecycleScope.launch {
            while (playerProvider() != null && statsEnabled) {
                updateStats()
                delay(1000)
            }
        }
    }

    fun stopStatsPoller() {
        statsPollerJob?.cancel()
        statsPollerJob = null
    }

    fun updateStats() {
        val p = playerProvider() ?: return

        // 1. Resolution & Aspect
        val width = p.videoSize.width
        val height = p.videoSize.height
        binding.tvStatsResolution.text = if (width > 0 && height > 0) "${width}x${height}" else "Detecting..."

        // 2. Codec & Decoder Mime
        val format = p.videoFormat
        val mimeType = format?.sampleMimeType ?: "Unknown"
        binding.tvStatsCodec.text = mimeType.replace("video/", "")

        // 3. Bitrate
        val bitrate = format?.bitrate
        binding.tvStatsBitrate.text = if (bitrate != null && bitrate > 0) "${bitrate / 1000} kbps" else "Measuring..."

        // 4. Buffer Health
        val bufferedPos = p.bufferedPosition
        val currentPos = p.currentPosition
        val bufferHealthMs = (bufferedPos - currentPos).coerceAtLeast(0)
        binding.tvStatsBuffer.text = String.format(Locale.getDefault(), "%.1fs", bufferHealthMs / 1000.0)

        // 5. Estimated Connection Speed
        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)
        val speedMbps = bandwidthMeter.bitrateEstimate / 1_000_000.0
        binding.tvStatsSpeed.text = String.format(Locale.getDefault(), "%.1f Mbps", speedMbps)

        // 6. Dropped Frame Counters
        val counters = p.videoDecoderCounters
        val dropped = counters?.droppedBufferCount ?: 0
        binding.tvStatsDropped.text = dropped.toString()
    }
}

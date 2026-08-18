package com.example.zubflix.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * Encapsulates audio track, embedded subtitle, and subtitle styling logic for ExoPlayer.
 */
@UnstableApi
object PlayerTrackManager {

    /**
     * Resolves human-readable label for audio tracks including codec tags (Dolby Atmos, 5.1, AAC, etc.)
     */
    fun formatAudioLabel(format: Format): String {
        val langDisplay = format.language?.let { langCode ->
            try {
                val loc = Locale(langCode)
                val disp = loc.displayLanguage
                if (disp.isNotEmpty()) disp else langCode
            } catch (e: Exception) {
                langCode
            }
        } ?: "Audio"

        val mimeType = format.sampleMimeType?.lowercase(Locale.ROOT)
        val codecInfo = when {
            mimeType == "audio/ac3" -> "AC-3 5.1"
            mimeType == "audio/eac3" -> "E-AC-3"
            mimeType == "audio/eac3-joc" -> "E-AC-3 Atmos"
            mimeType == "audio/vnd.dts" -> "DTS"
            mimeType == "audio/vnd.dts.hd" -> "DTS-HD"
            mimeType == "audio/mp4a-latm" -> "AAC"
            mimeType == "audio/mpeg" -> "MP3"
            mimeType == "audio/opus" -> "Opus"
            mimeType == "audio/flac" -> "FLAC"
            mimeType == "audio/raw" || mimeType == "audio/pcm" -> "PCM"
            mimeType != null -> mimeType.substringAfter("/").uppercase(Locale.ROOT)
            else -> null
        }

        val channelInfo = when (format.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (format.channelCount > 0) "${format.channelCount}ch" else null
        }

        val details = listOfNotNull(codecInfo, channelInfo).joinToString(" • ")
        val titleText = format.label ?: langDisplay

        return if (details.isNotEmpty()) "$titleText ($details)" else titleText
    }

    /**
     * Resolves human-readable label for subtitle tracks
     */
    fun formatSubtitleLabel(format: Format): String {
        val langDisplay = format.language?.let { langCode ->
            try {
                val loc = Locale(langCode)
                val disp = loc.displayLanguage
                if (disp.isNotEmpty()) disp else langCode
            } catch (e: Exception) {
                langCode
            }
        } ?: "Subtitle"

        val label = format.label
        return if (!label.isNullOrEmpty() && label != langDisplay) {
            "$langDisplay - $label"
        } else {
            langDisplay
        }
    }

    /**
     * Applies styling settings (Font size, Text color, Background color, Stroke) from player_prefs
     */
    fun applySubtitleStyle(playerView: PlayerView, context: Context) {
        try {
            val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            val textColor = prefs.getInt("sub_color", Color.WHITE)
            val bgColor = prefs.getInt("sub_bg", Color.parseColor("#80000000"))
            val sizeStr = prefs.getString("sub_size", "0.053") ?: "0.053"
            val subSize = sizeStr.toFloatOrNull() ?: 0.053f

            val captionStyle = CaptionStyleCompat(
                textColor,
                bgColor,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD
            )

            val subView = playerView.subtitleView
            subView?.setStyle(captionStyle)
            subView?.setFractionalTextSize(subSize)
        } catch (e: Exception) {
            // Fallback to default
        }
    }

    fun selectAudioTrack(player: ExoPlayer, group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    fun selectSubtitleTrack(player: ExoPlayer, group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun disableSubtitles(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
}

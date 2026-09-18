package com.example.zubflix.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.leanback.widget.Presenter
import coil.dispose
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.R
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.util.GenreUtils
import com.example.zubflix.util.NetworkUtils

class TvCardPresenter(
    val isLandscape: Boolean = false,
    private val onCardFocused: ((StreamingItem) -> Unit)? = null,
    private val onCardLongClicked: ((StreamingItem) -> Unit)? = null
) : Presenter() {

    class ViewHolder(view: View) : Presenter.ViewHolder(view) {
        val container: CardView = view.findViewById(R.id.tv_card_container)
        val poster: ImageView = view.findViewById(R.id.tv_card_poster)
        val title: TextView = view.findViewById(R.id.tv_card_title)
        val ratingBadge: LinearLayout = view.findViewById(R.id.tv_card_rating_badge)
        val ratingText: TextView = view.findViewById(R.id.tv_card_rating_text)
        val actionPill: LinearLayout = view.findViewById(R.id.tv_card_action_pill)
        val actionText: TextView = view.findViewById(R.id.tv_card_action_text)
        val progressBar: ProgressBar = view.findViewById(R.id.tv_card_progress_bar)
        val playOverlay: ImageView? = view.findViewById(R.id.tv_card_play_overlay)
        val focusOverlay: View = view.findViewById(R.id.tv_card_focus_overlay)
        val seeAllContainer: LinearLayout = view.findViewById(R.id.tv_see_all_container)
        val seeAllText: TextView = view.findViewById(R.id.tv_see_all_text)
        val scrim: View? = view.findViewById(R.id.tv_card_scrim)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_tv_movie_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as? ViewHolder ?: return
        val streamingItem = item as? StreamingItem ?: return
        val density = holder.view.resources.displayMetrics.density

        // Cancel any in-flight Coil image request on recycled view holder & clear image drawable
        holder.poster.dispose()
        holder.poster.setImageDrawable(null)

        val isNetworkCard = streamingItem.id.startsWith("network:") ||
                streamingItem.id.startsWith("stremio_network:") ||
                streamingItem.id.startsWith("provider:")

        val logoRes = if (isNetworkCard) {
            NetworkUtils.getLogoResId(streamingItem.id.removePrefix("network:").removePrefix("stremio_network:").removePrefix("provider:"))
                ?: NetworkUtils.getLogoResId(streamingItem.title)
        } else null

        val isGenreCard = streamingItem.id.startsWith("genre:")
        val isRegionCard = streamingItem.id.startsWith("region:")

        if (isGenreCard) {
            // Horizontal Landscape Pill Card for Genres (150dp x 85dp)
            val params = holder.view.layoutParams
            params.width = (150 * density).toInt()
            params.height = (85 * density).toInt()
            holder.view.layoutParams = params

            val genreKey = streamingItem.id.removePrefix("genre:").ifBlank { streamingItem.title }
            val gradientColors = GenreUtils.getGenreGradient(genreKey)
            val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradientColors).apply {
                cornerRadius = 8f * density
            }

            holder.container.background = gd
            holder.container.setCardBackgroundColor(Color.TRANSPARENT)
            holder.scrim?.visibility = View.GONE

            holder.seeAllContainer.visibility = View.GONE
            holder.ratingBadge.visibility = View.GONE
            holder.actionPill.visibility = View.GONE
            holder.progressBar.visibility = View.GONE

            val iconRes = GenreUtils.getGenreIconRes(genreKey)
            holder.poster.visibility = View.VISIBLE
            holder.poster.scaleType = ImageView.ScaleType.FIT_CENTER
            val px = (14 * density).toInt()
            val py = (10 * density).toInt()
            holder.poster.setPadding(px, py, px, (26 * density).toInt())
            holder.poster.setImageResource(iconRes)

            holder.title.visibility = View.VISIBLE
            holder.title.text = streamingItem.title
            holder.title.setTextColor(Color.WHITE)
            holder.title.textSize = 12f
            holder.title.setPadding((6 * density).toInt(), 0, (6 * density).toInt(), (6 * density).toInt())

        } else if (isRegionCard) {
            // Horizontal Landscape Pill Card for Regions / Countries (150dp x 85dp)
            val params = holder.view.layoutParams
            params.width = (150 * density).toInt()
            params.height = (85 * density).toInt()
            holder.view.layoutParams = params

            val countryCode = streamingItem.id.removePrefix("region:").uppercase()
            val colors = intArrayOf(0xFF262D35.toInt(), 0xFF12161A.toInt())
            val gd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                cornerRadius = 8f * density
            }

            holder.container.background = gd
            holder.container.setCardBackgroundColor(Color.TRANSPARENT)
            holder.scrim?.visibility = View.GONE

            holder.seeAllContainer.visibility = View.GONE
            holder.ratingBadge.visibility = View.GONE
            holder.actionPill.visibility = View.GONE
            holder.progressBar.visibility = View.GONE

            val flagEmoji = getFlagEmoji(countryCode)
            val flagUrl = if (countryCode != "ALL" && countryCode.length == 2) {
                "https://flagcdn.com/w160/${countryCode.lowercase()}.png"
            } else null

            holder.poster.visibility = View.VISIBLE
            holder.poster.scaleType = ImageView.ScaleType.FIT_CENTER
            val px = (12 * density).toInt()
            val py = (10 * density).toInt()
            holder.poster.setPadding(px, py, px, (26 * density).toInt())

            if (flagUrl != null) {
                holder.poster.load(flagUrl) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(4f * density))
                }
            } else {
                holder.poster.setImageDrawable(null)
            }

            holder.title.visibility = View.VISIBLE
            holder.title.text = "$flagEmoji  ${streamingItem.title}"
            holder.title.setTextColor(Color.WHITE)
            holder.title.textSize = 12f
            holder.title.setPadding((6 * density).toInt(), 0, (6 * density).toInt(), (6 * density).toInt())

        } else if (isNetworkCard) {
            // Landscape 16:9 brand card for Networks & Studios (160dp x 90dp)
            val params = holder.view.layoutParams
            params.width = (160 * density).toInt()
            params.height = (90 * density).toInt()
            holder.view.layoutParams = params

            val gradientColors = NetworkUtils.getBrandGradient(streamingItem.title.ifBlank { streamingItem.id })
            val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradientColors).apply {
                cornerRadius = 8f * density
            }

            holder.container.background = gd
            holder.container.setCardBackgroundColor(Color.TRANSPARENT)
            holder.scrim?.visibility = View.GONE

            holder.seeAllContainer.visibility = View.GONE
            holder.title.visibility = View.GONE
            holder.ratingBadge.visibility = View.GONE
            holder.actionPill.visibility = View.GONE
            holder.progressBar.visibility = View.GONE

            if (logoRes != null) {
                holder.poster.visibility = View.VISIBLE
                holder.poster.scaleType = ImageView.ScaleType.FIT_CENTER
                val p = (14 * density).toInt()
                holder.poster.setPadding(p, p, p, p)
                holder.poster.setImageResource(logoRes)
            } else if (!streamingItem.imageUrl.isNullOrBlank()) {
                holder.poster.visibility = View.VISIBLE
                holder.poster.scaleType = ImageView.ScaleType.FIT_CENTER
                val p = (12 * density).toInt()
                holder.poster.setPadding(p, p, p, p)
                holder.poster.load(streamingItem.imageUrl) {
                    crossfade(true)
                }
            } else {
                holder.poster.visibility = View.GONE
                holder.seeAllContainer.visibility = View.VISIBLE
                holder.seeAllContainer.background = null
                holder.seeAllText.text = streamingItem.title
                holder.view.findViewById<View>(R.id.tv_see_all_icon)?.visibility = View.GONE
            }
        } else if (streamingItem.isCategory) {
            // Category card (e.g., See All / Explore)
            val cardW = if (isLandscape) (190 * density).toInt() else (116 * density).toInt()
            val cardH = if (isLandscape) (107 * density).toInt() else (174 * density).toInt()
            val params = holder.view.layoutParams
            params.width = cardW
            params.height = cardH
            holder.view.layoutParams = params

            holder.container.background = null
            holder.container.setCardBackgroundColor(Color.parseColor("#14171E"))
            holder.scrim?.visibility = View.GONE

            holder.poster.setImageDrawable(null)
            holder.poster.setPadding(0, 0, 0, 0)
            holder.title.visibility = View.GONE
            holder.ratingBadge.visibility = View.GONE
            holder.actionPill.visibility = View.GONE
            holder.progressBar.visibility = View.GONE
            holder.seeAllContainer.visibility = View.VISIBLE
            holder.seeAllContainer.setBackgroundColor(Color.parseColor("#161820"))
            holder.seeAllText.text = streamingItem.title
            holder.view.findViewById<View>(R.id.tv_see_all_icon)?.visibility = View.VISIBLE
        } else {
            // Movie/Series card (16:9 Landscape 190dp x 107dp or 2:3 Portrait 116dp x 174dp)
            val cardW = if (isLandscape) (190 * density).toInt() else (116 * density).toInt()
            val cardH = if (isLandscape) (107 * density).toInt() else (174 * density).toInt()
            val params = holder.view.layoutParams
            params.width = cardW
            params.height = cardH
            holder.view.layoutParams = params

            holder.container.background = null
            holder.container.setCardBackgroundColor(Color.parseColor("#14171E"))
            holder.scrim?.visibility = View.VISIBLE

            holder.seeAllContainer.visibility = View.GONE
            holder.title.visibility = View.VISIBLE
            holder.title.text = streamingItem.title

            // Action Pill Text
            val progress = streamingItem.watchPercentage?.toInt() ?: 0
            if (progress > 0) {
                holder.actionText.text = "Resume"
            } else {
                holder.actionText.text = "Watch Now"
            }

            // Poster / Backdrop image loading (Backdrop preferred in landscape mode)
            val targetImageUrl = if (isLandscape) {
                streamingItem.backdropUrl?.takeIf { it.isNotBlank() }
                    ?: streamingItem.imageUrl?.takeIf { it.isNotBlank() }
            } else {
                streamingItem.imageUrl?.takeIf { it.isNotBlank() }
                    ?: streamingItem.backdropUrl?.takeIf { it.isNotBlank() }
            }

            holder.poster.setPadding(0, 0, 0, 0)
            holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP

            if (!targetImageUrl.isNullOrEmpty()) {
                holder.poster.load(targetImageUrl) {
                    crossfade(true)
                    allowRgb565(true)
                    transformations(RoundedCornersTransformation(6f))
                }
            } else {
                holder.poster.setImageResource(R.drawable.ic_movie)
            }

            // Rating Badge
            val rating = streamingItem.rating
            if (!rating.isNullOrBlank() && rating != "0" && rating != "0.0" && rating != "N/A") {
                val cleanRating = rating.replace("/10", "").trim()
                val formattedRating = try {
                    String.format("%.1f", cleanRating.toDouble())
                } catch (e: Exception) {
                    cleanRating
                }
                holder.ratingText.text = formattedRating
                holder.ratingBadge.visibility = View.VISIBLE
            } else {
                holder.ratingBadge.visibility = View.GONE
            }

            // Continue Watching Progress Bar & Play Overlay Icon
            if (progress > 0) {
                holder.progressBar.progress = progress.coerceIn(1, 100)
                holder.progressBar.visibility = View.VISIBLE
                holder.playOverlay?.visibility = View.VISIBLE
            } else {
                holder.progressBar.visibility = View.GONE
                holder.playOverlay?.visibility = View.GONE
            }

            // Long Press Options Menu for Continue Watching / Items
            if (streamingItem.watchPercentage != null && onCardLongClicked != null) {
                holder.view.setOnLongClickListener {
                    onCardLongClicked.invoke(streamingItem)
                    true
                }
            } else {
                holder.view.setOnLongClickListener(null)
            }
        }

        // Smooth Netflix-style focus scale and action pill toggle
        holder.view.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            val elevation = if (hasFocus) 16f else 3f
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Toggle Netflix Watch Now action pill on focus
            if (!streamingItem.isCategory && !isNetworkCard && !isGenreCard && !isRegionCard) {
                holder.actionPill.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }

            if (hasFocus) {
                onCardFocused?.invoke(streamingItem)
            }
        }
    }

    private fun getFlagEmoji(countryCode: String): String {
        if (countryCode.equals("ALL", ignoreCase = true) || countryCode.isBlank()) return "🌐"
        if (countryCode.length != 2) return "🌐"
        val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as? ViewHolder ?: return
        holder.poster.dispose()
        holder.poster.setImageDrawable(null)
    }
}

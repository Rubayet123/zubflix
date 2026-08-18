package com.example.zubflix.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.R
import com.example.zubflix.model.StreamingItem

class TvCardPresenter(
    private val onCardFocused: ((StreamingItem) -> Unit)? = null
) : Presenter() {

    class ViewHolder(view: View) : Presenter.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.tv_card_poster)
        val title: TextView = view.findViewById(R.id.tv_card_title)
        val ratingBadge: LinearLayout = view.findViewById(R.id.tv_card_rating_badge)
        val ratingText: TextView = view.findViewById(R.id.tv_card_rating_text)
        val progressBar: ProgressBar = view.findViewById(R.id.tv_card_progress_bar)
        val focusOverlay: View = view.findViewById(R.id.tv_card_focus_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_tv_movie_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as? ViewHolder ?: return
        val streamingItem = item as? StreamingItem ?: return

        holder.title.text = streamingItem.title

        // Poster image loading
        val posterUrl = streamingItem.imageUrl?.takeIf { it.isNotBlank() }
            ?: streamingItem.backdropUrl?.takeIf { it.isNotBlank() }

        if (!posterUrl.isNullOrEmpty()) {
            holder.poster.load(posterUrl) {
                crossfade(true)
                allowRgb565(true)
                transformations(RoundedCornersTransformation(10f))
            }
        } else {
            holder.poster.setImageResource(R.drawable.ic_movie)
        }

        // Rating
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

        // Continue Watching Progress Bar
        val progress = streamingItem.watchPercentage?.toInt() ?: 0
        if (progress > 0) {
            holder.progressBar.progress = progress.coerceIn(1, 100)
            holder.progressBar.visibility = View.VISIBLE
        } else {
            holder.progressBar.visibility = View.GONE
        }

        // Smooth focus scale effect
        holder.view.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            val elevation = if (hasFocus) 16f else 4f
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()

            if (hasFocus) {
                onCardFocused?.invoke(streamingItem)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as? ViewHolder ?: return
        holder.poster.setImageDrawable(null)
    }
}

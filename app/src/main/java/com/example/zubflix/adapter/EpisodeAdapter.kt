package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.R
import com.example.zubflix.model.StreamingEpisode

class EpisodeAdapter(
    private val episodes: List<StreamingEpisode>,
    private val onEpisodeClick: (StreamingEpisode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgStill: ImageView = itemView.findViewById(R.id.img_episode_still)
        val tvEpNumberBadge: TextView = itemView.findViewById(R.id.tv_episode_number_badge)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_episode_title)
        val tvAirDate: TextView = itemView.findViewById(R.id.tv_ep_air_date)
        val tvRuntime: TextView = itemView.findViewById(R.id.tv_ep_runtime)
        val tvRating: TextView = itemView.findViewById(R.id.tv_ep_rating)
        val tvDescription: TextView = itemView.findViewById(R.id.tv_episode_description)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        val epNum = position + 1
        holder.tvEpNumberBadge.text = "EP $epNum"
        holder.tvTitle.text = episode.title

        // Air Date
        if (!episode.airDate.isNullOrEmpty()) {
            holder.tvAirDate.text = episode.airDate
            holder.tvAirDate.visibility = View.VISIBLE
        } else {
            holder.tvAirDate.visibility = View.GONE
        }

        // Runtime
        if (episode.runtime != null && episode.runtime > 0) {
            holder.tvRuntime.text = "${episode.runtime}m"
            holder.tvRuntime.visibility = View.VISIBLE
        } else {
            holder.tvRuntime.visibility = View.GONE
        }

        // Rating
        if (episode.voteAverage != null && episode.voteAverage > 0) {
            holder.tvRating.text = "★ ${String.format("%.1f", episode.voteAverage)}"
            holder.tvRating.visibility = View.VISIBLE
        } else {
            holder.tvRating.visibility = View.GONE
        }

        // Description
        if (!episode.overview.isNullOrEmpty()) {
            holder.tvDescription.text = episode.overview
            holder.tvDescription.visibility = View.VISIBLE
        } else {
            holder.tvDescription.text = "No description available for this episode."
        }

        // Still image loading
        if (!episode.stillUrl.isNullOrEmpty()) {
            Glide.with(holder.itemView.context)
                .load(episode.stillUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .placeholder(R.drawable.bg_smart_poster)
                .error(R.drawable.bg_smart_poster)
                .centerCrop()
                .into(holder.imgStill)
        } else {
            holder.imgStill.setImageResource(R.drawable.bg_smart_poster)
        }

        holder.itemView.setOnClickListener {
            onEpisodeClick(episode)
        }

        setupTVFocus(holder.itemView)
    }

    override fun getItemCount() = episodes.size

    private fun setupTVFocus(view: View) {
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(150).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }
}

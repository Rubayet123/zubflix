package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.R
import com.example.zubflix.utils.TmdbHelper

class PersonCreditAdapter(
    private val onItemClick: (TmdbHelper.PersonCredit) -> Unit
) : RecyclerView.Adapter<PersonCreditAdapter.ViewHolder>() {

    private val creditsList = mutableListOf<TmdbHelper.PersonCredit>()

    fun submitList(newCredits: List<TmdbHelper.PersonCredit>) {
        creditsList.clear()
        creditsList.addAll(newCredits)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgPoster: ImageView = itemView.findViewById(R.id.img_poster)
        val tvMediaTypeBadge: TextView = itemView.findViewById(R.id.tv_media_type_badge)
        val tvRatingBadge: TextView = itemView.findViewById(R.id.tv_rating_badge)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tv_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filmography_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = creditsList[position]
        val context = holder.itemView.context

        holder.tvTitle.text = item.title

        // Subtitle: Year + Role/Job
        val yearPart = item.year?.takeIf { it.isNotBlank() }
        val rolePart = item.character?.takeIf { it.isNotBlank() }?.let { "as $it" } 
            ?: item.job?.takeIf { it.isNotBlank() }

        holder.tvSubtitle.text = when {
            yearPart != null && rolePart != null -> "$yearPart • $rolePart"
            yearPart != null -> yearPart
            rolePart != null -> rolePart
            else -> ""
        }

        // Media Type Badge
        if (item.mediaType == "tv") {
            holder.tvMediaTypeBadge.text = "📺 SERIES"
            holder.tvMediaTypeBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_series_chip)
        } else {
            holder.tvMediaTypeBadge.text = "🎬 MOVIE"
            holder.tvMediaTypeBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_movie_chip)
        }

        // Rating Badge
        if (!item.voteAverage.isNullOrEmpty() && item.voteAverage != "0.0") {
            holder.tvRatingBadge.visibility = View.VISIBLE
            holder.tvRatingBadge.text = "★ ${item.voteAverage}"
        } else {
            holder.tvRatingBadge.visibility = View.GONE
        }

        // Poster Image
        if (!item.posterPath.isNullOrEmpty()) {
            Glide.with(context)
                .load(item.posterPath)
                .placeholder(R.drawable.bg_smart_poster)
                .error(R.drawable.bg_smart_poster)
                .transition(DrawableTransitionOptions.withCrossFade())
                .centerCrop()
                .into(holder.imgPoster)
        } else {
            holder.imgPoster.setImageResource(R.drawable.bg_smart_poster)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        setupTVFocus(holder.itemView)
    }

    override fun getItemCount(): Int = creditsList.size

    private fun setupTVFocus(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(150)
                    .start()
            } else {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
        }
    }
}

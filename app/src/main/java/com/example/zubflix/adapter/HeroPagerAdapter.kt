package com.example.zubflix.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.R
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.database.MyListEntity
import com.example.zubflix.model.StreamingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HeroPagerAdapter(
    private val onMovieClick: (StreamingItem) -> Unit,
    private val onMyListToggled: ((StreamingItem, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<HeroPagerAdapter.HeroSlideViewHolder>() {

    private val items = mutableListOf<StreamingItem>()

    fun submitList(newItems: List<StreamingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroSlideViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hero_slide, parent, false)
        return HeroSlideViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeroSlideViewHolder, position: Int) {
        if (items.isNotEmpty()) {
            holder.bind(items[position], onMovieClick, onMyListToggled)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeroSlideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgBackdrop: ImageView = itemView.findViewById(R.id.img_hero_backdrop)
        private val tvBadgeType: TextView = itemView.findViewById(R.id.tv_hero_badge_type)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_hero_title)
        private val tvMatch: TextView = itemView.findViewById(R.id.tv_hero_match)
        private val tvRating: TextView = itemView.findViewById(R.id.tv_hero_rating)
        private val tvYear: TextView = itemView.findViewById(R.id.tv_hero_year)
        private val tvQuality: TextView = itemView.findViewById(R.id.tv_hero_quality)
        private val tvAudio: TextView = itemView.findViewById(R.id.tv_hero_audio)
        private val tvDesc: TextView = itemView.findViewById(R.id.tv_hero_desc)
        private val btnPlay: View = itemView.findViewById(R.id.btn_hero_play)
        private val btnMyList: View = itemView.findViewById(R.id.btn_hero_mylist)
        private val ivMyListIcon: ImageView = itemView.findViewById(R.id.iv_hero_mylist_icon)
        private val tvMyListText: TextView = itemView.findViewById(R.id.tv_hero_mylist_text)

        private val coroutineScope = CoroutineScope(Dispatchers.Main)

        init {
            applyTvFocusAnimation(btnPlay)
            applyTvFocusAnimation(btnMyList)
        }

        fun bind(
            item: StreamingItem,
            onMovieClick: (StreamingItem) -> Unit,
            onMyListToggled: ((StreamingItem, Boolean) -> Unit)?
        ) {
            tvTitle.text = item.title
            tvBadgeType.text = if (item.isSeries) "SERIES" else "FEATURED FILM"

            val ratingVal = item.rating?.trim() ?: "7.5"
            tvRating.text = "★ $ratingVal"

            val matchPercent = when {
                ratingVal.startsWith("8") || ratingVal.startsWith("9") -> "99% Match"
                ratingVal.startsWith("7") -> "98% Match"
                else -> "95% Match"
            }
            tvMatch.text = matchPercent

            val yearText = item.year ?: "2026"
            tvYear.text = yearText

            tvQuality.text = "4k"
            tvAudio.text = "5.1"

            if (!item.description.isNullOrBlank()) {
                tvDesc.visibility = View.VISIBLE
                tvDesc.text = item.description
            } else {
                tvDesc.visibility = View.VISIBLE
                tvDesc.text = "Explore this featured title on Zubflix."
            }

            val bgUrl = item.backdropUrl?.ifBlank { null } ?: item.imageUrl ?: ""
            if (bgUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(bgUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .transition(DrawableTransitionOptions.withCrossFade(300))
                    .centerCrop()
                    .into(imgBackdrop)
            } else {
                imgBackdrop.setImageResource(R.drawable.bg_smart_poster)
            }

            btnPlay.setOnClickListener {
                onMovieClick(item)
            }

            // Sync My List state
            checkAndBindMyListStatus(itemView.context, item, onMyListToggled)
        }

        private fun checkAndBindMyListStatus(
            context: Context,
            item: StreamingItem,
            onMyListToggled: ((StreamingItem, Boolean) -> Unit)?
        ) {
            var isInList = false

            coroutineScope.launch {
                try {
                    val dao = AppDatabase.getDatabase(context).myListDao()
                    isInList = withContext(Dispatchers.IO) {
                        dao.isItemInList(item.id)
                    }
                    updateMyListUi(isInList)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            btnMyList.setOnClickListener {
                coroutineScope.launch {
                    try {
                        val dao = AppDatabase.getDatabase(context).myListDao()
                        if (isInList) {
                            withContext(Dispatchers.IO) {
                                dao.deleteById(item.id)
                            }
                            isInList = false
                        } else {
                            val entity = MyListEntity(
                                itemId = item.id,
                                title = item.title,
                                imageUrl = item.imageUrl ?: item.backdropUrl,
                                isSeries = item.isSeries,
                                sourceName = item.sourceName ?: "Zubflix",
                                addedTimestamp = System.currentTimeMillis()
                            )
                            withContext(Dispatchers.IO) {
                                dao.insert(entity)
                            }
                            isInList = true
                        }
                        updateMyListUi(isInList)
                        onMyListToggled?.invoke(item, isInList)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun updateMyListUi(isInList: Boolean) {
            if (isInList) {
                ivMyListIcon.setImageResource(R.drawable.ic_check)
                tvMyListText.text = "In My List"
            } else {
                ivMyListIcon.setImageResource(R.drawable.ic_plus)
                tvMyListText.text = "My List"
            }
        }

        private fun applyTvFocusAnimation(view: View) {
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            }
        }
    }
}

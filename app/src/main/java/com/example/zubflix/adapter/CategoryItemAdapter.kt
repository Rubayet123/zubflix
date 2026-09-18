package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.R
import com.example.zubflix.model.StreamingItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import kotlinx.coroutines.launch

class CategoryItemAdapter(
    private val onItemClick: (StreamingItem) -> Unit,
    var onViewMoreClick: (() -> Unit)? = null,
    private val isGridMode: Boolean = false,
    private val onItemOptionsClick: ((StreamingItem) -> Unit)? = null
) : RecyclerView.Adapter<CategoryItemAdapter.ViewHolder>() {

    private val itemsList = mutableListOf<StreamingItem>()

    fun submitList(newItems: List<StreamingItem>?, commitCallback: Runnable? = null) {
        itemsList.clear()
        if (newItems != null) {
            itemsList.addAll(newItems)
        }
        notifyDataSetChanged()
        commitCallback?.run()
    }

    fun getItem(position: Int): StreamingItem? {
        return if (position in 0 until itemsList.size) itemsList[position] else null
    }

    companion object {
        private const val TYPE_VIEW_MORE = 0
        private const val TYPE_MOVIE = 1
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView? = itemView.findViewById(R.id.img_poster)
        val tvTitle: android.widget.TextView? = itemView.findViewById(R.id.tv_poster_title)
        val progressContainer: View? = itemView.findViewById(R.id.progress_container)
        val watchProgress: android.widget.ProgressBar? = itemView.findViewById(R.id.watch_progress)
        val playOverlay: ImageView? = itemView.findViewById(R.id.play_overlay)
        val tvRating: android.widget.TextView? = itemView.findViewById(R.id.tv_rating)
        val tvSourceName: android.widget.TextView? = itemView.findViewById(R.id.tv_source_name)
        val btnMoreOptions: ImageView? = itemView.findViewById(R.id.btn_more_options)
    }

    override fun getItemViewType(position: Int): Int {
        // Only show "View More" card at the END of horizontal rows (Home Screen)
        return if (!isGridMode && onViewMoreClick != null && position == itemCount - 1) TYPE_VIEW_MORE else TYPE_MOVIE
    }

    override fun getItemCount(): Int {
        val originalCount = itemsList.size
        return if (!isGridMode && onViewMoreClick != null && originalCount > 0) originalCount + 1 else originalCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = when (viewType) {
            TYPE_VIEW_MORE -> R.layout.item_view_more_card
            else -> if (isGridMode) R.layout.item_movie_grid else R.layout.item_movie_card
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == TYPE_VIEW_MORE) {
            setupViewMore(holder)
        } else {
            val actualPosition = position
            if (actualPosition < 0 || actualPosition >= itemsList.size) return
            
            val item = itemsList[actualPosition]
            setupMovieItem(holder, item)
        }
        
        setupTVFocus(holder.itemView)
        
        // EDGE-LOCK: Prevent horizontal focus leaking
        // If it's the first item, lock LEFT movement to stay on this item
        if (position == 0) {
            holder.itemView.nextFocusLeftId = holder.itemView.id
        } else {
            holder.itemView.nextFocusLeftId = View.NO_ID
        }
        
        // If it's the last item, lock RIGHT movement to stay on this item
        if (position == itemCount - 1) {
            holder.itemView.nextFocusRightId = holder.itemView.id
        } else {
            holder.itemView.nextFocusRightId = View.NO_ID
        }
    }

    private fun setupViewMore(holder: ViewHolder) {
        holder.itemView.setOnClickListener {
            onViewMoreClick?.invoke()
        }
    }

    private fun setupMovieItem(holder: ViewHolder, item: StreamingItem) {
        val cardView = holder.itemView as? androidx.cardview.widget.CardView
        val innerLayout = cardView?.getChildAt(0)
        
        // Clear any previous Glide target on recycled views
        holder.imageView?.let { imgView ->
            try {
                Glide.with(holder.itemView.context).clear(imgView)
            } catch (e: Exception) {
                // Ignore context lifecycle issues
            }
        }

        val density = holder.itemView.resources.displayMetrics.density
        val isCategoryCard = item.isCategory || 
                item.id.startsWith("genre:") || 
                item.id.startsWith("network:") || 
                item.id.startsWith("stremio_network:") || 
                item.id.startsWith("provider:") || 
                item.id.startsWith("region:") || 
                item.id.startsWith("language:")

        if (!isGridMode) {
            val params = holder.itemView.layoutParams
            if (isCategoryCard) {
                params.width = (135 * density).toInt()
                params.height = (75 * density).toInt()
            } else {
                params.width = (120 * density).toInt()
                params.height = (180 * density).toInt()
            }
            holder.itemView.layoutParams = params
        }

        if (item.id.startsWith("genre:")) {
            holder.imageView?.visibility = View.GONE
            holder.tvTitle?.visibility = View.VISIBLE
            holder.tvTitle?.text = item.title
            holder.tvTitle?.textSize = 15f
            holder.tvTitle?.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle?.setLineSpacing(0f, 1.1f)
            
            val gradients = listOf(
                intArrayOf(0xFFE02424.toInt(), 0xFF7A0909.toInt()), // Crimson gradient
                intArrayOf(0xFF7E3AF2.toInt(), 0xFF431396.toInt()), // Deep purple
                intArrayOf(0xFF1C64F2.toInt(), 0xFF052F7A.toInt()), // Cyber blue
                intArrayOf(0xFF047857.toInt(), 0xFF023223.toInt()), // Emerald
                intArrayOf(0xFFD61F69.toInt(), 0xFF730632.toInt()), // Hot pink/magenta
                intArrayOf(0xFFFF5A1F.toInt(), 0xFF881C00.toInt())  // Sunset orange
            )
            val index = Math.abs(item.title.hashCode()) % gradients.size
            val colors = gradients[index]
            val gd = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                colors
            )
            gd.cornerRadius = holder.itemView.resources.displayMetrics.density * 8f
            
            cardView?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            innerLayout?.background = gd
        } else if (item.id.startsWith("network:") || item.id.startsWith("stremio_network:") || item.id.startsWith("provider:") || item.id.contains("network")) {
            val titleLower = item.title.lowercase()
            val idLower = item.id.lowercase()
            val logoRes = when {
                titleLower.contains("netflix") || idLower.contains("netflix") || idLower.contains(":213") -> com.example.R.drawable.netflix
                titleLower.contains("amazon") || titleLower.contains("prime") || idLower.contains("prime") || idLower.contains(":1024") -> com.example.R.drawable.prime
                titleLower.contains("apple") || idLower.contains("apple") || idLower.contains(":2552") -> com.example.R.drawable.apple
                titleLower.contains("disney") || idLower.contains("disney") || idLower.contains(":2739") -> com.example.R.drawable.disney
                titleLower.contains("hbo") || titleLower.contains("max") || idLower.contains("hbo") || idLower.contains(":3186") || idLower.contains(":49") -> com.example.R.drawable.hbo
                titleLower.contains("hulu") || idLower.contains("hulu") || idLower.contains(":453") -> com.example.R.drawable.hulu
                titleLower.contains("paramount") || idLower.contains("paramount") || idLower.contains(":4330") -> com.example.R.drawable.paramount
                titleLower.contains("peacock") || idLower.contains("peacock") || idLower.contains(":3353") -> com.example.R.drawable.peacock
                titleLower.contains("bbc") || titleLower.contains("iplayer") || idLower.contains("bbc") || idLower.contains(":4") -> com.example.R.drawable.bbciplayer
                titleLower.contains("mubi") || idLower.contains("mubi") -> com.example.R.drawable.mubi
                titleLower.contains("crunchyroll") || idLower.contains("crunchyroll") || idLower.contains(":1112") -> com.example.R.drawable.crunchyroll
                titleLower.contains("curiosity") || idLower.contains("curiosity") -> com.example.R.drawable.curiositystream
                titleLower.contains("discovery") || idLower.contains("discovery") || idLower.contains(":64") -> com.example.R.drawable.discovery_plus
                titleLower.contains("magellan") || idLower.contains("magellan") -> com.example.R.drawable.magellan
                titleLower.contains("jio") || titleLower.contains("hotstar") || idLower.contains("hotstar") -> com.example.R.drawable.jiohotstar
                titleLower.contains("sony") || titleLower.contains("liv") || idLower.contains("sony") -> com.example.R.drawable.sonyliv
                titleLower.contains("zee") || idLower.contains("zee") -> com.example.R.drawable.zee5
                titleLower.contains("hoichoi") || idLower.contains("hoichoi") -> com.example.R.drawable.hoichhoi
                titleLower.contains("national geographic") || titleLower.contains("nat geo") || titleLower.contains("natgeo") || idLower.contains(":43") -> com.example.R.drawable.natgeo
                else -> null
            }

            if (logoRes != null) {
                holder.imageView?.visibility = View.VISIBLE
                holder.tvTitle?.visibility = View.GONE
                holder.imageView?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val p = (12 * holder.itemView.resources.displayMetrics.density).toInt()
                holder.imageView?.setPadding(p, p, p, p)
                holder.imageView?.let { imgView ->
                    try {
                        Glide.with(holder.itemView.context)
                            .load(logoRes)
                            .fitCenter()
                            .into(imgView)
                    } catch (e: Exception) {
                        imgView.setImageResource(logoRes)
                    }
                }
            } else {
                holder.imageView?.visibility = View.GONE
                holder.tvTitle?.visibility = View.VISIBLE
                holder.tvTitle?.text = item.title
                holder.tvTitle?.textSize = 14f
                holder.tvTitle?.setTextColor(android.graphics.Color.WHITE)
                holder.tvTitle?.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.tvTitle?.setLineSpacing(0f, 1.1f)
            }

            val networkColors = when {
                titleLower.contains("all") -> intArrayOf(0xFFE50914.toInt(), 0xFF1A1A1A.toInt())
                titleLower.contains("netflix") -> intArrayOf(0xFFE50914.toInt(), 0xFF700000.toInt())
                titleLower.contains("prime") || titleLower.contains("amazon") -> intArrayOf(0xFF00A8E1.toInt(), 0xFF003050.toInt())
                titleLower.contains("apple") -> intArrayOf(0xFF333333.toInt(), 0xFF111111.toInt())
                titleLower.contains("disney") -> intArrayOf(0xFF113CCF.toInt(), 0xFF081850.toInt())
                titleLower.contains("hbo") || titleLower.contains("max") -> intArrayOf(0xFF5822B4.toInt(), 0xFF200650.toInt())
                titleLower.contains("hulu") -> intArrayOf(0xFF1CE783.toInt(), 0xFF054525.toInt())
                titleLower.contains("paramount") -> intArrayOf(0xFF0064FF.toInt(), 0xFF002055.toInt())
                titleLower.contains("peacock") -> intArrayOf(0xFF00A859.toInt(), 0xFF003D20.toInt())
                titleLower.contains("bbc") || titleLower.contains("iplayer") -> intArrayOf(0xFF008080.toInt(), 0xFF003333.toInt())
                titleLower.contains("mubi") -> intArrayOf(0xFF001242.toInt(), 0xFF000515.toInt())
                titleLower.contains("crunchyroll") -> intArrayOf(0xFFFF6600.toInt(), 0xFF803300.toInt())
                titleLower.contains("curiosity") -> intArrayOf(0xFFFF0033.toInt(), 0xFF660014.toInt())
                titleLower.contains("discovery") -> intArrayOf(0xFF0243B5.toInt(), 0xFF011C50.toInt())
                titleLower.contains("magellan") -> intArrayOf(0xFF003366.toInt(), 0xFF001122.toInt())
                titleLower.contains("jio") || titleLower.contains("hotstar") -> intArrayOf(0xFF0F1035.toInt(), 0xFF020210.toInt())
                titleLower.contains("sony") || titleLower.contains("liv") -> intArrayOf(0xFFFF3300.toInt(), 0xFF881100.toInt())
                titleLower.contains("zee") -> intArrayOf(0xFF8B008B.toInt(), 0xFF3B003B.toInt())
                titleLower.contains("hoichoi") -> intArrayOf(0xFFE50914.toInt(), 0xFF500000.toInt())
                titleLower.contains("national geographic") || titleLower.contains("nat geo") || titleLower.contains("natgeo") -> intArrayOf(0xFFFFCC00.toInt(), 0xFF222222.toInt())
                else -> intArrayOf(0xFF333333.toInt(), 0xFF1A1A1A.toInt())
            }

            val gd = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                networkColors
            )
            gd.cornerRadius = holder.itemView.resources.displayMetrics.density * 8f
            
            cardView?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            innerLayout?.background = gd
        } else if (item.id.startsWith("region:")) {
            holder.imageView?.visibility = View.GONE
            holder.tvTitle?.visibility = View.VISIBLE
            
            val countryCode = item.id.removePrefix("region:")
            val flag = getFlagEmoji(countryCode)
            
            val sb = android.text.SpannableStringBuilder()
            sb.append(flag)
            sb.append("\n")
            sb.append(item.title)
            
            sb.setSpan(
                android.text.style.AbsoluteSizeSpan(20, true),
                0,
                flag.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                android.text.style.AbsoluteSizeSpan(12, true),
                flag.length,
                sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                flag.length,
                sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            holder.tvTitle?.text = sb
            holder.tvTitle?.textSize = 12f
            holder.tvTitle?.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle?.setLineSpacing(0f, 1.0f)
            
            // Matte carbon dark backdrop for modern look
            val colors = intArrayOf(0xFF262D35.toInt(), 0xFF12161A.toInt())
            val gd = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                colors
            )
            gd.cornerRadius = holder.itemView.resources.displayMetrics.density * 8f
            
            cardView?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            innerLayout?.background = gd
        } else if (item.id.startsWith("language:")) {
            holder.imageView?.visibility = View.GONE
            holder.tvTitle?.visibility = View.VISIBLE
            holder.tvTitle?.text = item.title
            holder.tvTitle?.textSize = 15f
            holder.tvTitle?.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle?.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.tvTitle?.setLineSpacing(0f, 1.1f)
            
            val gradients = listOf(
                intArrayOf(0xFF3B82F6.toInt(), 0xFF1E3A8A.toInt()),
                intArrayOf(0xFF8B5CF6.toInt(), 0xFF4C1D95.toInt()),
                intArrayOf(0xFFEC4899.toInt(), 0xFF831843.toInt()),
                intArrayOf(0xFF10B981.toInt(), 0xFF064E3B.toInt()),
                intArrayOf(0xFFF59E0B.toInt(), 0xFF78350F.toInt()),
                intArrayOf(0xFF6366F1.toInt(), 0xFF312E81.toInt())
            )
            val index = Math.abs(item.title.hashCode()) % gradients.size
            val colors = gradients[index]
            val gd = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                colors
            )
            gd.cornerRadius = holder.itemView.resources.displayMetrics.density * 8f
            
            cardView?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            innerLayout?.background = gd
        } else {
            // Reset to default movie item styling
            cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            innerLayout?.background = null
            
            holder.imageView?.setPadding(0, 0, 0, 0)
            holder.imageView?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

            holder.tvTitle?.textSize = 14f
            holder.tvTitle?.setTextColor(android.graphics.Color.WHITE)
            holder.tvTitle?.setLineSpacing(0f, 1.0f)
            
            if (item.imageUrl.isNullOrEmpty()) {
                holder.imageView?.visibility = View.GONE
                holder.tvTitle?.visibility = View.VISIBLE
                holder.tvTitle?.text = item.title
            } else {
                holder.imageView?.visibility = View.VISIBLE
                holder.tvTitle?.visibility = View.GONE
                
                holder.imageView?.let { imgView ->
                    val context = holder.itemView.context
                    val isValidContext = when (context) {
                        is android.app.Activity -> !context.isDestroyed && !context.isFinishing
                        else -> true
                    }
                    if (isValidContext) {
                        try {
                            Glide.with(context)
                                .load(item.imageUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                                .transition(DrawableTransitionOptions.withCrossFade(250))
                                .centerCrop()
                                .into(imgView)
                        } catch (e: Exception) {
                            // Ignore Glide exception
                        }
                    }
                }
            }
        }
        
        // Show progress bar and play overlay if watch percentage is available
        val percentage = item.watchPercentage
        if (percentage != null && percentage > 0f && percentage < 100f) {
            holder.progressContainer?.visibility = View.VISIBLE
            holder.watchProgress?.progress = percentage.toInt()
            holder.playOverlay?.visibility = View.VISIBLE
        } else {
            holder.progressContainer?.visibility = View.GONE
            holder.playOverlay?.visibility = View.GONE
        }

        // TMDB Rating Badge
        val homeRatingsEnabled = com.example.zubflix.SourceManager.isHomeScreenRatingsEnabled(holder.itemView.context)
        
        if (!item.rating.isNullOrEmpty()) {
            holder.tvRating?.visibility = View.VISIBLE
            val shortRating = item.rating.substringBefore("/")
            holder.tvRating?.text = shortRating
        } else if (homeRatingsEnabled && !item.isCategory) {
            // Lazy fetch rating
            val cachedRating = lazilyFetchedRatings[item.id]
            if (cachedRating != null) {
                if (cachedRating == "N/A") {
                    holder.tvRating?.visibility = View.GONE
                } else {
                    holder.tvRating?.visibility = View.VISIBLE
                    holder.tvRating?.text = cachedRating
                }
            } else {
                holder.tvRating?.visibility = View.GONE
                // Start fetch
                fetchRatingLazily(holder, item)
            }
        } else {
            holder.tvRating?.visibility = View.GONE
        }

        // Show Source Name Badge for Continue Watching items
        if (!item.sourceName.isNullOrEmpty() && item.watchPercentage != null) {
            holder.tvSourceName?.visibility = View.VISIBLE
            holder.tvSourceName?.text = item.sourceName
        } else {
            holder.tvSourceName?.visibility = View.GONE
        }

        // Options Button & Long Press for Continue Watching items
        if (item.watchPercentage != null && onItemOptionsClick != null) {
            holder.btnMoreOptions?.visibility = View.VISIBLE
            holder.btnMoreOptions?.setOnClickListener {
                onItemOptionsClick.invoke(item)
            }
            holder.itemView.setOnLongClickListener {
                onItemOptionsClick.invoke(item)
                true
            }
        } else {
            holder.btnMoreOptions?.visibility = View.GONE
            holder.btnMoreOptions?.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    private val lazilyFetchedRatings = mutableMapOf<String, String>()
    private val pendingFetches = mutableSetOf<String>()
    private val adapterScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private fun fetchRatingLazily(holder: ViewHolder, item: StreamingItem) {
        if (pendingFetches.contains(item.id)) return
        pendingFetches.add(item.id)

        adapterScope.launch {
            val details = com.example.zubflix.utils.TmdbHelper.searchAndFetchDetails(
                holder.itemView.context,
                item.title,
                item.year,
                item.isSeries
            )
            val rating = details?.rating ?: "N/A"
            lazilyFetchedRatings[item.id] = rating
            pendingFetches.remove(item.id)
            
            // Re-bind only if the holder is still showing this item
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                val actualPos = currentPos
                if (actualPos >= 0 && actualPos < itemsList.size && getItem(actualPos)?.id == item.id) {
                    if (rating != "N/A") {
                        holder.tvRating?.visibility = View.VISIBLE
                        holder.tvRating?.text = rating
                    } else {
                        holder.tvRating?.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.imageView?.let { imgView ->
            try {
                val context = holder.itemView.context
                if (context is android.app.Activity) {
                    if (!context.isDestroyed && !context.isFinishing) {
                        Glide.with(context).clear(imgView)
                    }
                } else {
                    Glide.with(context.applicationContext).clear(imgView)
                }
            } catch (e: Exception) {
                // Ignore Glide clear exception when view recycled during Activity destroy
            }
        }
    }

    private fun setupTVFocus(view: View) {
        val density = view.resources.displayMetrics.density
        val elevationPx = 8 * density
        
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
                v.z = 10f // Boost Z-ordering to avoid clipping by neighbors
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
                v.z = 0f
            }
        }
    }

    private fun getFlagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        try {
            val firstLetter = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondLetter = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
            return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
        } catch (e: Exception) {
            return ""
        }
    }

    class ItemDiffCallback : DiffUtil.ItemCallback<StreamingItem>() {
        override fun areItemsTheSame(oldItem: StreamingItem, newItem: StreamingItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StreamingItem, newItem: StreamingItem): Boolean {
            return oldItem == newItem
        }
    }
}

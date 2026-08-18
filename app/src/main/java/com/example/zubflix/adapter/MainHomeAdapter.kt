package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.R
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem

class MainHomeAdapter(
    private val onMovieClick: (StreamingItem) -> Unit,
    private val onViewMoreClick: (StreamingCategory) -> Unit,
    private val onItemOptionsClick: ((StreamingItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_CATEGORY = 1
    }

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 30) // TYPE_MOVIE
        setMaxRecycledViews(1, 10) // TYPE_VIEW_MORE
    }

    private var heroItems: List<StreamingItem>? = null
    private val categories = mutableListOf<StreamingCategory>()

    fun setHeroItem(item: StreamingItem?) {
        heroItems = if (item != null) listOf(item) else null
        notifyDataSetChanged()
    }

    fun setHeroItems(items: List<StreamingItem>?) {
        heroItems = if (!items.isNullOrEmpty()) items else null
        notifyDataSetChanged()
    }

    fun getHeroItems(): List<StreamingItem>? = heroItems

    fun setCategories(newCategories: List<StreamingCategory>) {
        categories.clear()
        categories.addAll(newCategories)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (!heroItems.isNullOrEmpty() && position == 0) TYPE_HERO else TYPE_CATEGORY
    }

    override fun getItemCount(): Int {
        return if (!heroItems.isNullOrEmpty()) categories.size + 1 else categories.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) {
            val view = inflater.inflate(R.layout.item_home_hero_pager, parent, false)
            HeroViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_home_category_row, parent, false)
            CategoryViewHolder(view, sharedViewPool)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeroViewHolder) {
            val items = heroItems ?: return
            holder.bind(items, onMovieClick)
        } else if (holder is CategoryViewHolder) {
            val categoryIndex = if (!heroItems.isNullOrEmpty()) position - 1 else position
            val category = categories[categoryIndex]
            holder.bind(category, onMovieClick, onViewMoreClick, onItemOptionsClick)
        }
    }

    class CategoryViewHolder(itemView: View, private val sharedPool: RecyclerView.RecycledViewPool) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_category_title)
        private val rvItems: RecyclerView = itemView.findViewById(R.id.rv_category_items)

        init {
            rvItems.setRecycledViewPool(sharedPool)
            rvItems.setItemViewCacheSize(15)
        }

        fun bind(
            category: StreamingCategory,
            onMovieClick: (StreamingItem) -> Unit,
            onViewMoreClick: (StreamingCategory) -> Unit,
            onItemOptionsClick: ((StreamingItem) -> Unit)?
        ) {
            tvTitle.text = category.title

            if (rvItems.layoutManager == null) {
                rvItems.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            }

            var itemAdapter = rvItems.adapter as? CategoryItemAdapter
            if (itemAdapter == null) {
                itemAdapter = CategoryItemAdapter(
                    onItemClick = onMovieClick,
                    onViewMoreClick = if (category.hideViewMore) null else { { onViewMoreClick(category) } },
                    onItemOptionsClick = onItemOptionsClick
                )
                rvItems.adapter = itemAdapter
            } else {
                itemAdapter.onViewMoreClick = if (category.hideViewMore) null else { { onViewMoreClick(category) } }
            }

            itemAdapter.submitList(category.items)
        }
    }

    class HeroViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewPager: ViewPager2 = itemView.findViewById(R.id.hero_view_pager)
        private val dotsContainer: LinearLayout = itemView.findViewById(R.id.hero_dots_container)
        private val pagerAdapter = HeroPagerAdapter(
            onMovieClick = { item -> currentOnMovieClick?.invoke(item) }
        )
        private var currentOnMovieClick: ((StreamingItem) -> Unit)? = null
        private val handler = Handler(Looper.getMainLooper())
        private var autoScrollRunnable: Runnable? = null
        private var itemsCount = 0

        init {
            viewPager.adapter = pagerAdapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    resetAutoScroll()
                }
            })
        }

        fun bind(items: List<StreamingItem>, onMovieClick: (StreamingItem) -> Unit) {
            currentOnMovieClick = onMovieClick
            itemsCount = items.size
            pagerAdapter.submitList(items)
            setupDots(items.size)
            updateDots(viewPager.currentItem)
            resetAutoScroll()
        }

        private fun setupDots(count: Int) {
            dotsContainer.removeAllViews()
            if (count <= 1) {
                dotsContainer.visibility = View.GONE
                return
            }
            dotsContainer.visibility = View.VISIBLE
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            for (i in 0 until count) {
                val dot = View(context).apply {
                    val params = LinearLayout.LayoutParams(
                        (6 * density).toInt(),
                        (6 * density).toInt()
                    ).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    layoutParams = params
                    setBackgroundResource(R.drawable.bg_hero_dot_inactive)
                }
                dotsContainer.addView(dot)
            }
        }

        private fun updateDots(selectedIndex: Int) {
            val count = dotsContainer.childCount
            val density = itemView.context.resources.displayMetrics.density
            for (i in 0 until count) {
                val dot = dotsContainer.getChildAt(i) ?: continue
                val isSelected = (i == selectedIndex)
                val params = dot.layoutParams as LinearLayout.LayoutParams
                if (isSelected) {
                    params.width = (18 * density).toInt()
                    params.height = (6 * density).toInt()
                    dot.setBackgroundResource(R.drawable.bg_hero_dot_active)
                } else {
                    params.width = (6 * density).toInt()
                    params.height = (6 * density).toInt()
                    dot.setBackgroundResource(R.drawable.bg_hero_dot_inactive)
                }
                dot.layoutParams = params
            }
        }

        private fun resetAutoScroll() {
            autoScrollRunnable?.let { handler.removeCallbacks(it) }
            if (itemsCount <= 1) return
            autoScrollRunnable = Runnable {
                if (itemsCount > 1) {
                    val nextItem = (viewPager.currentItem + 1) % itemsCount
                    viewPager.setCurrentItem(nextItem, true)
                }
            }
            handler.postDelayed(autoScrollRunnable!!, 6500L)
        }
    }
}

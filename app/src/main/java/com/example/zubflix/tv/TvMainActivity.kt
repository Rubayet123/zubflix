package com.example.zubflix.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.R
import com.example.databinding.ActivityTvMainBinding
import com.example.zubflix.CategoryViewActivity
import com.example.zubflix.DetailsActivity
import com.example.zubflix.MyListActivity
import com.example.zubflix.SearchActivity
import com.example.zubflix.SettingsActivity
import com.example.zubflix.SourceManager
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.database.MyListEntity
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvMainActivity : FragmentActivity() {

    private lateinit var binding: ActivityTvMainBinding
    private lateinit var rowsFragment: TvHomeRowsFragment

    private val providerCategories = mutableListOf<StreamingCategory>()
    private var continueWatchingCategory: StreamingCategory? = null
    private var currentlyFocusedItem: StreamingItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.zubflix.cloudstream.CloudStreamInitializer.init(applicationContext)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SourceManager.initialize(this)
        com.example.zubflix.provider.sdk.ProviderManager.initialize(this)

        setupRowsFragment()
        setupSidebarListeners()
        updateActiveProviderDisplay()
        loadHomeData()
        observeWatchHistory()
    }

    override fun onResume() {
        super.onResume()
        updateActiveProviderDisplay()
    }

    private fun setupRowsFragment() {
        rowsFragment = TvHomeRowsFragment().apply {
            onItemClickListener = { item ->
                handleItemClick(item)
            }
            onItemFocusListener = { item ->
                updateHeroBanner(item)
            }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.tv_rows_frame, rowsFragment)
            .commit()
    }

    private fun setupSidebarListeners() {
        // 1. Search
        binding.btnTvSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        // 2. Home
        binding.btnTvHome.setOnClickListener {
            rowsFragment.view?.requestFocus()
        }

        // 3. Provider Selector
        binding.btnTvProvider.setOnClickListener {
            showProviderPicker()
        }
        binding.tvActiveProviderChip.setOnClickListener {
            showProviderPicker()
        }

        // 4. My List
        binding.btnTvMylist.setOnClickListener {
            startActivity(Intent(this, MyListActivity::class.java))
        }

        // 5. Settings
        binding.btnTvSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showProviderPicker() {
        TvProviderPickerDialog(this) { _ ->
            updateActiveProviderDisplay()
            loadHomeData(forceRefresh = false)
        }.show()
    }

    private fun updateActiveProviderDisplay() {
        val selectedSource = SourceManager.getSelectedSource(this)
        binding.tvActiveProviderName.text = selectedSource.name
    }

    private fun handleItemClick(item: StreamingItem) {
        if (item.isCategory) {
            val intent = Intent(this, CategoryViewActivity::class.java).apply {
                putExtra("CATEGORY_ID", item.id)
                putExtra("CATEGORY_TITLE", item.title.removePrefix("See All ").ifBlank { item.id })
                val src = item.sourceName?.takeIf { it.isNotBlank() } ?: SourceManager.getSelectedSource(this@TvMainActivity).name
                putExtra("SOURCE_NAME", src)
            }
            startActivity(intent)
        } else {
            val srcItem = if (item.sourceName.isNullOrBlank()) {
                item.copy(sourceName = SourceManager.getSelectedSource(this).name)
            } else {
                item
            }
            DetailsActivity.start(this, srcItem)
        }
    }

    private fun updateHeroBanner(item: StreamingItem) {
        currentlyFocusedItem = item

        if (item.isCategory) {
            val cleanTitle = item.title.removePrefix("See All ").ifBlank { item.id }
            binding.tvHeroBrand.text = "COLLECTION"
            binding.tvHeroTitle.text = cleanTitle
            binding.tvHeroRatingBadge.visibility = View.GONE
            binding.tvHeroYear.visibility = View.GONE
            binding.tvHeroDot1.visibility = View.GONE
            binding.tvHeroBadgeAudio.visibility = View.GONE
            binding.tvHeroSource.text = "Category"
            binding.tvHeroOverview.text = item.description?.takeIf { it.isNotBlank() } ?: "Browse the complete collection of titles in $cleanTitle."
            binding.tvHeroHighlightTag.visibility = View.GONE
            return
        }

        // Brand tag
        binding.tvHeroBrand.text = if (item.isSeries) "SERIES" else "MOVIE"

        // Title
        binding.tvHeroTitle.text = item.title

        // Rating
        val rating = item.rating
        if (!rating.isNullOrBlank() && rating != "0" && rating != "0.0" && rating != "N/A") {
            val cleanRating = rating.replace("/10", "").trim()
            val formatted = try {
                String.format("%.1f", cleanRating.toDouble())
            } catch (e: Exception) {
                cleanRating
            }
            binding.tvHeroRating.text = formatted
            binding.tvHeroRatingBadge.visibility = View.VISIBLE
        } else {
            binding.tvHeroRatingBadge.visibility = View.GONE
        }

        // Year
        val year = item.year?.takeIf { it.isNotBlank() }
        if (!year.isNullOrBlank()) {
            binding.tvHeroYear.text = year
            binding.tvHeroYear.visibility = View.VISIBLE
            binding.tvHeroDot1.visibility = View.VISIBLE
        } else {
            binding.tvHeroYear.visibility = View.GONE
            binding.tvHeroDot1.visibility = View.GONE
        }

        // Type
        binding.tvHeroSource.text = if (item.isSeries) "Series" else "Movie"
        binding.tvHeroBadgeAudio.visibility = View.VISIBLE

        // Synopsis
        val overview = item.description?.takeIf { it.isNotBlank() }
            ?: "Click or press Select to stream instantly or discover more episodes and details."
        binding.tvHeroOverview.text = overview

        // Highlight tagline (Netflix style)
        binding.tvHeroHighlightTag.visibility = View.VISIBLE
        val ratingNum = rating?.replace("/10", "")?.trim()?.toDoubleOrNull() ?: 0.0
        if (ratingNum >= 7.5) {
            binding.tvHeroHighlightText.text = "⭐ Top Rated (${String.format("%.1f", ratingNum)}/10) • 4K HDR Audio"
        } else if (item.isSeries) {
            binding.tvHeroHighlightText.text = "📺 Full Seasons Available • Instant Stream"
        } else {
            binding.tvHeroHighlightText.text = "🔥 Trending on Zubflix • Press OK to Play"
        }

        // Backdrop Art
        val backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf { it.isNotBlank() }

        if (!backdropUrl.isNullOrBlank()) {
            binding.tvHeroBackdrop.load(backdropUrl) {
                crossfade(300)
            }
        }
    }

    private fun loadHomeData(forceRefresh: Boolean = false) {
        binding.tvLoadingIndicator.visibility = View.VISIBLE

        if (forceRefresh) {
            SourceManager.invalidateAllCaches()
        }

        lifecycleScope.launch {
            try {
                val activeSource = SourceManager.getSelectedSource(this@TvMainActivity)
                val allFetchedCategories = withContext(Dispatchers.IO) {
                    try {
                        activeSource.getHomeCategories().map { category ->
                            val shouldKeepAll = category.hideViewMore || 
                                    category.id == "continue_watching" || 
                                    category.id.contains("network") || 
                                    category.id.contains("genre") || 
                                    category.id.contains("region") || 
                                    category.items.any { it.isCategory }
                            if (shouldKeepAll) {
                                category
                            } else {
                                category.copy(items = category.items.take(15))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                }

                providerCategories.clear()
                providerCategories.addAll(allFetchedCategories.filterNot { it.id == "continue_watching" })

                updateCombinedTvCategories()

                // Set first item in Hero Banner if not yet set
                if (currentlyFocusedItem == null) {
                    val firstItem = providerCategories.firstOrNull()?.items?.firstOrNull()
                    if (firstItem != null) {
                        updateHeroBanner(firstItem)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                binding.tvLoadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun updateCombinedTvCategories() {
        val combined = mutableListOf<StreamingCategory>()
        continueWatchingCategory?.let { combined.add(it) }
        combined.addAll(providerCategories)
        rowsFragment.setCategories(combined)
    }

    private fun observeWatchHistory() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TvMainActivity)
            db.watchHistoryDao().getRecentlyWatched(20).collectLatest { historyList ->
                val activeHistory = historyList.filter {
                    it.watchPercentage < 95f
                }

                if (activeHistory.isNotEmpty()) {
                    val items = activeHistory.map { history ->
                        StreamingItem(
                            id = history.itemId,
                            title = history.title,
                            imageUrl = history.imageUrl,
                            sourceName = history.sourceName,
                            isSeries = history.isSeries,
                            watchPercentage = history.watchPercentage
                        )
                    }
                    continueWatchingCategory = StreamingCategory(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = items,
                        hideViewMore = true
                    )
                } else {
                    continueWatchingCategory = null
                }
                updateCombinedTvCategories()
            }
        }
    }
}

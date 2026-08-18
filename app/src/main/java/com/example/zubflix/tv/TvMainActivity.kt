package com.example.zubflix.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class TvMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvMainBinding
    private lateinit var rowsFragment: TvHomeRowsFragment

    private val providerCategories = mutableListOf<StreamingCategory>()
    private var continueWatchingCategory: StreamingCategory? = null
    private var currentlyFocusedItem: StreamingItem? = null
    private var isInMyList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.zubflix.cloudstream.CloudStreamInitializer.init(applicationContext)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SourceManager.initialize(this)
        com.example.zubflix.provider.sdk.ProviderManager.initialize(this)

        setupRowsFragment()
        setupSidebarListeners()
        setupHeroActionListeners()
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

    private fun setupHeroActionListeners() {
        binding.btnTvHeroPlay.setOnClickListener {
            val item = currentlyFocusedItem ?: return@setOnClickListener
            val intent = Intent(this, DetailsActivity::class.java).apply {
                putExtra("ITEM_ID", item.id)
                putExtra("SOURCE_NAME", item.sourceName)
                putExtra("AUTO_PLAY", true)
            }
            startActivity(intent)
        }

        binding.btnTvHeroDetails.setOnClickListener {
            val item = currentlyFocusedItem ?: return@setOnClickListener
            handleItemClick(item)
        }

        binding.btnTvHeroMylist.setOnClickListener {
            val item = currentlyFocusedItem ?: return@setOnClickListener
            toggleMyList(item)
        }
    }

    private fun handleItemClick(item: StreamingItem) {
        if (item.isCategory) {
            val intent = Intent(this, CategoryViewActivity::class.java).apply {
                putExtra("CATEGORY_ID", item.id)
                putExtra("CATEGORY_TITLE", item.title)
                putExtra("SOURCE_NAME", item.sourceName)
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, DetailsActivity::class.java).apply {
                putExtra("ITEM_ID", item.id)
                putExtra("SOURCE_NAME", item.sourceName)
            }
            startActivity(intent)
        }
    }

    private fun updateHeroBanner(item: StreamingItem) {
        currentlyFocusedItem = item

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
        } else {
            binding.tvHeroYear.visibility = View.GONE
        }

        // Type
        binding.tvHeroSource.text = if (item.isSeries) "Series" else "Movie"

        // Synopsis
        val overview = item.description?.takeIf { it.isNotBlank() }
            ?: "Press 'Watch Now' or 'Details' to stream instantly or discover more info."
        binding.tvHeroOverview.text = overview

        // Backdrop Art
        val backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf { it.isNotBlank() }

        if (!backdropUrl.isNullOrBlank()) {
            binding.tvHeroBackdrop.load(backdropUrl) {
                crossfade(300)
            }
        }

        checkMyListStatus(item)
    }

    private fun checkMyListStatus(item: StreamingItem) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TvMainActivity)
            val exists = withContext(Dispatchers.IO) {
                db.myListDao().isItemInList(item.id)
            }
            isInMyList = exists
            updateMyListButtonUi()
        }
    }

    private fun toggleMyList(item: StreamingItem) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TvMainActivity)
            withContext(Dispatchers.IO) {
                if (isInMyList) {
                    db.myListDao().deleteById(item.id)
                } else {
                    val entity = MyListEntity(
                        itemId = item.id,
                        title = item.title,
                        imageUrl = item.imageUrl ?: item.backdropUrl,
                        isSeries = item.isSeries,
                        sourceName = item.sourceName ?: SourceManager.getSelectedSource(this@TvMainActivity).name
                    )
                    db.myListDao().insert(entity)
                }
            }
            isInMyList = !isInMyList
            updateMyListButtonUi()
            val msg = if (isInMyList) "Added to My List" else "Removed from My List"
            Toast.makeText(this@TvMainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMyListButtonUi() {
        if (isInMyList) {
            binding.ivTvHeroMylist.setImageResource(R.drawable.ic_check)
            binding.ivTvHeroMylist.setColorFilter(0xFF00E676.toInt())
            binding.tvTvHeroMylistText.text = "In My List"
        } else {
            binding.ivTvHeroMylist.setImageResource(R.drawable.ic_plus)
            binding.ivTvHeroMylist.setColorFilter(0xFFD0D3D9.toInt())
            binding.tvTvHeroMylistText.text = "My List"
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

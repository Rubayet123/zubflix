package com.example.zubflix

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ActivityCategoryViewBinding
import com.example.zubflix.adapter.CategoryItemAdapter
import com.example.zubflix.model.FilterConfigRepository
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.util.GridSpacingItemDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoryViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryViewBinding
    private lateinit var adapter: CategoryItemAdapter
    
    private var categoryId: String = ""
    private var sourceName: String = ""
    
    // Pagination and Filter state
    private var currentPage = 1
    private var isLoading = false
    private var isLastPage = false
    private val loadedItems = mutableListOf<StreamingItem>()
    
    // Filter state
    private var isFilterCategory = false
    private var categoryPrefix = ""
    private var baseCategoryId = ""
    private var selectedType = "movie" // "movie" or "tv"
    private var selectedGenreId = "all" // TMDB genre ID
    private var selectedNetworkId = "all" // TMDB network ID e.g. "213" for Netflix
    private var selectedRegionCode = "all" // Country code e.g. "KR"
    private var selectedSort = "popularity.desc" // "popularity.desc", "primary_release_date.desc", "vote_average.desc"

    private fun getCurrentGenreMap(): List<Pair<String, String>> {
        val items = if (selectedType == "tv") {
            FilterConfigRepository.getTvGenres(this)
        } else {
            FilterConfigRepository.getMovieGenres(this)
        }
        return listOf("all" to "All Genres") + items.map { it.id to it.name }
    }

    private fun getCurrentNetworkMap(): List<Pair<String, String>> {
        val items = FilterConfigRepository.getNetworks(this)
        return listOf("all" to "All Networks") + items.map { it.id to it.name }
    }

    private fun getCurrentRegionMap(): List<Pair<String, String>> {
        val items = FilterConfigRepository.getRegions(this)
        return listOf("all" to "All Regions") + items.map { it.code to it.name }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarInset)
            windowInsets
        }

        categoryId = intent.getStringExtra("CATEGORY_ID") ?: intent.getStringExtra("CATEGORY_KEY") ?: intent.getStringExtra("categoryKey") ?: ""
        sourceName = intent.getStringExtra("SOURCE_NAME") ?: intent.getStringExtra("sourceName") ?: ""
        val categoryTitle = intent.getStringExtra("CATEGORY_TITLE") ?: intent.getStringExtra("categoryTitle") ?: "Category"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "$categoryTitle - $sourceName"

        if (categoryId.startsWith("network:") || categoryId.startsWith("stremio_network:") || categoryId.startsWith("genre:") || categoryId.startsWith("region:")) {
            isFilterCategory = true
            categoryPrefix = categoryId.split(":").first()
            baseCategoryId = categoryId.removePrefix("$categoryPrefix:").split(":").first()
            
            if (categoryPrefix == "network" || categoryPrefix == "stremio_network") {
                selectedNetworkId = if (baseCategoryId != "all") baseCategoryId else "all"
                selectedType = "all"
            } else {
                selectedGenreId = if (categoryPrefix == "genre" && baseCategoryId != "all") baseCategoryId else "all"
                selectedRegionCode = if (categoryPrefix == "region" && baseCategoryId != "all") baseCategoryId else "all"
                selectedType = "movie"
                selectedSort = "popularity.desc"
            }

            binding.filterScrollView.visibility = View.VISIBLE
            setupFilterBar()
        } else {
            binding.filterScrollView.visibility = View.GONE
        }

        setupRecyclerView()
        loadNextPage()
    }

    private fun setupFilterBar() {
        val isNetworkMode = categoryPrefix == "network" || categoryPrefix == "stremio_network"

        binding.btnFilterNetwork.visibility = View.VISIBLE
        binding.btnFilterType.visibility = View.VISIBLE
        binding.btnFilterGenre.visibility = View.VISIBLE
        binding.btnFilterRegion.visibility = View.VISIBLE
        binding.btnFilterSort.visibility = View.VISIBLE

        updateFilterButtonsUI()

        binding.btnFilterType.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            if (isNetworkMode) {
                popup.menu.add(0, 1, 0, "All Types")
                popup.menu.add(0, 2, 0, "Movies")
                popup.menu.add(0, 3, 0, "Series")

                popup.setOnMenuItemClickListener { menuItem ->
                    val newType = when (menuItem.itemId) {
                        2 -> "movie"
                        3 -> "tv"
                        else -> "all"
                    }
                    if (selectedType != newType) {
                        selectedType = newType
                        updateFilterButtonsUI()
                        reloadContent()
                    }
                    true
                }
            } else {
                popup.menu.add(0, 1, 0, "Movies")
                popup.menu.add(0, 2, 0, "Series")

                popup.setOnMenuItemClickListener { menuItem ->
                    val newType = if (menuItem.itemId == 2) "tv" else "movie"
                    if (selectedType != newType) {
                        selectedType = newType
                        val currentGenres = getCurrentGenreMap()
                        if (selectedGenreId != "all" && currentGenres.none { it.first == selectedGenreId }) {
                            selectedGenreId = "all"
                        }
                        updateFilterButtonsUI()
                        reloadContent()
                    }
                    true
                }
            }
            popup.show()
        }

        binding.btnFilterGenre.setOnClickListener { view ->
            val currentGenres = getCurrentGenreMap()
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            currentGenres.forEachIndexed { index, (_, name) ->
                popup.menu.add(0, index, index, name)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selected = currentGenres.getOrNull(menuItem.itemId)
                if (selected != null && selectedGenreId != selected.first) {
                    selectedGenreId = selected.first
                    updateFilterButtonsUI()
                    reloadContent()
                }
                true
            }
            popup.show()
        }

        binding.btnFilterNetwork.setOnClickListener { view ->
            val netMap = getCurrentNetworkMap()
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            netMap.forEachIndexed { index, (_, name) ->
                popup.menu.add(0, index, index, name)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selected = netMap.getOrNull(menuItem.itemId)
                if (selected != null && selectedNetworkId != selected.first) {
                    selectedNetworkId = selected.first
                    updateFilterButtonsUI()
                    reloadContent()
                }
                true
            }
            popup.show()
        }

        binding.btnFilterRegion.setOnClickListener { view ->
            val regMap = getCurrentRegionMap()
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            regMap.forEachIndexed { index, (_, name) ->
                popup.menu.add(0, index, index, name)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selected = regMap.getOrNull(menuItem.itemId)
                if (selected != null && selectedRegionCode != selected.first) {
                    selectedRegionCode = selected.first
                    updateFilterButtonsUI()
                    reloadContent()
                }
                true
            }
            popup.show()
        }

        binding.btnFilterSort.setOnClickListener { view ->
            val sortOptions = FilterConfigRepository.getSortOptions(this)
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            sortOptions.forEachIndexed { index, item ->
                popup.menu.add(0, index, index, item.title)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selected = sortOptions.getOrNull(menuItem.itemId)
                if (selected != null && selectedSort != selected.id) {
                    selectedSort = selected.id
                    updateFilterButtonsUI()
                    reloadContent()
                }
                true
            }
            popup.show()
        }
    }

    private fun updateFilterButtonsUI() {
        val isNetworkMode = categoryPrefix == "network" || categoryPrefix == "stremio_network"

        if (isNetworkMode) {
            binding.btnFilterType.text = when (selectedType) {
                "tv" -> "Series ▾"
                "movie" -> "Movies ▾"
                else -> "Type: All ▾"
            }
            val netMap = getCurrentNetworkMap()
            val networkName = netMap.find { it.first == selectedNetworkId }?.second ?: "All"
            binding.btnFilterNetwork.text = if (selectedNetworkId == "all") "Network: All ▾" else "Network: $networkName ▾"

            val currentGenreMap = getCurrentGenreMap()
            val genreName = currentGenreMap.find { it.first == selectedGenreId }?.second ?: "All"
            binding.btnFilterGenre.text = if (selectedGenreId == "all") "Genre: All ▾" else "Genre: $genreName ▾"

            val regMap = getCurrentRegionMap()
            val countryName = regMap.find { it.first == selectedRegionCode }?.second ?: "All"
            binding.btnFilterRegion.text = if (selectedRegionCode == "all") "Country: All ▾" else "Country: $countryName ▾"

            val sortText = when (selectedSort) {
                "primary_release_date.desc", "first_air_date.desc" -> "Sort: Latest ▾"
                "vote_average.desc" -> "Sort: Rating ▾"
                else -> "Sort: Popularity ▾"
            }
            binding.btnFilterSort.text = sortText
        } else {
            val typeText = if (selectedType == "tv") "Series ▾" else "Movies ▾"
            binding.btnFilterType.text = typeText

            val netMap = getCurrentNetworkMap()
            val networkName = netMap.find { it.first == selectedNetworkId }?.second ?: "All Networks"
            binding.btnFilterNetwork.text = if (selectedNetworkId == "all") "Network: All ▾" else "$networkName ▾"

            val currentGenreMap = getCurrentGenreMap()
            val genreName = currentGenreMap.find { it.first == selectedGenreId }?.second ?: "All Genres"
            binding.btnFilterGenre.text = if (selectedGenreId == "all") "Genre: All ▾" else "$genreName ▾"

            val regMap = getCurrentRegionMap()
            val regionName = regMap.find { it.first == selectedRegionCode }?.second ?: "All Regions"
            binding.btnFilterRegion.text = if (selectedRegionCode == "all") "Region: All ▾" else "$regionName ▾"

            val sortTitle = FilterConfigRepository.getSortOptions(this)
                .find { it.id == selectedSort }?.title ?: "Popular"
            binding.btnFilterSort.text = "$sortTitle ▾"
        }
    }

    private fun reloadContent() {
        currentPage = 1
        isLastPage = false
        loadedItems.clear()
        adapter.submitList(emptyList())
        binding.emptyStateText.visibility = View.GONE
        loadNextPage()
    }

    private fun setupRecyclerView() {
        val screenWidthDp = resources.configuration.screenWidthDp
        // Target card width is roughly 120dp. With margins and spacing, 132dp is a perfect divisor.
        val spanCount = (screenWidthDp / 132).coerceAtLeast(3)
        val layoutManager = GridLayoutManager(this, spanCount)
        binding.rvCategoryContent.layoutManager = layoutManager
        
        // Custom 8dp grid spacing decoration (Material 3 standard)
        val spacingPx = (8 * resources.displayMetrics.density).toInt()
        binding.rvCategoryContent.addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx, true))

        adapter = CategoryItemAdapter(
            onItemClick = { item ->
                if (item.isCategory) {
                    val intent = Intent(this, CategoryViewActivity::class.java).apply {
                        putExtra("CATEGORY_ID", item.id)
                        putExtra("CATEGORY_TITLE", item.title)
                        putExtra("SOURCE_NAME", if (!item.sourceName.isNullOrEmpty()) item.sourceName else sourceName)
                    }
                    startActivity(intent)
                } else {
                    val srcItem = if (item.sourceName.isNullOrBlank()) {
                        item.copy(sourceName = sourceName)
                    } else {
                        item
                    }
                    DetailsActivity.start(this, srcItem)
                }
            },
            onViewMoreClick = null, // Disable "View More" button since we are in grid/view-more mode
            isGridMode = true
        )
        binding.rvCategoryContent.adapter = adapter

        // Pagination Scroll Listener
        binding.rvCategoryContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) { // check for scroll down
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    if (!isLoading && !isLastPage) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 6) {
                            loadNextPage()
                        }
                    }
                }
            }
        })
    }

    private fun loadNextPage() {
        isLoading = true
        if (currentPage == 1 && loadedItems.isEmpty()) {
            binding.shimmerGridContainer.shimmerGridScroll.visibility = View.VISIBLE
            com.example.zubflix.util.ShimmerHelper.startShimmer(binding.shimmerGridContainer.shimmerGridLayout)
            binding.loadingProgress.visibility = View.GONE
        } else {
            binding.loadingProgress.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val source = (if (sourceName.isNotEmpty()) SourceManager.getSourceByName(sourceName) else null)
                    ?: SourceManager.getSelectedSource(this@CategoryViewActivity)

                val effectiveCategoryId = if (isFilterCategory) {
                    "filter:$selectedType:$selectedSort:$selectedGenreId:$selectedNetworkId:$selectedRegionCode"
                } else {
                    categoryId
                }

                val newItems = withContext(Dispatchers.IO) {
                    source.getCategoryContent(effectiveCategoryId, currentPage)
                }

                if (newItems.isNotEmpty()) {
                    loadedItems.addAll(newItems)
                    adapter.submitList(loadedItems.toList())
                    currentPage++
                } else {
                    isLastPage = true
                    if (loadedItems.isEmpty()) {
                        binding.emptyStateText.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@CategoryViewActivity, "Failed to load page $currentPage", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
                com.example.zubflix.util.ShimmerHelper.stopShimmer(binding.shimmerGridContainer.shimmerGridLayout)
                binding.shimmerGridContainer.shimmerGridScroll.visibility = View.GONE
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

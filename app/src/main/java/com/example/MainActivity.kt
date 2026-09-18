package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.databinding.ActivityMainBinding
import com.example.zubflix.SourceManager
import com.example.zubflix.adapter.MainHomeAdapter
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.sources.CachedSource
import com.example.zubflix.utils.TmdbHelper
import com.example.zubflix.CategoryViewActivity
import com.example.zubflix.DetailsActivity
import com.example.zubflix.PlayerActivity
import com.example.zubflix.MyListActivity
import com.example.zubflix.SearchActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: MainHomeAdapter
    private val providerCategories = mutableListOf<StreamingCategory>()
    private var continueWatchingCategory: StreamingCategory? = null
    private var lastLoadedCSCatalogEnabled: Boolean? = null
    private var lastLoadedSourceName: String? = null

    private fun onHomeItemClick(item: StreamingItem) {
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

    private fun updateCombinedHomeCategories() {
        val combined = mutableListOf<StreamingCategory>()
        continueWatchingCategory?.let { combined.add(it) }
        combined.addAll(providerCategories)

        homeAdapter.setCategories(combined)
        binding.topBar.visibility = View.VISIBLE
        binding.mainRecyclerView.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updateSourceSelectorText()
        val currentCSState = SourceManager.isCloudStreamHomeCatalogEnabled(this)
        val currentSelected = SourceManager.getSelectedSource(this).name

        if (lastLoadedCSCatalogEnabled != null &&
            (lastLoadedCSCatalogEnabled != currentCSState || lastLoadedSourceName != currentSelected)
        ) {
            loadHomeData(forceRefresh = true)
        } else {
            updateCombinedHomeCategories()
        }

        lastLoadedCSCatalogEnabled = currentCSState
        lastLoadedSourceName = currentSelected
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            com.example.zubflix.cloudstream.CloudStreamInitializer.init(applicationContext)
        } catch (t: Throwable) {}

        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTvMode = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                com.example.zubflix.util.AppearanceSettings.isHomeFlixTvTheme(this)

        if (isTvMode) {
            startActivity(Intent(this, com.example.zubflix.tv.TvMainActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = android.graphics.Color.parseColor("#0D0E12")
        window.navigationBarColor = android.graphics.Color.parseColor("#0D0E12")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val basePaddingTop = (12 * resources.displayMetrics.density).toInt()
            view.updatePadding(top = basePaddingTop + statusBarInset)
            windowInsets
        }

        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        if (!isTv) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigationBar) { view, windowInsets ->
                val navigationBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val baseHeight = (64 * resources.displayMetrics.density).toInt()
                val params = view.layoutParams
                params.height = baseHeight + navigationBarInset
                view.layoutParams = params
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navigationBarInset)
                windowInsets
            }
        }

        // Initialize Source Manager & Provider SDK Manager
        SourceManager.initialize(this)
        com.example.zubflix.provider.sdk.ProviderManager.initialize(this)

        setupRecyclerView()
        setupListeners()
        setupBackPressHandler()
        loadHomeData()
        observeWatchHistory()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.topBar.hasFocus()) {
                    // Safe, clean exit of root activity on Android TV without disabling the callback
                    finish()
                } else {
                    // Snappy jump to top of lists
                    binding.mainRecyclerView.scrollToPosition(0)
                    // Request focus on the main provider selector button with safety post delay
                    binding.sourceSelectorBtn.post {
                        binding.sourceSelectorBtn.requestFocus()
                    }
                }
            }
        })
    }

    private fun setupRecyclerView() {
        binding.mainRecyclerView.layoutManager = LinearLayoutManager(this)
        homeAdapter = MainHomeAdapter(
            onMovieClick = { item ->
                if (item.isCategory) {
                    // It's a category/genre card
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
            },
            onViewMoreClick = { category ->
                val intent = Intent(this, CategoryViewActivity::class.java).apply {
                    putExtra("CATEGORY_ID", category.id)
                    putExtra("CATEGORY_TITLE", category.title)
                    putExtra("SOURCE_NAME", category.items.firstOrNull()?.sourceName ?: SourceManager.getSelectedSource(this@MainActivity).name)
                }
                startActivity(intent)
            },
            onItemOptionsClick = { item ->
                showContinueWatchingOptionsMenu(item)
            }
        )
        binding.mainRecyclerView.adapter = homeAdapter
    }

    private fun showContinueWatchingOptionsMenu(item: StreamingItem) {
        val options = arrayOf("Resume", "Details", "Dismiss")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Resume
                        if (!item.streamUrl.isNullOrEmpty()) {
                            val intent = Intent(this, PlayerActivity::class.java).apply {
                                putExtra("VIDEO_URL", item.streamUrl)
                                putStringArrayListExtra("VIDEO_URLS", arrayListOf(item.streamUrl))
                                putExtra("VIDEO_TITLE", item.title)
                                putExtra("ITEM_ID", item.id)
                                putExtra("IMAGE_URL", item.imageUrl)
                                putExtra("IS_SERIES", item.isSeries)
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
                    1 -> { // Details
                        val intent = Intent(this, DetailsActivity::class.java).apply {
                            putExtra("ITEM_ID", item.id)
                            putExtra("SOURCE_NAME", item.sourceName)
                        }
                        startActivity(intent)
                    }
                    2 -> { // Dismiss
                        lifecycleScope.launch(Dispatchers.IO) {
                            AppDatabase.getDatabase(this@MainActivity).watchHistoryDao().deleteById(item.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun updateBottomBarSelection(selectedLayout: android.widget.LinearLayout) {
        val selectedColor = android.graphics.Color.parseColor("#E50914")
        val unselectedColor = android.graphics.Color.parseColor("#8E8E93")

        val items = listOf(
            Triple(binding.navHome, binding.navHomeIcon, binding.navHomeText),
            Triple(binding.navProviders, binding.navProvidersIcon, binding.navProvidersText),
            Triple(binding.navSearch, binding.navSearchIcon, binding.navSearchText),
            Triple(binding.navMyList, binding.navMyListIcon, binding.navMyListText),
            Triple(binding.navSettings, binding.navSettingsIcon, binding.navSettingsText)
        )

        for (item in items) {
            val isSelected = item.first == selectedLayout
            val color = if (isSelected) selectedColor else unselectedColor
            item.second.imageTintList = android.content.res.ColorStateList.valueOf(color)
            item.third.setTextColor(color)
        }
    }

    private fun setupListeners() {
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.btnMyList.setOnClickListener {
            startActivity(Intent(this, MyListActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            binding.btnRefresh.animate()
                .rotationBy(360f)
                .setDuration(600)
                .start()
            Toast.makeText(this, "Refreshing catalog...", Toast.LENGTH_SHORT).show()
            loadHomeData(forceRefresh = true)
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.sourceSelectorBtn.setOnClickListener {
            showTopProviderDropdown(it)
        }
        
        applyTvFocusAnimation(binding.btnSearch)
        applyTvFocusAnimation(binding.btnMyList)
        applyTvFocusAnimation(binding.btnRefresh)
        applyTvFocusAnimation(binding.btnSettings)
        applyTvFocusAnimation(binding.sourceSelectorBtn)

        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val isTv = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // Keep Search and Refresh explicitly visible in the top bar as requested
        binding.btnSearch.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE

        if (!isTv) {
            binding.bottomNavigationBar.visibility = View.VISIBLE
            
            binding.mainRecyclerView.clipToPadding = false
            val density = resources.displayMetrics.density
            val paddingBottomPx = (76 * density).toInt()
            binding.mainRecyclerView.setPadding(
                binding.mainRecyclerView.paddingLeft,
                binding.mainRecyclerView.paddingTop,
                binding.mainRecyclerView.paddingRight,
                paddingBottomPx
            )

            updateBottomBarSelection(binding.navHome)

            binding.navHome.setOnClickListener {
                binding.mainRecyclerView.smoothScrollToPosition(0)
                updateBottomBarSelection(binding.navHome)
            }
            binding.navProviders.setOnClickListener {
                updateBottomBarSelection(binding.navProviders)
                showTopProviderDropdown(binding.sourceSelectorBtn)
            }
            binding.navSearch.setOnClickListener {
                updateBottomBarSelection(binding.navSearch)
                startActivity(Intent(this, SearchActivity::class.java))
            }
            binding.navMyList.setOnClickListener {
                updateBottomBarSelection(binding.navMyList)
                startActivity(Intent(this, MyListActivity::class.java))
            }
            binding.navSettings.setOnClickListener {
                updateBottomBarSelection(binding.navSettings)
                showSettingsDialog()
            }
        } else {
            binding.bottomNavigationBar.visibility = View.GONE
        }
        
        updateSourceSelectorText()
    }

    private fun applyTvFocusAnimation(view: View) {
        view.isFocusable = true
        val density = view.resources.displayMetrics.density
        val elevationPx = 4 * density
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    private fun updateSourceSelectorText() {
        val selectedSource = SourceManager.getSelectedSource(this)
        binding.currentSourceText.text = selectedSource.name
    }

    private fun showTopProviderDropdown(anchorView: View) {
        val enabledSources = SourceManager.getEnabledSources(this)
        if (enabledSources.isEmpty()) return

        val activeSource = SourceManager.getSelectedSource(this)

        val popupView = layoutInflater.inflate(R.layout.popup_provider_dropdown, null)
        val recyclerView = popupView.findViewById<RecyclerView>(R.id.rv_providers)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val popupWindow = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 20f
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        val adapter = com.example.zubflix.adapter.ProviderDropdownAdapter(
            sources = enabledSources,
            selectedName = activeSource.name,
            onSourceSelected = { source ->
                popupWindow.dismiss()
                if (source.name != activeSource.name) {
                    SourceManager.setSelectedSource(this, source.name)
                    updateSourceSelectorText()
                    loadHomeData(forceRefresh = false)
                }
            }
        )
        recyclerView.adapter = adapter

        popupWindow.showAsDropDown(anchorView, 0, 10)
    }

    private fun loadHomeData(forceRefresh: Boolean = false) {
        if (providerCategories.isEmpty()) {
            binding.loadingIndicator.visibility = View.VISIBLE
        } else {
            binding.loadingIndicator.visibility = View.VISIBLE
        }

        if (forceRefresh) {
            SourceManager.invalidateAllCaches()
        }

        lastLoadedCSCatalogEnabled = SourceManager.isCloudStreamHomeCatalogEnabled(this)
        val currentActiveSource = SourceManager.getSelectedSource(this)
        lastLoadedSourceName = currentActiveSource.name

        lifecycleScope.launch {
            try {
                val activeSource = currentActiveSource
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
                                category.copy(items = category.items.take(12))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                }

                providerCategories.clear()
                providerCategories.addAll(allFetchedCategories.filterNot { it.id == "continue_watching" })

                if (providerCategories.isNotEmpty()) {
                    val candidateHeroes = providerCategories
                        .flatMap { it.items }
                        .filter { !it.isCategory && (!it.backdropUrl.isNullOrEmpty() || !it.imageUrl.isNullOrEmpty()) }
                        .distinctBy { it.id }
                        .take(8)

                    val featuredHeroes = candidateHeroes.ifEmpty {
                        listOf(
                            StreamingItem(
                                id = "hero_default",
                                title = "Send Help",
                                isSeries = false,
                                imageUrl = "",
                                backdropUrl = "",
                                description = "Two colleagues become stranded on a deserted island, the only survivors of a plane crash. On the island, they must overcome past grievances and work together to survive.",
                                year = "2026",
                                rating = "7.5"
                            )
                        )
                    }

                    homeAdapter.setHeroItems(featuredHeroes)
                    updateCombinedHomeCategories()

                    // If hero items are missing backdrop image or description, enrich async via TMDB
                    featuredHeroes.forEach { hero ->
                        if (hero.backdropUrl.isNullOrEmpty() || hero.description.isNullOrEmpty()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val tmdbDetails = TmdbHelper.searchAndFetchDetails(
                                        this@MainActivity,
                                        hero.title,
                                        hero.year,
                                        hero.isSeries
                                    )
                                    if (tmdbDetails != null) {
                                        val enrichedHero = hero.copy(
                                            backdropUrl = tmdbDetails.backdropPath ?: hero.backdropUrl,
                                            description = tmdbDetails.overview ?: hero.description,
                                            rating = tmdbDetails.rating ?: hero.rating,
                                            year = tmdbDetails.year ?: hero.year
                                        )
                                        withContext(Dispatchers.Main) {
                                            val currentList = homeAdapter.getHeroItems()?.toMutableList() ?: mutableListOf()
                                            val itemIdx = currentList.indexOfFirst { it.id == hero.id }
                                            if (itemIdx != -1) {
                                                currentList[itemIdx] = enrichedHero
                                                homeAdapter.setHeroItems(currentList)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } else {
                    updateCombinedHomeCategories()
                    Toast.makeText(this@MainActivity, "No media content loaded from ${activeSource.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Failed to load media sources", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun observeWatchHistory() {
        lifecycleScope.launch {
            try {
                AppDatabase.getDatabase(this@MainActivity).watchHistoryDao().getRecentlyWatched(10)
                    .collectLatest { history ->
                        withContext(Dispatchers.Main) {
                            if (history.isNotEmpty()) {
                                val historyItems = history.map { entity ->
                                    val cleanId = entity.itemId.replace(Regex(":\\d+:\\d+$"), "")
                                    StreamingItem(
                                        id = cleanId,
                                        title = entity.title,
                                        imageUrl = entity.imageUrl,
                                        isSeries = entity.isSeries,
                                        sourceName = entity.sourceName,
                                        watchPercentage = entity.watchPercentage,
                                        streamUrl = entity.streamUrl
                                    )
                                }
                                continueWatchingCategory = StreamingCategory(
                                    id = "continue_watching",
                                    title = "Continue Watching",
                                    items = historyItems,
                                    hideViewMore = true
                                )
                            } else {
                                continueWatchingCategory = null
                            }
                            updateCombinedHomeCategories()
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showSettingsDialog() {
        startActivity(Intent(this, com.example.zubflix.SettingsActivity::class.java))
    }

    private fun showCreditsBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_credits, null)
        dialog.setContentView(dialogView)

        val btnGithub = dialogView.findViewById<View>(R.id.btn_github_profile)
        btnGithub?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rubayet123"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClose = dialogView.findViewById<Button>(R.id.btn_close_credits)
        btnClose?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDebugDialog() {
        val options = arrayOf(
            if (com.example.zubflix.util.DebugLogger.debugEnabled) "Disable Debug Logging" else "Enable Debug Logging",
            "View Debug Logs"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Debug Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        com.example.zubflix.util.DebugLogger.debugEnabled = !com.example.zubflix.util.DebugLogger.debugEnabled
                        android.widget.Toast.makeText(this, "Debug logging: ${if (com.example.zubflix.util.DebugLogger.debugEnabled) "Enabled" else "Disabled"}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        startActivity(android.content.Intent(this, com.example.zubflix.DebugLogsActivity::class.java))
                    }
                }
            }
            .show()
    }

    private fun showProvidersDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_providers, null)
        val rvProviders = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_manage_providers)
        rvProviders.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val sourcesList = SourceManager.getOrderedSources(this).toMutableList()
        val enabledMap = sourcesList.associate { it.name to SourceManager.isSourceEnabled(this, it.name) }.toMutableMap()

        class ProviderViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvName: android.widget.TextView = view.findViewById(R.id.tv_provider_name)
            val switchProvider: androidx.appcompat.widget.SwitchCompat = view.findViewById(R.id.switch_provider)
            val btnUp: android.widget.ImageButton = view.findViewById(R.id.btn_move_up)
            val btnDown: android.widget.ImageButton = view.findViewById(R.id.btn_move_down)
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ProviderViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ProviderViewHolder {
                val v = layoutInflater.inflate(R.layout.item_manage_provider, parent, false)
                return ProviderViewHolder(v)
            }

            override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
                val source = sourcesList[position]
                holder.tvName.text = source.name

                holder.switchProvider.setOnCheckedChangeListener(null)
                holder.switchProvider.isChecked = enabledMap[source.name] ?: true
                holder.switchProvider.setOnCheckedChangeListener { _, isChecked ->
                    enabledMap[source.name] = isChecked
                }

                holder.btnUp.visibility = if (position > 0) android.view.View.VISIBLE else android.view.View.INVISIBLE
                holder.btnDown.visibility = if (position < sourcesList.size - 1) android.view.View.VISIBLE else android.view.View.INVISIBLE

                holder.btnUp.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos > 0 && pos < sourcesList.size) {
                        val temp = sourcesList[pos]
                        sourcesList[pos] = sourcesList[pos - 1]
                        sourcesList[pos - 1] = temp
                        notifyItemRangeChanged(pos - 1, 2)
                    }
                }

                holder.btnDown.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos >= 0 && pos < sourcesList.size - 1) {
                        val temp = sourcesList[pos]
                        sourcesList[pos] = sourcesList[pos + 1]
                        sourcesList[pos + 1] = temp
                        notifyItemRangeChanged(pos, 2)
                    }
                }
            }

            override fun getItemCount(): Int = sourcesList.size
        }

        rvProviders.adapter = adapter

        MaterialAlertDialogBuilder(this)
            .setTitle("Manage Content Providers")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                var enabledCount = 0
                for (source in sourcesList) {
                    val isEnabled = enabledMap[source.name] ?: true
                    SourceManager.setSourceEnabled(this, source.name, isEnabled)
                    if (isEnabled) enabledCount++
                }

                if (enabledCount == 0 && sourcesList.isNotEmpty()) {
                    SourceManager.setSourceEnabled(this, sourcesList[0].name, true)
                    Toast.makeText(this, "At least one provider must be enabled", Toast.LENGTH_SHORT).show()
                }

                SourceManager.saveSourceOrder(this, sourcesList)

                updateSourceSelectorText()
                loadHomeData(forceRefresh = false)
                Toast.makeText(this, "Provider preferences saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTmdbSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tmdb_settings, null)
        val etKey = dialogView.findViewById<EditText>(R.id.input_tmdb_api_key)
        val cbBackdrops = dialogView.findViewById<CheckBox>(R.id.check_tmdb_backdrops)
        val cbCast = dialogView.findViewById<CheckBox>(R.id.check_tmdb_cast)
        val cbRatings = dialogView.findViewById<CheckBox>(R.id.check_tmdb_ratings)
        val cbGenres = dialogView.findViewById<CheckBox>(R.id.check_tmdb_genres)
        val cbPlot = dialogView.findViewById<CheckBox>(R.id.check_tmdb_plot)
        val btnTmdbFilterMenu = dialogView.findViewById<Button>(R.id.btn_tmdb_filter_menu)
        val tvVoteCountSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_vote_count_subtitle)

        fun updateVoteCountSubtitleUI() {
            val large = com.example.zubflix.util.FilterSettings.getLargeMarketVoteCount(this)
            val medium = com.example.zubflix.util.FilterSettings.getMediumMarketVoteCount(this)
            val small = com.example.zubflix.util.FilterSettings.getSmallMarketVoteCount(this)
            tvVoteCountSubtitle?.text = "Large: $large votes | Medium: $medium votes | Small: $small votes"
        }

        updateVoteCountSubtitleUI()

        btnTmdbFilterMenu?.setOnClickListener {
            showTmdbFilterSettingsDialog { updateVoteCountSubtitleUI() }
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        etKey.setText(prefs.getString("tmdb_api_key", ""))
        cbBackdrops.isChecked = prefs.getBoolean("use_tmdb_backdrops", true)
        cbCast.isChecked = prefs.getBoolean("use_tmdb_cast", true)
        cbRatings.isChecked = prefs.getBoolean("use_tmdb_ratings", true)
        cbGenres.isChecked = prefs.getBoolean("use_tmdb_genres", true)
        cbPlot.isChecked = prefs.getBoolean("use_tmdb_plot", true)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setTitle("TMDB Enhancement Settings")
            .setPositiveButton("Save") { _, _ ->
                val key = etKey.text.toString().trim()
                prefs.edit().apply {
                    putString("tmdb_api_key", key)
                    putBoolean("use_tmdb_backdrops", cbBackdrops.isChecked)
                    putBoolean("use_tmdb_cast", cbCast.isChecked)
                    putBoolean("use_tmdb_ratings", cbRatings.isChecked)
                    putBoolean("use_tmdb_genres", cbGenres.isChecked)
                    putBoolean("use_tmdb_plot", cbPlot.isChecked)
                }.apply()
                
                Toast.makeText(this, "Settings saved. Refreshing home screen...", Toast.LENGTH_SHORT).show()
                loadHomeData(forceRefresh = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTmdbFilterSettingsDialog(onUpdated: () -> Unit) {
        val large = com.example.zubflix.util.FilterSettings.getLargeMarketVoteCount(this)
        val medium = com.example.zubflix.util.FilterSettings.getMediumMarketVoteCount(this)
        val small = com.example.zubflix.util.FilterSettings.getSmallMarketVoteCount(this)

        val options = arrayOf(
            "Large Markets Vote Threshold (US, GB, CA, etc.): $large",
            "Medium Markets Vote Threshold (IN, ES, IT, BR, etc.): $medium",
            "Small Markets Vote Threshold (BD, EG, PK, etc.): $small",
            "Reset Filter Settings to Defaults"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("TMDB Market Vote Thresholds")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditVoteCountDialog("large", onUpdated)
                    1 -> showEditVoteCountDialog("medium", onUpdated)
                    2 -> showEditVoteCountDialog("small", onUpdated)
                    3 -> {
                        com.example.zubflix.util.FilterSettings.resetToDefaults(this)
                        onUpdated()
                        Toast.makeText(this, "Reset filter thresholds to default", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEditVoteCountDialog(marketTier: String, onUpdated: () -> Unit) {
        val currentCount = when (marketTier) {
            "large" -> com.example.zubflix.util.FilterSettings.getLargeMarketVoteCount(this)
            "medium" -> com.example.zubflix.util.FilterSettings.getMediumMarketVoteCount(this)
            else -> com.example.zubflix.util.FilterSettings.getSmallMarketVoteCount(this)
        }
        val label = marketTier.replaceFirstChar { it.uppercase() }
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentCount.toString())
        input.setPadding(48, 24, 48, 24)

        MaterialAlertDialogBuilder(this)
            .setTitle("$label Markets Minimum Vote Count")
            .setMessage("Enter minimum votes required for $label market regions:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newValue = input.text.toString().trim().toIntOrNull() ?: currentCount
                when (marketTier) {
                    "large" -> com.example.zubflix.util.FilterSettings.setLargeMarketVoteCount(this, newValue)
                    "medium" -> com.example.zubflix.util.FilterSettings.setMediumMarketVoteCount(this, newValue)
                    else -> com.example.zubflix.util.FilterSettings.setSmallMarketVoteCount(this, newValue)
                }
                onUpdated()
                Toast.makeText(this, "$label market vote threshold set to $newValue", Toast.LENGTH_SHORT).show()
                showTmdbFilterSettingsDialog(onUpdated)
            }
            .setNegativeButton("Cancel") { _, _ -> showTmdbFilterSettingsDialog(onUpdated) }
            .show()
    }

    private fun showCacheSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cache_settings, null)
        val etMaxCache = dialogView.findViewById<EditText>(R.id.input_max_cache)
        val spinnerTtl = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_cache_ttl)
        val switchPersistent = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_persistent_tmdb)
        val switchHomeRatings = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_home_ratings)
        val btnClearSource = dialogView.findViewById<Button>(R.id.btn_clear_source_cache)
        val btnClearTmdb = dialogView.findViewById<Button>(R.id.btn_clear_tmdb_cache)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        
        // Setup Spinner
        val ttlOptions = arrayOf("1 Hour", "2 Hours", "4 Hours", "8 Hours", "24 Hours")
        val ttlValues = arrayOf(1, 2, 4, 8, 24)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, ttlOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTtl.adapter = adapter

        // Set current values
        val currentTtl = prefs.getInt("cache_ttl_hours", 2)
        val selectedIndex = ttlValues.indexOf(currentTtl).coerceAtLeast(0)
        spinnerTtl.setSelection(selectedIndex)

        etMaxCache.setText(prefs.getInt("max_cache_size_mb", 200).toString())
        switchPersistent.isChecked = prefs.getBoolean("persistent_tmdb_cache", true)
        switchHomeRatings.isChecked = prefs.getBoolean("home_screen_ratings", false)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setTitle("Cache Configuration")
            .setPositiveButton("Save") { _, _ ->
                val ttlSelected = ttlValues[spinnerTtl.selectedItemPosition]
                val maxCacheVal = etMaxCache.text.toString().toIntOrNull() ?: 200
                prefs.edit().apply {
                    putInt("cache_ttl_hours", ttlSelected)
                    putInt("max_cache_size_mb", maxCacheVal)
                    putBoolean("persistent_tmdb_cache", switchPersistent.isChecked)
                    putBoolean("home_screen_ratings", switchHomeRatings.isChecked)
                }.apply()
                Toast.makeText(this, "Cache configuration saved", Toast.LENGTH_SHORT).show()
                loadHomeData(forceRefresh = true)
            }
            .setNegativeButton("Cancel", null)
            .create()

        btnClearSource.setOnClickListener {
            SourceManager.invalidateAllCaches()
            Toast.makeText(this, "Provider Cache Cleared", Toast.LENGTH_SHORT).show()
        }

        btnClearTmdb.setOnClickListener {
            com.example.zubflix.utils.TmdbDatabaseHelper(this).clearAll()
            Toast.makeText(this, "TMDB Metadata Cache Cleared", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showPlayerSettingsDialog() {
        startActivity(Intent(this, com.example.zubflix.SettingsActivity::class.java))
    }

    private fun showSubtitleStyleDialog() {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val autoEnable = prefs.getBoolean("pref_auto_enable_subtitles", true)
        val defaultLang = prefs.getString("pref_default_sub_lang", "en")
        
        val langLabel = when (defaultLang) {
            "en" -> "English"
            "bn" -> "Bengali"
            "hi" -> "Hindi"
            else -> "English"
        }
        
        val options = arrayOf(
            "Auto Enable Subtitles: ${if (autoEnable) "ON" else "OFF"}",
            "Default Language: $langLabel",
            "Text Color",
            "Background Style",
            "Text Size"
        )
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitle Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putBoolean("pref_auto_enable_subtitles", !autoEnable).apply()
                        Toast.makeText(this, "Auto Enable Subtitles: ${if (!autoEnable) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                        showSubtitleStyleDialog()
                    }
                    1 -> {
                        showDefaultLanguageDialog()
                    }
                    2 -> showTextColorDialog()
                    3 -> showBgStyleDialog()
                    4 -> showTextSizeDialog()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showDefaultLanguageDialog() {
        val languages = arrayOf("English", "Bengali", "Hindi")
        val codes = arrayOf("en", "bn", "hi")
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("pref_default_sub_lang", "en")
        val currentIndex = codes.indexOf(currentLang).coerceAtLeast(0)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Default Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                prefs.edit().putString("pref_default_sub_lang", codes[which]).apply()
                Toast.makeText(this, "Default language set to ${languages[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showSubtitleStyleDialog()
            }
            .setNegativeButton("Back") { _, _ ->
                showSubtitleStyleDialog()
            }
            .show()
    }

    private fun showTextColorDialog() {
        val colors = arrayOf("White", "Yellow", "Cyan", "Green", "Red")
        val colorValues = intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN, android.graphics.Color.GREEN, android.graphics.Color.RED)
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Subtitle Color")
            .setItems(colors) { _, which ->
                val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("sub_color", colorValues[which]).apply()
                Toast.makeText(this, "Subtitle color updated to ${colors[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showBgStyleDialog() {
        val bgStyles = arrayOf("None", "Translucent Black", "Solid Black")
        val bgValues = intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.parseColor("#80000000"), android.graphics.Color.BLACK)
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Background Style")
            .setItems(bgStyles) { _, which ->
                val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("sub_bg", bgValues[which]).apply()
                Toast.makeText(this, "Subtitle background updated to ${bgStyles[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showTextSizeDialog() {
        val sizes = arrayOf("Small", "Normal", "Large", "Extra Large")
        val sizeValues = floatArrayOf(0.043f, 0.053f, 0.063f, 0.075f)
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Text Size")
            .setItems(sizes) { _, which ->
                val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("sub_size", sizeValues[which].toString()).apply()
                Toast.makeText(this, "Subtitle size updated to ${sizes[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}

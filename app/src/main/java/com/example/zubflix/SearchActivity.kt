package com.example.zubflix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.databinding.ActivitySearchBinding
import com.example.zubflix.adapter.MainHomeAdapter
import com.example.zubflix.adapter.SearchHistoryAdapter
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.database.SearchHistoryRepository
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.provider.sdk.LegacyStreamingSourceAdapter
import com.example.zubflix.provider.sdk.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var searchAdapter: MainHomeAdapter
    private lateinit var historyAdapter: SearchHistoryAdapter
    private lateinit var searchHistoryRepository: SearchHistoryRepository
    private var isGlobalSearch: Boolean = false
    private var searchJob: Job? = null
    private val searchCategories = mutableListOf<StreamingCategory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        searchHistoryRepository = SearchHistoryRepository(AppDatabase.getDatabase(this).searchHistoryDao())

        ViewCompat.setOnApplyWindowInsetsListener(binding.searchBarContainer) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val basePaddingTop = (12 * resources.displayMetrics.density).toInt()
            view.updatePadding(top = basePaddingTop + statusBarInset)
            windowInsets
        }

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        isGlobalSearch = prefs.getBoolean("search_global_enabled", com.example.zubflix.util.SearchSettings.isGlobalSearchDefault(this))

        binding.btnBack.setOnClickListener { finish() }
        applyTvFocusAnimation(binding.btnBack)
        applyTvFocusAnimation(binding.btnSearchAction)
        applyTvFocusAnimation(binding.btnGlobalSearch)
        applyTvFocusAnimation(binding.btnClearSearch)
        applyTvFocusAnimation(binding.btnClearHistory)

        val dpadDownListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (binding.llSearchHistoryContainer.visibility == View.VISIBLE && binding.rvSearchHistory.childCount > 0) {
                    binding.rvSearchHistory.getChildAt(0)?.requestFocus()
                    return@OnKeyListener true
                } else if (binding.rvSearchResults.childCount > 0) {
                    binding.rvSearchResults.getChildAt(0)?.requestFocus()
                    return@OnKeyListener true
                }
            }
            false
        }
        binding.btnSearchAction.setOnKeyListener(dpadDownListener)
        binding.btnGlobalSearch.setOnKeyListener(dpadDownListener)
        binding.btnBack.setOnKeyListener(dpadDownListener)
        binding.btnClearHistory.setOnKeyListener(dpadDownListener)

        setupRecyclerView()
        setupHistoryRecyclerView()
        setupSearchView()
        setupGlobalSearchButton()
        updateInitialStatusText()
        observeSearchHistory()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val forceGlobal = intent.getBooleanExtra("FORCE_GLOBAL_SEARCH", false)
        if (forceGlobal) {
            isGlobalSearch = true
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("search_global_enabled", true)
                .apply()
            updateGlobalSearchButtonUI()
        }

        val query = intent.getStringExtra("SEARCH_QUERY")
            ?: intent.getStringExtra("MOVIE_TITLE")
            ?: intent.getStringExtra("QUERY")

        if (!query.isNullOrBlank()) {
            binding.etSearch.setText(query)
            binding.etSearch.setSelection(query.length)
            performSearch(query)
        }
    }

    private fun setupRecyclerView() {
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        
        searchAdapter = MainHomeAdapter(
            onMovieClick = { item ->
                if (item.isCategory) {
                    val intent = Intent(this, CategoryViewActivity::class.java).apply {
                        putExtra("CATEGORY_ID", item.id)
                        putExtra("CATEGORY_TITLE", item.title)
                        putExtra("SOURCE_NAME", item.sourceName ?: SourceManager.getSelectedSource(this@SearchActivity).name)
                    }
                    startActivity(intent)
                } else {
                    val srcItem = if (item.sourceName.isNullOrBlank()) {
                        item.copy(sourceName = SourceManager.getSelectedSource(this@SearchActivity).name)
                    } else {
                        item
                    }
                    DetailsActivity.start(this, srcItem)
                }
            },
            onViewMoreClick = { category ->
                val intent = Intent(this, CategoryViewActivity::class.java).apply {
                    putExtra("CATEGORY_ID", category.id)
                    putExtra("CATEGORY_TITLE", category.title)
                    putExtra("SOURCE_NAME", category.items.firstOrNull()?.sourceName ?: SourceManager.getSelectedSource(this@SearchActivity).name)
                }
                startActivity(intent)
            }
        )
        binding.rvSearchResults.adapter = searchAdapter
    }

    private fun setupHistoryRecyclerView() {
        binding.rvSearchHistory.layoutManager = LinearLayoutManager(this)
        historyAdapter = SearchHistoryAdapter(
            onItemClick = { query ->
                binding.etSearch.setText(query)
                binding.etSearch.setSelection(query.length)
                performSearch(query)
            },
            onDeleteClick = { query ->
                lifecycleScope.launch(Dispatchers.IO) {
                    searchHistoryRepository.deleteQuery(query)
                }
            }
        )
        binding.rvSearchHistory.adapter = historyAdapter

        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                searchHistoryRepository.clearHistory()
            }
        }
    }

    private fun observeSearchHistory() {
        lifecycleScope.launch {
            searchHistoryRepository.getRecentSearches().collectLatest { history ->
                historyAdapter.submitList(history)
                if (history.isNotEmpty() && searchCategories.isEmpty() && binding.etSearch.text.isNullOrEmpty()) {
                    binding.llSearchHistoryContainer.visibility = View.VISIBLE
                } else if (history.isEmpty()) {
                    binding.llSearchHistoryContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun setupSearchView() {
        binding.etSearch.requestFocus()

        // Clear button listener & text watcher
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
            binding.btnClearSearch.visibility = View.GONE
            binding.etSearch.requestFocus()
            if (historyAdapter.itemCount > 0) {
                binding.llSearchHistoryContainer.visibility = View.VISIBLE
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    binding.btnClearSearch.visibility = View.VISIBLE
                    binding.llSearchHistoryContainer.visibility = View.GONE
                } else {
                    binding.btnClearSearch.visibility = View.GONE
                    if (historyAdapter.itemCount > 0 && searchCategories.isEmpty()) {
                        binding.llSearchHistoryContainer.visibility = View.VISIBLE
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Focus change styling for TV Remote
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            binding.llUnifiedSearchPill.animate()
                .scaleX(if (hasFocus) 1.02f else 1.0f)
                .scaleY(if (hasFocus) 1.02f else 1.0f)
                .translationZ(if (hasFocus) 6f else 0f)
                .setDuration(150)
                .start()
        }

        // D-Pad down navigation for TV remotes
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (binding.llSearchHistoryContainer.visibility == View.VISIBLE && binding.rvSearchHistory.childCount > 0) {
                    binding.rvSearchHistory.getChildAt(0)?.requestFocus()
                    return@setOnKeyListener true
                } else if (binding.rvSearchResults.childCount > 0) {
                    binding.rvSearchResults.getChildAt(0)?.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        // IME Search trigger
        binding.etSearch.setOnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (keyEvent != null && keyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER && keyEvent.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }

        // Action Search Button trigger
        binding.btnSearchAction.setOnClickListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearch(query)
            } else {
                Toast.makeText(this, "Please enter a search query", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGlobalSearchButton() {
        updateGlobalSearchButtonUI()

        binding.btnGlobalSearch.setOnClickListener {
            isGlobalSearch = !isGlobalSearch
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("search_global_enabled", isGlobalSearch)
                .apply()

            updateGlobalSearchButtonUI()

            val selectedSource = SourceManager.getSelectedSource(this)
            val toastMsg = if (isGlobalSearch) "Global search ON (Searching all providers)" else "Global search OFF (Searching in ${selectedSource.name})"
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()

            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearch(query)
            } else {
                updateInitialStatusText()
            }
        }
    }

    private fun updateGlobalSearchButtonUI() {
        val selectedSource = SourceManager.getSelectedSource(this)
        if (isGlobalSearch) {
            binding.btnGlobalSearch.setBackgroundResource(R.drawable.bg_globe_btn_on)
            binding.btnGlobalSearch.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
            binding.etSearch.hint = "Search across all providers..."
        } else {
            binding.btnGlobalSearch.setBackgroundResource(R.drawable.bg_globe_btn_off)
            binding.btnGlobalSearch.setColorFilter(android.graphics.Color.parseColor("#8B949E"))
            binding.etSearch.hint = "Search in ${selectedSource.name}..."
        }
    }

    private fun updateInitialStatusText() {
        val selectedSource = SourceManager.getSelectedSource(this)
        if (isGlobalSearch) {
            val enabledCount = ProviderManager.getEnabledProviders(this).size
            binding.tvSearchStatus.text = "Global Search enabled ($enabledCount active providers). Enter query & tap Search."
        } else {
            binding.tvSearchStatus.text = "Searching in ${selectedSource.name} mode. Enter query & tap Search."
        }
    }

    private fun performSearch(query: String) {
        // Hide soft keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()

        // Save query to search history
        lifecycleScope.launch(Dispatchers.IO) {
            searchHistoryRepository.saveQuery(query)
        }

        // Hide search history
        binding.llSearchHistoryContainer.visibility = View.GONE

        // Cancel previous search
        searchJob?.cancel()
        searchCategories.clear()
        searchAdapter.setCategories(emptyList())

        binding.tvNoResults.visibility = View.GONE
        binding.pbSearchProgress.visibility = View.VISIBLE

        val selectedSource = SourceManager.getSelectedSource(this)

        if (isGlobalSearch) {
            val providers = ProviderManager.getEnabledProviders(this)
            var completedProviders = 0
            var totalResultsCount = 0
            val foundProviders = mutableListOf<String>()

            binding.tvSearchStatus.visibility = View.VISIBLE
            binding.tvSearchStatus.text = "Searching across ${providers.size} providers..."

            searchJob = lifecycleScope.launch {
                coroutineScope {
                    providers.map { provider ->
                        launch(Dispatchers.IO) {
                            try {
                                val results = provider.search(query)
                                val items = results.map {
                                    LegacyStreamingSourceAdapter.searchResultToStreamingItem(it).copy(
                                        sourceName = provider.name
                                    )
                                }

                                withContext(Dispatchers.Main) {
                                    completedProviders++
                                    if (items.isNotEmpty()) {
                                        totalResultsCount += items.size
                                        foundProviders.add(provider.name)
                                        val category = StreamingCategory(
                                            id = "provider_${provider.name}",
                                            title = "🎬 ${provider.name} (${items.size} results)",
                                            items = items,
                                            hideViewMore = true
                                        )
                                        searchCategories.add(category)
                                        searchAdapter.setCategories(searchCategories.toList())
                                    }

                                    if (totalResultsCount > 0) {
                                        binding.tvSearchStatus.text = "Found $totalResultsCount results across ${foundProviders.size} provider(s)..."
                                    } else {
                                        binding.tvSearchStatus.text = "Searching... ($completedProviders/${providers.size} providers checked)"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    completedProviders++
                                }
                            }
                        }
                    }
                }

                // All concurrent searches completed
                binding.pbSearchProgress.visibility = View.GONE
                if (totalResultsCount == 0) {
                    binding.tvSearchStatus.text = "No results found for \"$query\""
                    binding.tvNoResults.visibility = View.VISIBLE
                    binding.tvNoResults.text = "No results found across ${providers.size} providers for \"$query\""
                } else {
                    binding.tvSearchStatus.text = "Found $totalResultsCount results across ${foundProviders.size} provider(s)"
                }
            }
        } else {
            // Single provider search
            binding.tvSearchStatus.visibility = View.VISIBLE
            binding.tvSearchStatus.text = "Searching in ${selectedSource.name}..."

            searchJob = lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    try {
                        selectedSource.search(query).map { it.copy(sourceName = selectedSource.name) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                binding.pbSearchProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    val category = StreamingCategory(
                        id = "provider_${selectedSource.name}",
                        title = "🎬 ${selectedSource.name} (${items.size} results)",
                        items = items,
                        hideViewMore = true
                    )
                    searchCategories.add(category)
                    searchAdapter.setCategories(searchCategories.toList())
                    binding.tvSearchStatus.text = "Found ${items.size} results in ${selectedSource.name}"
                } else {
                    binding.tvSearchStatus.text = "No results found in ${selectedSource.name}"
                    binding.tvNoResults.visibility = View.VISIBLE
                    binding.tvNoResults.text = "No results found in ${selectedSource.name} for \"$query\""
                }
            }
        }
    }

    private fun applyTvFocusAnimation(view: View) {
        view.isFocusable = true
        val density = view.resources.displayMetrics.density
        val elevationPx = 4 * density
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

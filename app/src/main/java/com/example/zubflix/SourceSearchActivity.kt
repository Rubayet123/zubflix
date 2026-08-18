package com.example.zubflix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.databinding.ActivitySourceSearchBinding
import com.example.zubflix.adapter.MainHomeAdapter
import com.example.zubflix.model.StreamingCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SourceSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceSearchBinding
    private lateinit var homeAdapter: MainHomeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.headerLayout) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarInset)
            windowInsets
        }

        binding.btnBack.setOnClickListener { finish() }
        applyTvFocusAnimation(binding.btnBack)

        setupRecyclerView()
        performSourceSearch()
    }

    private fun setupRecyclerView() {
        binding.rvSourceSearchResults.layoutManager = LinearLayoutManager(this)
        homeAdapter = MainHomeAdapter(
            onMovieClick = { item ->
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
            },
            onViewMoreClick = { /* no-op since hideViewMore is true */ }
        )
        binding.rvSourceSearchResults.adapter = homeAdapter
    }

    private fun performSourceSearch() {
        val movieTitle = intent.getStringExtra("MOVIE_TITLE") ?: ""
        val currentSourceName = intent.getStringExtra("CURRENT_SOURCE") ?: ""

        binding.tvTitle.text = "Sources for \"$movieTitle\""
        binding.loadingProgress.visibility = View.VISIBLE
        binding.tvNoResults.visibility = View.GONE

        Log.d("SourceSearch", "Starting source search for movie: '$movieTitle', current source: '$currentSourceName'")

        lifecycleScope.launch {
            try {
                // Exclude the current source
                val searchProviders = com.example.zubflix.provider.sdk.ProviderManager.getAllProviders().filter { it.name != currentSourceName }
                Log.d("SourceSearch", "Total providers to query: ${searchProviders.size}")

                val currentResults = mutableListOf<StreamingCategory>()
                
                withContext(Dispatchers.IO) {
                    val deferreds = searchProviders.map { provider ->
                        async {
                            try {
                                Log.d("SourceSearch", "Initiating search on provider: ${provider.name}")
                                // We use a shorter timeout to prevent hanging the UI for too long
                                val searchResults = withTimeoutOrNull(5000) {
                                    provider.search(movieTitle)
                                }
                                val items = searchResults?.map { com.example.zubflix.provider.sdk.LegacyStreamingSourceAdapter.searchResultToStreamingItem(it) }
                                
                                if (items != null && items.isNotEmpty()) {
                                    Log.d("SourceSearch", "Search on provider ${provider.name} completed successfully with ${items.size} results.")
                                    val category = StreamingCategory(
                                        id = "source_${provider.name}",
                                        title = provider.name,
                                        items = items.map { it.copy(sourceName = provider.name) },
                                        hideViewMore = true
                                    )
                                    // Update the UI progressively
                                    withContext(Dispatchers.Main) {
                                        currentResults.add(category)
                                        homeAdapter.setCategories(currentResults.toList()) // .toList() creates a new copy for the adapter
                                        
                                        // Hide loading/empty text as soon as we get at least one result
                                        binding.loadingProgress.visibility = View.GONE
                                        binding.tvNoResults.visibility = View.GONE
                                    }
                                } else {
                                    Log.d("SourceSearch", "Search on provider ${provider.name} returned 0 results or timed out.")
                                }
                            } catch (e: Exception) {
                                Log.e("SourceSearch", "Exception during search on provider ${provider.name}", e)
                            }
                        }
                    }
                    // Wait for all searches to finish or timeout
                    deferreds.awaitAll()
                }

                Log.d("SourceSearch", "Completed search for all sources.")

                // Check at the very end if we still have no results
                if (currentResults.isEmpty()) {
                    binding.tvNoResults.visibility = View.VISIBLE
                    binding.tvNoResults.text = "No matches found on any other source."
                }
            } catch (e: Exception) {
                Log.e("SourceSearch", "Global exception in performSourceSearch", e)
                binding.tvNoResults.visibility = View.VISIBLE
                binding.tvNoResults.text = "Error searching sources: ${e.message}"
            } finally {
                // Ensure loading indicator is hidden
                binding.loadingProgress.visibility = View.GONE
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

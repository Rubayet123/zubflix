package com.example.zubflix

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.R
import com.example.databinding.ActivityMyListBinding
import com.example.zubflix.adapter.CategoryItemAdapter
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.util.GridSpacingItemDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyListBinding
    private lateinit var adapter: CategoryItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarInset)
            windowInsets
        }

        binding.btnBack.setOnClickListener { finish() }
        applyTvFocusAnimation(binding.btnBack)

        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadMyList()
    }

    private fun setupRecyclerView() {
        val spanCount = 3
        binding.rvMyList.layoutManager = GridLayoutManager(this, spanCount)
        
        val spacingPx = (8 * resources.displayMetrics.density).toInt()
        binding.rvMyList.addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx, true))

        adapter = CategoryItemAdapter(
            onItemClick = { item ->
                DetailsActivity.start(this, item)
            },
            onViewMoreClick = null,
            isGridMode = true
        )
        binding.rvMyList.adapter = adapter
    }

    private fun loadMyList() {
        binding.loadingProgress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@MyListActivity).myListDao()
            val entities = dao.getAllItems()

            val items = entities.map { entity ->
                StreamingItem(
                    id = entity.itemId,
                    title = entity.title,
                    imageUrl = entity.imageUrl,
                    isSeries = entity.isSeries,
                    sourceName = entity.sourceName
                )
            }

            withContext(Dispatchers.Main) {
                binding.loadingProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    adapter.submitList(items)
                } else {
                    adapter.submitList(emptyList())
                    binding.tvEmpty.visibility = View.VISIBLE
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

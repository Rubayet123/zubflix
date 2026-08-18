package com.example.zubflix.bdix

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class LocalScrapersActivity : AppCompatActivity() {

    private lateinit var rvScrapers: RecyclerView
    private lateinit var adapter: LocalScrapersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_scrapers)

        val tvTitle = findViewById<View>(R.id.tv_title)
        tvTitle?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val marginParams = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (marginParams != null) {
                    val baseMargin = (24 * resources.displayMetrics.density).toInt()
                    marginParams.topMargin = baseMargin + statusBarInset
                    v.layoutParams = marginParams
                }
                windowInsets
            }
        }

        rvScrapers = findViewById(R.id.rv_scrapers)
        rvScrapers.layoutManager = LinearLayoutManager(this)

        adapter = LocalScrapersAdapter(
            items = emptyList(),
            onToggleEnabled = { scraperId, enabled ->
                LocalScraperManager.setScraperEnabled(this, scraperId, enabled)
                loadScrapers()
            },
            onMoveUp = { scraperId ->
                LocalScraperManager.moveUp(this, scraperId)
                loadScrapers()
            },
            onMoveDown = { scraperId ->
                LocalScraperManager.moveDown(this, scraperId)
                loadScrapers()
            }
        )

        rvScrapers.adapter = adapter
        loadScrapers()
    }

    private fun loadScrapers() {
        val scrapers = LocalScraperManager.getAllScrapersInfo(this)
        adapter.updateItems(scrapers)
    }
}

package com.example.zubflix.tv

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.util.AppearanceSettings

class TvHomeRowsFragment : RowsSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply {
        shadowEnabled = false
        selectEffectEnabled = false
    })

    var onItemClickListener: ((StreamingItem) -> Unit)? = null
    var onItemLongClickListener: ((StreamingItem) -> Unit)? = null
    var onItemFocusListener: ((StreamingItem) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = rowsAdapter

        val headerOffset = (36 * resources.displayMetrics.density).toInt()
        verticalGridView?.apply {
            setWindowAlignment(BaseGridView.WINDOW_ALIGN_LOW_EDGE)
            windowAlignmentOffset = headerOffset
            windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = BaseGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
            setWindowAlignmentPreferKeyLineOverLowEdge(false)
            setWindowAlignmentPreferKeyLineOverHighEdge(false)
            clipToPadding = false
            clipChildren = false
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val streamingItem = item as? StreamingItem ?: return@OnItemViewClickedListener
            onItemClickListener?.invoke(streamingItem)
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            val streamingItem = item as? StreamingItem ?: return@OnItemViewSelectedListener
            onItemFocusListener?.invoke(streamingItem)
        }
    }

    fun setCategories(
        categories: List<StreamingCategory>,
        isSourceLandscape: Boolean = false,
        cardStylePref: String = AppearanceSettings.TV_CARD_STYLE_PORTRAIT
    ) {
        rowsAdapter.clear()

        for ((index, category) in categories.withIndex()) {
            if (category.items.isEmpty()) continue

            val isContinueWatching = category.id == "continue_watching" ||
                    category.title.equals("Continue Watching", ignoreCase = true)

            val rowIsLandscape = when (cardStylePref) {
                AppearanceSettings.TV_CARD_STYLE_LANDSCAPE -> true
                AppearanceSettings.TV_CARD_STYLE_PORTRAIT -> false
                else -> {
                    // Smart / Adaptive Auto mode:
                    // 16:9 Landscape for Continue Watching and TMDB/Backdrop-enabled providers
                    if (isContinueWatching) true else isSourceLandscape
                }
            }

            val cardPresenter = TvCardPresenter(
                isLandscape = rowIsLandscape,
                onCardFocused = { item ->
                    onItemFocusListener?.invoke(item)
                },
                onCardLongClicked = { item ->
                    onItemLongClickListener?.invoke(item)
                }
            )

            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            for (item in category.items) {
                rowAdapter.add(item)
            }
            if (!category.hideViewMore && (category.id.isNotBlank() || category.items.isNotEmpty())) {
                val categorySource = category.items.firstOrNull()?.sourceName ?: ""
                val seeAllItem = StreamingItem(
                    id = category.id.ifBlank { category.title },
                    title = "See All",
                    imageUrl = null,
                    backdropUrl = null,
                    description = "Browse the complete collection of ${category.title} titles.",
                    rating = null,
                    year = null,
                    isSeries = false,
                    isCategory = true,
                    sourceName = categorySource
                )
                rowAdapter.add(seeAllItem)
            }
            val header = HeaderItem(index.toLong(), category.title)
            rowsAdapter.add(ListRow(header, rowAdapter))
        }
    }
}

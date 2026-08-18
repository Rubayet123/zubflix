package com.example.zubflix.tv

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem

class TvHomeRowsFragment : RowsSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply {
        shadowEnabled = false
        selectEffectEnabled = false
    })

    var onItemClickListener: ((StreamingItem) -> Unit)? = null
    var onItemFocusListener: ((StreamingItem) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val streamingItem = item as? StreamingItem ?: return@OnItemViewClickedListener
            onItemClickListener?.invoke(streamingItem)
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            val streamingItem = item as? StreamingItem ?: return@OnItemViewSelectedListener
            onItemFocusListener?.invoke(streamingItem)
        }
    }

    fun setCategories(categories: List<StreamingCategory>) {
        rowsAdapter.clear()
        val cardPresenter = TvCardPresenter(
            onCardFocused = { item ->
                onItemFocusListener?.invoke(item)
            }
        )

        for ((index, category) in categories.withIndex()) {
            if (category.items.isEmpty()) continue
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            for (item in category.items) {
                rowAdapter.add(item)
            }
            val header = HeaderItem(index.toLong(), category.title)
            rowsAdapter.add(ListRow(header, rowAdapter))
        }
    }
}

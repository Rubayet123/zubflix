package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemSearchHistoryBinding
import com.example.zubflix.database.SearchHistoryEntity

class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<SearchHistoryEntity>()

    fun submitList(newItems: List<SearchHistoryEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemSearchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isFocusable = true
            binding.root.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(6f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }

            binding.btnDeleteHistory.isFocusable = true
            binding.btnDeleteHistory.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).start()
                    binding.btnDeleteHistory.setColorFilter(android.graphics.Color.parseColor("#E50914"))
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    binding.btnDeleteHistory.setColorFilter(android.graphics.Color.parseColor("#8B949E"))
                }
            }
        }

        fun bind(entity: SearchHistoryEntity) {
            binding.tvSearchQuery.text = entity.query
            binding.root.setOnClickListener {
                onItemClick(entity.query)
            }
            binding.btnDeleteHistory.setOnClickListener {
                onDeleteClick(entity.query)
            }
        }
    }
}

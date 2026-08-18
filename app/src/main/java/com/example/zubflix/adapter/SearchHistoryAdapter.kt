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

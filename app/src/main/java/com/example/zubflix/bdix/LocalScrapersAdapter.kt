package com.example.zubflix.bdix

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class LocalScrapersAdapter(
    private var items: List<LocalScraperManager.LocalScraperInfo>,
    private val onToggleEnabled: (String, Boolean) -> Unit,
    private val onMoveUp: (String) -> Unit,
    private val onMoveDown: (String) -> Unit
) : RecyclerView.Adapter<LocalScrapersAdapter.ViewHolder>() {

    fun updateItems(newItems: List<LocalScraperManager.LocalScraperInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView = view.findViewById(R.id.tv_rank)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvDescription: TextView = view.findViewById(R.id.tv_description)
        val btnMoveUp: Button = view.findViewById(R.id.btn_move_up)
        val btnMoveDown: Button = view.findViewById(R.id.btn_move_down)
        val switchEnabled: SwitchCompat = view.findViewById(R.id.switch_enabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_scraper, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvRank.text = "#${item.priority}"
        holder.tvName.text = item.scraper.name
        holder.tvDescription.text = item.scraper.description

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = item.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggleEnabled(item.scraper.id, isChecked)
        }

        holder.btnMoveUp.isEnabled = position > 0
        holder.btnMoveUp.alpha = if (position > 0) 1.0f else 0.3f
        holder.btnMoveUp.setOnClickListener {
            onMoveUp(item.scraper.id)
        }

        holder.btnMoveDown.isEnabled = position < items.size - 1
        holder.btnMoveDown.alpha = if (position < items.size - 1) 1.0f else 0.3f
        holder.btnMoveDown.setOnClickListener {
            onMoveDown(item.scraper.id)
        }
    }

    override fun getItemCount(): Int = items.size
}

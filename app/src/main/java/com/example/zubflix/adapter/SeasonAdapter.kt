package com.example.zubflix.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.zubflix.model.StreamingSeason

class SeasonAdapter(
    private val seasons: List<StreamingSeason>,
    private var selectedIndex: Int = 0,
    private val onSeasonSelected: (StreamingSeason, Int) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_season_title)
        val container: View = itemView.findViewById(R.id.header_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val season = seasons[position]
        holder.tvTitle.text = season.title

        // Highlight selected season chip
        if (position == selectedIndex) {
            holder.container.setBackgroundResource(R.drawable.bg_season_chip_active)
            holder.tvTitle.setTextColor(Color.WHITE)
        } else {
            holder.container.setBackgroundResource(R.drawable.bg_season_chip_inactive)
            holder.tvTitle.setTextColor(Color.parseColor("#D0FFFFFF"))
        }

        holder.itemView.setOnClickListener {
            val prevSelected = selectedIndex
            selectedIndex = holder.adapterPosition
            notifyItemChanged(prevSelected)
            notifyItemChanged(selectedIndex)
            onSeasonSelected(season, selectedIndex)
        }

        setupTVFocus(holder.itemView, holder.container, holder.tvTitle, position)
    }

    override fun getItemCount() = seasons.size

    private fun setupTVFocus(view: View, container: View, textView: TextView, position: Int) {
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                container.setBackgroundResource(R.drawable.bg_season_chip_active)
                textView.setTextColor(Color.WHITE)
                view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
            } else {
                if (position == selectedIndex) {
                    container.setBackgroundResource(R.drawable.bg_season_chip_active)
                    textView.setTextColor(Color.WHITE)
                } else {
                    container.setBackgroundResource(R.drawable.bg_season_chip_inactive)
                    textView.setTextColor(Color.parseColor("#D0FFFFFF"))
                }
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }
}

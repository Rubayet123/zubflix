package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.zubflix.model.StreamingSource

class ProviderDropdownAdapter(
    private val sources: List<StreamingSource>,
    private val selectedName: String,
    private val onSourceSelected: (StreamingSource) -> Unit
) : RecyclerView.Adapter<ProviderDropdownAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_provider_name)
        val ivCheck: ImageView = itemView.findViewById(R.id.iv_check_mark)
        val vIndicator: View = itemView.findViewById(R.id.v_selected_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_provider_dropdown, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        holder.tvName.text = source.name

        val isSelected = source.name == selectedName
        holder.vIndicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        holder.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

        if (isSelected) {
            holder.tvName.setTextColor(0xFFFFFFFF.toInt())
            holder.tvName.typeface = android.graphics.Typeface.DEFAULT_BOLD
        } else {
            holder.tvName.setTextColor(0xFF9E9EA7.toInt())
            holder.tvName.typeface = android.graphics.Typeface.DEFAULT
        }

        holder.itemView.setOnClickListener {
            onSourceSelected(source)
        }
    }

    override fun getItemCount() = sources.size
}

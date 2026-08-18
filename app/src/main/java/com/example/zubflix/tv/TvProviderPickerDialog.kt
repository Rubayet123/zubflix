package com.example.zubflix.tv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.zubflix.SourceManager
import com.example.zubflix.model.StreamingSource

class TvProviderPickerDialog(
    context: Context,
    private val onProviderSelected: (StreamingSource) -> Unit
) : Dialog(context, R.style.Theme_Zubflix_Tv) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.dialog_tv_provider_picker)

        val rv = findViewById<RecyclerView>(R.id.rv_tv_providers)
        rv.layoutManager = LinearLayoutManager(context)

        val sources = SourceManager.getEnabledSources(context).filterNot {
            it.name.contains("MegaProvider", ignoreCase = true) ||
            it.name.contains("CloudStream Extensions", ignoreCase = true)
        }
        val currentSelected = SourceManager.getSelectedSource(context)

        val adapter = ProviderAdapter(sources, currentSelected) { source ->
            SourceManager.setSelectedSource(context, source.name)
            onProviderSelected(source)
            dismiss()
        }
        rv.adapter = adapter

        // Focus selected position
        val selectedIndex = sources.indexOfFirst { it.name == currentSelected.name }
        if (selectedIndex >= 0) {
            rv.post {
                rv.scrollToPosition(selectedIndex)
                val targetView = rv.findViewHolderForAdapterPosition(selectedIndex)?.itemView ?: rv.getChildAt(0)
                com.example.zubflix.util.FocusHelper.safeRequestFocus(targetView)
            }
        }
    }

    private class ProviderAdapter(
        private val sources: List<StreamingSource>,
        private val currentSelected: StreamingSource,
        private val onSelect: (StreamingSource) -> Unit
    ) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_provider_name)
            val icon: ImageView = v.findViewById(R.id.iv_provider_icon)
            val checked: ImageView = v.findViewById(R.id.iv_provider_checked)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_provider_picker, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val source = sources[position]
            val isSelected = source.name == currentSelected.name

            holder.name.text = source.name
            holder.checked.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.icon.setColorFilter(if (isSelected) 0xFF00E676.toInt() else 0xFF8B949E.toInt())

            holder.itemView.setOnClickListener {
                onSelect(source)
            }
        }

        override fun getItemCount(): Int = sources.size
    }
}

import sys
import re

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

# Replace the showNuvioStreamSelectorBottomSheet function using regex
pattern = r"private fun showNuvioStreamSelectorBottomSheet\(.*?dialog\.show\(\)\n    \}"

new_sheet = """private fun showNuvioStreamSelectorBottomSheet(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_nuvio_stream_selector, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
        val tvSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_subtitle)
        val tvCount = dialogView.findViewById<android.widget.TextView>(R.id.stream_count_badge)
        val rvStreams = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.nuvio_stream_list)
        
        tvTitle.text = "Stream Source"
        tvSubtitle.text = "Searching for: $title"
        tvCount.text = "0 found"
        
        rvStreams.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val sourceList = mutableListOf<Pair<String, String>>()
        
        class NuvioStreamViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            val tvAddonProvider: android.widget.TextView = itemView.findViewById(R.id.tv_addon_provider)
            val tvMoniker: android.widget.TextView = itemView.findViewById(R.id.tv_moniker)
            val tvTorrentTitle: android.widget.TextView = itemView.findViewById(R.id.tv_torrent_title)
            val tvBadgeResolution: android.widget.TextView = itemView.findViewById(R.id.tv_badge_resolution)
            val tvBadgeSize: android.widget.TextView = itemView.findViewById(R.id.tv_badge_size)
            val tvBadgeAudio: android.widget.TextView = itemView.findViewById(R.id.tv_badge_audio)
            val tvBadgeQuality: android.widget.TextView = itemView.findViewById(R.id.tv_badge_quality)
            val tvBadgeCodec: android.widget.TextView = itemView.findViewById(R.id.tv_badge_codec)
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<NuvioStreamViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NuvioStreamViewHolder {
                val view = layoutInflater.inflate(R.layout.item_nuvio_stream, parent, false)
                return NuvioStreamViewHolder(view)
            }

            override fun onBindViewHolder(holder: NuvioStreamViewHolder, position: Int) {
                val item = sourceList[position]
                val parsed = parseNuvioStreamName(item.first, item.second)
                
                holder.tvAddonProvider.text = parsed.addonName.ifEmpty { "Unknown Addon" }
                
                if (parsed.moniker.isNotEmpty()) {
                    holder.tvMoniker.text = parsed.moniker
                    holder.tvMoniker.visibility = android.view.View.VISIBLE
                } else {
                    holder.tvMoniker.visibility = android.view.View.GONE
                }
                
                holder.tvTorrentTitle.text = parsed.torrentName
                
                // Reset badges
                holder.tvBadgeResolution.visibility = android.view.View.GONE
                holder.tvBadgeSize.visibility = android.view.View.GONE
                holder.tvBadgeAudio.visibility = android.view.View.GONE
                holder.tvBadgeQuality.visibility = android.view.View.GONE
                holder.tvBadgeCodec.visibility = android.view.View.GONE
                
                parsed.attributes.forEach { attr ->
                    val lowerAttr = attr.lowercase()
                    when {
                        lowerAttr.matches(Regex(".*(4k|1080p|720p|480p|2160p).*")) -> {
                            holder.tvBadgeResolution.text = attr
                            holder.tvBadgeResolution.visibility = android.view.View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(gb|mb).*")) -> {
                            holder.tvBadgeSize.text = attr
                            holder.tvBadgeSize.visibility = android.view.View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(aac|ac3|dts|dolby|5\\.1|7\\.1).*")) -> {
                            holder.tvBadgeAudio.text = attr
                            holder.tvBadgeAudio.visibility = android.view.View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hdr|sdr|bluray|web-dl|webrip|cam).*")) -> {
                            holder.tvBadgeQuality.text = attr
                            holder.tvBadgeQuality.visibility = android.view.View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hevc|x265|h264|x264|av1).*")) -> {
                            holder.tvBadgeCodec.text = attr
                            holder.tvBadgeCodec.visibility = android.view.View.VISIBLE
                        }
                        else -> {
                            if (holder.tvBadgeQuality.visibility == android.view.View.GONE) {
                                holder.tvBadgeQuality.text = attr
                                holder.tvBadgeQuality.visibility = android.view.View.VISIBLE
                            } else if (holder.tvBadgeAudio.visibility == android.view.View.GONE) {
                                holder.tvBadgeAudio.text = attr
                                holder.tvBadgeAudio.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
                
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    val allUrls = sourceList.map { it.second }
                    playVideo(title, item.second, allUrls, item.first, seasonNumber, episodeNumber, imdbId)
                }
                
                // TV focus
                holder.itemView.isFocusable = true
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.animate().scaleX(1.02f).scaleY(1.02f).translationZ(4f).setDuration(150).start()
                        view.setBackgroundResource(R.drawable.bg_stream_item_focused)
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).start()
                        view.setBackgroundResource(R.drawable.bg_stream_item)
                    }
                }
            }

            override fun getItemCount() = sourceList.size
        }
        
        rvStreams.adapter = adapter
        dialog.show()

        androidx.lifecycle.lifecycleScope.launch {
            try {
                val nuvioSource = SourceManager.getSourceByName("Nuvio") as? com.example.zubflix.sources.NuvioSource ?: return@launch
                var completedSources = 0
                var maxSources = 0
                nuvioSource.extractVideoLinksStreaming(lazyUrl,
                    onProgress = { done, total ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            completedSources = done
                            maxSources = total
                            if (done < total) {
                                tvSubtitle.text = "Scraping: $done out of $total sources done"
                            } else {
                                tvSubtitle.text = "Select a stream for: $title"
                                if (sourceList.isEmpty()) {
                                    tvSubtitle.text = "No streams found for: $title"
                                }
                            }
                        }
                    },
                    onStreamFound = { streams ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val startPos = sourceList.size
                            sourceList.addAll(streams.toList())
                            adapter.notifyItemRangeInserted(startPos, streams.size)
                            tvCount.text = "${sourceList.size} found"
                        }
                    }
                )
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvSubtitle.text = "Error fetching streams: ${e.message}"
                }
            }
        }
    }"""

content = re.sub(pattern, new_sheet, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)

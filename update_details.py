import sys

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

# Update resolveAndPlayLazy
old_resolve = """    private fun resolveAndPlayLazy(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        binding.loadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val source = SourceManager.getSourceByName(sourceName) ?: return@launch
                com.example.zubflix.util.DebugLogger.d("DetailsActivity", "Resolving stream for $title, URL: $lazyUrl, Source: $sourceName")
                val resolvedSources = withContext(Dispatchers.IO) {
                    source.extractVideoLinks(lazyUrl)
                }
                com.example.zubflix.util.DebugLogger.d("DetailsActivity", "Resolved ${resolvedSources.size} sources for $title")

                if (resolvedSources.isNotEmpty()) {
                    if (sourceName.equals("Nuvio", ignoreCase = true)) {
                        showNuvioStreamSelectorBottomSheet(title, resolvedSources, seasonNumber, episodeNumber, imdbId)
                    } else if (resolvedSources.size > 1) {
                        showSourceSelectorBottomSheet(title, resolvedSources, seasonNumber, episodeNumber, imdbId)
                    } else {
                        val rawName = resolvedSources.keys.first()
                        val finalUrl = resolvedSources.values.first()
                        playVideo(title, finalUrl, resolvedSources.values.toList(), rawName, seasonNumber, episodeNumber, imdbId)
                    }
                } else {
                    Toast.makeText(this@DetailsActivity, "Failed to resolve stream link", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@DetailsActivity, "Error resolving: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }"""

new_resolve = """    private fun resolveAndPlayLazy(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        if (sourceName.equals("Nuvio", ignoreCase = true)) {
            showNuvioStreamSelectorBottomSheet(title, lazyUrl, seasonNumber, episodeNumber, imdbId)
            return
        }
        binding.loadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val source = SourceManager.getSourceByName(sourceName) ?: return@launch
                com.example.zubflix.util.DebugLogger.d("DetailsActivity", "Resolving stream for $title, URL: $lazyUrl, Source: $sourceName")
                val resolvedSources = withContext(Dispatchers.IO) {
                    source.extractVideoLinks(lazyUrl)
                }
                com.example.zubflix.util.DebugLogger.d("DetailsActivity", "Resolved ${resolvedSources.size} sources for $title")

                if (resolvedSources.isNotEmpty()) {
                    if (resolvedSources.size > 1) {
                        showSourceSelectorBottomSheet(title, resolvedSources, seasonNumber, episodeNumber, imdbId)
                    } else {
                        val rawName = resolvedSources.keys.first()
                        val finalUrl = resolvedSources.values.first()
                        playVideo(title, finalUrl, resolvedSources.values.toList(), rawName, seasonNumber, episodeNumber, imdbId)
                    }
                } else {
                    Toast.makeText(this@DetailsActivity, "Failed to resolve stream link", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@DetailsActivity, "Error resolving: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }"""

content = content.replace(old_resolve, new_resolve)

# Update showNuvioStreamSelectorBottomSheet
old_sheet = """    private fun showNuvioStreamSelectorBottomSheet(
        title: String,
        sources: Map<String, String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_nuvio_stream_selector, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
        val tvSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_subtitle)
        val tvCount = dialogView.findViewById<android.widget.TextView>(R.id.stream_count_badge)
        val rvStreams = dialogView.findViewById<RecyclerView>(R.id.nuvio_stream_list)
        
        tvTitle.text = "Stremio Addon Streams"
        tvSubtitle.text = "Select a stream for: $title"
        tvCount.text = "${sources.size} found"
        
        rvStreams.layoutManager = LinearLayoutManager(this)
        
        val sourceList = sources.toList()
        
        class NuvioStreamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvAddonProvider: android.widget.TextView = itemView.findViewById(R.id.tv_addon_provider)
            val tvMoniker: android.widget.TextView = itemView.findViewById(R.id.tv_moniker)
            val tvTorrentTitle: android.widget.TextView = itemView.findViewById(R.id.tv_torrent_title)
            val tvBadgeResolution: android.widget.TextView = itemView.findViewById(R.id.tv_badge_resolution)
            val tvBadgeSize: android.widget.TextView = itemView.findViewById(R.id.tv_badge_size)
            val tvBadgeAudio: android.widget.TextView = itemView.findViewById(R.id.tv_badge_audio)
            val tvBadgeQuality: android.widget.TextView = itemView.findViewById(R.id.tv_badge_quality)
            val tvBadgeCodec: android.widget.TextView = itemView.findViewById(R.id.tv_badge_codec)
        }

        rvStreams.adapter = object : RecyclerView.Adapter<NuvioStreamViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NuvioStreamViewHolder {
                val view = layoutInflater.inflate(R.layout.item_nuvio_stream, parent, false)
                return NuvioStreamViewHolder(view)
            }

            override fun onBindViewHolder(holder: NuvioStreamViewHolder, position: Int) {
                val item = sourceList[position]
                val parsed = parseNuvioStreamName(item.first, item.second)
                
                holder.tvAddonProvider.text = parsed.addonName.ifEmpty { "Unknown Addon" }
                
                if (parsed.moniker.isNotEmpty()) {
                    holder.tvMoniker.text = parsed.moniker
                    holder.tvMoniker.visibility = View.VISIBLE
                } else {
                    holder.tvMoniker.visibility = View.GONE
                }
                
                holder.tvTorrentTitle.text = parsed.torrentName
                
                // Reset badges
                holder.tvBadgeResolution.visibility = View.GONE
                holder.tvBadgeSize.visibility = View.GONE
                holder.tvBadgeAudio.visibility = View.GONE
                holder.tvBadgeQuality.visibility = View.GONE
                holder.tvBadgeCodec.visibility = View.GONE
                
                parsed.attributes.forEach { attr ->
                    val lowerAttr = attr.lowercase()
                    when {
                        lowerAttr.matches(Regex(".*(4k|1080p|720p|480p|2160p).*")) -> {
                            holder.tvBadgeResolution.text = attr
                            holder.tvBadgeResolution.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(gb|mb).*")) -> {
                            holder.tvBadgeSize.text = attr
                            holder.tvBadgeSize.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(aac|ac3|dts|dolby|5\\.1|7\\.1).*")) -> {
                            holder.tvBadgeAudio.text = attr
                            holder.tvBadgeAudio.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hdr|sdr|bluray|web-dl|webrip|cam).*")) -> {
                            holder.tvBadgeQuality.text = attr
                            holder.tvBadgeQuality.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hevc|x265|h264|x264|av1).*")) -> {
                            holder.tvBadgeCodec.text = attr
                            holder.tvBadgeCodec.visibility = View.VISIBLE
                        }
                        else -> {
                            // If we can't categorize it, put it in one of the unused badges if available
                            if (holder.tvBadgeQuality.visibility == View.GONE) {
                                holder.tvBadgeQuality.text = attr
                                holder.tvBadgeQuality.visibility = View.VISIBLE
                            } else if (holder.tvBadgeAudio.visibility == View.GONE) {
                                holder.tvBadgeAudio.text = attr
                                holder.tvBadgeAudio.visibility = View.VISIBLE
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
        
        dialog.show()
    }"""

new_sheet = """    private fun showNuvioStreamSelectorBottomSheet(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_nuvio_stream_selector, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
        val tvSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_subtitle)
        val tvCount = dialogView.findViewById<android.widget.TextView>(R.id.stream_count_badge)
        val rvStreams = dialogView.findViewById<RecyclerView>(R.id.nuvio_stream_list)
        
        tvTitle.text = "Stream Source"
        tvSubtitle.text = "Searching for: $title"
        tvCount.text = "0 found"
        
        rvStreams.layoutManager = LinearLayoutManager(this)
        
        val sourceList = mutableListOf<Pair<String, String>>()
        
        class NuvioStreamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvAddonProvider: android.widget.TextView = itemView.findViewById(R.id.tv_addon_provider)
            val tvMoniker: android.widget.TextView = itemView.findViewById(R.id.tv_moniker)
            val tvTorrentTitle: android.widget.TextView = itemView.findViewById(R.id.tv_torrent_title)
            val tvBadgeResolution: android.widget.TextView = itemView.findViewById(R.id.tv_badge_resolution)
            val tvBadgeSize: android.widget.TextView = itemView.findViewById(R.id.tv_badge_size)
            val tvBadgeAudio: android.widget.TextView = itemView.findViewById(R.id.tv_badge_audio)
            val tvBadgeQuality: android.widget.TextView = itemView.findViewById(R.id.tv_badge_quality)
            val tvBadgeCodec: android.widget.TextView = itemView.findViewById(R.id.tv_badge_codec)
        }

        val adapter = object : RecyclerView.Adapter<NuvioStreamViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NuvioStreamViewHolder {
                val view = layoutInflater.inflate(R.layout.item_nuvio_stream, parent, false)
                return NuvioStreamViewHolder(view)
            }

            override fun onBindViewHolder(holder: NuvioStreamViewHolder, position: Int) {
                val item = sourceList[position]
                val parsed = parseNuvioStreamName(item.first, item.second)
                
                holder.tvAddonProvider.text = parsed.addonName.ifEmpty { "Unknown Addon" }
                
                if (parsed.moniker.isNotEmpty()) {
                    holder.tvMoniker.text = parsed.moniker
                    holder.tvMoniker.visibility = View.VISIBLE
                } else {
                    holder.tvMoniker.visibility = View.GONE
                }
                
                holder.tvTorrentTitle.text = parsed.torrentName
                
                // Reset badges
                holder.tvBadgeResolution.visibility = View.GONE
                holder.tvBadgeSize.visibility = View.GONE
                holder.tvBadgeAudio.visibility = View.GONE
                holder.tvBadgeQuality.visibility = View.GONE
                holder.tvBadgeCodec.visibility = View.GONE
                
                parsed.attributes.forEach { attr ->
                    val lowerAttr = attr.lowercase()
                    when {
                        lowerAttr.matches(Regex(".*(4k|1080p|720p|480p|2160p).*")) -> {
                            holder.tvBadgeResolution.text = attr
                            holder.tvBadgeResolution.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(gb|mb).*")) -> {
                            holder.tvBadgeSize.text = attr
                            holder.tvBadgeSize.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(aac|ac3|dts|dolby|5\\.1|7\\.1).*")) -> {
                            holder.tvBadgeAudio.text = attr
                            holder.tvBadgeAudio.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hdr|sdr|bluray|web-dl|webrip|cam).*")) -> {
                            holder.tvBadgeQuality.text = attr
                            holder.tvBadgeQuality.visibility = View.VISIBLE
                        }
                        lowerAttr.matches(Regex(".*(hevc|x265|h264|x264|av1).*")) -> {
                            holder.tvBadgeCodec.text = attr
                            holder.tvBadgeCodec.visibility = View.VISIBLE
                        }
                        else -> {
                            if (holder.tvBadgeQuality.visibility == View.GONE) {
                                holder.tvBadgeQuality.text = attr
                                holder.tvBadgeQuality.visibility = View.VISIBLE
                            } else if (holder.tvBadgeAudio.visibility == View.GONE) {
                                holder.tvBadgeAudio.text = attr
                                holder.tvBadgeAudio.visibility = View.VISIBLE
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

        lifecycleScope.launch {
            try {
                val nuvioSource = SourceManager.getSourceByName("Nuvio") as? com.example.zubflix.sources.NuvioSource ?: return@launch
                var completedSources = 0
                var maxSources = 0
                nuvioSource.extractVideoLinksStreaming(lazyUrl,
                    onProgress = { done, total ->
                        withContext(Dispatchers.Main) {
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
                        withContext(Dispatchers.Main) {
                            val startPos = sourceList.size
                            sourceList.addAll(streams.toList())
                            adapter.notifyItemRangeInserted(startPos, streams.size)
                            tvCount.text = "${sourceList.size} found"
                        }
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvSubtitle.text = "Error fetching streams: ${e.message}"
                }
            }
        }
    }"""

content = content.replace(old_sheet, new_sheet)

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)


import sys
import re

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

pattern = r"lifecycleScope\.launch \{\n            try \{\n                val nuvioSource = SourceManager\.getSourceByName.*?progressScraping\.visibility = android\.view\.View\.GONE\n                \}\n            \}\n        \}"

new_block = """lifecycleScope.launch {
            try {
                val cachedSource = SourceManager.getSourceByName("Nuvio") as? com.example.zubflix.sources.CachedSource
                val nuvioSource = cachedSource?.source as? com.example.zubflix.sources.NuvioSource ?: SourceManager.getSourceByName("Nuvio") as? com.example.zubflix.sources.NuvioSource ?: return@launch
                var completedSources = 0
                var maxSources = 0
                nuvioSource.extractVideoLinksStreaming(lazyUrl,
                    onProgress = { done, total ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            completedSources = done
                            maxSources = total
                            if (done < total) {
                                tvSubtitle.text = "Scraping: $done out of $total sources done"
                            }
                        }
                    },
                    onStreamFound = { streams ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            emptyStateView.visibility = android.view.View.GONE
                            val startPos = sourceList.size
                            val streamList = streams.toList()
                            sourceList.addAll(streamList)
                            adapter.notifyItemRangeInserted(startPos, streamList.size)
                            tvCount.text = "${sourceList.size} found"
                            tvCount.visibility = android.view.View.VISIBLE
                        }
                    }
                )
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvSubtitle.text = "Error fetching streams: ${e.message}"
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressScraping.visibility = android.view.View.GONE
                    if (sourceList.isEmpty()) {
                        tvSubtitle.text = "No streams found for: $title"
                        emptyStateView.visibility = android.view.View.VISIBLE
                        tvCount.visibility = android.view.View.GONE
                    } else {
                        tvSubtitle.text = "Select a stream for: $title"
                    }
                }
            }
        }"""

content = re.sub(pattern, new_block, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)

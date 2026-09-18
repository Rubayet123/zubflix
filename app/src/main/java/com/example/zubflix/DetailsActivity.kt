package com.example.zubflix

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import com.example.databinding.ActivityDetailsBinding
import com.example.zubflix.adapter.EpisodeAdapter
import com.example.zubflix.adapter.SeasonAdapter
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.database.MyListEntity
import com.example.zubflix.database.WatchHistoryEntity
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailsBinding
    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    
    private var itemId: String = ""
    private var sourceName: String = ""
    private var currentItem: StreamingItem? = null
    private var isInMyList = false
    private var watchHistoryEntity: WatchHistoryEntity? = null
    private var targetSeasonNumber: Int = 1
    private var targetEpisodeNumber: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.btnBack) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val marginParams = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (marginParams != null) {
                val baseMarginTop = (16 * resources.displayMetrics.density).toInt()
                marginParams.topMargin = baseMarginTop + statusBarInset
                view.layoutParams = marginParams
            }
            windowInsets
        }

        val rawItemId = intent.getStringExtra("ITEM_ID") ?: ""
        itemId = cleanSeriesItemId(rawItemId)
        sourceName = intent.getStringExtra("SOURCE_NAME") ?: ""

        // Instant rendering: check for pre-filled item metadata passed in Intent
        val prefillTitle = intent.getStringExtra("ITEM_TITLE")
        if (!prefillTitle.isNullOrEmpty()) {
            val prefillItem = StreamingItem(
                id = itemId,
                title = prefillTitle,
                isSeries = intent.getBooleanExtra("ITEM_IS_SERIES", false),
                sourceName = sourceName,
                imageUrl = intent.getStringExtra("ITEM_POSTER"),
                backdropUrl = intent.getStringExtra("ITEM_BACKDROP"),
                description = intent.getStringExtra("ITEM_DESCRIPTION"),
                streamUrl = intent.getStringExtra("ITEM_STREAM_URL"),
                rating = intent.getStringExtra("ITEM_RATING"),
                year = intent.getStringExtra("ITEM_YEAR"),
                quality = intent.getStringExtra("ITEM_QUALITY")
            )
            currentItem = prefillItem
            displayDetails(prefillItem)
        }

        binding.btnBack.setOnClickListener { finish() }

        setupListeners()
        loadDetails()
    }

    private fun cleanSeriesItemId(rawId: String): String {
        var cleaned = rawId.trim()
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            try {
                val obj = org.json.JSONObject(cleaned)
                return obj.optString("movieId", cleaned)
            } catch (_: Exception) {}
        }
        return cleaned.replace(Regex(":\\d+:\\d+$"), "")
    }

    override fun onResume() {
        super.onResume()
        checkWatchHistoryAndUpdateCta()
    }

    private fun checkWatchHistoryAndUpdateCta() {
        if (itemId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@DetailsActivity).watchHistoryDao()
            var history = dao.getWatchHistoryById(itemId)
            if (history == null) {
                val recent = dao.getRecentlyWatchedOnce(50)
                history = recent.find { 
                    it.itemId == itemId || cleanSeriesItemId(it.itemId) == itemId 
                }
            }
            watchHistoryEntity = history

            withContext(Dispatchers.Main) {
                updatePrimaryCtaButton()
            }
        }
    }

    private fun updatePrimaryCtaButton() {
        val item = currentItem ?: return
        val history = watchHistoryEntity

        if (item.isSeries) {
            if (history != null && history.lastSeasonNumber != null && history.lastEpisodeNumber != null) {
                val s = history.lastSeasonNumber
                val e = history.lastEpisodeNumber
                val percent = history.watchPercentage

                if (percent > 90f) {
                    targetSeasonNumber = s
                    targetEpisodeNumber = e + 1
                    binding.tvPlayText.text = "PLAY S${targetSeasonNumber}:E${targetEpisodeNumber}"
                } else {
                    targetSeasonNumber = s
                    targetEpisodeNumber = e
                    binding.tvPlayText.text = "RESUME S${s}:E${e}"
                }
            } else {
                targetSeasonNumber = 1
                targetEpisodeNumber = 1
                binding.tvPlayText.text = "PLAY S1:E1"
            }
        } else {
            if (history != null) {
                val percent = history.watchPercentage
                val currentPos = history.currentPosition
                if (percent > 90f) {
                    binding.tvPlayText.text = "WATCH AGAIN"
                } else if (percent > 1f || currentPos > 10000L) {
                    binding.tvPlayText.text = "RESUME"
                } else {
                    binding.tvPlayText.text = "WATCH MOVIE"
                }
            } else {
                binding.tvPlayText.text = "WATCH MOVIE"
            }
        }
    }

    private fun setupListeners() {
        binding.btnPlay.setOnClickListener {
            currentItem?.let { item ->
                if (item.isSeries) {
                    playTargetEpisode(targetSeasonNumber, targetEpisodeNumber)
                } else {
                    val isWatchAgain = binding.tvPlayText.text == "WATCH AGAIN"
                    val streamUrl = item.streamUrl
                    if (!streamUrl.isNullOrEmpty()) {
                        val imdbIdMatch = Regex("tt\\d+").find(streamUrl)
                        val extractedImdbId = imdbIdMatch?.value ?: (if (itemId.startsWith("tt")) itemId else null)
                        if (streamUrl.startsWith("nuvio_resolve/") ||
                            streamUrl.startsWith("servers/") ||
                            streamUrl.contains("|") ||
                            streamUrl.contains("moviebox", ignoreCase = true) ||
                            streamUrl.contains("movielinkbd", ignoreCase = true) ||
                            sourceName.contains("MovieBox", ignoreCase = true) ||
                            sourceName.contains("MovieLinkBD", ignoreCase = true) ||
                            sourceName.contains("CTGMovies", ignoreCase = true) ||
                            sourceName.contains("CTG", ignoreCase = true) ||
                            sourceName.contains("Nuvio", ignoreCase = true) ||
                            sourceName.contains("MovieBlast", ignoreCase = true) ||
                            !streamUrl.startsWith("http")) {
                            resolveAndPlayLazy(item.title, streamUrl, imdbId = extractedImdbId)
                        } else {
                            // Check if multiple sources exist
                            val sources = item.videoSources
                            if (sources != null && sources.size > 1) {
                                showSourceSelectorBottomSheet(item.title, sources, imdbId = extractedImdbId)
                            } else {
                                playVideo(item.title, streamUrl, listOf(streamUrl), imdbId = extractedImdbId, forceRestart = isWatchAgain)
                            }
                        }
                    } else {
                        Toast.makeText(this, "Stream URL not available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnMyList.setOnClickListener {
            toggleMyList()
        }

        binding.btnSearchSources.setOnClickListener {
            currentItem?.let { item ->
                val intent = Intent(this, SearchActivity::class.java).apply {
                    putExtra("SEARCH_QUERY", item.title)
                    putExtra("MOVIE_TITLE", item.title)
                    putExtra("FORCE_GLOBAL_SEARCH", true)
                }
                startActivity(intent)
            } ?: run {
                Toast.makeText(this, "Please wait for details to load", Toast.LENGTH_SHORT).show()
            }
        }
        
        applyTvFocusAnimation(binding.btnBack)
        applyTvFocusAnimation(binding.btnPlay)
        applyTvFocusAnimation(binding.btnMyList)
        applyTvFocusAnimation(binding.btnSearchSources)
    }

    private fun applyTvFocusAnimation(view: View) {
        com.example.zubflix.util.FocusHelper.applyTvFocus(view, scale = 1.08f, elevationDp = 4f)
    }

    private fun loadDetails() {
        if (currentItem == null || currentItem?.title.isNullOrEmpty()) {
            binding.loadingProgress.visibility = View.VISIBLE
            binding.scrollView.visibility = View.GONE
        } else {
            binding.loadingProgress.visibility = View.GONE
            binding.scrollView.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val isSeriesExtra = intent.getBooleanExtra("ITEM_IS_SERIES", false) || currentItem?.isSeries == true
                var source = SourceManager.getSourceByName(sourceName)
                if (source == null) {
                    source = SourceManager.getSelectedSource(this@DetailsActivity)
                }
                val details = withContext(Dispatchers.IO) {
                    var item = source?.getDetails(itemId)
                    if (item == null) {
                        val cleanId = cleanSeriesItemId(itemId)
                        if (cleanId != itemId) {
                            item = source?.getDetails(cleanId)
                        }
                    }
                    if ((item == null || (isSeriesExtra && item.seasons.isNullOrEmpty())) && (itemId.startsWith("tmdb") || sourceName.contains("Nuvio", ignoreCase = true))) {
                        try {
                            val nuvio = com.example.zubflix.sources.NuvioSource(this@DetailsActivity)
                            val nuvioItem = nuvio.getDetails(itemId)
                            if (nuvioItem != null) {
                                item = if (item != null) {
                                    item.copy(
                                        seasons = nuvioItem.seasons?.takeIf { it.isNotEmpty() } ?: item.seasons,
                                        isSeries = item.isSeries || nuvioItem.isSeries
                                    )
                                } else nuvioItem
                            }
                        } catch (e: Exception) {
                            Log.e("DetailsActivity", "Nuvio fallback error", e)
                        }
                    }
                    item
                }

                if (details != null) {
                    val prefill = currentItem
                    val resolvedTitle = if (details.title.isNotBlank() && !details.title.startsWith("tmdb_") && !details.title.startsWith("tmdb:")) {
                        details.title
                    } else {
                        prefill?.title?.takeIf { it.isNotBlank() } ?: details.title
                    }
                    val isSeriesVal = details.isSeries || (prefill?.isSeries == true) || isSeriesExtra
                    val seasonsVal = details.seasons?.takeIf { it.isNotEmpty() } ?: prefill?.seasons
                    val mergedDetails = if (prefill != null) {
                        details.copy(
                            title = resolvedTitle,
                            imageUrl = details.imageUrl?.takeIf { it.isNotBlank() } ?: prefill.imageUrl,
                            backdropUrl = details.backdropUrl?.takeIf { it.isNotBlank() } ?: prefill.backdropUrl,
                            description = details.description?.takeIf { it.isNotBlank() } ?: prefill.description,
                            rating = details.rating?.takeIf { it.isNotBlank() } ?: prefill.rating,
                            year = details.year?.takeIf { it.isNotBlank() } ?: prefill.year,
                            quality = details.quality?.takeIf { it.isNotBlank() } ?: prefill.quality,
                            isSeries = isSeriesVal,
                            seasons = seasonsVal,
                            streamUrl = details.streamUrl?.takeIf { it.isNotBlank() } ?: prefill.streamUrl
                        )
                    } else {
                        details.copy(
                            title = resolvedTitle,
                            isSeries = isSeriesVal,
                            seasons = seasonsVal
                        )
                    }
                    currentItem = mergedDetails
                    displayDetails(mergedDetails)
                    checkMyListStatus()
                } else if (currentItem == null) {
                    Toast.makeText(this@DetailsActivity, "Failed to load item details", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (currentItem == null) {
                    Toast.makeText(this@DetailsActivity, "Error loading details: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun displayDetails(item: StreamingItem) {
        binding.scrollView.visibility = View.VISIBLE

        binding.tvTitle.text = item.title

        // Load Poster / Backdrop image
        val heroImage = item.backdropUrl?.takeIf { it.isNotEmpty() } ?: item.imageUrl
        if (!isDestroyed && !isFinishing) {
            try {
                Glide.with(this)
                    .load(heroImage)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .placeholder(R.drawable.bg_smart_poster)
                    .error(R.drawable.bg_smart_poster)
                    .into(binding.imgBackdrop)
            } catch (e: Exception) {
                // Ignore Glide exception
            }
        }

        // Metadata block
        val ratingText = if (!item.rating.isNullOrEmpty() && item.rating != "0.0") "★ ${item.rating}" else "★ 7.5"
        binding.tvMatch.text = ratingText
        binding.tvYear.text = item.year ?: "2026"
        binding.tvProvider.text = sourceName.ifEmpty { "Nuvio" }

        // Dynamic Genre Capsules
        displayGenres(item.genres)

        // Main description/overview
        binding.tvDescription.text = item.description ?: ""

        // Load initial cast fallback from scraper
        val fallbackCast = item.cast?.map { name ->
            com.example.zubflix.utils.TmdbHelper.CastMember(name, null, null)
        }
        setupCastUI(fallbackCast)

        // Handle Series structure (Seasons / Episodes) vs Movie
        if (item.isSeries && !item.seasons.isNullOrEmpty()) {
            binding.layoutSeasons.visibility = View.VISIBLE
            binding.layoutEpisodes.visibility = View.VISIBLE

            setupSeriesUI(item.seasons)
        } else {
            binding.layoutSeasons.visibility = View.GONE
            binding.layoutEpisodes.visibility = View.GONE
        }
        
        binding.btnPlay.requestFocus() // Focus main CTA for TV navigations

        checkWatchHistoryAndUpdateCta()

        // Fetch rich background details from TMDB
        fetchTmdbExtraDetails(item.title, item.year, item.isSeries)
    }

    private fun displayGenres(genres: List<String>?) {
        binding.genresContainer.removeAllViews()
        if (genres.isNullOrEmpty()) {
            binding.scrollGenres.visibility = View.GONE
            return
        }
        binding.scrollGenres.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        genres.forEach { genre ->
            val textView = android.widget.TextView(this).apply {
                text = genre
                setTextColor(android.graphics.Color.parseColor("#E5E7EB"))
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(
                    (10 * density).toInt(),
                    (3.5 * density).toInt(),
                    (10 * density).toInt(),
                    (3.5 * density).toInt()
                )
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = params
                setBackgroundResource(R.drawable.bg_genre_pill)
            }
            binding.genresContainer.addView(textView)
        }
    }

    private fun setupCastUI(castMembers: List<com.example.zubflix.utils.TmdbHelper.CastMember>?) {
        if (castMembers.isNullOrEmpty()) {
            binding.castSection.visibility = View.GONE
            return
        }
        binding.castSection.visibility = View.VISIBLE
        binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = com.example.zubflix.adapter.CastAdapter(castMembers) { member ->
            val intent = Intent(this@DetailsActivity, com.example.zubflix.PersonDetailsActivity::class.java).apply {
                putExtra("PERSON_ID", member.id ?: -1)
                putExtra("PERSON_NAME", member.name)
                putExtra("PERSON_PROFILE", member.profilePath)
            }
            startActivity(intent)
        }
    }

    private fun fetchTmdbExtraDetails(title: String, year: String?, isSeries: Boolean) {
        lifecycleScope.launch {
            try {
                val tmdbDetails = withContext(Dispatchers.IO) {
                    com.example.zubflix.utils.TmdbHelper.searchAndFetchDetails(
                        this@DetailsActivity,
                        title,
                        year,
                        isSeries
                    )
                }

                if (tmdbDetails != null) {
                    // Update rating with accurate vote average
                    if (!tmdbDetails.rating.isNullOrEmpty() && tmdbDetails.rating != "0.0") {
                        binding.tvMatch.text = "★ ${tmdbDetails.rating}"
                    }

                    // Update description if richer synopsis is found
                    if (!tmdbDetails.overview.isNullOrEmpty()) {
                        binding.tvDescription.text = tmdbDetails.overview
                    }

                    // Refresh genres with standard ones from TMDB
                    if (!tmdbDetails.genres.isNullOrEmpty()) {
                        displayGenres(tmdbDetails.genres)
                    }

                    // Load actors with real headshots and roles
                    if (!tmdbDetails.castMembers.isNullOrEmpty()) {
                        setupCastUI(tmdbDetails.castMembers)
                    }

                    // Blend higher-resolution backdrop image
                    if (!tmdbDetails.backdropPath.isNullOrEmpty() && !isDestroyed && !isFinishing) {
                        try {
                            Glide.with(this@DetailsActivity)
                                .load(tmdbDetails.backdropPath)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                                .placeholder(binding.imgBackdrop.drawable ?: getDrawable(R.drawable.bg_smart_poster))
                                .into(binding.imgBackdrop)
                        } catch (e: Exception) {
                            // Ignore Glide exception
                        }
                    }

                    // Fallback TV Series seasons if currentItem doesn't have seasons yet
                    if (isSeries && (currentItem?.seasons.isNullOrEmpty())) {
                        val tmdbSeasons = withContext(Dispatchers.IO) {
                            com.example.zubflix.utils.TmdbHelper.fetchTvSeasonsAndEpisodes(
                                this@DetailsActivity,
                                tmdbDetails.imdbId,
                                title,
                                year
                            )
                        }
                        if (!tmdbSeasons.isNullOrEmpty()) {
                            val streamingSeasons = tmdbSeasons.map { s ->
                                val encodedT = java.net.URLEncoder.encode(title, "UTF-8")
                                val episodes = s.episodes.map { ep ->
                                    StreamingEpisode(
                                        title = "S${s.seasonNumber}E${ep.episodeNumber} - ${ep.title}",
                                        streamUrl = "nuvio_resolve/series:${tmdbDetails.imdbId ?: ""}:${s.seasonNumber}:${ep.episodeNumber}:$encodedT:${year ?: "0"}",
                                        stillUrl = ep.stillPath,
                                        overview = ep.overview
                                    )
                                }
                                StreamingSeason(
                                    title = s.name,
                                    episodes = episodes,
                                    seasonNumber = s.seasonNumber
                                )
                            }
                            currentItem = currentItem?.copy(seasons = streamingSeasons, isSeries = true)
                            binding.layoutSeasons.visibility = View.VISIBLE
                            binding.layoutEpisodes.visibility = View.VISIBLE
                            setupSeriesUI(streamingSeasons)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupSeriesUI(seasons: List<StreamingSeason>) {
        // Seasons Horizontal Row
        binding.rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val initialSeason = seasons.find { it.seasonNumber == targetSeasonNumber } ?: seasons.firstOrNull()
        val initialPos = if (initialSeason != null) seasons.indexOf(initialSeason) else 0

        seasonAdapter = SeasonAdapter(seasons, initialPos) { selectedSeason, position ->
            val selSeason = currentItem?.seasons?.getOrNull(position) ?: selectedSeason
            if (selSeason.episodes.isEmpty()) {
                loadEpisodesForSeason(selSeason, position)
            } else {
                displayEpisodes(selSeason.episodes, selSeason.seasonNumber)
            }
        }
        binding.rvSeasons.adapter = seasonAdapter
        if (initialPos > 0) {
            binding.rvSeasons.scrollToPosition(initialPos)
        }

        // Episodes List Layout Manager (Based on AppearanceSettings preference)
        val isHorizontalEpLayout = com.example.zubflix.util.AppearanceSettings.getEpisodeLayout(this) == com.example.zubflix.util.AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL
        binding.rvEpisodes.layoutManager = LinearLayoutManager(
            this,
            if (isHorizontalEpLayout) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false
        )
        binding.rvEpisodes.isNestedScrollingEnabled = false
        if (initialSeason != null) {
            if (initialSeason.episodes.isEmpty()) {
                loadEpisodesForSeason(initialSeason, initialPos)
            } else {
                displayEpisodes(initialSeason.episodes, initialSeason.seasonNumber)
            }
        }
    }

    private fun loadEpisodesForSeason(season: StreamingSeason, position: Int) {
        binding.loadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                var source = SourceManager.getSourceByName(sourceName)
                if (source == null) {
                    source = SourceManager.getSelectedSource(this@DetailsActivity)
                }
                if (source == null) return@launch

                val episodes = withContext(Dispatchers.IO) {
                    var eps = source.getSeasonEpisodes(itemId, season.seasonNumber)
                    if (eps.isEmpty() && (itemId.startsWith("tmdb") || sourceName.contains("Nuvio", ignoreCase = true))) {
                        try {
                            val nuvio = com.example.zubflix.sources.NuvioSource(this@DetailsActivity)
                            eps = nuvio.getSeasonEpisodes(itemId, season.seasonNumber)
                        } catch (e: Exception) {
                            Log.e("DetailsActivity", "Error in Nuvio fallback getSeasonEpisodes", e)
                        }
                    }
                    eps
                }
                
                if (episodes.isNotEmpty()) {
                    val updatedSeasons = currentItem?.seasons?.map {
                        if (it.seasonNumber == season.seasonNumber) {
                            it.copy(episodes = episodes)
                        } else {
                            it
                        }
                    } ?: emptyList()
                    
                    currentItem = currentItem?.copy(seasons = updatedSeasons)
                    
                    seasonAdapter = SeasonAdapter(updatedSeasons, position) { selectedSeason, pos ->
                        val selSeason = currentItem?.seasons?.getOrNull(pos) ?: selectedSeason
                        if (selSeason.episodes.isEmpty()) {
                            loadEpisodesForSeason(selSeason, pos)
                        } else {
                            displayEpisodes(selSeason.episodes, selSeason.seasonNumber)
                        }
                    }
                    binding.rvSeasons.adapter = seasonAdapter
                    binding.rvSeasons.scrollToPosition(position)
                    
                    displayEpisodes(episodes, season.seasonNumber)
                } else {
                    Toast.makeText(this@DetailsActivity, "Failed to load episodes for ${season.title}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@DetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun extractEpisodeNumber(episode: StreamingEpisode, defaultIndex: Int): Int {
        if (episode.streamUrl.startsWith("nuvio_resolve/series")) {
            val parts = episode.streamUrl.removePrefix("nuvio_resolve/series:").removePrefix("nuvio_resolve/series").split(":")
            if (parts.size >= 4) {
                val possibleEp = parts.getOrNull(3)?.toIntOrNull() ?: parts.getOrNull(2)?.toIntOrNull()
                if (possibleEp != null) return possibleEp
            }
        }
        val match = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(episode.title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: defaultIndex
    }

    private fun ensureNuvioUrlHasSeasonEpisode(url: String, seasonNumber: Int, episodeNumber: Int): String {
        if (!url.startsWith("nuvio_resolve/series")) return url
        val parts = url.removePrefix("nuvio_resolve/series:").removePrefix("nuvio_resolve/series").split(":")
        return try {
            if (parts.size >= 6) {
                val tmdbId = parts[0]
                val imdbId = parts[1]
                val title = parts[4]
                val year = parts.getOrNull(5) ?: "0"
                "nuvio_resolve/series:$tmdbId:$imdbId:$seasonNumber:$episodeNumber:$title:$year"
            } else if (parts.size >= 5) {
                val id = parts[0]
                val title = parts[3]
                val year = parts.getOrNull(4) ?: "0"
                "nuvio_resolve/series:$id:$seasonNumber:$episodeNumber:$title:$year"
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun displayEpisodes(episodes: List<StreamingEpisode>, seasonNumber: Int) {
        binding.tvEpisodesHeader.text = "SEASON $seasonNumber EPISODES"
        binding.tvEpisodesCount.text = "${episodes.size} EPISODES"

        episodeAdapter = EpisodeAdapter(episodes) { episode ->
            val epIndex = episodes.indexOf(episode)
            val extractedEpNum = extractEpisodeNumber(episode, epIndex + 1)
            playEpisodeItem(episode, seasonNumber, extractedEpNum)
        }
        binding.rvEpisodes.adapter = episodeAdapter

        if (seasonNumber == targetSeasonNumber) {
            val scrollPos = (targetEpisodeNumber - 1).coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
            binding.rvEpisodes.post {
                binding.rvEpisodes.scrollToPosition(scrollPos)
            }
        }
    }

    private fun playTargetEpisode(seasonNumber: Int, episodeNumber: Int) {
        val seasons = currentItem?.seasons
        val targetSeason = seasons?.find { it.seasonNumber == seasonNumber } ?: seasons?.firstOrNull()

        if (targetSeason != null) {
            val epList = targetSeason.episodes
            if (epList.isNotEmpty()) {
                val ep = epList.find { episode ->
                    val epNum = extractEpisodeNumber(episode, -1)
                    epNum == episodeNumber
                }

                if (ep != null) {
                    playEpisodeItem(ep, targetSeason.seasonNumber, episodeNumber)
                    return
                }
            }
            loadEpisodesForSeasonAndPlay(targetSeason, seasonNumber, episodeNumber)
        } else {
            binding.scrollView.post {
                binding.scrollView.smoothScrollTo(0, binding.layoutEpisodes.top)
            }
        }
    }

    private fun playEpisodeItem(episode: StreamingEpisode, seasonNumber: Int, episodeNumber: Int) {
        val imdbIdMatch = Regex("tt\\d+").find(episode.streamUrl)
        val extractedImdbId = imdbIdMatch?.value ?: (if (itemId.startsWith("tt")) itemId else null)
        val targetUrl = ensureNuvioUrlHasSeasonEpisode(episode.streamUrl, seasonNumber, episodeNumber)

        if (targetUrl.startsWith("servers/") ||
            targetUrl.startsWith("nuvio_resolve/") ||
            targetUrl.contains("|") ||
            targetUrl.contains("moviebox", ignoreCase = true) ||
            targetUrl.contains("movielinkbd", ignoreCase = true) ||
            sourceName.contains("MovieBox", ignoreCase = true) ||
            sourceName.contains("MovieLinkBD", ignoreCase = true) ||
            sourceName.contains("CTGMovies", ignoreCase = true) ||
            sourceName.contains("CTG", ignoreCase = true) ||
            sourceName.contains("Nuvio", ignoreCase = true) ||
            sourceName.contains("MovieBlast", ignoreCase = true) ||
            !targetUrl.startsWith("http")) {
            resolveAndPlayLazy(episode.title, targetUrl, seasonNumber, episodeNumber, extractedImdbId)
        } else {
            playVideo(episode.title, targetUrl, listOf(targetUrl), null, seasonNumber, episodeNumber, extractedImdbId)
        }
    }

    private fun loadEpisodesForSeasonAndPlay(season: StreamingSeason, seasonNumber: Int, episodeNumber: Int) {
        binding.loadingProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                var source = SourceManager.getSourceByName(sourceName)
                if (source == null) {
                    source = SourceManager.getSelectedSource(this@DetailsActivity)
                }
                if (source == null) return@launch

                val episodes = withContext(Dispatchers.IO) {
                    source.getSeasonEpisodes(itemId, season.seasonNumber)
                }
                if (episodes.isNotEmpty()) {
                    val updatedSeasons = currentItem?.seasons?.map {
                        if (it.seasonNumber == season.seasonNumber) {
                            it.copy(episodes = episodes)
                        } else {
                            it
                        }
                    } ?: emptyList()
                    currentItem = currentItem?.copy(seasons = updatedSeasons)

                    val ep = episodes.find { episode ->
                        val epNum = extractEpisodeNumber(episode, -1)
                        epNum == episodeNumber
                    }

                    if (ep != null) {
                        playEpisodeItem(ep, seasonNumber, episodeNumber)
                    } else {
                        Toast.makeText(this@DetailsActivity, "Episode S${seasonNumber}:E${episodeNumber} is not released or available yet", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@DetailsActivity, "Failed to load episodes for ${season.title}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@DetailsActivity, "Error loading episode: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun resolveAndPlayLazy(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        val finalUrl = if (seasonNumber != null && episodeNumber != null) {
            ensureNuvioUrlHasSeasonEpisode(lazyUrl, seasonNumber, episodeNumber)
        } else {
            lazyUrl
        }
        if (com.example.zubflix.util.PlaybackSettings.isAutoPlayEnabled(this)) {
            startAutoPlayScraping(title, finalUrl, seasonNumber, episodeNumber, imdbId)
        } else {
            showNuvioStreamSelectorBottomSheet(title, finalUrl, seasonNumber, episodeNumber, imdbId)
        }
    }

    private fun startAutoPlayScraping(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        com.example.zubflix.util.ActiveStreamManager.clear()
        val allSourceList = mutableListOf<Pair<String, String>>()
        var playerLaunched = false

        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Auto-Play Best Stream")
            setMessage("Searching for 1080p stream...")
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }
        try {
            progressDialog.show()
        } catch (_: Exception) {}

        lifecycleScope.launch {
            try {
                Log.d("DetailsActivity", "🔍 Auto-Play scraping requested. sourceName='$sourceName', lazyUrl='$lazyUrl'")
                val rawSource = SourceManager.getSourceByName(sourceName)
                var activeSource = (rawSource as? com.example.zubflix.sources.CachedSource)?.source ?: rawSource

                if (lazyUrl.startsWith("nuvio_resolve/") || sourceName.contains("Nuvio", ignoreCase = true) || sourceName.isEmpty() || activeSource == null) {
                    activeSource = com.example.zubflix.sources.NuvioSource(this@DetailsActivity)
                }
                if (activeSource == null && (sourceName.contains("MovieBox", ignoreCase = true) || lazyUrl.contains("|"))) {
                    activeSource = when {
                        sourceName.equals("MovieBoxIN", ignoreCase = true) -> com.example.zubflix.sources.MovieBoxINSource()
                        sourceName.equals("MovieBoxApp", ignoreCase = true) -> com.example.zubflix.sources.MovieBoxAppSource()
                        else -> com.example.zubflix.sources.MovieBoxWebSource()
                    }
                }

                val onProgressCallback: suspend (Int, Int) -> Unit = { done, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (progressDialog.isShowing) {
                            progressDialog.setMessage("Scraping streams: $done of $total completed...")
                        }
                    }
                }

                val onStreamFoundCallback: suspend (Map<String, String>) -> Unit = { streams ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        val streamList = streams.toList()
                        allSourceList.addAll(streamList)
                        allSourceList.sortByDescending { com.example.zubflix.util.PlaybackSettings.getAutoPlayStreamScore(this@DetailsActivity, it.first, it.second) }

                        com.example.zubflix.util.ActiveStreamManager.addStreams(
                            this@DetailsActivity,
                            streamList.map { com.example.zubflix.util.ActiveStreamManager.StreamItem(it.first, it.second) }
                        )

                        if (!playerLaunched && allSourceList.isNotEmpty()) {
                            playerLaunched = true
                            if (progressDialog.isShowing) {
                                try { progressDialog.dismiss() } catch (_: Exception) {}
                            }
                            val best = allSourceList.first()
                            playVideo(
                                title,
                                best.second,
                                allSourceList.map { it.second },
                                best.first,
                                seasonNumber,
                                episodeNumber,
                                imdbId,
                                allSourceList.map { it.first }
                            )
                        }
                    }
                }

                if (activeSource is com.example.zubflix.sources.CachedSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.SingleCloudStreamPluginSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.NuvioSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.MovieLinkBDSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.MovieBoxWebSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.MovieBoxAppSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.MovieBoxINSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.CtgMoviesSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.CinefreakSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource is com.example.zubflix.sources.RtallySource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl, onProgressCallback, onStreamFoundCallback)
                } else if (activeSource != null) {
                    val resolvedStreams = withContext(Dispatchers.IO) { activeSource.extractVideoLinks(lazyUrl) }
                    if (resolvedStreams.isNotEmpty()) {
                        onStreamFoundCallback(resolvedStreams)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        try { progressDialog.dismiss() } catch (_: Exception) {}
                    }
                    Toast.makeText(this@DetailsActivity, "Error fetching streams: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        try { progressDialog.dismiss() } catch (_: Exception) {}
                    }
                    if (!playerLaunched) {
                        if (allSourceList.isNotEmpty()) {
                            playerLaunched = true
                            val best = allSourceList.first()
                            playVideo(
                                title,
                                best.second,
                                allSourceList.map { it.second },
                                best.first,
                                seasonNumber,
                                episodeNumber,
                                imdbId,
                                allSourceList.map { it.first }
                            )
                        } else {
                            Toast.makeText(this@DetailsActivity, "No streams found for: $title", Toast.LENGTH_LONG).show()
                            showNuvioStreamSelectorBottomSheet(title, lazyUrl, seasonNumber, episodeNumber, imdbId, preScrapedStreams = allSourceList)
                        }
                    }
                }
            }
        }
    }

    private data class ParsedStream(
        val rawName: String,
        val url: String,
        val addonName: String,
        val moniker: String,
        val torrentName: String,
        val attributes: List<String>
    )

    private fun parseStream(name: String, url: String): ParsedStream {
        var workingName = name.trim()
        
        // Parse addonName: starts with "["
        var addonName = "Nuvio"
        if (workingName.startsWith("[")) {
            val endIdx = workingName.indexOf("]")
            if (endIdx > 1) {
                addonName = workingName.substring(1, endIdx)
                workingName = workingName.substring(endIdx + 1).trim()
            }
        }
        
        // Parse moniker: starts with "["
        var moniker = ""
        if (workingName.startsWith("[")) {
            val endIdx = workingName.indexOf("]")
            if (endIdx > 1) {
                moniker = workingName.substring(1, endIdx)
                workingName = workingName.substring(endIdx + 1).trim()
            }
        }
        
        // Parse attributes: ends with ")"
        var attributes = emptyList<String>()
        var torrentName = workingName
        if (workingName.endsWith(")")) {
            val startIdx = workingName.lastIndexOf("(")
            if (startIdx > 0) {
                val attrText = workingName.substring(startIdx + 1, workingName.length - 1)
                attributes = attrText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                torrentName = workingName.substring(0, startIdx).trim()
            }
        }
        
        return ParsedStream(name, url, addonName, moniker, torrentName, attributes)
    }

    private fun getStreamQualityScore(name: String, url: String? = null): Int {
        return com.example.zubflix.util.PlaybackSettings.getAutoPlayStreamScore(this, name, url)
    }

    private fun getScraperCategoryTags(streamName: String): Set<String> {
        val tags = java.util.LinkedHashSet<String>()
        val trimmed = streamName.trim()
        val lower = trimmed.lowercase()

        // 1. Quality Tags
        when {
            lower.contains("2160p") || lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> tags.add("4K")
            lower.contains("1080p") || lower.contains("1080i") || lower.contains("1080") || lower.contains("fhd") || lower.contains("full hd") -> tags.add("1080p")
            lower.contains("720p") || lower.contains("720i") || lower.contains("720") -> tags.add("720p")
            lower.contains("480p") || lower.contains("480i") || lower.contains("480") -> tags.add("480p")
        }

        // 2. BDIX
        if (lower.contains("bdix") || lower.contains("icc") || lower.contains("dhaka") ||
            lower.contains("discovery") || lower.contains("circle") || lower.contains("dflix") || lower.contains("ctg")) {
            tags.add("BDIX")
        }

        // 3. Local Scrapers
        val isLocalScraper = trimmed.startsWith("[Local Scraper]") ||
                lower.contains("dhakaflix") || lower.contains("dflix") ||
                lower.contains("circleftp") || lower.contains("circle ftp") ||
                lower.contains("iccftp") || lower.contains("icc ftp") ||
                lower.contains("ctgmovie") || lower.contains("ctgmovies") ||
                lower.contains("discoveryftp") || lower.contains("discovery ftp")
        if (isLocalScraper) {
            tags.add("Local Scrapers")
        }

        // 4. Penguplay / Stremio Addons
        if (lower.contains("pengu") || lower.contains("stremio") || lower.contains("torrentio") || lower.contains("addon")) {
            tags.add("Penguplay")
        }

        // 5. Specific Sources
        if (lower.contains("vegamovie") || lower.contains("vegamovies")) tags.add("VegaMovies")
        if (lower.contains("ctgmovie") || lower.contains("ctgmovies")) tags.add("CTGMovies")
        if (lower.contains("cinefreak")) tags.add("Cinefreak")
        if (lower.contains("moviebox")) tags.add("MovieBox")
        if (lower.contains("movielink")) tags.add("MovieLinkBD")
        if (lower.contains("castletv") || lower.contains("castle")) tags.add("CastleTV")
        if (lower.contains("hdghar")) tags.add("HDGharTV")
        if (lower.contains("movieblast")) tags.add("MovieBlast")
        if (lower.contains("cloudstream") || lower.contains("cs")) tags.add("CloudStream")

        if (tags.isEmpty()) {
            tags.add("Other")
        }

        return tags
    }

    private fun getScraperCategory(streamName: String): String {
        return getScraperCategoryTags(streamName).firstOrNull() ?: "Other"
    }

    private fun showNuvioStreamSelectorBottomSheet(
        title: String,
        lazyUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null,
        preScrapedStreams: List<Pair<String, String>>? = null
    ) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_nuvio_stream_selector, null)
        dialog.setContentView(dialogView)

        // Make bottom sheet expanded by default
        val bottomSheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
        }

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
        val tvSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.dialog_subtitle)
        val tvCount = dialogView.findViewById<android.widget.TextView>(R.id.stream_count_badge)
        val progressScraping = dialogView.findViewById<android.widget.ProgressBar>(R.id.progress_scraping)
        val rvStreams = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.nuvio_stream_list)
        val emptyStateView = dialogView.findViewById<android.view.View>(R.id.empty_state_view)
        val scrollFilterTabs = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.scroll_filter_tabs)
        val layoutFilterTabs = dialogView.findViewById<android.widget.LinearLayout>(R.id.layout_filter_tabs)
        
        tvTitle.text = "Stream Source"
        tvSubtitle.text = "Searching for: $title"
        tvCount.visibility = android.view.View.GONE
        progressScraping.visibility = android.view.View.VISIBLE
        emptyStateView.visibility = android.view.View.GONE
        scrollFilterTabs.visibility = android.view.View.GONE
        
        rvStreams.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val allSourceList = mutableListOf<Pair<String, String>>()
        val displayedSourceList = mutableListOf<Pair<String, String>>()
        var selectedCategory = "All"
        
        class NuvioStreamViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            val tvStreamIndex: android.widget.TextView = itemView.findViewById(R.id.tv_stream_index)
            val tvAddonProvider: android.widget.TextView = itemView.findViewById(R.id.tv_addon_provider)
            val tvMoniker: android.widget.TextView = itemView.findViewById(R.id.tv_moniker)
            val tvTorrentTitle: android.widget.TextView = itemView.findViewById(R.id.tv_torrent_title)
            val tvBadgeResolution: android.widget.TextView = itemView.findViewById(R.id.tv_badge_resolution)
            val tvBadgeSize: android.widget.TextView = itemView.findViewById(R.id.tv_badge_size)
            val tvBadgeAudio: android.widget.TextView = itemView.findViewById(R.id.tv_badge_audio)
            val tvBadgeQuality: android.widget.TextView = itemView.findViewById(R.id.tv_badge_quality)
            val tvBadgeCodec: android.widget.TextView = itemView.findViewById(R.id.tv_badge_codec)
            val viewAccentBar: android.view.View = itemView.findViewById(R.id.view_accent_bar)

            init {
                itemView.isFocusable = true
                itemView.isClickable = true
                itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.scaleX = 1.02f
                        view.scaleY = 1.02f
                        view.translationZ = 4f
                    } else {
                        view.scaleX = 1.0f
                        view.scaleY = 1.0f
                        view.translationZ = 0f
                    }
                }
            }
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<NuvioStreamViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NuvioStreamViewHolder {
                val view = layoutInflater.inflate(R.layout.item_nuvio_stream, parent, false)
                return NuvioStreamViewHolder(view)
            }

            override fun onBindViewHolder(holder: NuvioStreamViewHolder, position: Int) {
                val item = displayedSourceList[position]
                val parsed = parseStream(item.first, item.second)
                
                // Set index number (01, 02, 03...)
                val indexStr = (position + 1).toString().padStart(2, '0')
                holder.tvStreamIndex.text = indexStr

                val addonDisplayName = parsed.addonName.ifEmpty { "Unknown Addon" }
                holder.tvAddonProvider.text = addonDisplayName
                
                // Set distinct background and text color based on provider/addon type
                val addonStyle = com.example.zubflix.util.StreamUIUtils.getAddonStyle(addonDisplayName)
                holder.tvAddonProvider.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(addonStyle.bgHex))
                holder.tvAddonProvider.setTextColor(android.graphics.Color.parseColor(addonStyle.textHex))
                
                // Set accent bar color matching the provider style
                holder.viewAccentBar.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(addonStyle.bgHex))

                if (parsed.moniker.isNotEmpty()) {
                    holder.tvMoniker.text = parsed.moniker
                    holder.tvMoniker.visibility = android.view.View.VISIBLE
                } else {
                    holder.tvMoniker.visibility = android.view.View.GONE
                }
                
                holder.tvTorrentTitle.text = com.example.zubflix.util.StreamUIUtils.sanitizeTitle(parsed.torrentName, parsed.attributes)
                
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

                val lowerStream = (item.first + " " + item.second).lowercase()
                if (lowerStream.contains("preview") || lowerStream.contains("_preview_") || lowerStream.contains("sample") || lowerStream.contains("trailer")) {
                    holder.tvBadgeQuality.text = "⚠️ Preview / Sample"
                    holder.tvBadgeQuality.visibility = android.view.View.VISIBLE
                }
                
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    val allUrls = displayedSourceList.map { it.second }
                    val allNames = displayedSourceList.map { it.first }
                    playVideo(title, item.second, allUrls, item.first, seasonNumber, episodeNumber, imdbId, allNames)
                }
                
                // TV focus
                holder.itemView.isFocusable = true
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.animate().scaleX(1.02f).scaleY(1.02f).translationZ(4f).setDuration(150).start()
                        view.setBackgroundResource(R.drawable.bg_item_focus)
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).start()
                        view.background = null
                    }
                }
            }

            override fun getItemCount() = displayedSourceList.size
        }
        
        rvStreams.adapter = adapter
        dialog.show()

        fun updateStreamFilterUI() {
            val tagCounts = java.util.LinkedHashMap<String, Int>()
            allSourceList.forEach { item ->
                val tags = getScraperCategoryTags(item.first)
                tags.forEach { tag ->
                    tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                }
            }

            if (allSourceList.isNotEmpty()) {
                scrollFilterTabs.visibility = android.view.View.VISIBLE
            } else {
                scrollFilterTabs.visibility = android.view.View.GONE
            }

            layoutFilterTabs.removeAllViews()

            val categories = mutableListOf<Pair<String, Int>>()
            categories.add(Pair("All", allSourceList.size))

            // Preferred chip display order
            val visibleQualities = com.example.zubflix.util.PlaybackSettings.getVisibleQualityChips(this@DetailsActivity)
            val qualityKeys = listOf("1080p", "720p", "4K", "480p")
            val priorityKeys = listOf("1080p", "720p", "4K", "480p", "MovieBox", "BDIX", "Penguplay", "Local Scrapers")
            
            priorityKeys.forEach { key ->
                if (qualityKeys.contains(key) && !visibleQualities.contains(key)) {
                    // User disabled this quality chip in Settings
                    return@forEach
                }
                if (tagCounts.containsKey(key)) {
                    categories.add(Pair(key, tagCounts[key]!!))
                }
            }

            tagCounts.forEach { (tag, count) ->
                if (!priorityKeys.contains(tag)) {
                    categories.add(Pair(tag, count))
                }
            }

            val validCatNames = categories.map { it.first }
            if (selectedCategory != "All" && !validCatNames.contains(selectedCategory)) {
                selectedCategory = "All"
            }

            categories.forEach { (cat, count) ->
                val tabView = android.widget.TextView(this@DetailsActivity).apply {
                    text = "$cat ($count)"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    val padH = (14 * resources.displayMetrics.density).toInt()
                    val padV = (8 * resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                    
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    layoutParams = lp

                    tag = cat

                    val isSelected = (cat == selectedCategory)
                    if (isSelected) {
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundResource(R.drawable.bg_season_chip_selected)
                    } else {
                        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                        setBackgroundResource(R.drawable.bg_season_chip_unselected)
                    }

                    isFocusable = true
                    isClickable = true

                    setOnClickListener {
                        if (selectedCategory != cat) {
                            selectedCategory = cat
                            updateStreamFilterUI()
                        }
                    }

                    setOnFocusChangeListener { v, hasFocus ->
                        if (hasFocus) {
                            v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                            if (selectedCategory != cat) {
                                selectedCategory = cat
                                // Update chip selection UI
                                for (i in 0 until layoutFilterTabs.childCount) {
                                    val child = layoutFilterTabs.getChildAt(i) as? android.widget.TextView ?: continue
                                    val childCat = child.tag as? String ?: continue
                                    if (childCat == selectedCategory) {
                                        child.setTextColor(android.graphics.Color.WHITE)
                                        child.setBackgroundResource(R.drawable.bg_season_chip_selected)
                                    } else {
                                        child.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                                        child.setBackgroundResource(R.drawable.bg_season_chip_unselected)
                                    }
                                }
                                // Filter stream list
                                displayedSourceList.clear()
                                if (selectedCategory == "All") {
                                    displayedSourceList.addAll(allSourceList)
                                } else {
                                    displayedSourceList.addAll(allSourceList.filter { getScraperCategoryTags(it.first).contains(selectedCategory) })
                                }
                                adapter.notifyDataSetChanged()
                                emptyStateView.visibility = if (displayedSourceList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                            }
                            scrollFilterTabs.post {
                                val scrollTo = v.left - (scrollFilterTabs.width / 2) + (v.width / 2)
                                scrollFilterTabs.smoothScrollTo(scrollTo.coerceAtLeast(0), 0)
                            }
                        } else {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                        }
                    }
                }
                layoutFilterTabs.addView(tabView)
            }

            displayedSourceList.clear()
            if (selectedCategory == "All") {
                displayedSourceList.addAll(allSourceList)
            } else {
                displayedSourceList.addAll(allSourceList.filter { getScraperCategoryTags(it.first).contains(selectedCategory) })
            }

            adapter.notifyDataSetChanged()

            emptyStateView.visibility = if (displayedSourceList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            tvCount.text = "${allSourceList.size} found"
            tvCount.visibility = if (allSourceList.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        var scrapingJob: kotlinx.coroutines.Job? = null

        dialog.setOnDismissListener {
            scrapingJob?.cancel()
            scrapingJob = null
        }

        if (preScrapedStreams != null) {
            progressScraping.visibility = android.view.View.GONE
            allSourceList.addAll(preScrapedStreams)
            allSourceList.sortByDescending { getStreamQualityScore(it.first) }
            updateStreamFilterUI()
            if (allSourceList.isEmpty()) {
                tvSubtitle.text = "No streams found for: $title"
                emptyStateView.visibility = android.view.View.VISIBLE
                tvCount.visibility = android.view.View.GONE
            } else {
                tvSubtitle.text = "Select a stream for: $title"
            }
        } else {
            scrapingJob = lifecycleScope.launch {
                try {
                    Log.d("DetailsActivity", "🔍 Scraping requested. sourceName='$sourceName', lazyUrl='$lazyUrl'")
                    val rawSource = SourceManager.getSourceByName(sourceName)
                    var activeSource = (rawSource as? com.example.zubflix.sources.CachedSource)?.source 
                        ?: rawSource 

                    if (lazyUrl.startsWith("nuvio_resolve/") || sourceName.contains("Nuvio", ignoreCase = true) || sourceName.isEmpty() || activeSource == null) {
                        activeSource = com.example.zubflix.sources.NuvioSource(this@DetailsActivity)
                    }
                if (activeSource == null && (sourceName.contains("MovieBox", ignoreCase = true) || lazyUrl.contains("|"))) {
                    activeSource = when {
                        sourceName.equals("MovieBoxIN", ignoreCase = true) -> com.example.zubflix.sources.MovieBoxINSource()
                        sourceName.equals("MovieBoxApp", ignoreCase = true) -> com.example.zubflix.sources.MovieBoxAppSource()
                        else -> com.example.zubflix.sources.MovieBoxWebSource()
                    }
                }

                Log.d("DetailsActivity", "Active source resolved to: ${activeSource?.javaClass?.simpleName ?: "NULL"}")

                if (activeSource is com.example.zubflix.sources.CachedSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (total > 0) {
                                    tvSubtitle.text = "Scraping ${activeSource.name}: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.SingleCloudStreamPluginSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (total > 0) {
                                    tvSubtitle.text = "Scraping ${activeSource.name}: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.NuvioSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (total > 0) {
                                    tvSubtitle.text = "Scraping: $done out of $total sources done"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.MovieLinkBDSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping MovieLinkBD: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.MovieBoxWebSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping MovieBoxWeb: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.MovieBoxAppSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping MovieBoxApp: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.CtgMoviesSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping CTGMovies: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.CinefreakSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping Cinefreak: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.MovieBoxINSource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping MovieBoxIN: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource is com.example.zubflix.sources.RtallySource) {
                    activeSource.extractVideoLinksStreaming(lazyUrl,
                        onProgress = { done, total ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (done < total) {
                                    tvSubtitle.text = "Scraping Rtally: $done of $total links"
                                }
                            }
                        },
                        onStreamFound = { streams ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                emptyStateView.visibility = android.view.View.GONE
                                allSourceList.addAll(streams.toList())
                                allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                                updateStreamFilterUI()
                            }
                        }
                    )
                } else if (activeSource != null) {
                    val resolvedStreams = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        activeSource.extractVideoLinks(lazyUrl)
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (resolvedStreams.isNotEmpty()) {
                            emptyStateView.visibility = android.view.View.GONE
                            allSourceList.addAll(resolvedStreams.toList())
                            allSourceList.sortByDescending { getStreamQualityScore(it.first, it.second) }
                            updateStreamFilterUI()
                        }
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvSubtitle.text = "Error fetching streams: ${e.message}"
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressScraping.visibility = android.view.View.GONE
                    if (allSourceList.isEmpty()) {
                        tvSubtitle.text = "No streams found for: $title"
                        emptyStateView.visibility = android.view.View.VISIBLE
                        tvCount.visibility = android.view.View.GONE
                    } else {
                        tvSubtitle.text = "Select a stream for: $title"
                    }
                }
            }
        }
    }
    }

    private fun showSourceSelectorBottomSheet(
        title: String,
        sources: Map<String, String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_source_selector, null)
        dialog.setContentView(dialogView)

        val rvSources = dialogView.findViewById<RecyclerView>(R.id.source_list)
        rvSources.layoutManager = LinearLayoutManager(this)
        
        val sourceList = sources.toList()
        
        class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: android.widget.TextView = itemView.findViewById(R.id.source_name)
        }

        rvSources.adapter = object : RecyclerView.Adapter<SourceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
                val view = layoutInflater.inflate(R.layout.item_source_selection, parent, false)
                return SourceViewHolder(view)
            }

            override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
                val (name, url) = sourceList[position]
                holder.tvName.text = name
                
                // Styling active source item on focus/touch
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    playVideo(title, url, listOf(url), name, seasonNumber, episodeNumber, imdbId)
                }
                
                holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        holder.itemView.setBackgroundResource(R.drawable.bg_item_focus)
                    } else {
                        holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                }
            }

            override fun getItemCount() = sourceList.size
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            rvSources.requestFocus()
        }

        dialog.show()
    }

    private fun playVideo(
        title: String,
        url: String,
        allUrls: List<String> = listOf(url),
        streamName: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null,
        allNames: List<String> = emptyList(),
        forceRestart: Boolean = false
    ) {
        val streamItems = if (allNames.isNotEmpty() && allNames.size == allUrls.size) {
            allNames.zip(allUrls).map { com.example.zubflix.util.ActiveStreamManager.StreamItem(it.first, it.second) }
        } else {
            allUrls.map { com.example.zubflix.util.ActiveStreamManager.StreamItem(streamName ?: "Stream", it) }
        }
        com.example.zubflix.util.ActiveStreamManager.setStreams(this, streamItems)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_URL", url)
            putStringArrayListExtra("VIDEO_URLS", ArrayList(allUrls))
            putStringArrayListExtra("VIDEO_NAMES", ArrayList(allNames))
            putExtra("VIDEO_TITLE", title)
            putExtra("ITEM_ID", itemId)
            putExtra("IMAGE_URL", currentItem?.imageUrl)
            putExtra("BACKDROP_URL", currentItem?.backdropUrl)
            putExtra("IS_SERIES", currentItem?.isSeries ?: false)
            putExtra("SOURCE_NAME", sourceName)
            if (forceRestart) {
                putExtra("FORCE_RESTART", true)
            }
            if (seasonNumber != null) {
                putExtra("SEASON_NUMBER", seasonNumber)
            }
            if (episodeNumber != null) {
                putExtra("EPISODE_NUMBER", episodeNumber)
            }
            if (streamName != null) {
                putExtra("SELECTED_STREAM_NAME", streamName)
            }
            if (imdbId != null) {
                putExtra("IMDB_ID", imdbId)
            }
        }
        startActivity(intent)
    }

    private fun checkMyListStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@DetailsActivity).myListDao()
            isInMyList = dao.isItemInList(itemId)
            withContext(Dispatchers.Main) {
                updateMyListButton()
            }
        }
    }

    private fun toggleMyList() {
        val item = currentItem ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@DetailsActivity).myListDao()
            if (isInMyList) {
                dao.deleteById(itemId)
                isInMyList = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DetailsActivity, "Removed from My List", Toast.LENGTH_SHORT).show()
                }
            } else {
                val entity = MyListEntity(
                    itemId = itemId,
                    title = item.title,
                    imageUrl = item.imageUrl,
                    isSeries = item.isSeries,
                    sourceName = sourceName
                )
                dao.insert(entity)
                isInMyList = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DetailsActivity, "Added to My List", Toast.LENGTH_SHORT).show()
                }
            }
            withContext(Dispatchers.Main) {
                updateMyListButton()
            }
        }
    }

    private fun playEpisodeItem(episode: StreamingEpisode, seasonNumber: Int) {
        val epIndex = currentItem?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }?.episodes?.indexOf(episode) ?: 0
        val extractedEpNum = extractEpisodeNumber(episode, epIndex + 1)
        playEpisodeItem(episode, seasonNumber, extractedEpNum)
    }

    private fun updateMyListButton() {
        if (isInMyList) {
            binding.btnMyList.setBackgroundResource(R.drawable.bg_circle_action_btn_active)
            binding.ivMyListIcon.setImageResource(R.drawable.ic_heart)
            binding.ivMyListIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF1E27"))
        } else {
            binding.btnMyList.setBackgroundResource(R.drawable.bg_circle_action_btn)
            binding.ivMyListIcon.setImageResource(R.drawable.ic_heart_outline)
            binding.ivMyListIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        fun start(context: android.content.Context, item: StreamingItem) {
            val intent = Intent(context, DetailsActivity::class.java).apply {
                putExtra("ITEM_ID", item.id)
                putExtra("SOURCE_NAME", item.sourceName ?: "")
                putExtra("ITEM_TITLE", item.title)
                putExtra("ITEM_POSTER", item.imageUrl)
                putExtra("ITEM_BACKDROP", item.backdropUrl)
                putExtra("ITEM_YEAR", item.year)
                putExtra("ITEM_RATING", item.rating)
                putExtra("ITEM_QUALITY", item.quality)
                putExtra("ITEM_DESCRIPTION", item.description)
                putExtra("ITEM_IS_SERIES", item.isSeries)
                putExtra("ITEM_STREAM_URL", item.streamUrl)
            }
            context.startActivity(intent)
        }
    }
}

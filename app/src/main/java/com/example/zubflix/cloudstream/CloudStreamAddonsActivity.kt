package com.example.zubflix.cloudstream

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.zubflix.SourceManager
import kotlinx.coroutines.launch

class CloudStreamAddonsActivity : AppCompatActivity() {

    private enum class ScreenState {
        REPOS_LIST,
        REPO_DETAILS
    }

    private lateinit var rvItems: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitleStats: TextView
    private lateinit var btnCheckUpdates: View
    private lateinit var btnAddRepo: View
    private lateinit var bannerUpdates: LinearLayout
    private lateinit var tvUpdateBannerText: TextView
    private lateinit var btnUpdateAll: MaterialButton

    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var scrollFilterChips: HorizontalScrollView
    private lateinit var layoutFilterChips: LinearLayout
    private lateinit var layoutSearch: LinearLayout

    private var screenState = ScreenState.REPOS_LIST
    private var selectedRepo: CloudStreamRepo? = null
    private var selectedCategoryTab = 0
    private var availableManifests = mutableListOf<CloudStreamPluginManifest>()
    private var availableUpdates = mutableListOf<PluginUpdateInfo>()

    private val adapter = ExtensionAdapter(
        onActionClick = { item -> handleAction(item) },
        onDeleteClick = { item -> handleDelete(item) },
        onUpdateClick = { item -> handleUpdateSingle(item) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloudstream_addons)

        tvTitle = findViewById(R.id.tv_title)
        tvSubtitleStats = findViewById(R.id.tv_subtitle_stats)
        rvItems = findViewById(R.id.rv_items)
        progressBar = findViewById(R.id.progress_bar)
        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        btnAddRepo = findViewById(R.id.btn_add_repo)
        bannerUpdates = findViewById(R.id.banner_updates)
        tvUpdateBannerText = findViewById(R.id.tv_update_banner_text)
        btnUpdateAll = findViewById(R.id.btn_update_all)

        etSearch = findViewById(R.id.et_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        scrollFilterChips = findViewById(R.id.scroll_filter_chips)
        layoutFilterChips = findViewById(R.id.layout_filter_chips)
        layoutSearch = findViewById(R.id.layout_search)

        findViewById<ImageButton>(R.id.btn_back)?.setOnClickListener {
            onBackPressed()
        }

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = adapter

        btnCheckUpdates.setOnClickListener {
            checkPluginUpdates()
        }

        btnAddRepo.setOnClickListener {
            showAddRepoDialog()
        }

        btnUpdateAll.setOnClickListener {
            handleUpdateAll()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                if (screenState == ScreenState.REPOS_LIST) {
                    loadRepositories()
                } else {
                    filterAndDisplayPlugins()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.text = null
        }

        val cardHomeCatalogToggle = findViewById<View>(R.id.card_home_catalog_toggle)
        val switchHomeCatalog = findViewById<SwitchMaterial>(R.id.switch_home_catalog)
        if (switchHomeCatalog != null) {
            val thumbStates = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(
                    android.graphics.Color.parseColor("#FFFFFF"),
                    android.graphics.Color.parseColor("#9CA3AF")
                )
            )
            val trackStates = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(
                    android.graphics.Color.parseColor("#E50914"),
                    android.graphics.Color.parseColor("#30363D")
                )
            )
            switchHomeCatalog.thumbTintList = thumbStates
            switchHomeCatalog.trackTintList = trackStates

            switchHomeCatalog.isChecked = SourceManager.isCloudStreamHomeCatalogEnabled(this)
            switchHomeCatalog.setOnCheckedChangeListener { _, isChecked ->
                SourceManager.setCloudStreamHomeCatalogEnabled(this, isChecked)
                val statusText = if (isChecked) {
                    "CloudStream Home Catalogs ENABLED"
                } else {
                    "CloudStream Home Catalogs DISABLED (Plugins active for Watch Now only)"
                }
                Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
            }

            cardHomeCatalogToggle?.setOnClickListener {
                switchHomeCatalog.toggle()
            }
        }

        loadData()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screenState == ScreenState.REPO_DETAILS) {
            etSearch.text = null
            screenState = ScreenState.REPOS_LIST
            selectedRepo = null
            selectedCategoryTab = 0
            loadData()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun loadData() {
        updateUiForState()
        when (screenState) {
            ScreenState.REPOS_LIST -> loadRepositories()
            ScreenState.REPO_DETAILS -> loadRepoPlugins()
        }
    }

    private fun updateUiForState() {
        if (screenState == ScreenState.REPOS_LIST) {
            scrollFilterChips.visibility = View.GONE
            layoutSearch.visibility = View.VISIBLE
            btnAddRepo.visibility = View.VISIBLE
            tvTitle.text = "Extensions"
        } else {
            scrollFilterChips.visibility = View.VISIBLE
            layoutSearch.visibility = View.VISIBLE
            btnAddRepo.visibility = View.GONE
            tvTitle.text = selectedRepo?.name ?: "Plugins"
            setupCategoryChips()
        }
    }

    private fun setupCategoryChips() {
        layoutFilterChips.removeAllViews()
        val categories = listOf("All", "Installed", "Movies", "TV Series", "Anime", "Asian Dramas", "Cartoons", "Others")

        for (i in categories.indices) {
            val textView = TextView(this).apply {
                text = categories[i]
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dpToPx(8)
                }
                layoutParams = params
                
                if (selectedCategoryTab == i) {
                    setBackgroundResource(R.drawable.bg_chip_selected)
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                } else {
                    setBackgroundResource(R.drawable.bg_chip_unselected)
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setTypeface(null, Typeface.NORMAL)
                }

                setOnClickListener {
                    selectedCategoryTab = i
                    setupCategoryChips()
                    filterAndDisplayPlugins()
                }
            }
            layoutFilterChips.addView(textView)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun loadRepositories() {
        progressBar.visibility = View.GONE
        val repos = CloudStreamPluginManager.getRepositories(this)
        val installed = CloudStreamPluginManager.getInstalledPlugins(this)
        tvSubtitleStats.text = "${installed.size} installed • ${repos.size} repos"

        val query = etSearch.text?.toString()?.trim() ?: ""
        val filteredRepos = if (query.isNotEmpty()) {
            repos.filter { it.name.contains(query, ignoreCase = true) || it.description?.contains(query, ignoreCase = true) == true }
        } else {
            repos
        }

        val items = filteredRepos.map { repo ->
            ExtensionDisplayItem(
                id = repo.url,
                name = repo.name,
                description = repo.description ?: repo.url,
                isInstalled = true,
                isEnabled = true,
                pluginUrl = repo.url,
                isRepoItem = true,
                repo = repo
            )
        }
        adapter.setItems(items)
    }

    private fun loadRepoPlugins() {
        val repo = selectedRepo ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = CloudStreamPluginManager.fetchRepoManifest(repo.url)
                availableManifests = list.toMutableList()
                filterAndDisplayPlugins()
            } catch (e: Exception) {
                Toast.makeText(this@CloudStreamAddonsActivity, "Failed to load repository plugins", Toast.LENGTH_SHORT).show()
                screenState = ScreenState.REPOS_LIST
                loadData()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun filterAndDisplayPlugins() {
        val installed = CloudStreamPluginManager.getInstalledPlugins(this)
        val installedUrls = installed.mapNotNull { it.repoUrl }.toSet()
        val installedNames = installed.map { it.name.lowercase() }.toSet()

        val searchQuery = etSearch.text?.toString()?.trim() ?: ""

        val filtered = availableManifests.filter { manifest ->
            val isInstalled = installedUrls.contains(manifest.pluginUrl) ||
                    installedNames.contains(manifest.name.lowercase())

            if (searchQuery.isNotEmpty()) {
                val matchesName = manifest.name.contains(searchQuery, ignoreCase = true)
                val matchesDesc = manifest.description?.contains(searchQuery, ignoreCase = true) == true
                if (!matchesName && !matchesDesc) return@filter false
            }

            val tvTypes = manifest.tvTypes ?: emptyList()
            when (selectedCategoryTab) {
                0 -> true // All
                1 -> isInstalled // Installed
                2 -> tvTypes.any { it.contains("Movie", ignoreCase = true) } // Movies
                3 -> tvTypes.any { it.contains("TvSeries", ignoreCase = true) || it.contains("Documentary", ignoreCase = true) } // TV Series
                4 -> tvTypes.any { it.contains("Anime", ignoreCase = true) || it.contains("OVA", ignoreCase = true) } // Anime
                5 -> tvTypes.any { it.contains("Drama", ignoreCase = true) || it.contains("Asian", ignoreCase = true) } // Asian Dramas
                6 -> tvTypes.any { it.contains("Cartoon", ignoreCase = true) } // Cartoons
                7 -> { // Others
                    val matchesAny = tvTypes.any {
                        it.contains("Movie", ignoreCase = true) ||
                        it.contains("TvSeries", ignoreCase = true) ||
                        it.contains("Documentary", ignoreCase = true) ||
                        it.contains("Anime", ignoreCase = true) ||
                        it.contains("OVA", ignoreCase = true) ||
                        it.contains("Drama", ignoreCase = true) ||
                        it.contains("Asian", ignoreCase = true) ||
                        it.contains("Cartoon", ignoreCase = true)
                    }
                    !matchesAny
                }
                else -> true
            }
        }

        val displayItems = filtered.map { manifest ->
            val isInstalled = installedUrls.contains(manifest.pluginUrl) ||
                    installedNames.contains(manifest.name.lowercase())
            val matchingInstalled = installed.firstOrNull {
                it.repoUrl == manifest.pluginUrl || it.name.equals(manifest.name, ignoreCase = true)
            }
            val id = matchingInstalled?.id ?: manifest.pluginUrl.hashCode().toString()
            val isEnabled = matchingInstalled?.isEnabled ?: true

            val updateInfo = availableUpdates.firstOrNull { update ->
                update.pluginId == id || update.pluginName.equals(manifest.name, ignoreCase = true) ||
                (matchingInstalled != null && update.pluginId == matchingInstalled.id)
            }

            ExtensionDisplayItem(
                id = id,
                name = manifest.name,
                description = manifest.description ?: "No description available",
                isInstalled = isInstalled,
                isEnabled = isEnabled,
                pluginUrl = manifest.pluginUrl,
                manifest = manifest,
                hasUpdate = updateInfo != null,
                updateInfo = updateInfo,
                subtitle = getLanguageDisplay(manifest.language) + " • v" + manifest.version +
                        (if (manifest.fileSize != null && manifest.fileSize > 0) " • " + formatSize(manifest.fileSize) else "")
            )
        }

        // Update statistics counter
        val totalRepoInstalled = displayItems.count { it.isInstalled }
        val totalRepoAvailable = displayItems.size
        tvSubtitleStats.text = "$totalRepoInstalled installed • $totalRepoAvailable available"

        adapter.setItems(displayItems)
    }

    private fun checkPluginUpdates() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val updates = CloudStreamPluginManager.checkForPluginUpdates(this@CloudStreamAddonsActivity)
                availableUpdates.clear()
                availableUpdates.addAll(updates)

                if (updates.isNotEmpty()) {
                    tvUpdateBannerText.text = "${updates.size} Plugin update${if (updates.size > 1) "s" else ""} available!"
                    bannerUpdates.visibility = View.VISIBLE
                    Toast.makeText(this@CloudStreamAddonsActivity, "Found ${updates.size} plugin update(s)!", Toast.LENGTH_SHORT).show()
                } else {
                    bannerUpdates.visibility = View.GONE
                    Toast.makeText(this@CloudStreamAddonsActivity, "All installed plugins are up to date!", Toast.LENGTH_SHORT).show()
                }
                if (screenState == ScreenState.REPO_DETAILS) {
                    filterAndDisplayPlugins()
                }
            } catch (e: Exception) {
                Toast.makeText(this@CloudStreamAddonsActivity, "Failed to check for updates: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun handleUpdateAll() {
        if (availableUpdates.isEmpty()) return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val updatedCount = CloudStreamPluginManager.updateAllPlugins(this@CloudStreamAddonsActivity, availableUpdates)
            progressBar.visibility = View.GONE
            Toast.makeText(this@CloudStreamAddonsActivity, "Updated $updatedCount plugin(s) successfully!", Toast.LENGTH_SHORT).show()
            availableUpdates.clear()
            bannerUpdates.visibility = View.GONE
            loadData()
        }
    }

    private fun handleUpdateSingle(item: ExtensionDisplayItem) {
        val updateInfo = item.updateInfo ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = CloudStreamPluginManager.updatePlugin(this@CloudStreamAddonsActivity, updateInfo)
            progressBar.visibility = View.GONE
            if (success) {
                Toast.makeText(this@CloudStreamAddonsActivity, "'${item.name}' updated successfully!", Toast.LENGTH_SHORT).show()
                availableUpdates.removeAll { it.pluginId == item.id || it.pluginName.equals(item.name, ignoreCase = true) }
                if (availableUpdates.isEmpty()) {
                    bannerUpdates.visibility = View.GONE
                } else {
                    tvUpdateBannerText.text = "${availableUpdates.size} Plugin update${if (availableUpdates.size > 1) "s" else ""} available!"
                }
                loadData()
            } else {
                Toast.makeText(this@CloudStreamAddonsActivity, "Failed to update '${item.name}'", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAction(item: ExtensionDisplayItem) {
        if (item.isRepoItem) {
            screenState = ScreenState.REPO_DETAILS
            selectedRepo = item.repo
            loadData()
            return
        }

        if (item.isInstalled) {
            CloudStreamPluginManager.togglePluginEnabled(this, item.id)
            Toast.makeText(this, "Plugin status updated", Toast.LENGTH_SHORT).show()
            filterAndDisplayPlugins()
        } else if (item.manifest != null) {
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val success = CloudStreamPluginManager.downloadAndInstallPlugin(
                    context = this@CloudStreamAddonsActivity,
                    manifest = item.manifest
                )
                progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@CloudStreamAddonsActivity, "'${item.name}' installed successfully!", Toast.LENGTH_SHORT).show()
                    filterAndDisplayPlugins()
                } else {
                    Toast.makeText(this@CloudStreamAddonsActivity, "Failed to download or install '${item.name}'", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleDelete(item: ExtensionDisplayItem) {
        if (item.isRepoItem) {
            CloudStreamPluginManager.removeRepository(this, item.id)
            Toast.makeText(this, "Repository removed", Toast.LENGTH_SHORT).show()
            loadData()
        } else {
            CloudStreamPluginManager.deletePlugin(this, item.id)
            Toast.makeText(this, "Plugin uninstalled", Toast.LENGTH_SHORT).show()
            filterAndDisplayPlugins()
        }
    }

    private fun showAddRepoDialog() {
        val input = EditText(this)
        input.hint = "https://raw.githubusercontent.com/.../plugins.json"
        input.setPadding(32, 24, 32, 24)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add CloudStream Repository URL")
            .setMessage("Paste a CloudStream repository manifest JSON URL:")
            .setView(input)
            .setPositiveButton("Add Repo") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    // Translate shortcodes
                    val translatedUrl = when (url.lowercase()) {
                        "cncrepo" -> "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
                        "anicompat" -> "https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json"
                        "phisherrepo" -> "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
                        "csx" -> "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"
                        "cakes" -> "https://codeberg.org/CakesTwix/cloudstream-extensions-uk/raw/branch/master/repo.json"
                        else -> url
                    }
                    url = translatedUrl

                    var name = "Custom Repository"
                    if (url.contains("CranberrySoup/AniyomiCompat", ignoreCase = true)) {
                        name = "Aniyomi Compat"
                    } else if (url.contains("NivinCNC/CNCVerse", ignoreCase = true) || url.contains("/CNC.json", ignoreCase = true)) {
                        name = "CNC Repo(All Language)"
                    } else if (url.contains("Gian-Fr/ItalianProviders", ignoreCase = true) || url.contains("Gian-Fr/ItalianProvider", ignoreCase = true)) {
                        name = "Italian providers repository"
                    } else if (url.contains("CakesTwix/cloudstream-extensions", ignoreCase = true)) {
                        name = "CakesTwix Providers Repository Codeberg"
                    } else if (url.contains("techtanic/SkillShare", ignoreCase = true)) {
                        name = "SkillShare-Repo"
                    } else if (url.contains("SaurabhKaperwan/CSX", ignoreCase = true)) {
                        name = "Megix Repo(Hindi & English)"
                    } else if (url.contains("git.disroot.org/ayza/FStream", ignoreCase = true)) {
                        name = "No name"
                    } else if (url.contains("phisher98/cloudstream-extensions-phisher", ignoreCase = true)) {
                        name = "Phisher Repo"
                    } else if (url.contains("recloudstream/extensions", ignoreCase = true)) {
                        name = "Cloudstream providers repository"
                    } else if (url.contains("hexated", ignoreCase = true)) {
                        name = "Hexated Repository"
                    } else {
                        // Extract name from URL if possible
                        try {
                            val parts = url.split("/")
                            if (parts.size >= 5) {
                                name = parts[4].replace("-", " ").capitalize()
                            }
                        } catch (e: Exception) {}
                    }
                    val repo = CloudStreamRepo(
                        name = name,
                        url = url
                    )
                    CloudStreamPluginManager.addRepository(this, repo)
                    Toast.makeText(this, "Repository added successfully", Toast.LENGTH_SHORT).show()
                    loadData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstallCustomCs3Dialog() {
        val input = EditText(this)
        input.hint = "http://127.0.0.1:8080/manifest.json or https://.../manifest.json"
        input.setPadding(32, 24, 32, 24)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add CNCVerse Bridge / Stream Addon")
            .setMessage("Paste CNCVerse Bridge or Stremio manifest URL (e.g., http://127.0.0.1:8080/manifest.json):")
            .setView(input)
            .setPositiveButton("Add Addon") { _, _ ->
                val rawUrl = input.text.toString().trim()
                if (rawUrl.isNotEmpty()) {
                    val url = rawUrl.replace("stremio://", "https://")
                    if (url.endsWith("manifest.json") || url.contains("/manifest.json")) {
                        val name = if (url.contains("cncverse", ignoreCase = true) || url.contains("8080")) "CNCVerse Bridge" else "Custom Stream Addon"
                        val addon = com.example.zubflix.stremio.StremioAddon(
                            manifestUrl = url,
                            name = name,
                            description = "CNCVerse CloudStream Bridge stream provider",
                            version = "1.0.0",
                            resources = listOf("stream", "catalog")
                        )
                        com.example.zubflix.stremio.StremioAddonManager.addAddon(this, addon)
                        Toast.makeText(this, "'$name' added to Stream Addons!", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        val customManifest = CloudStreamPluginManifest(
                            name = "Custom Extension",
                            pluginUrl = url,
                            description = "Custom installed CloudStream plugin"
                        )
                        progressBar.visibility = View.VISIBLE
                        lifecycleScope.launch {
                            val success = CloudStreamPluginManager.downloadAndInstallPlugin(
                                context = this@CloudStreamAddonsActivity,
                                manifest = customManifest
                            )
                            progressBar.visibility = View.GONE
                            if (success) {
                                Toast.makeText(this@CloudStreamAddonsActivity, "Custom extension installed!", Toast.LENGTH_SHORT).show()
                                loadData()
                            } else {
                                Toast.makeText(this@CloudStreamAddonsActivity, "Download failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getLanguageDisplay(lang: String?): String {
        if (lang == null) return "🇬🇧 English"
        return when (lang.lowercase()) {
            "en" -> "🇬🇧 English"
            "hi" -> "🇮🇳 Hindi"
            "ta" -> "🇮🇳 Tamil"
            "te" -> "🇮🇳 Telugu"
            "ml" -> "🇮🇳 Malayalam"
            "kn" -> "🇮🇳 Kannada"
            "de" -> "🇩🇪 German"
            "es" -> "🇪🇸 Spanish"
            "fr" -> "🇫🇷 French"
            "it" -> "🇮🇹 Italian"
            "pt" -> "🇵🇹 Portuguese"
            "vi" -> "🇻🇳 Vietnamese"
            "id" -> "🇮🇩 Indonesian"
            "ms" -> "🇲🇾 Malay"
            "zh" -> "🇨🇳 Chinese"
            "ja" -> "🇯🇵 Japanese"
            "ko" -> "🇰🇷 Korean"
            "ar" -> "🇸🇦 Arabic"
            "ru" -> "🇷🇺 Russian"
            else -> "🌐 ${lang.uppercase()}"
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return ""
        return if (size < 1024) {
            "$size B"
        } else if (size < 1024 * 1024) {
            "${size / 1024} kB"
        } else {
            String.format("%.1f MB", size.toFloat() / (1024 * 1024))
        }
    }
}

data class ExtensionDisplayItem(
    val id: String,
    val name: String,
    val description: String,
    val isInstalled: Boolean,
    val isEnabled: Boolean,
    val pluginUrl: String?,
    val manifest: CloudStreamPluginManifest? = null,
    val isRepoItem: Boolean = false,
    val repo: CloudStreamRepo? = null,
    val subtitle: String? = null,
    val hasUpdate: Boolean = false,
    val updateInfo: PluginUpdateInfo? = null
)

class ExtensionAdapter(
    private val onActionClick: (ExtensionDisplayItem) -> Unit,
    private val onDeleteClick: (ExtensionDisplayItem) -> Unit,
    private val onUpdateClick: ((ExtensionDisplayItem) -> Unit)? = null
) : RecyclerView.Adapter<ExtensionAdapter.VH>() {

    private val items = mutableListOf<ExtensionDisplayItem>()

    fun setItems(list: List<ExtensionDisplayItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cloudstream_extension, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val containerItem: View = view.findViewById(R.id.container_item)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvVersion: TextView = view.findViewById(R.id.tv_version)
        private val tvDescription: TextView = view.findViewById(R.id.tv_description)
        private val layoutTags: View = view.findViewById(R.id.layout_tags)
        private val tvTag1: TextView = view.findViewById(R.id.tv_tag1)
        private val tvTag2: TextView = view.findViewById(R.id.tv_tag2)
        private val tvTagLang: TextView = view.findViewById(R.id.tv_tag_lang)
        private val tvTagStatus: TextView = view.findViewById(R.id.tv_tag_status)
        private val tvAuthor: TextView = view.findViewById(R.id.tv_author)
        private val btnAction: MaterialButton = view.findViewById(R.id.btn_action)
        private val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)

        fun bind(item: ExtensionDisplayItem) {
            tvName.text = item.name
            tvDescription.text = item.description

            // Bind icons
            if (!item.isRepoItem && !item.manifest?.iconUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(item.manifest?.iconUrl)
                    .placeholder(R.drawable.ic_movie)
                    .error(R.drawable.ic_movie)
                    .into(ivIcon)
            } else if (item.isRepoItem) {
                val isGithub = item.repo?.url?.contains("github.com", ignoreCase = true) == true
                ivIcon.setImageResource(if (isGithub) R.drawable.ic_github else android.R.drawable.ic_menu_compass)
            } else {
                ivIcon.setImageResource(R.drawable.ic_movie)
            }

            // Bind status / badge and action buttons
            if (item.isRepoItem) {
                tvVersion.visibility = View.GONE
                layoutTags.visibility = View.GONE
                tvAuthor.visibility = View.GONE
                btnAction.visibility = View.GONE
                btnDelete.visibility = View.VISIBLE
                btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
            } else {
                tvVersion.visibility = View.VISIBLE
                tvVersion.text = "v${item.manifest?.version ?: 1}"

                layoutTags.visibility = View.VISIBLE
                tvAuthor.visibility = View.VISIBLE
                tvAuthor.text = item.manifest?.authors?.joinToString(", ") ?: "Unknown"

                // Bind TV type category tags
                val tvTypes = item.manifest?.tvTypes ?: emptyList()
                if (tvTypes.isNotEmpty()) {
                    tvTag1.visibility = View.VISIBLE
                    tvTag1.text = tvTypes[0].uppercase()
                } else {
                    tvTag1.visibility = View.GONE
                }

                if (tvTypes.size > 1) {
                    tvTag2.visibility = View.VISIBLE
                    tvTag2.text = tvTypes[1].uppercase()
                } else {
                    tvTag2.visibility = View.GONE
                }

                // Language Tag
                val langCode = item.manifest?.language?.uppercase() ?: "EN"
                tvTagLang.text = langCode

                // Status Tag (OK, DOWN, ACTIVE, DISABLED)
                if (item.isInstalled) {
                    if (item.isEnabled) {
                        tvTagStatus.text = "● ACTIVE"
                        tvTagStatus.setBackgroundResource(R.drawable.bg_green_pill)
                        tvTagStatus.setTextColor(Color.parseColor("#86EFAC"))
                    } else {
                        tvTagStatus.text = "● INACTIVE"
                        tvTagStatus.setBackgroundResource(R.drawable.bg_chip_unselected)
                        tvTagStatus.setTextColor(Color.parseColor("#9CA3AF"))
                    }
                } else {
                    val status = item.manifest?.status ?: 1
                    if (status == 3) {
                        tvTagStatus.text = "● DOWN"
                        tvTagStatus.setBackgroundResource(R.drawable.bg_purple_pill)
                        tvTagStatus.setTextColor(Color.parseColor("#FCA5A5"))
                    } else {
                        tvTagStatus.text = "● OK"
                        tvTagStatus.setBackgroundResource(R.drawable.bg_green_pill)
                        tvTagStatus.setTextColor(Color.parseColor("#86EFAC"))
                    }
                }

                // Bind action buttons
                if (item.hasUpdate) {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = "Update"
                    btnAction.setIconResource(android.R.drawable.stat_sys_download)
                    btnDelete.visibility = View.VISIBLE
                } else if (item.isInstalled) {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = if (item.isEnabled) "Disable" else "Enable"
                    btnAction.setIconResource(if (item.isEnabled) android.R.drawable.button_onoff_indicator_on else android.R.drawable.button_onoff_indicator_off)
                    btnDelete.visibility = View.VISIBLE
                } else {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = "Install"
                    btnAction.setIconResource(android.R.drawable.stat_sys_download)
                    btnDelete.visibility = View.GONE
                }
            }

            containerItem.setOnClickListener {
                if (item.isRepoItem) {
                    onActionClick(item)
                }
            }
            btnAction.setOnClickListener {
                if (item.hasUpdate) {
                    onUpdateClick?.invoke(item)
                } else {
                    onActionClick(item)
                }
            }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}

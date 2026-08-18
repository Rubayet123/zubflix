package com.example.zubflix

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zubflix.cloudstream.CloudStreamAddonsActivity
import com.example.zubflix.stremio.AddonsMenuActivity
import com.example.zubflix.util.AppearanceSettings
import com.example.zubflix.util.DebugLogger
import com.example.zubflix.util.FilterSettings
import com.example.zubflix.util.PlaybackSettings
import com.example.zubflix.utils.TmdbDatabaseHelper

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZubFlixSettingsTheme {
                MasterSettingsScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }
}

private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@Composable
fun ZubFlixSettingsTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFE50914),
        onPrimary = Color.White,
        surface = Color(0xFF161B22),
        onSurface = Color.White,
        background = Color(0xFF0F1217),
        onBackground = Color.White,
        secondaryContainer = Color(0xFF21262D),
        onSecondaryContainer = Color.White
    )
    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}

enum class SettingsCategory(val title: String, val icon: ImageVector, val subtitle: String) {
    APPEARANCE("Appearance & Layout", Icons.Default.Palette, "Homescreen layout & UI styling"),
    PROVIDERS("Content Providers", Icons.Default.Dvr, "Enable, reorder & configure scrapers"),
    EXTENSIONS("Extensions & Addons", Icons.Default.Extension, "CloudStream & Stremio plugins"),
    PLAYBACK("Playback & Subtitles", Icons.Default.PlayCircle, "Autoplay, quality, exclusions & subtitles"),
    TMDB("TMDB & Market Filters", Icons.Default.Movie, "TMDB API keys & regional vote thresholds"),
    STORAGE("Storage & Cache", Icons.Default.Storage, "Image, provider & metadata cache limits"),
    DIAGNOSTICS("Diagnostics & About", Icons.Default.BugReport, "Debug logs, version & credits")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterSettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE) }

    // Dialog & Modal State Managers
    var showProvidersDialog by remember { mutableStateOf(false) }
    var showExcludedDialog by remember { mutableStateOf(false) }
    var showAddKeywordDialog by remember { mutableStateOf(false) }
    var showSubtitlesDialog by remember { mutableStateOf(false) }
    var showTmdbDialog by remember { mutableStateOf(false) }
    var showTmdbThresholdsDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Zubflix Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isTv) Color(0xFFE50914) else Color(0xFF30363D))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isTv) "ANDROID TV MODE" else "MOBILE MODE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0F14),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F1217)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isTv) {
                // Two-Pane Responsive Layout for TV Wide Screens
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Rail Category List
                    LazyColumn(
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF0D0F14))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(SettingsCategory.entries.toTypedArray()) { category ->
                            CategoryRailItem(
                                category = category,
                                isSelected = category == selectedCategory,
                                onClick = { selectedCategory = category }
                            )
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF21262D))
                    )

                    // Right Content Pane
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp)
                    ) {
                        CategoryContentPane(
                            category = selectedCategory,
                            onOpenProviders = { showProvidersDialog = true },
                            onOpenExcluded = { showExcludedDialog = true },
                            onOpenSubtitles = { showSubtitlesDialog = true },
                            onOpenTmdb = { showTmdbDialog = true },
                            onOpenTmdbThresholds = { showTmdbThresholdsDialog = true },
                            onOpenCache = { showCacheDialog = true },
                            onOpenCredits = { showCreditsDialog = true }
                        )
                    }
                }
            } else {
                // Mobile Categorized Scrolling Layout
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(SettingsCategory.entries.toTypedArray()) { category ->
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = category.title,
                                    tint = Color(0xFFFF1E27),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = category.title.uppercase(),
                                    color = Color(0xFFF0F6FC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CategoryContentPane(
                                    category = category,
                                    onOpenProviders = { showProvidersDialog = true },
                                    onOpenExcluded = { showExcludedDialog = true },
                                    onOpenSubtitles = { showSubtitlesDialog = true },
                                    onOpenTmdb = { showTmdbDialog = true },
                                    onOpenTmdbThresholds = { showTmdbThresholdsDialog = true },
                                    onOpenCache = { showCacheDialog = true },
                                    onOpenCredits = { showCreditsDialog = true }
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }

        // --- MODALS & DIALOGS ---
        if (showProvidersDialog) {
            ManageProvidersDialog(onDismiss = { showProvidersDialog = false })
        }
        if (showExcludedDialog) {
            ManageExcludedProvidersDialog(
                onDismiss = { showExcludedDialog = false },
                onAddKeywordClick = { showAddKeywordDialog = true }
            )
        }
        if (showAddKeywordDialog) {
            AddCustomKeywordDialog(onDismiss = { showAddKeywordDialog = false })
        }
        if (showSubtitlesDialog) {
            SubtitleSettingsDialog(onDismiss = { showSubtitlesDialog = false })
        }
        if (showTmdbDialog) {
            TmdbSettingsDialog(
                onDismiss = { showTmdbDialog = false },
                onOpenFilterThresholds = { showTmdbThresholdsDialog = true }
            )
        }
        if (showTmdbThresholdsDialog) {
            TmdbThresholdsDialog(onDismiss = { showTmdbThresholdsDialog = false })
        }
        if (showCacheDialog) {
            CacheSettingsDialog(onDismiss = { showCacheDialog = false })
        }
        if (showCreditsDialog) {
            CreditsDialog(onDismiss = { showCreditsDialog = false })
        }
    }
}

@Composable
fun CategoryRailItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(150),
        label = "rail_scale"
    )

    val bgColor = when {
        isSelected -> Color(0xFFE50914)
        isFocused -> Color.White.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = category.title,
            tint = if (isSelected || isFocused) Color.White else Color(0xFF8B949E),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = category.title,
                color = if (isSelected || isFocused) Color.White else Color(0xFFC9D1D9),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun CategoryContentPane(
    category: SettingsCategory,
    onOpenProviders: () -> Unit,
    onOpenExcluded: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenTmdb: () -> Unit,
    onOpenTmdbThresholds: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenCredits: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (category) {
            SettingsCategory.APPEARANCE -> {
                var currentTheme by remember {
                    mutableStateOf(AppearanceSettings.getAppTheme(context))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF1E27))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "APP THEME & DESIGN ENGINE",
                        color = Color(0xFFC9D1D9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Option 1: Zubflix TV
                ThemeSelectionTile(
                    title = "Zubflix TV",
                    subtitle = "Native Android Leanback TV interface with 10-foot Sidebar Rail (Search, Home, Provider, My List, Settings), Dynamic Hero Banner with quick actions, and hardware D-Pad focus rows.",
                    icon = Icons.Default.Tv,
                    isSelected = currentTheme == AppearanceSettings.THEME_HOMEFLIX_TV,
                    onClick = {
                        AppearanceSettings.setAppTheme(context, AppearanceSettings.THEME_HOMEFLIX_TV)
                        currentTheme = AppearanceSettings.THEME_HOMEFLIX_TV
                        Toast.makeText(context, "Theme set to Zubflix TV", Toast.LENGTH_SHORT).show()
                    }
                )

                // Option 2: ZubFlix Classic
                ThemeSelectionTile(
                    title = "ZubFlix Classic",
                    subtitle = "Legacy Android XML View-based layout with traditional grid cards and standard top bar navigation.",
                    icon = Icons.Default.GridView,
                    isSelected = currentTheme == AppearanceSettings.THEME_ZUBFLIX_CLASSIC,
                    onClick = {
                        AppearanceSettings.setAppTheme(context, AppearanceSettings.THEME_ZUBFLIX_CLASSIC)
                        currentTheme = AppearanceSettings.THEME_ZUBFLIX_CLASSIC
                        Toast.makeText(context, "Theme set to ZubFlix Classic", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                var currentEpLayout by remember {
                    mutableStateOf(AppearanceSettings.getEpisodeLayout(context))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF1E27))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SEASON EPISODES LAYOUT",
                        color = Color(0xFFC9D1D9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                ThemeSelectionTile(
                    title = "Horizontal Carousel",
                    subtitle = "Display season episodes in a horizontal scrolling row with episode thumbnails and play overlays.",
                    icon = Icons.Default.ViewArray,
                    isSelected = currentEpLayout == AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL,
                    onClick = {
                        AppearanceSettings.setEpisodeLayout(context, AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL)
                        currentEpLayout = AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL
                        Toast.makeText(context, "Episode layout set to Horizontal Carousel", Toast.LENGTH_SHORT).show()
                    }
                )

                ThemeSelectionTile(
                    title = "Vertical List",
                    subtitle = "Display season episodes in a vertical list format with detailed overviews and metadata badges.",
                    icon = Icons.Default.List,
                    isSelected = currentEpLayout == AppearanceSettings.EPISODE_LAYOUT_VERTICAL,
                    onClick = {
                        AppearanceSettings.setEpisodeLayout(context, AppearanceSettings.EPISODE_LAYOUT_VERTICAL)
                        currentEpLayout = AppearanceSettings.EPISODE_LAYOUT_VERTICAL
                        Toast.makeText(context, "Episode layout set to Vertical List", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
                var showRatings by remember { mutableStateOf(prefs.getBoolean("home_screen_ratings", false)) }

                SettingsSwitchRow(
                    title = "Show Ratings on Home Cards",
                    subtitle = "Display IMDB / TMDB rating badge on poster thumbnails",
                    icon = Icons.Default.Star,
                    checked = showRatings,
                    onCheckedChange = { isChecked ->
                        prefs.edit().putBoolean("home_screen_ratings", isChecked).apply()
                        showRatings = isChecked
                    }
                )
            }

            SettingsCategory.PROVIDERS -> {
                val enabledCount = remember { SourceManager.getEnabledSources(context).size }

                SettingsActionRow(
                    title = "Manage Content Providers",
                    subtitle = "$enabledCount Providers Enabled (Move order & toggle providers)",
                    icon = Icons.Default.Dvr,
                    onClick = onOpenProviders
                )

                val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
                var groupLocalScrapers by remember { mutableStateOf(prefs.getBoolean("group_local_scrapers", true)) }

                SettingsSwitchRow(
                    title = "Group Local Scrapers into Single Chip",
                    subtitle = "Combine all BDIX local scrapers (DhakaFlix, CircleFTP, etc.) under one 'Local Scrapers' filter chip",
                    icon = Icons.Default.FolderZip,
                    checked = groupLocalScrapers,
                    onCheckedChange = { isChecked ->
                        prefs.edit().putBoolean("group_local_scrapers", isChecked).apply()
                        groupLocalScrapers = isChecked
                    }
                )

                SettingsActionRow(
                    title = "Local Scraper & Network Timeout",
                    subtitle = "BDIX Auto-Detect & Connection Timeout settings",
                    icon = Icons.Default.NetworkCheck,
                    onClick = {
                        Toast.makeText(context, "BDIX & Local Scrapers are active", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            SettingsCategory.EXTENSIONS -> {
                SettingsActionRow(
                    title = "CloudStream Extensions & Repos",
                    subtitle = "Install, enable, or update CloudStream provider extensions",
                    icon = Icons.Default.Extension,
                    onClick = {
                        context.startActivity(Intent(context, CloudStreamAddonsActivity::class.java))
                    }
                )

                SettingsActionRow(
                    title = "Stremio Addons Management",
                    subtitle = "Manage Stremio manifests (Torrentio, CyberFlix, Pengu, etc.)",
                    icon = Icons.Default.Widgets,
                    onClick = {
                        context.startActivity(Intent(context, AddonsMenuActivity::class.java))
                    }
                )
            }

            SettingsCategory.PLAYBACK -> {
                var playbackMode by remember { mutableStateOf(PlaybackSettings.getPlaybackMode(context)) }

                SettingsActionRow(
                    title = "Playback Mode",
                    subtitle = if (playbackMode == PlaybackSettings.MODE_MANUAL) "Manual Link Selection" else "Auto-Play Best Quality",
                    icon = Icons.Default.PlayArrow,
                    onClick = {
                        val newMode = if (playbackMode == PlaybackSettings.MODE_MANUAL) PlaybackSettings.MODE_AUTO_PLAY else PlaybackSettings.MODE_MANUAL
                        PlaybackSettings.setPlaybackMode(context, newMode)
                        playbackMode = newMode
                    }
                )

                val excludedCount = remember { PlaybackSettings.getExcludedProviders(context).size }
                SettingsActionRow(
                    title = "Excluded Providers & Custom Keywords",
                    subtitle = "$excludedCount Excluded providers / keywords",
                    icon = Icons.Default.Block,
                    onClick = onOpenExcluded
                )

                SettingsActionRow(
                    title = "Subtitle Customization",
                    subtitle = "Auto-enable, default language, text color, size & background",
                    icon = Icons.Default.Subtitles,
                    onClick = onOpenSubtitles
                )
            }

            SettingsCategory.TMDB -> {
                SettingsActionRow(
                    title = "TMDB API & Metadata Settings",
                    subtitle = "Custom API keys, cast, backdrops, ratings & plot options",
                    icon = Icons.Default.Movie,
                    onClick = onOpenTmdb
                )

                SettingsActionRow(
                    title = "TMDB Regional Market Vote Thresholds",
                    subtitle = "Filter out low-vote noise for Large, Medium & Small markets",
                    icon = Icons.Default.FilterList,
                    onClick = onOpenTmdbThresholds
                )
            }

            SettingsCategory.STORAGE -> {
                SettingsActionRow(
                    title = "Cache & Storage Configuration",
                    subtitle = "Adjust TTL expiry, max MB limit & clear provider/TMDB caches",
                    icon = Icons.Default.Storage,
                    onClick = onOpenCache
                )
            }

            SettingsCategory.DIAGNOSTICS -> {
                var isDebug by remember { mutableStateOf(DebugLogger.debugEnabled) }

                SettingsSwitchRow(
                    title = "Verbose Debug Logging",
                    subtitle = "Log detailed network requests & scraper errors",
                    icon = Icons.Default.BugReport,
                    checked = isDebug,
                    onCheckedChange = { checked ->
                        DebugLogger.debugEnabled = checked
                        isDebug = checked
                    }
                )

                SettingsActionRow(
                    title = "View System Debug Logs",
                    subtitle = "Inspect real-time application logs and error traces",
                    icon = Icons.Default.Terminal,
                    onClick = {
                        context.startActivity(Intent(context, DebugLogsActivity::class.java))
                    }
                )

                SettingsActionRow(
                    title = "About ZubFlix & Credits",
                    subtitle = "App Version 1.0.1 - Developer credits & repository info",
                    icon = Icons.Default.Info,
                    onClick = onOpenCredits
                )
            }
        }
    }
}

@Composable
private fun ThemeSelectionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "theme_tile_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) Color(0x25FF1E27) else if (isFocused) Color(0xFF21262D) else Color(0xFF0D1117)
            )
            .border(
                width = if (isSelected || isFocused) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF1E27) else if (isFocused) Color.White else Color(0xFF30363D),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) Color(0xFFFF1E27) else Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) Color.White else Color(0xFF8B949E),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF1E27))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "row_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color(0xFF21262D) else Color(0xFF0D1117))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color(0xFFE50914) else Color(0xFF30363D),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFocused) Color(0xFFE50914).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isFocused) Color(0xFFE50914) else Color(0xFF8B949E),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = Color(0xFF8B949E)
        )
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "switch_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color(0xFF21262D) else Color(0xFF0D1117))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color(0xFFE50914) else Color(0xFF30363D),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .focusable(interactionSource = interactionSource)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFocused) Color(0xFFE50914).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isFocused) Color(0xFFE50914) else Color(0xFF8B949E),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFE50914),
                uncheckedThumbColor = Color(0xFF8B949E),
                uncheckedTrackColor = Color(0xFF21262D)
            )
        )
    }
}

// --- MODAL DIALOG COMPOSABLES ---

@Composable
fun ManageProvidersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sourcesList by remember { mutableStateOf(SourceManager.getOrderedSources(context).toMutableList()) }
    var enabledMap by remember {
        mutableStateOf(sourcesList.associate { it.name to SourceManager.isSourceEnabled(context, it.name) }.toMutableMap())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Manage Content Providers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(sourcesList, key = { _, s -> s.name }) { index, source ->
                    val isChecked = enabledMap[source.name] ?: true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D1117))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = source.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val newList = sourcesList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index - 1]
                                        newList[index - 1] = temp
                                        sourcesList = newList
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Move Up",
                                    tint = if (index > 0) Color.White else Color(0xFF30363D)
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (index < sourcesList.size - 1) {
                                        val newList = sourcesList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index + 1]
                                        newList[index + 1] = temp
                                        sourcesList = newList
                                    }
                                },
                                enabled = index < sourcesList.size - 1
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move Down",
                                    tint = if (index < sourcesList.size - 1) Color.White else Color(0xFF30363D)
                                )
                            }

                            Switch(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val newMap = enabledMap.toMutableMap()
                                    newMap[source.name] = checked
                                    enabledMap = newMap
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFE50914)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    var enabledCount = 0
                    for (source in sourcesList) {
                        val isEnabled = enabledMap[source.name] ?: true
                        SourceManager.setSourceEnabled(context, source.name, isEnabled)
                        if (isEnabled) enabledCount++
                    }
                    if (enabledCount == 0 && sourcesList.isNotEmpty()) {
                        SourceManager.setSourceEnabled(context, sourcesList[0].name, true)
                        Toast.makeText(context, "At least one provider must be enabled", Toast.LENGTH_SHORT).show()
                    }
                    SourceManager.saveSourceOrder(context, sourcesList)
                    Toast.makeText(context, "Provider order saved", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8B949E))
            }
        }
    )
}

@Composable
fun ManageExcludedProvidersDialog(
    onDismiss: () -> Unit,
    onAddKeywordClick: () -> Unit
) {
    val context = LocalContext.current
    val knownProviders = PlaybackSettings.KNOWN_PROVIDERS
    var excludedSet by remember { mutableStateOf(PlaybackSettings.getExcludedProviders(context).toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Manage Excluded Providers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Excluded keywords will be bypassed during Auto-Play:",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(knownProviders) { provider ->
                        val isExcluded = excludedSet.any { it.equals(provider, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D1117))
                                .clickable {
                                    val newSet = excludedSet.toMutableSet()
                                    if (isExcluded) {
                                        newSet.removeIf { it.equals(provider, ignoreCase = true) }
                                    } else {
                                        newSet.add(provider.lowercase())
                                    }
                                    excludedSet = newSet
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = provider, color = Color.White, fontSize = 14.sp)
                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = { checked ->
                                    val newSet = excludedSet.toMutableSet()
                                    if (!checked) {
                                        newSet.removeIf { it.equals(provider, ignoreCase = true) }
                                    } else {
                                        newSet.add(provider.lowercase())
                                    }
                                    excludedSet = newSet
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914))
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onAddKeywordClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ Add Custom Keyword")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    PlaybackSettings.setExcludedProviders(context, excludedSet)
                    Toast.makeText(context, "Exclusions updated", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8B949E))
            }
        }
    )
}

@Composable
fun AddCustomKeywordDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Add Custom Keyword", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                Text(
                    text = "Enter provider or server name keyword to exclude:",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("e.g. hostxyz, dflix", color = Color(0xFF8B949E)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE50914),
                        unfocusedBorderColor = Color(0xFF30363D),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val kw = textInput.trim().lowercase()
                    if (kw.isNotEmpty()) {
                        val set = PlaybackSettings.getExcludedProviders(context).toMutableSet()
                        set.add(kw)
                        PlaybackSettings.setExcludedProviders(context, set)
                        Toast.makeText(context, "'$kw' added to exclusions", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Add", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8B949E))
            }
        }
    )
}

@Composable
fun SubtitleSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    var autoEnable by remember { mutableStateOf(prefs.getBoolean("pref_auto_enable_subtitles", true)) }
    var defaultLang by remember { mutableStateOf(prefs.getString("pref_default_sub_lang", "en") ?: "en") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Subtitle Options", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto Enable Subtitles", color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = autoEnable,
                        onCheckedChange = {
                            autoEnable = it
                            prefs.edit().putBoolean("pref_auto_enable_subtitles", it).apply()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFE50914))
                    )
                }

                Text("Default Language:", color = Color(0xFF8B949E), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "English", "bn" to "Bengali", "hi" to "Hindi").forEach { (code, label) ->
                        FilterChip(
                            selected = defaultLang == code,
                            onClick = {
                                defaultLang = code
                                prefs.edit().putString("pref_default_sub_lang", code).apply()
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE50914),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Close", color = Color.White)
            }
        }
    )
}

@Composable
fun TmdbSettingsDialog(
    onDismiss: () -> Unit,
    onOpenFilterThresholds: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    var apiKey by remember { mutableStateOf(prefs.getString("tmdb_api_key", "") ?: "") }
    var useBackdrops by remember { mutableStateOf(prefs.getBoolean("use_tmdb_backdrops", true)) }
    var useCast by remember { mutableStateOf(prefs.getBoolean("use_tmdb_cast", true)) }
    var useRatings by remember { mutableStateOf(prefs.getBoolean("use_tmdb_ratings", true)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("TMDB API & Options", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Custom TMDB API Key:", color = Color(0xFF8B949E), fontSize = 12.sp)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("Optional API Key", color = Color(0xFF8B949E)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE50914),
                        unfocusedBorderColor = Color(0xFF30363D),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useBackdrops, onCheckedChange = { useBackdrops = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914)))
                    Text("Fetch High-Res Backdrops", color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCast, onCheckedChange = { useCast = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914)))
                    Text("Fetch Cast & Crew Info", color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRatings, onCheckedChange = { useRatings = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE50914)))
                    Text("Fetch TMDB Ratings", color = Color.White, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenFilterThresholds()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Configure Market Vote Thresholds")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit().apply {
                        putString("tmdb_api_key", apiKey.trim())
                        putBoolean("use_tmdb_backdrops", useBackdrops)
                        putBoolean("use_tmdb_cast", useCast)
                        putBoolean("use_tmdb_ratings", useRatings)
                    }.apply()
                    Toast.makeText(context, "TMDB preferences saved", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8B949E)) }
        }
    )
}

@Composable
fun TmdbThresholdsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var large by remember { mutableIntStateOf(FilterSettings.getLargeMarketVoteCount(context)) }
    var medium by remember { mutableIntStateOf(FilterSettings.getMediumMarketVoteCount(context)) }
    var small by remember { mutableIntStateOf(FilterSettings.getSmallMarketVoteCount(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("TMDB Market Vote Thresholds", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Large Markets (US, GB, CA, etc.) Min Votes: $large", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = large.toFloat(),
                    onValueChange = { large = it.toInt() },
                    valueRange = 0f..500f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914))
                )

                Text("Medium Markets (IN, ES, IT, BR, etc.) Min Votes: $medium", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = medium.toFloat(),
                    onValueChange = { medium = it.toInt() },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914))
                )

                Text("Small Markets (BD, EG, PK, etc.) Min Votes: $small", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = small.toFloat(),
                    onValueChange = { small = it.toInt() },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914))
                )

                TextButton(
                    onClick = {
                        FilterSettings.resetToDefaults(context)
                        large = FilterSettings.getLargeMarketVoteCount(context)
                        medium = FilterSettings.getMediumMarketVoteCount(context)
                        small = FilterSettings.getSmallMarketVoteCount(context)
                        Toast.makeText(context, "Reset to default thresholds", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Reset Thresholds to Defaults", color = Color(0xFFE50914))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    FilterSettings.setLargeMarketVoteCount(context, large)
                    FilterSettings.setMediumMarketVoteCount(context, medium)
                    FilterSettings.setSmallMarketVoteCount(context, small)
                    Toast.makeText(context, "Vote thresholds updated", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8B949E)) }
        }
    )
}

@Composable
fun CacheSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var ttlHours by remember { mutableIntStateOf(prefs.getInt("cache_ttl_hours", 2)) }
    var maxCacheMb by remember { mutableIntStateOf(prefs.getInt("max_cache_size_mb", 200)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Cache Configuration", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cache Expiry TTL: $ttlHours Hours", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 8, 24).forEach { hours ->
                        FilterChip(
                            selected = ttlHours == hours,
                            onClick = { ttlHours = hours },
                            label = { Text("${hours}h") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE50914),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Text("Max Cache Size Limit: $maxCacheMb MB", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = maxCacheMb.toFloat(),
                    onValueChange = { maxCacheMb = it.toInt() },
                    valueRange = 50f..1000f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFE50914), activeTrackColor = Color(0xFFE50914))
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            SourceManager.invalidateAllCaches()
                            Toast.makeText(context, "Provider Cache Cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Provider Cache", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            TmdbDatabaseHelper(context).clearAll()
                            Toast.makeText(context, "TMDB Cache Cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear TMDB Cache", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit().putInt("cache_ttl_hours", ttlHours).putInt("max_cache_size_mb", maxCacheMb).apply()
                    Toast.makeText(context, "Cache configuration saved", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8B949E)) }
        }
    )
}

@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("About ZubFlix", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE50914)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "ZubFlix Logo",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text("ZubFlix Streaming Platform", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Version 1.0.1 (Build 2)", color = Color(0xFF8B949E), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rubayet123"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Icon(imageVector = Icons.Default.Code, contentDescription = "GitHub", tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Developer GitHub Profile", color = Color.White)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Text("Close", color = Color.White)
            }
        }
    )
}

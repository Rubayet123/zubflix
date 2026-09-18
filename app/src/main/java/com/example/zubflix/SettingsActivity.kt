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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
    return AppearanceSettings.getAppTheme(context) == AppearanceSettings.THEME_HOMEFLIX_TV
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
    SEARCH("Search & Discovery", Icons.Default.Search, "Global search defaults & remote navigation"),
    PLAYBACK("Playback & Subtitles", Icons.Default.PlayCircle, "Autoplay, quality, exclusions & subtitles"),
    TMDB("TMDB & Market Filters", Icons.Default.Movie, "TMDB API keys & regional vote thresholds"),
    STORAGE("Storage & Cache", Icons.Default.Storage, "Metadata duration, disk limit & clear cache"),
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
    var showAudioBoostDialog by remember { mutableStateOf(false) }
    var showBufferDialog by remember { mutableStateOf(false) }
    var showQualityChipsDialog by remember { mutableStateOf(false) }
    var showTmdbDialog by remember { mutableStateOf(false) }
    var showTmdbThresholdsDialog by remember { mutableStateOf(false) }
    var showCacheDurationDialog by remember { mutableStateOf(false) }
    var showMaxCacheSizeDialog by remember { mutableStateOf(false) }
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
                    val backInteractionSource = remember { MutableInteractionSource() }
                    val isBackFocused by backInteractionSource.collectIsFocusedAsState()
                    IconButton(
                        onClick = onBackClick,
                        interactionSource = backInteractionSource,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(
                                width = if (isBackFocused) 2.dp else 0.dp,
                                color = if (isBackFocused) Color(0xFFE50914) else Color.Transparent,
                                shape = CircleShape
                            )
                            .background(
                                if (isBackFocused) Color(0xFF21262D) else Color.Transparent,
                                shape = CircleShape
                            )
                            .focusable(interactionSource = backInteractionSource)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isBackFocused) Color(0xFFE50914) else Color.White
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

                    // Right Content Pane with Scroll & Bottom Breathing Room
                    val rightPaneScrollState = rememberScrollState()
                    LaunchedEffect(selectedCategory) {
                        rightPaneScrollState.scrollTo(0)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rightPaneScrollState)
                                .padding(bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CategoryContentPane(
                                category = selectedCategory,
                                onOpenProviders = { showProvidersDialog = true },
                                onOpenExcluded = { showExcludedDialog = true },
                                onOpenSubtitles = { showSubtitlesDialog = true },
                                onOpenAudioBoost = { showAudioBoostDialog = true },
                                onOpenBuffer = { showBufferDialog = true },
                                onOpenQualityChips = { showQualityChipsDialog = true },
                                onOpenTmdb = { showTmdbDialog = true },
                                onOpenTmdbThresholds = { showTmdbThresholdsDialog = true },
                                onOpenCacheDuration = { showCacheDurationDialog = true },
                                onOpenMaxCacheSize = { showMaxCacheSizeDialog = true },
                                onOpenCache = { showCacheDialog = true },
                                onOpenCredits = { showCreditsDialog = true }
                            )
                        }
                    }
                }
            } else {
                // Mobile Categorized Scrolling Layout
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentPadding = PaddingValues(bottom = 120.dp),
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
                                    onOpenAudioBoost = { showAudioBoostDialog = true },
                                    onOpenBuffer = { showBufferDialog = true },
                                    onOpenQualityChips = { showQualityChipsDialog = true },
                                    onOpenTmdb = { showTmdbDialog = true },
                                    onOpenTmdbThresholds = { showTmdbThresholdsDialog = true },
                                    onOpenCacheDuration = { showCacheDurationDialog = true },
                                    onOpenMaxCacheSize = { showMaxCacheSizeDialog = true },
                                    onOpenCache = { showCacheDialog = true },
                                    onOpenCredits = { showCreditsDialog = true }
                                )
                            }
                        }
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
        if (showAudioBoostDialog) {
            AudioBoostSettingsDialog(onDismiss = { showAudioBoostDialog = false })
        }
        if (showBufferDialog) {
            BufferSettingsDialog(onDismiss = { showBufferDialog = false })
        }
        if (showQualityChipsDialog) {
            QualityChipsSettingsDialog(onDismiss = { showQualityChipsDialog = false })
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
        if (showCacheDurationDialog) {
            CacheDurationDialog(onDismiss = { showCacheDurationDialog = false })
        }
        if (showMaxCacheSizeDialog) {
            MaxCacheSizeDialog(onDismiss = { showMaxCacheSizeDialog = false })
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(150),
        label = "rail_scale"
    )

    val bgColor = when {
        isSelected && isFocused -> Color(0xFFE50914)
        isSelected -> Color(0xFFE50914).copy(alpha = 0.85f)
        isFocused -> Color(0xFF262C36)
        else -> Color.Transparent
    }

    val borderColor = when {
        isFocused -> Color.White
        isSelected -> Color(0xFFE50914)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
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
    onOpenAudioBoost: () -> Unit,
    onOpenBuffer: () -> Unit,
    onOpenQualityChips: () -> Unit,
    onOpenTmdb: () -> Unit,
    onOpenTmdbThresholds: () -> Unit,
    onOpenCacheDuration: () -> Unit,
    onOpenMaxCacheSize: () -> Unit,
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
                    subtitle = "Native 10-foot TV experience with sidebar rail & dynamic hero banner",
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
                    subtitle = "Traditional grid layout with standard top navigation bar",
                    icon = Icons.Default.GridView,
                    isSelected = currentTheme == AppearanceSettings.THEME_ZUBFLIX_CLASSIC,
                    onClick = {
                        AppearanceSettings.setAppTheme(context, AppearanceSettings.THEME_ZUBFLIX_CLASSIC)
                        currentTheme = AppearanceSettings.THEME_ZUBFLIX_CLASSIC
                        Toast.makeText(context, "Theme set to ZubFlix Classic", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                var currentEpLayout by remember {
                    mutableStateOf(AppearanceSettings.getEpisodeLayout(context))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModernCompactOptionCard(
                        modifier = Modifier.weight(1f),
                        title = "Horizontal Carousel",
                        subtitle = "Episode slider preview",
                        icon = Icons.Default.ViewArray,
                        isSelected = currentEpLayout == AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL,
                        onClick = {
                            AppearanceSettings.setEpisodeLayout(context, AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL)
                            currentEpLayout = AppearanceSettings.EPISODE_LAYOUT_HORIZONTAL
                            Toast.makeText(context, "Layout: Horizontal Carousel", Toast.LENGTH_SHORT).show()
                        }
                    )

                    ModernCompactOptionCard(
                        modifier = Modifier.weight(1f),
                        title = "Vertical List",
                        subtitle = "Detailed episode overview",
                        icon = Icons.Default.List,
                        isSelected = currentEpLayout == AppearanceSettings.EPISODE_LAYOUT_VERTICAL,
                        onClick = {
                            AppearanceSettings.setEpisodeLayout(context, AppearanceSettings.EPISODE_LAYOUT_VERTICAL)
                            currentEpLayout = AppearanceSettings.EPISODE_LAYOUT_VERTICAL
                            Toast.makeText(context, "Layout: Vertical List", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                var currentTvCardStyle by remember {
                    mutableStateOf(AppearanceSettings.getTvCardStyle(context))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF1E27))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "TV HOMESCREEN CARD STYLE",
                        color = Color(0xFFC9D1D9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                ThemeSelectionTile(
                    title = "Portrait (Default)",
                    subtitle = "Standard 2:3 vertical poster cards",
                    icon = Icons.Default.CropPortrait,
                    isSelected = currentTvCardStyle == AppearanceSettings.TV_CARD_STYLE_PORTRAIT,
                    onClick = {
                        AppearanceSettings.setTvCardStyle(context, AppearanceSettings.TV_CARD_STYLE_PORTRAIT)
                        currentTvCardStyle = AppearanceSettings.TV_CARD_STYLE_PORTRAIT
                        Toast.makeText(context, "Card style: Portrait", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                ThemeSelectionTile(
                    title = "Landscape",
                    subtitle = "Wide 16:9 cinematic backdrop cards",
                    icon = Icons.Default.CropLandscape,
                    isSelected = currentTvCardStyle == AppearanceSettings.TV_CARD_STYLE_LANDSCAPE,
                    onClick = {
                        AppearanceSettings.setTvCardStyle(context, AppearanceSettings.TV_CARD_STYLE_LANDSCAPE)
                        currentTvCardStyle = AppearanceSettings.TV_CARD_STYLE_LANDSCAPE
                        Toast.makeText(context, "Card style: Landscape", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                ThemeSelectionTile(
                    title = "Smart / Adaptive",
                    subtitle = "Auto-fits 16:9 backdrops for TMDB/Cloud & 2:3 posters for FTP",
                    icon = Icons.Default.AutoAwesome,
                    isSelected = currentTvCardStyle == AppearanceSettings.TV_CARD_STYLE_AUTO,
                    onClick = {
                        AppearanceSettings.setTvCardStyle(context, AppearanceSettings.TV_CARD_STYLE_AUTO)
                        currentTvCardStyle = AppearanceSettings.TV_CARD_STYLE_AUTO
                        Toast.makeText(context, "Card style: Smart Adaptive", Toast.LENGTH_SHORT).show()
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

                val currentBufferSize = remember { PlaybackSettings.getBufferSizeSeconds(context) }
                SettingsActionRow(
                    title = "ExoPlayer Video Buffer Size",
                    subtitle = PlaybackSettings.getBufferSizeLabel(currentBufferSize),
                    icon = Icons.Default.Speed,
                    onClick = onOpenBuffer
                )

                val qualityChipsLabel = remember { PlaybackSettings.getVisibleQualityChipsLabel(context) }
                SettingsActionRow(
                    title = "Visible Quality Filter Chips",
                    subtitle = qualityChipsLabel,
                    icon = Icons.Default.FilterList,
                    onClick = onOpenQualityChips
                )

                val excludedCount = remember { PlaybackSettings.getExcludedProviders(context).size }
                SettingsActionRow(
                    title = "Excluded Providers & Custom Keywords",
                    subtitle = "$excludedCount Excluded providers / keywords",
                    icon = Icons.Default.Block,
                    onClick = onOpenExcluded
                )

                val defaultBoost = remember { com.example.zubflix.util.AudioSettings.getDefaultBoostLevel(context) }
                SettingsActionRow(
                    title = "Audio Volume Boost & TV Sound",
                    subtitle = "Default: ${com.example.zubflix.util.AudioSettings.getBoostLabel(defaultBoost)} (Hardware DSP Loudness Enhancement)",
                    icon = Icons.Default.VolumeUp,
                    onClick = onOpenAudioBoost
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

            SettingsCategory.SEARCH -> {
                var isGlobalSearchDefault by remember {
                    mutableStateOf(com.example.zubflix.util.SearchSettings.isGlobalSearchDefault(context))
                }

                SettingsSwitchRow(
                    title = "Always Enable Global Search",
                    subtitle = if (isGlobalSearchDefault) "Search across all enabled providers by default (ON)" else "Search in active provider mode by default (OFF)",
                    icon = Icons.Default.Search,
                    checked = isGlobalSearchDefault,
                    onCheckedChange = { enabled ->
                        com.example.zubflix.util.SearchSettings.setGlobalSearchDefault(context, enabled)
                        isGlobalSearchDefault = enabled
                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("search_global_enabled", enabled).apply()
                        Toast.makeText(
                            context,
                            if (enabled) "Global Search enabled by default" else "Global Search disabled by default",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            SettingsCategory.STORAGE -> {
                var cacheHours by remember {
                    mutableIntStateOf(com.example.zubflix.util.CacheSettings.getMetadataCacheHours(context))
                }
                var maxCacheMb by remember {
                    mutableIntStateOf(com.example.zubflix.util.CacheSettings.getMaxCacheSizeMb(context))
                }
                var currentCacheSize by remember {
                    mutableDoubleStateOf(com.example.zubflix.util.CacheSettings.getCurrentCacheSizeMb(context))
                }

                SettingsActionRow(
                    title = "Metadata Cache Duration",
                    subtitle = "$cacheHours Hours",
                    icon = Icons.Default.Schedule,
                    onClick = onOpenCacheDuration
                )

                SettingsActionRow(
                    title = "Maximum Disk Cache Size",
                    subtitle = "$maxCacheMb MB",
                    icon = Icons.Default.Storage,
                    onClick = onOpenMaxCacheSize
                )

                SettingsActionRow(
                    title = "Clear Application Cache",
                    subtitle = "Currently using ${String.format("%.1f", currentCacheSize)} MB",
                    icon = Icons.Default.Delete,
                    onClick = {
                        com.example.zubflix.util.CacheSettings.clearAllCache(context)
                        SourceManager.invalidateAllCaches()
                        com.example.zubflix.utils.TmdbDatabaseHelper(context).clearAll()
                        currentCacheSize = com.example.zubflix.util.CacheSettings.getCurrentCacheSizeMb(context)
                        Toast.makeText(context, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                    }
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
    badge: String? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(150),
        label = "theme_tile_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Color(0xFF1C1317) else if (isFocused) Color(0xFF1F2530) else Color(0xFF0E1217)
            )
            .border(
                width = if (isSelected || isFocused) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF1E27) else if (isFocused) Color.White else Color(0xFF222938),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) Color(0x25FF1E27) else Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) Color(0xFFFF1E27) else Color(0xFF8B949E),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x30FF1E27))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = badge,
                            color = Color(0xFFFF4B55),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                color = Color(0xFF8B949E),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = Color(0xFFFF1E27),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ModernCompactOptionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "compact_card_scale"
    )

    Column(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Color(0xFF1C1317) else if (isFocused) Color(0xFF1F2530) else Color(0xFF0E1217)
            )
            .border(
                width = if (isSelected || isFocused) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFFFF1E27) else if (isFocused) Color.White else Color(0xFF222938),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Color(0x25FF1E27) else Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) Color(0xFFFF1E27) else Color(0xFF8B949E),
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color(0xFFFF1E27),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = subtitle,
            color = Color(0xFF8B949E),
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "row_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
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
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(150),
        label = "switch_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
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
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
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
fun AudioBoostSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var defaultBoost by remember { mutableIntStateOf(com.example.zubflix.util.AudioSettings.getDefaultBoostLevel(context)) }
    var rememberSession by remember { mutableStateOf(com.example.zubflix.util.AudioSettings.isRememberSessionBoostEnabled(context)) }
    var dialogueClarity by remember { mutableStateOf(com.example.zubflix.util.AudioSettings.isDialogueClarityEnabled(context)) }
    var gestureExtendedBoost by remember { mutableStateOf(com.example.zubflix.util.AudioSettings.isGestureExtendedBoostEnabled(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color(0xFFFF1E27),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Audio Boost & TV Sound Engine", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Hardware DSP Loudness Enhancement engine provides safe decibel boost (+0dB to +16dB) with anti-clipping headroom protection for quiet movies and TV speakers.",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Text(
                    text = "DEFAULT PLAYBACK BOOST LEVEL:",
                    color = Color(0xFFC9D1D9),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                com.example.zubflix.util.AudioSettings.BOOST_LEVELS.forEach { (levelMb, label) ->
                    val isSelected = defaultBoost == levelMb
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF21262D) else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) Color(0xFFFF1E27) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                defaultBoost = levelMb
                                com.example.zubflix.util.AudioSettings.setDefaultBoostLevel(context, levelMb)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else Color(0xFFC9D1D9),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (levelMb == com.example.zubflix.util.AudioSettings.BOOST_MEDIUM) {
                                Text(
                                    text = "Optimal for Android TV & Flat Screen Speakers",
                                    color = Color(0xFF388BFD),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                defaultBoost = levelMb
                                com.example.zubflix.util.AudioSettings.setDefaultBoostLevel(context, levelMb)
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFFFF1E27),
                                unselectedColor = Color(0xFF8B949E)
                            )
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF21262D), thickness = 1.dp)

                // Remember per session switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remember In-Player Adjustments", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Save boost level picked inside player as new default", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                    Switch(
                        checked = rememberSession,
                        onCheckedChange = {
                            rememberSession = it
                            com.example.zubflix.util.AudioSettings.setRememberSessionBoostEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFF1E27))
                    )
                }

                // Dialogue Clarity Enhancement
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dialogue Clarity & Intelligibility", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Equalize speech frequencies to hear whisper dialogue clearly over background effects", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                    Switch(
                        checked = dialogueClarity,
                        onCheckedChange = {
                            dialogueClarity = it
                            com.example.zubflix.util.AudioSettings.setDialogueClarityEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFF1E27))
                    )
                }

                // Extended Gesture Swipe Boost (100% - 200%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Extended Gesture Swipe (up to 200%)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Allow right-side vertical swipe in player to boost beyond 100% up to 200%", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                    Switch(
                        checked = gestureExtendedBoost,
                        onCheckedChange = {
                            gestureExtendedBoost = it
                            com.example.zubflix.util.AudioSettings.setGestureExtendedBoostEnabled(context, it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFF1E27))
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1E27))
            ) {
                Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
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
fun DialogRadioOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(150),
        label = "opt_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) Color(0xFF262C36)
                else if (isSelected) Color(0xFFE50914).copy(alpha = 0.2f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                color = if (isFocused) Color.White else if (isSelected) Color(0xFFE50914) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE50914))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (isFocused || isSelected) Color.White else Color(0xFFC9D1D9),
            fontSize = 14.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CacheDurationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cacheHours by remember { mutableIntStateOf(com.example.zubflix.util.CacheSettings.getMetadataCacheHours(context)) }
    val options = listOf(1 to "1 Hour", 6 to "6 Hours", 12 to "12 Hours", 24 to "24 Hours", 48 to "48 Hours")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Metadata Cache Duration", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (hours, label) ->
                    val isSelected = cacheHours == hours
                    DialogRadioOptionItem(
                        label = label,
                        isSelected = isSelected,
                        onClick = {
                            com.example.zubflix.util.CacheSettings.setMetadataCacheHours(context, hours)
                            cacheHours = hours
                            Toast.makeText(context, "Cache duration set to $label", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF8B949E)) }
        }
    )
}

@Composable
fun MaxCacheSizeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var maxMb by remember { mutableIntStateOf(com.example.zubflix.util.CacheSettings.getMaxCacheSizeMb(context)) }
    val options = listOf(50 to "50 MB", 100 to "100 MB", 250 to "250 MB", 500 to "500 MB")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Maximum Disk Cache Size", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (mb, label) ->
                    val isSelected = maxMb == mb
                    DialogRadioOptionItem(
                        label = label,
                        isSelected = isSelected,
                        onClick = {
                            com.example.zubflix.util.CacheSettings.setMaxCacheSizeMb(context, mb)
                            maxMb = mb
                            Toast.makeText(context, "Max cache size set to $label", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF8B949E)) }
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
        containerColor = Color(0xFF0E1217),
        shape = RoundedCornerShape(16.dp),
        title = null,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                // App Emblem
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x25FF1E27))
                        .border(1.dp, Color(0x60FF1E27), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "ZubFlix Logo",
                        tint = Color(0xFFFF1E27),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Title & Version
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ZubFlix",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Version 1.0.1 (Build 2) • Universal TV & Mobile",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )
                }

                // Developer Spotlight Card
                val devInteractionSource = remember { MutableInteractionSource() }
                val isDevFocused by devInteractionSource.collectIsFocusedAsState()
                val devScale by animateFloatAsState(
                    targetValue = if (isDevFocused) 1.03f else 1.0f,
                    animationSpec = tween(150),
                    label = "dev_card_scale"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = devScale
                            scaleY = devScale
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDevFocused) Color(0xFF1F2530) else Color(0xFF151921))
                        .border(
                            width = if (isDevFocused) 1.5.dp else 1.dp,
                            color = if (isDevFocused) Color.White else Color(0xFF262D3D),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(
                            interactionSource = devInteractionSource,
                            indication = null,
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rubayet123"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        .focusable(interactionSource = devInteractionSource)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar / Dev Icon
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE50914)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "RA",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Rubayet Alam",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x30FF1E27))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "CREATOR",
                                    color = Color(0xFFFF4B55),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "@rubayet123 on GitHub",
                            color = Color(0xFF8B949E),
                            fontSize = 11.5.sp
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open GitHub Profile",
                        tint = if (isDevFocused) Color.White else Color(0xFF8B949E),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Tech Stack Tags
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    listOf("Kotlin", "Jetpack Compose", "Media3", "Android TV").forEachIndexed { index, tag ->
                        if (index > 0) {
                            Text(
                                text = "•",
                                color = Color(0xFF484F58),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        Text(
                            text = tag,
                            color = Color(0xFF8B949E),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "Crafted for premium streaming on TV & Handheld devices.",
                    color = Color(0xFF6E7681),
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    )
}

@Composable
fun DialogCheckboxOptionItem(
    label: String,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(150),
        label = "cb_opt_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) Color(0xFF262C36)
                else if (isChecked) Color(0xFFE50914).copy(alpha = 0.2f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused) 2.dp else if (isChecked) 1.dp else 0.dp,
                color = if (isFocused) Color.White else if (isChecked) Color(0xFFE50914) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFFE50914),
                uncheckedColor = Color(0xFF8B949E)
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (isFocused || isChecked) Color.White else Color(0xFFC9D1D9),
            fontSize = 14.sp,
            fontWeight = if (isChecked || isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun BufferSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedBuffer by remember { mutableStateOf(PlaybackSettings.getBufferSizeSeconds(context)) }

    val bufferOptions = listOf(
        PlaybackSettings.BUFFER_SIZE_120 to "Balanced (120s Max) - Default",
        PlaybackSettings.BUFFER_SIZE_180 to "Ultra Buffer (180s Max)",
        PlaybackSettings.BUFFER_SIZE_45 to "Low Memory (45s Max)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("ExoPlayer Video Buffer Size", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Configure maximum video segment pre-buffering. Higher buffer prevents stuttering on slow networks, while lower buffer saves RAM and improves initial seek speeds.",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(bufferOptions) { (seconds, label) ->
                        val isSelected = selectedBuffer == seconds || (selectedBuffer == PlaybackSettings.BUFFER_SIZE_AUTO && seconds == PlaybackSettings.BUFFER_SIZE_120)
                        DialogRadioOptionItem(
                            label = label,
                            isSelected = isSelected,
                            onClick = {
                                selectedBuffer = seconds
                                PlaybackSettings.setBufferSizeSeconds(context, seconds)
                                Toast.makeText(context, "Buffer set to ${PlaybackSettings.getBufferSizeLabel(seconds)}", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
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
                Text("Done", color = Color.White)
            }
        }
    )
}

@Composable
fun QualityChipsSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedChips by remember { mutableStateOf(PlaybackSettings.getVisibleQualityChips(context)) }

    val qualityOptions = listOf(
        "4K" to "4K / Ultra HD (2160p)",
        "1080p" to "1080p (Full HD)",
        "720p" to "720p (HD)",
        "480p" to "480p (SD)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Text("Visible Quality Filter Chips", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Select which resolution quality pills appear in the stream selection filter bar. 'All' and Provider chips (PenguPlay, BDIX, Local Scrapers, etc.) will always remain visible.",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            selectedChips = PlaybackSettings.ALL_QUALITY_CHIPS
                            PlaybackSettings.setVisibleQualityChips(context, selectedChips)
                        }
                    ) {
                        Text("Select All", color = Color(0xFF58A6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            selectedChips = emptySet()
                            PlaybackSettings.setVisibleQualityChips(context, selectedChips)
                        }
                    ) {
                        Text("Deselect All", color = Color(0xFFF85149), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    qualityOptions.forEach { (code, label) ->
                        val isChecked = selectedChips.contains(code)
                        DialogCheckboxOptionItem(
                            label = label,
                            isChecked = isChecked,
                            onToggle = {
                                val updated = if (isChecked) {
                                    selectedChips - code
                                } else {
                                    selectedChips + code
                                }
                                selectedChips = updated
                                PlaybackSettings.setVisibleQualityChips(context, updated)
                            }
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
                Text("Done", color = Color.White)
            }
        }
    )
}

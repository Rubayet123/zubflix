# Guide: Dynamic Provider-Based Home Screens in Nuvio

In the standard Nuvio architecture, the home screen is typically static or "fixed," fetching metadata (titles, posters, details) from centralized catalogs like **Cinemeta** or **TMDB**. The scrapers are then only invoked downstream to search for streaming links/embeds when a specific item is selected. 

By contrast, a **dynamic provider-based home screen** enables the app to dynamically change the content displayed on the main dashboard according to the active provider selected by the user. If a user selects a provider like `iccftp` or `vegamovies`, the home screen dynamically triggers that provider's scraper/plugin script to scrape its homepage listings and show them directly.

This guide details the architectural changes required to bring this feature to the standard Nuvio application, followed by a reusable **AI System Prompt** you can use to implement it automatically in another codebase.

---

## Part 1: Architectural Changes & Implementation Guide

To adapt a standard metadata-first app (like standard Nuvio) to support dynamic home screens powered by scrapers, you must implement changes across three layers:
1. **The Plugin/Scraper Interface Layer** (JS/Kotlin)
2. **State Management & Selection Layer** (Kotlin ViewModels)
3. **The User Interface Layer** (Jetpack Compose)

### 1. The Plugin/Scraper Interface Layer
Standard scrapers only implement a `loadLinks` (or search) function. To support home page listings, you must expand the JavaScript plugin interface and the Kotlin bridge to support a `getHomePage` or `getMainPage` method.

#### JavaScript Plugin Extension:
In each `.js` provider script, implement a `getMainPage` or `getHomePage` function:
```javascript
async function getMainPage(page = 1, category = null) {
    // 1. Fetch the provider's actual home page/catalog endpoint
    // 2. Parse the HTML or JSON response
    // 3. Map items to a standardized search result format:
    return [
        {
            title: "Movie Title",
            id: "provider_specific_id_or_url",
            poster: "https://.../poster.jpg",
            type: "movie", // or "series"
            rating: 8.5
        }
    ];
}
```

#### Kotlin Bridge (JS Engine Wrapper) Extension:
Your JS execution manager (e.g., QuickJS or WebView bridge) must expose a method to call this JS function:
```kotlin
suspend fun fetchProviderHome(pluginId: String, page: Int, category: String?): List<ScrapedItem> {
    val jsCode = pluginManager.getPluginJsContent(context, pluginId) ?: return emptyList()
    // Execute JS code inside the engine and invoke `getMainPage(page, category)`
    val jsonResult = jsEngine.evaluate("getMainPage($page, ${category?.let { "'$it'" } ?: "null"})")
    return parseScrapedItems(jsonResult)
}
```

---

### 2. State Management & ViewModel Layer
Instead of hardcoding a call to TMDB/Cinemeta APIs on home screen initialization, the `HomeViewModel` must track the "Active Source" and conditionally switch its fetching strategy.

#### Active Source Tracker:
```kotlin
enum class HomeSourceType {
    METADATA_CATALOG, // TMDB/Cinemeta
    PROVIDER_SCRAPER  // Dynamic scraper
}

class HomeViewModel(
    private val pluginManager: NuvioPluginManager,
    private val apiRepository: ApiRepository
) : ViewModel() {

    private val _currentSourceType = MutableStateFlow(HomeSourceType.METADATA_CATALOG)
    val currentSourceType: StateFlow<HomeSourceType> = _currentSourceType

    private val _activeProviderId = MutableStateFlow<String?>(null)
    val activeProviderId: StateFlow<String?> = _activeProviderId

    private val _homePageData = MutableStateFlow<UiState<List<HomeListSection>>>(UiState.Loading)
    val homePageData: StateFlow<UiState<List<HomeListSection>>> = _homePageData

    fun selectSource(sourceType: HomeSourceType, providerId: String? = null) {
        _currentSourceType.value = sourceType
        _activeProviderId.value = providerId
        loadHomeScreenData()
    }

    fun loadHomeScreenData() {
        viewModelScope.launch {
            _homePageData.value = UiState.Loading
            try {
                if (_currentSourceType.value == HomeSourceType.METADATA_CATALOG) {
                    // Standard flow: Fetch from TMDB/Cinemeta
                    val data = apiRepository.getTrendingMoviesAndSeries()
                    _homePageData.value = UiState.Success(data)
                } else {
                    // Dynamic flow: Trigger selected provider's main page scraper
                    val providerId = _activeProviderId.value ?: return@launch
                    val items = pluginManager.fetchProviderHome(providerId, page = 1, category = null)
                    
                    // Wrap items into standard visual row categories
                    val section = HomeListSection(
                        title = "Trending on ${providerId.replaceFirstChar { it.uppercase() }}",
                        items = items
                    )
                    _homePageData.value = UiState.Success(listOf(section))
                }
            } catch (e: Exception) {
                _homePageData.value = UiState.Error(e.message ?: "Failed to load content")
            }
        }
    }
}
```

---

### 3. Jetpack Compose UI Layer
Create a dropdown menu, a top sliding horizontal tab, or a bottom sheet selector where users can switch between "Global Catalog (TMDB/Cinemeta)" and installed provider plug-ins.

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val sourceType by viewModel.currentSourceType.collectAsState()
    val activeProviderId by viewModel.activeProviderId.collectAsState()
    val uiState by viewModel.homePageData.collectAsState()
    val installedPlugins by viewModel.installedPlugins.collectAsState()

    Scaffold(
        topBar = {
            HomeTopBar(
                currentSourceType = sourceType,
                activeProviderId = activeProviderId,
                providers = installedPlugins,
                onProviderSelected = { provider ->
                    if (provider == null) {
                        viewModel.selectSource(HomeSourceType.METADATA_CATALOG)
                    } else {
                        viewModel.selectSource(HomeSourceType.PROVIDER_SCRAPER, provider.id)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (val state = uiState) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> ErrorText(state.message)
                is UiState.Success -> {
                    LazyColumn {
                        items(state.data) { section ->
                            HomeSectionRow(title = section.title, items = section.items)
                        }
                    }
                }
            }
        }
    }
}
```

---

## Part 2: AI System Prompt for Auto-Implementation

Copy and paste the exact prompt below into your AI Coding Companion/Agent to automatically modify your codebase and implement this feature.

```text
# Context & Goal
Our application currently uses a fixed metadata home screen showing unified recommendations from TMDB/Cinemeta. We want to implement a "Dynamic Provider-Based Home Screen" feature. When a user selects a specific provider/plugin (e.g., 'iccftp', 'vegamovies'), the home screen should change dynamically, executing that provider's custom JS scraping code to render its own home screen recommendations.

# Technical Requirements & Step-by-Step Task

1. **JS Plugin API Upgrade**:
   - Update the scraper/plugin execution bridge (Kotlin/JS Engine) to accept and execute a dynamic `getMainPage(page, category)` call.
   - Fall back gracefully to standard search/metadata queries if a plugin does not implement a homepage scraper.

2. **Source Selection State (ViewModel)**:
   - Expand the Home Screen's ViewModel to track:
     * `currentSourceType`: Either `CATALOG` (TMDB) or `PROVIDER` (Dynamic Scraper).
     * `activeProviderId`: The ID of the currently selected provider.
   - Refactor the data loading method to check if the source is `PROVIDER`. If so, bypass standard TMDB api requests and instead run the active provider's scraper script to fetch lists of items.

3. **Standardized UI Model Mapping**:
   - Ensure items scraped from the provider homepage are mapped safely to our application's unified Card UI models (`title`, `id`/`url`, `posterUrl`, `type`, `score` / `rating`).

4. **Jetpack Compose UI Upgrades**:
   - Implement a clean selector (e.g., a modern, scrollable Row of chips or a TopBar drop-down menu) listing all active providers alongside a default option called "Global/Default Catalog".
   - Clicking a chip should update the selected provider in the ViewModel and trigger an immediate reload/scrape of the home screen content.
   - Ensure the `LazyColumn` displays the parsed categories and cards responsively.

Before editing, view the files representing the Home Screen UI, the Home Viewmodel, and the plugin executing bridge to find the perfect integration points. Execute the changes safely using type safety, standard coroutines, state flows, and Material Design 3.
```

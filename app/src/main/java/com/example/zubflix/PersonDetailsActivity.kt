package com.example.zubflix

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.R
import com.example.databinding.ActivityPersonDetailsBinding
import com.example.zubflix.adapter.PersonCreditAdapter
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.utils.TmdbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PersonDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonDetailsBinding
    private lateinit var adapter: PersonCreditAdapter

    private var personId: Int = -1
    private var personName: String = ""
    private var personProfile: String? = null

    private var allCredits = listOf<TmdbHelper.PersonCredit>()
    private var currentFilter = FilterType.ALL
    private var currentSort = SortType.LATEST
    private var isBioExpanded = false

    private enum class FilterType {
        ALL, MOVIES, SERIES
    }

    private enum class SortType {
        LATEST, POPULAR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarInset)
            windowInsets
        }

        personId = intent.getIntExtra("PERSON_ID", -1)
        personName = intent.getStringExtra("PERSON_NAME") ?: ""
        personProfile = intent.getStringExtra("PERSON_PROFILE")

        setupToolbar()
        setupUI()
        setupRecyclerView()
        setupFilterClicks()
        loadPersonDetails()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = personName.ifEmpty { "Cast & Crew" }
    }

    private fun setupUI() {
        binding.tvName.text = personName.ifEmpty { "Cast Member" }

        // Load pre-passed thumbnail if available
        if (!personProfile.isNullOrEmpty()) {
            Glide.with(this)
                .load(personProfile)
                .placeholder(R.drawable.ic_avatar_placeholder)
                .error(R.drawable.ic_avatar_placeholder)
                .centerCrop()
                .into(binding.imgProfile)
        }

        binding.tvBioToggle.setOnClickListener {
            toggleBioExpansion()
        }
    }

    private fun setupRecyclerView() {
        adapter = PersonCreditAdapter { credit ->
            val isSeries = credit.mediaType == "tv"
            val item = StreamingItem(
                id = "tmdb:${if (isSeries) "tv" else "movie"}:${credit.id}",
                title = credit.title,
                isSeries = isSeries,
                imageUrl = credit.posterPath,
                backdropUrl = credit.backdropPath,
                year = credit.year,
                rating = credit.voteAverage,
                description = credit.overview,
                sourceName = "Nuvio"
            )
            DetailsActivity.start(this, item)
        }

        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val spanCount = when {
            screenWidthDp >= 900 -> 6
            screenWidthDp >= 600 -> 4
            else -> 3
        }

        binding.rvFilmography.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvFilmography.adapter = adapter
    }

    private fun setupFilterClicks() {
        binding.btnFilterAll.setOnClickListener {
            setFilter(FilterType.ALL)
        }
        binding.btnFilterMovies.setOnClickListener {
            setFilter(FilterType.MOVIES)
        }
        binding.btnFilterSeries.setOnClickListener {
            setFilter(FilterType.SERIES)
        }

        binding.btnSortLatest.setOnClickListener {
            setSort(SortType.LATEST)
        }
        binding.btnSortPopular.setOnClickListener {
            setSort(SortType.POPULAR)
        }
    }

    private fun setFilter(filter: FilterType) {
        if (currentFilter == filter) return
        currentFilter = filter

        // Update button states
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_chip_selected)
        val unselectedBg = ContextCompat.getDrawable(this, R.drawable.bg_chip_unselected)
        val colorSelected = ContextCompat.getColor(this, android.R.color.white)
        val colorUnselected = ContextCompat.getColor(this, android.R.color.darker_gray)

        binding.btnFilterAll.background = if (filter == FilterType.ALL) selectedBg else unselectedBg
        binding.btnFilterAll.setTextColor(if (filter == FilterType.ALL) colorSelected else colorUnselected)

        binding.btnFilterMovies.background = if (filter == FilterType.MOVIES) selectedBg else unselectedBg
        binding.btnFilterMovies.setTextColor(if (filter == FilterType.MOVIES) colorSelected else colorUnselected)

        binding.btnFilterSeries.background = if (filter == FilterType.SERIES) selectedBg else unselectedBg
        binding.btnFilterSeries.setTextColor(if (filter == FilterType.SERIES) colorSelected else colorUnselected)

        applyFilter()
    }

    private fun setSort(sort: SortType) {
        if (currentSort == sort) return
        currentSort = sort

        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_chip_selected)
        val unselectedBg = ContextCompat.getDrawable(this, R.drawable.bg_chip_unselected)
        val colorSelected = ContextCompat.getColor(this, android.R.color.white)
        val colorUnselected = ContextCompat.getColor(this, android.R.color.darker_gray)

        binding.btnSortLatest.background = if (sort == SortType.LATEST) selectedBg else unselectedBg
        binding.btnSortLatest.setTextColor(if (sort == SortType.LATEST) colorSelected else colorUnselected)

        binding.btnSortPopular.background = if (sort == SortType.POPULAR) selectedBg else unselectedBg
        binding.btnSortPopular.setTextColor(if (sort == SortType.POPULAR) colorSelected else colorUnselected)

        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            FilterType.ALL -> allCredits
            FilterType.MOVIES -> allCredits.filter { it.mediaType == "movie" }
            FilterType.SERIES -> allCredits.filter { it.mediaType == "tv" }
        }

        val sorted = when (currentSort) {
            SortType.LATEST -> filtered.sortedWith(
                compareByDescending<TmdbHelper.PersonCredit> { it.releaseDate ?: "" }
                    .thenByDescending { it.popularity }
            )
            SortType.POPULAR -> filtered.sortedByDescending { it.popularity }
        }

        binding.tvCreditsCount.text = "${sorted.size} Titles"
        adapter.submitList(sorted)

        binding.tvEmptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFilmography.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleBioExpansion() {
        isBioExpanded = !isBioExpanded
        if (isBioExpanded) {
            binding.tvBiography.maxLines = Int.MAX_VALUE
            binding.tvBioToggle.text = "Show Less ▲"
        } else {
            binding.tvBiography.maxLines = 3
            binding.tvBioToggle.text = "Read More ▼"
        }
    }

    private fun loadPersonDetails() {
        binding.loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    TmdbHelper.fetchPersonDetails(
                        this@PersonDetailsActivity,
                        personId.takeIf { it > 0 },
                        personName
                    )
                }

                if (details != null && !isDestroyed && !isFinishing) {
                    displayPersonDetails(details)
                } else if (!isDestroyed && !isFinishing) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "Could not load details for $personName"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isDestroyed && !isFinishing) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "Error loading filmography: ${e.message}"
                }
            } finally {
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun displayPersonDetails(details: TmdbHelper.PersonDetails) {
        binding.tvName.text = details.name
        supportActionBar?.title = details.name

        // Profile Avatar
        val profileUrl = details.profilePath ?: personProfile
        if (!profileUrl.isNullOrEmpty() && !isDestroyed && !isFinishing) {
            Glide.with(this)
                .load(profileUrl)
                .placeholder(R.drawable.ic_avatar_placeholder)
                .error(R.drawable.ic_avatar_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade())
                .centerCrop()
                .into(binding.imgProfile)
        }

        // Department & Place of Birth
        val dept = details.knownForDepartment ?: "Acting"
        val place = details.placeOfBirth
        binding.tvDepartmentOrigin.text = if (!place.isNullOrBlank()) "$dept • $place" else dept

        // Birthday & Age
        val birthText = formatBirthInfo(details.birthday, details.deathday)
        if (!birthText.isNullOrBlank()) {
            binding.tvBirthInfo.visibility = View.VISIBLE
            binding.tvBirthInfo.text = birthText
        } else {
            binding.tvBirthInfo.visibility = View.GONE
        }

        // Biography
        if (!details.biography.isNullOrBlank()) {
            binding.bioContainer.visibility = View.VISIBLE
            binding.tvBiography.text = details.biography
            binding.tvBiography.maxLines = 3
            binding.tvBioToggle.visibility = if (details.biography.length > 160) View.VISIBLE else View.GONE
        } else {
            binding.bioContainer.visibility = View.GONE
        }

        // Filmography Credits
        allCredits = details.credits
        applyFilter()
    }

    private fun formatBirthInfo(birthdayStr: String?, deathdayStr: String?): String? {
        if (birthdayStr.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val birthDate = format.parse(birthdayStr) ?: return null

            val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
            val formattedBirth = displayFormat.format(birthDate)

            if (!deathdayStr.isNullOrBlank()) {
                val deathDate = format.parse(deathdayStr)
                val formattedDeath = if (deathDate != null) displayFormat.format(deathDate) else deathdayStr
                "Born: $formattedBirth • Died: $formattedDeath"
            } else {
                val age = calculateAge(birthDate)
                if (age > 0) "Born: $formattedBirth (Age $age)" else "Born: $formattedBirth"
            }
        } catch (e: Exception) {
            "Born: $birthdayStr"
        }
    }

    private fun calculateAge(birthDate: Date): Int {
        val birthCal = Calendar.getInstance().apply { time = birthDate }
        val nowCal = Calendar.getInstance()
        var age = nowCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
        if (nowCal.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) {
            age--
        }
        return age
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

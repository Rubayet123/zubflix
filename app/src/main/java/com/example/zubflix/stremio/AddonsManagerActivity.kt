package com.example.zubflix.stremio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AddonsManagerActivity : AppCompatActivity() {

    private lateinit var rvAddons: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: AddonAdapter

    private val client = OkHttpClient()
    private val gson = Gson()
    
    private var allAddons = listOf<StremioAddon>()
    private var currentFilter = FilterType.ALL

    enum class FilterType { ALL, SUBTITLES, STREAMS, OTHERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_addons_manager)

        val tvTitle = findViewById<View>(R.id.tv_title)
        tvTitle?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val marginParams = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (marginParams != null) {
                    val baseMargin = (32 * resources.displayMetrics.density).toInt()
                    marginParams.topMargin = baseMargin + statusBarInset
                    v.layoutParams = marginParams
                }
                windowInsets
            }
        }

        rvAddons = findViewById(R.id.rv_addons)
        rvAddons.layoutManager = LinearLayoutManager(this)

        tabLayout = findViewById(R.id.tab_layout)
        
        tabLayout.addTab(tabLayout.newTab().setText("All Addons"))
        tabLayout.addTab(tabLayout.newTab().setText("Subtitles"))
        tabLayout.addTab(tabLayout.newTab().setText("Streams"))
        tabLayout.addTab(tabLayout.newTab().setText("Others"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilter = when (tab?.position) {
                    0 -> FilterType.ALL
                    1 -> FilterType.SUBTITLES
                    2 -> FilterType.STREAMS
                    3 -> FilterType.OTHERS
                    else -> FilterType.ALL
                }
                filterAddons()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        adapter = AddonAdapter(
            onRemove = { addon ->
                StremioAddonManager.removeAddon(this, addon.manifestUrl)
                loadAddons()
            },
            onEdit = { addon ->
                showEditAddonDialog(addon)
            },
            onToggleEnabled = { addon ->
                StremioAddonManager.toggleAddonEnabled(this, addon.manifestUrl)
                loadAddons()
            },
            onMove = { addon, up ->
                StremioAddonManager.moveAddon(this, addon.manifestUrl, up)
                loadAddons()
            }
        )
        rvAddons.adapter = adapter

        val btnAddAddon = findViewById<View>(R.id.btn_add_addon)
        applyTvFocusAnimation(btnAddAddon)
        btnAddAddon.setOnClickListener {
            showAddAddonDialog()
        }

        loadAddons()
    }

    private fun applyTvFocusAnimation(view: View) {
        view.isFocusable = true
        val density = view.resources.displayMetrics.density
        val elevationPx = 4 * density
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    private fun loadAddons() {
        allAddons = StremioAddonManager.getInstalledAddons(this)
        filterAddons()
    }
    
    private fun filterAddons() {
        val filtered = allAddons.filter { addon ->
            when (currentFilter) {
                FilterType.ALL -> true
                FilterType.SUBTITLES -> addon.resources.contains("subtitles")
                FilterType.STREAMS -> addon.resources.contains("stream")
                FilterType.OTHERS -> !addon.resources.contains("subtitles") && !addon.resources.contains("stream")
            }
        }
        adapter.setAddons(filtered)
    }

    private fun showAddAddonDialog() {
        val container = android.widget.FrameLayout(this)
        val input = EditText(this).apply {
            hint = "https://v3-cinemeta.strem.io/manifest.json"
            isSingleLine = true
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val density = resources.displayMetrics.density
        val marginPx = (24 * density).toInt()
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = marginPx
            rightMargin = marginPx
            topMargin = (12 * density).toInt()
            bottomMargin = (12 * density).toInt()
        }
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Nuvio Addon")
            .setMessage("Paste the manifest.json URL of the Nuvio Addon.")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    fetchAndAddAddon(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditAddonDialog(addon: StremioAddon) {
        val container = android.widget.FrameLayout(this)
        val input = EditText(this).apply {
            setText(addon.manifestUrl)
            setSelection(addon.manifestUrl.length)
            isSingleLine = true
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val density = resources.displayMetrics.density
        val marginPx = (24 * density).toInt()
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = marginPx
            rightMargin = marginPx
            topMargin = (12 * density).toInt()
            bottomMargin = (12 * density).toInt()
        }
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Nuvio Addon")
            .setMessage("Edit the manifest.json URL of the Nuvio Addon.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    fetchAndUpdateAddon(addon.manifestUrl, url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchAndUpdateAddon(oldUrl: String, newUrl: String) {
        val httpUrl = newUrl.replace("stremio://", "https://")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(httpUrl).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val name = json.getString("name")
                    val description = json.optString("description", "")
                    val version = json.optString("version", "")
                    val resourcesList = mutableListOf<String>()
                    val resourcesArray = json.optJSONArray("resources")
                    if (resourcesArray != null) {
                        for (i in 0 until resourcesArray.length()) {
                            val element = resourcesArray.get(i)
                            if (element is String) {
                                resourcesList.add(element)
                            } else if (element is JSONObject) {
                                val resName = element.optString("name")
                                if (resName.isNotEmpty()) {
                                    resourcesList.add(resName)
                                }
                            }
                        }
                    }
                    
                    val addon = StremioAddon(
                        manifestUrl = httpUrl,
                        name = name,
                        description = description,
                        version = version,
                        resources = resourcesList
                    )

                    withContext(Dispatchers.Main) {
                        StremioAddonManager.updateAddon(this@AddonsManagerActivity, oldUrl, addon)
                        Toast.makeText(this@AddonsManagerActivity, "Addon '$name' updated successfully!", Toast.LENGTH_SHORT).show()
                        loadAddons()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AddonsManagerActivity, "Failed to fetch manifest from new URL.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddonsManagerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchAndAddAddon(url: String) {
        val httpUrl = url.replace("stremio://", "https://")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(httpUrl).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val name = json.getString("name")
                    val description = json.optString("description", "")
                    val version = json.optString("version", "")
                    val resourcesList = mutableListOf<String>()
                    val resourcesArray = json.optJSONArray("resources")
                    if (resourcesArray != null) {
                        for (i in 0 until resourcesArray.length()) {
                            val element = resourcesArray.get(i)
                            if (element is String) {
                                resourcesList.add(element)
                            } else if (element is JSONObject) {
                                val resName = element.optString("name")
                                if (resName.isNotEmpty()) {
                                    resourcesList.add(resName)
                                }
                            }
                        }
                    }
                    
                    val addon = StremioAddon(
                        manifestUrl = httpUrl,
                        name = name,
                        description = description,
                        version = version,
                        resources = resourcesList
                    )

                    withContext(Dispatchers.Main) {
                        StremioAddonManager.addAddon(this@AddonsManagerActivity, addon)
                        Toast.makeText(this@AddonsManagerActivity, "Addon '$name' added successfully!", Toast.LENGTH_SHORT).show()
                        loadAddons()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AddonsManagerActivity, "Failed to fetch manifest.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddonsManagerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

class AddonAdapter(
    private val onRemove: (StremioAddon) -> Unit,
    private val onEdit: (StremioAddon) -> Unit,
    private val onToggleEnabled: (StremioAddon) -> Unit,
    private val onMove: (StremioAddon, Boolean) -> Unit
) : RecyclerView.Adapter<AddonAdapter.AddonViewHolder>() {

    private val addons = mutableListOf<StremioAddon>()

    private fun applyTvFocusAnimation(view: View) {
        view.isFocusable = true
        val density = view.resources.displayMetrics.density
        val elevationPx = 4 * density
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    fun setAddons(newAddons: List<StremioAddon>) {
        addons.clear()
        addons.addAll(newAddons)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddonViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_addon, parent, false)
        return AddonViewHolder(view)
    }

    override fun onBindViewHolder(holder: AddonViewHolder, position: Int) {
        val addon = addons[position]
        holder.tvName.text = "${addon.name} ${addon.version?.let { "v$it" } ?: ""}"
        holder.tvDesc.text = addon.description ?: addon.manifestUrl
        
        val types = mutableListOf<String>()
        if (addon.resources.contains("subtitles")) types.add("Subtitles")
        if (addon.resources.contains("stream")) types.add("Streams")
        if (addon.resources.contains("meta")) types.add("Metadata")
        
        holder.tvType.text = if (types.isEmpty()) "Others" else types.joinToString(", ")

        applyTvFocusAnimation(holder.switchEnabled)
        applyTvFocusAnimation(holder.btnUp)
        applyTvFocusAnimation(holder.btnDown)
        applyTvFocusAnimation(holder.btnEdit)
        applyTvFocusAnimation(holder.btnRemove)

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = addon.isEnabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != addon.isEnabled) {
                onToggleEnabled(addon)
            }
        }

        holder.btnRemove.setOnClickListener {
            onRemove(addon)
        }

        holder.btnEdit.setOnClickListener {
            onEdit(addon)
        }
        
        holder.btnUp.setOnClickListener {
            onMove(addon, true)
        }
        
        holder.btnDown.setOnClickListener {
            onMove(addon, false)
        }
    }

    override fun getItemCount(): Int = addons.size

    class AddonViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_addon_name)
        val tvDesc: TextView = view.findViewById(R.id.tv_addon_desc)
        val tvType: TextView = view.findViewById(R.id.tv_addon_type)
        val switchEnabled: androidx.appcompat.widget.SwitchCompat = view.findViewById(R.id.switch_addon_enabled)
        val btnUp: ImageButton = view.findViewById(R.id.btn_up)
        val btnDown: ImageButton = view.findViewById(R.id.btn_down)
        val btnRemove: ImageButton = view.findViewById(R.id.btn_remove)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
    }
}

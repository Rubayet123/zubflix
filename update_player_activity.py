import sys

with open('app/src/main/java/com/example/zubflix/PlayerActivity.kt', 'r') as f:
    content = f.read()

# Add videoNames
content = content.replace("private var videoUrls: List<String> = emptyList()", "private var videoUrls: List<String> = emptyList()\n    private var videoNames: List<String> = emptyList()")
content = content.replace("videoUrls = intent.getStringArrayListExtra(\"VIDEO_URLS\")?.toList() ?: emptyList()", "videoUrls = intent.getStringArrayListExtra(\"VIDEO_URLS\")?.toList() ?: emptyList()\n        videoNames = intent.getStringArrayListExtra(\"VIDEO_NAMES\")?.toList() ?: emptyList()")

# Find setupListeners and add btnSources click listener
setup_listener_target = "val btnAudioSub = findViewById<LinearLayout>(R.id.btn_audio_sub)"
setup_listener_add = """val btnSources = findViewById<LinearLayout>(R.id.btn_sources)
        btnSources?.setOnClickListener {
            showSourceSidebar()
        }
        
        binding.btnCloseSourceSidebar.setOnClickListener {
            hideSourceSidebar()
        }

        """
content = content.replace(setup_listener_target, setup_listener_add + setup_listener_target)

# Add showSourceSidebar, hideSourceSidebar, and populateSourceSidebar functions
sidebar_funcs = """    private fun showSourceSidebar() {
        populateSourceSidebar()
        binding.sidebarScrim.visibility = View.VISIBLE
        binding.sidebarScrim.alpha = 0f
        binding.sidebarScrim.animate().alpha(1f).setDuration(300).start()

        binding.sourceSidebarContainer.visibility = View.VISIBLE
        val sidebarWidth = 380f * resources.displayMetrics.density
        binding.sourceSidebarContainer.translationX = sidebarWidth
        binding.sourceSidebarContainer.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction {
                if (binding.sourcesContainer.childCount > 0) {
                    binding.sourcesContainer.getChildAt(0).requestFocus()
                } else {
                    binding.btnCloseSourceSidebar.requestFocus()
                }
            }
            .start()

        binding.playerView.hideController()
    }

    private fun hideSourceSidebar() {
        binding.sidebarScrim.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.sidebarScrim.visibility = View.GONE }
            .start()

        val sidebarWidth = binding.sourceSidebarContainer.width.toFloat().let { if (it == 0f) 380f * resources.displayMetrics.density else it }
        binding.sourceSidebarContainer.animate()
            .translationX(sidebarWidth)
            .setDuration(300)
            .withEndAction { binding.sourceSidebarContainer.visibility = View.GONE }
            .start()
    }

    private fun populateSourceSidebar() {
        binding.sourcesContainer.removeAllViews()

        for (i in videoUrls.indices) {
            val url = videoUrls[i]
            val name = if (i < videoNames.size) videoNames[i] else "Source ${i + 1}"
            val isSelected = (i == currentUrlIndex)

            val rowView = createTrackRow(name, isSelected) {
                if (!isSelected) {
                    currentUrlIndex = i
                    hideSourceSidebar()
                    playCurrentVideo()
                    Toast.makeText(this, "Switched to $name", Toast.LENGTH_SHORT).show()
                }
            }
            binding.sourcesContainer.addView(rowView)
        }
    }

"""

content = content.replace("private fun showSidebar() {", sidebar_funcs + "    private fun showSidebar() {")

# Also hide source sidebar when scrim is clicked
scrim_target = """binding.sidebarScrim.setOnClickListener {
            hideSidebar()
        }"""
scrim_replace = """binding.sidebarScrim.setOnClickListener {
            hideSidebar()
            hideSourceSidebar()
        }"""
content = content.replace(scrim_target, scrim_replace)

with open('app/src/main/java/com/example/zubflix/PlayerActivity.kt', 'w') as f:
    f.write(content)

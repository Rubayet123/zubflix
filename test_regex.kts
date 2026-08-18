val titles = listOf(
    "Black Box (2026)",
    "Hunting Jessica Brok (Hindi + English)",
    "Passenger (2026) (Hindi + English)",
    "Lingam Season 01 (2026) Completed"
)

for (title in titles) {
    var cleanTitle = title.replace(Regex("\\(.*?\\)"), "").trim()
    cleanTitle = cleanTitle.replace(Regex("(?i)season\\s*\\d+"), "").trim()
    cleanTitle = cleanTitle.replace(Regex("(?i)completed"), "").trim()
    cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()
    println("'$title' -> '$cleanTitle'")
}

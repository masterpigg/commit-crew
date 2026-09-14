package com.fll.pushtogithub.shared

import kotlin.math.abs

/** A folder inside the GitHub repo representing a SPIKE Prime project. */
data class ProjectFolder(
    val name: String,
    val path: String,
    val htmlUrl: String? = null
)

/** A commit entry from the file history. */
data class CommitInfo(
    val sha: String,
    val message: String,
    val date: String,           // ISO 8601
    val committerName: String
)

/** A task card / Kanban item mapped to a GitHub issue & Project v2 fields. */
data class KanbanCard(
    val number: Int,
    var title: String,
    var body: String,
    val state: String,       // "open" or "closed"
    var column: String,      // "todo", "doing", "done"
    val owners: MutableList<String>,
    val ownerColors: MutableMap<String, String> = mutableMapOf(),
    var category: String = "general", // "robot-game", "innovation-project", "general"
    val coreValues: MutableList<String> = mutableListOf(),
    var projectItemId: String? = null,
    var projectId: String? = null,
    var statusFieldId: String? = null,
    var ownerFieldId: String? = null
)

/** Result of a GitHub file commit operation. */
data class CommitResult(
    val path: String,
    val htmlUrl: String? = null
)

/** Resolve hex color string for a team member name. */
fun resolveOwnerColorHex(
    name: String,
    cardColors: Map<String, String> = emptyMap(),
    repoLabelColors: Map<String, String> = emptyMap()
): String {
    val lower = name.lowercase().trim()

    val cardColor = cardColors[name] ?: cardColors[lower] ?: cardColors["owner:$lower"]
    if (!cardColor.isNullOrBlank() && cardColor != "#1976D2") return cardColor

    val repoColor = repoLabelColors[lower] ?: repoLabelColors["owner:$lower"]
    if (!repoColor.isNullOrBlank()) return repoColor

    val defaultOptionsMap = mapOf(
        "polly" to "#BF3989",          // Pink
        "whiskers" to "#2563EB",         // Blue
        "bubbles" to "#8957E5",            // Purple
        "fido" to "#DA3633",          // Red
        "thumper" to "#F59E0B",         // Yellow
        "nibbles" to "#D97706",          // Orange
        "patches" to "#2EA043",        // Green
        "coach owl" to "#6E7681", // Gray
        "coach pigg" to "#6E7681"      // Gray
    )
    val defaultColor = defaultOptionsMap[lower]
    if (defaultColor != null) return defaultColor

    val palette = listOf(
        "#BF3989", "#2563EB", "#8957E5", "#DA3633",
        "#F59E0B", "#D97706", "#2EA043", "#6E7681"
    )
    val index = abs(lower.hashCode()) % palette.size
    return palette[index]
}

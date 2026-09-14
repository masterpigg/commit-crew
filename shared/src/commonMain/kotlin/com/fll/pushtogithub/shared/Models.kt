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
        "fido" to "#E53935",          // Red
        "whiskers" to "#F57C00",         // Orange
        "polly" to "#FFB300",           // Yellow
        "bubbles" to "#43A047",            // Green
        "nibbles" to "#1E88E5",          // Blue
        "thumper" to "#8E24AA",         // Purple
        "patches" to "#D81B60",        // Pink
        "coach owl" to "#546E7A", // Slate
        "coach pigg" to "#546E7A"      // Slate
    )
    val defaultColor = defaultOptionsMap[lower]
    if (defaultColor != null) return defaultColor

    val palette = listOf(
        "#E53935", "#F57C00", "#FFB300", "#43A047",
        "#1E88E5", "#8E24AA", "#D81B60", "#546E7A"
    )
    val index = abs(lower.hashCode()) % palette.size
    return palette[index]
}

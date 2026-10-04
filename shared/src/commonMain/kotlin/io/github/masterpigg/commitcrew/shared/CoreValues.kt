package io.github.masterpigg.commitcrew.shared

/**
 * Official FIRST LEGO League Core Values.
 * Supported across Code Commits, Snap Notes, and Task Board Issues.
 */
enum class CoreValue(
    val displayName: String,
    val labelKey: String,
    val hexColor: String,
    val description: String
) {
    DISCOVERY("🤝 Discovery", "discovery", "#0288D1", "We explore new skills and ideas."),
    INNOVATION("💡 Innovation", "innovation", "#F57C00", "We use creativity and persistence to solve problems."),
    IMPACT("📈 Impact", "impact", "#388E3C", "We apply what we learn to improve our world."),
    INCLUSION("🤝 Inclusion", "inclusion", "#7B1FA2", "We respect each other and embrace our differences."),
    TEAMWORK("🤝 Teamwork", "teamwork", "#C2185B", "We are stronger when we work together."),
    FUN("🎉 Fun", "fun", "#FBC02D", "We enjoy and celebrate what we do!");

    companion object {
        fun fromLabel(label: String): CoreValue? {
            val clean = label.lowercase().removePrefix("core-value:").trim()
            return entries.find { it.labelKey == clean || it.displayName.lowercase().contains(clean) }
        }
    }
}

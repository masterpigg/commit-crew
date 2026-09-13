package com.fll.pushtogithub

/**
 * Kid-friendly (ages 10-13) README templates for FIRST LEGO League team workspaces.
 *
 * Includes fill-in-the-blank placeholders ([Fill in: ...]) for kids to personalize,
 * linked team roster badges, and clear section navigation.
 */
object ReadmeTemplates {

    fun getRootReadme(
        owner: String,
        repo: String,
        teamRoster: List<String>,
        existingContent: String? = null
    ): String {
        val rosterLinks = teamRoster.joinToString("\n") { name ->
            val queryUrl = "https://github.com/$owner/$repo/issues?q=is%3Aissue+owner%3A$name"
            "* [**$name**]($queryUrl)"
        }

        // If existing content exists, preserve kid notes while updating roster
        if (!existingContent.isNullOrBlank()) {
            val rosterSection = "## 👥 Team Roster\n$rosterLinks"
            if (existingContent.contains("## 👥 Team Roster")) {
                return existingContent.replace(
                    Regex("(?s)## 👥 Team Roster.*?(?=##|\\z)"),
                    "$rosterSection\n\n"
                )
            }
        }

        return """
# 🏆 FIRST LEGO League Team Workspace

Welcome to our team's official GitHub workspace! Here we track our robot code, innovation project, and team notes for the season.

---

## 🎯 Team Info & Season Goals
* **Team Number:** `[Fill in: e.g. #12345]`
* **Team Name:** `[Fill in: e.g. The Brick Builders]`
* **Robot Name:** `[Fill in: e.g. Alpha Bot]`
* **Season Goal:** `[Fill in: e.g. Score 350+ points in Robot Game & present Innovation Project]`

---

## 👥 Team Roster
$rosterLinks

*(Tap a team member's name above to see all tasks assigned to them!)*

---

## 📂 Team Sections
* [🤖 Robot Game Code & Strategy](robot-game/)
* [💡 Innovation Project Notes & Research](innovation-project/)
* [📋 Team Meeting Notes & Journal](meeting-notes/)

---

*Powered by Push to Team GitHub 🚀*
        """.trimIndent()
    }

    fun getRobotGameReadme(): String = """
# 🤖 Robot Game Workspace

Welcome to the Robot Game hub! Here we store all our SPIKE Prime robot programs, mission strategies, and run plans.

---

## 🚀 Mission Strategy & Run Plans
* **Run 1:** `[Fill in: Missions targeted, e.g. Mission 01 Coral Reef & Mission 02 Submarine]`
* **Run 2:** `[Fill in: Missions targeted, e.g. Mission 03 Shark & Mission 04 Scuba Diver]`
* **Run 3:** `[Fill in: Missions targeted, e.g. Mission 05 Research Vessel]`

---

## 🛠️ Robot Attachments & Features
* **Attachment 1:** `[Fill in: e.g. Dual-gear lift arm for coral]`
* **Attachment 2:** `[Fill in: e.g. Passive latch mechanism for scuba diver]`

---

## 📊 Target Score
* **Goal Score:** `[Fill in: e.g. 380 Points]`

---

*Code and Scratch diagrams are updated automatically on every push! 🚀*
    """.trimIndent()

    fun getInnovationProjectReadme(): String = """
# 💡 Innovation Project Workspace

Welcome to our Innovation Project hub! Here we document our problem research, innovative solution, and expert feedback.

---

## ❓ The Problem We Are Solving
`[Fill in: What ocean/community problem did our team identify?]`

---

## 💡 Our Innovative Solution
`[Fill in: Describe our team's invention or solution]`

---

## 🗣️ Expert & Community Feedback
* **Expert 1:** `[Fill in: Name & feedback from marine biologist / engineer]`
* **Expert 2:** `[Fill in: Name & feedback from community presentation]`

---

## 🎭 Presentation Plan
`[Fill in: Describe our skit or presentation slides]`
    """.trimIndent()

    fun getMeetingNotesReadme(): String = """
# 📋 Meeting Notes & Team Journal

Welcome to our team journal! Here we document what we accomplished during each practice session.

---

## 📝 Practice Session Log
* `[Fill in Date]: Brainstormed innovation project ideas and built Run 1 attachment.`
* `[Fill in Date]: Tested gyro turns for Run 2 and interviewed local expert.`

---

*Photos, drawings, and notes shared from the tablet appear automatically here! 🚀*
    """.trimIndent()
}

package com.fll.pushtogithub

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates a single "save to GitHub" push:
 *  1. Commit the raw .llsp3 to  <basePath>/<project>/<project>.llsp3  (latest).
 *  2. Extract and commit all readable files from the ZIP so diffs, previews,
 *     and screenshots are human-readable on GitHub.
 *  3. Generate and commit a kid-friendly README.md inside the project folder
 *     with the Scratch code preview diagram, contributors, and activity log.
 *
 * All files ride under one dated, commented message so the repo history reads
 * like a session log with contributor attribution.
 */
class PushService(private val settings: Settings) {

    data class Outcome(
        val success: Boolean,
        val message: String,
        val commitUrl: String?
    )

    /**
     * Perform the full push. Runs network I/O; call from a background thread.
     */
    fun push(
        projectName: String,
        contributors: List<String>,
        tabletName: String,
        robotNickname: String?,
        comment: String,
        fileName: String,
        fileBytes: ByteArray,
        issueNumber: Int?
    ): Outcome {
        if (!settings.isConfigured) {
            return Outcome(false, "App is not set up yet (token/repo/roster missing).", null)
        }

        val now = Date()
        val dateStamp = DATE_FMT.format(now)
        val ext = fileExtension(fileName)

        val base = "${settings.basePath}/$projectName"
        val latestPath = "$base/$projectName$ext"

        val commitMessage = buildCommitMessage(
            projectName, issueNumber, contributors, tabletName, robotNickname, dateStamp, comment
        )

        val client = GitHubClient(
            token = settings.token,
            owner = settings.owner,
            repo = settings.repo,
            branch = settings.branch
        )

        return try {
            // 1) Latest binary .llsp3
            val latest = client.putFile(latestPath, fileBytes, commitMessage)

            // 2) Extract all files from ZIP and commit them for readable diffs & previews
            val extracted = LlspExtractor.extract(fileBytes)
            for (item in extracted) {
                val extractedPath = "$base/${item.subPath}"
                runCatching {
                    client.putFile(extractedPath, item.bytes, commitMessage)
                }
            }

            // 3) Generate & commit kid-friendly README.md for the project directory
            val previewItem = extracted.find { it.subPath == "extracted/icon.png" }
                ?: extracted.find { it.subPath == "extracted/project.png" }
                ?: extracted.find { it.subPath.endsWith(".png", ignoreCase = true) }

            val history = runCatching {
                client.getFileHistory(latestPath, perPage = 10)
            }.getOrDefault(emptyList())

            val readmeText = generateProjectReadme(
                projectName = projectName,
                previewRelPath = previewItem?.subPath,
                contributors = contributors,
                tabletName = tabletName,
                robotNickname = robotNickname,
                dateStamp = dateStamp,
                comment = comment,
                history = history
            )

            val readmePath = "$base/README.md"
            runCatching {
                client.putFile(readmePath, readmeText.toByteArray(Charsets.UTF_8), commitMessage)
            }

            val extractedNote = if (extracted.isNotEmpty()) {
                " (+${extracted.size} readable file${if (extracted.size == 1) "" else "s"})"
            } else ""

            Outcome(
                success = true,
                message = "Saved $projectName to GitHub$extractedNote.",
                commitUrl = latest.htmlUrl
            )
        } catch (e: Exception) {
            Outcome(false, e.message ?: "Push failed.", null)
        }
    }

    /**
     * Generate kid-friendly README.md markdown content for a project folder.
     */
    private fun generateProjectReadme(
        projectName: String,
        previewRelPath: String?,
        contributors: List<String>,
        tabletName: String,
        robotNickname: String?,
        dateStamp: String,
        comment: String,
        history: List<GitHubClient.CommitInfo>
    ): String {
        val sb = StringBuilder()
        sb.append("# 🤖 $projectName\n\n")
        sb.append("> **FIRST LEGO League Robot Program**  \n")
        sb.append("> *Last updated on $dateStamp*\n\n")
        sb.append("---\n\n")

        if (!previewRelPath.isNullOrBlank()) {
            sb.append("## 📸 Scratch Code Preview\n\n")
            sb.append("![$projectName Scratch Code]($previewRelPath)\n\n")
            sb.append("---\n\n")
        }

        sb.append("## 👥 Active Team Members & Robot\n\n")
        sb.append("* **Team Members:** ${contributors.joinToString(", ")}\n")
        sb.append("* **Tablet:** $tabletName\n")
        if (!robotNickname.isNullOrBlank()) {
            sb.append("* **Robot Nickname:** $robotNickname\n")
        }
        sb.append("\n---\n\n")

        sb.append("## 💬 Latest Update\n\n")
        sb.append("**\"${comment.trim()}\"**\n\n")
        sb.append("---\n\n")

        sb.append("## 📜 Activity Log & Recent Updates\n\n")
        if (history.isNotEmpty()) {
            for (commit in history) {
                val parsedComment = parseUserComment(commit.message)
                val date = if (commit.date.length >= 10) commit.date.substring(0, 10) else commit.date
                val committer = if (commit.committerName.isNotBlank()) " *(by ${commit.committerName})*" else ""
                sb.append("* **$date:** $parsedComment$committer\n")
            }
        } else {
            sb.append("* **$dateStamp:** ${comment.trim()} *(by ${contributors.joinToString(", ")})*\n")
        }

        sb.append("\n---\n\n")
        sb.append("*Generated automatically by Push to Team GitHub 🚀*\n")
        return sb.toString()
    }

    private fun parseUserComment(message: String): String {
        val parts = message.split(" — ")
        return if (parts.size >= 3) parts.last().trim()
        else if (parts.size == 2) parts.last().trim()
        else message.trim()
    }

    /**
     * Check whether someone else has pushed to this project since the given
     * commit SHA. Returns the latest commit info message if a conflict exists,
     * or null if there is no conflict (or the file doesn't exist yet).
     */
    fun checkForConflict(projectName: String, lastKnownSha: String?): String? {
        if (lastKnownSha == null) return null // File doesn't exist yet — no conflict possible

        val client = GitHubClient(
            token = settings.token,
            owner = settings.owner,
            repo = settings.repo,
            branch = settings.branch
        )

        val ext = ".llsp3"
        val filePath = "${settings.basePath}/$projectName/$projectName$ext"
        val latestSha = client.getLatestCommitSha(filePath) ?: return null

        return if (latestSha != lastKnownSha) {
            // Someone else pushed — get the commit message for the user
            val history = client.getFileHistory(filePath, perPage = 1)
            if (history.isNotEmpty()) {
                history[0].message
            } else {
                "Someone else updated this project."
            }
        } else {
            null // No conflict
        }
    }

    /**
     * Build the standardized commit message:
     * Run 1 (#7) [Bubbles, Patches] (Tablet A, Robot Alpha) — 2026-09-13 — Tuned gyro turn
     */
    private fun buildCommitMessage(
        projectName: String,
        issueNumber: Int?,
        contributors: List<String>,
        tabletName: String,
        robotNickname: String?,
        dateStamp: String,
        comment: String
    ): String {
        val sb = StringBuilder()
        sb.append(projectName)
        if (issueNumber != null) {
            sb.append(" (#$issueNumber)")
        }
        sb.append(" [${contributors.joinToString(", ")}]")
        sb.append(" ($tabletName")
        if (!robotNickname.isNullOrBlank()) {
            sb.append(", $robotNickname")
        }
        sb.append(")")
        sb.append(" — $dateStamp — ${comment.trim()}")
        return sb.toString()
    }

    private fun fileExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return ".llsp3"
        val ext = fileName.substring(dot)
        // Guard against absurdly long "extensions".
        return if (ext.length <= 8) ext else ".llsp3"
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

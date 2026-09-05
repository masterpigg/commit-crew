package com.fll.pushtogithub

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates a single "save robot code" push:
 *  1. Commit the raw .llsp3 to  <basePath>/<robot>/<robot>.llsp3  (latest).
 *  2. Commit a timestamped history copy to  <basePath>/<robot>/history/<robot>-<ts>.llsp3
 *  3. Extract and commit readable files (project.json, manifest.json) so diffs
 *     between sessions are human-readable.
 *
 * All files ride under one dated, commented message so the repo history reads
 * like a session log.
 */
class PushService(private val settings: Settings) {

    data class Robot(val slug: String, val displayNumber: Int)

    data class Outcome(
        val success: Boolean,
        val message: String,
        val commitUrl: String?
    )

    fun robotFor(number: Int): Robot = when (number) {
        1 -> Robot(settings.robot1Name, 1)
        else -> Robot(settings.robot2Name, 2)
    }

    /**
     * Perform the full push. Runs network I/O; call from a background thread.
     *
     * @param robotNumber 1 or 2
     * @param comment required kid-written note about what changed
     * @param fileName the original shared file name (used to keep the extension)
     * @param fileBytes raw bytes of the shared file
     */
    fun push(
        robotNumber: Int,
        comment: String,
        fileName: String,
        fileBytes: ByteArray
    ): Outcome {
        if (!settings.isConfigured) {
            return Outcome(false, "App is not set up yet (token/repo missing).", null)
        }

        val robot = robotFor(robotNumber)
        val now = Date()
        val dateStamp = DATE_FMT.format(now)
        val timeStamp = TS_FMT.format(now)
        val ext = fileExtension(fileName)

        val base = "${settings.basePath}/${robot.slug}"
        val latestPath = "$base/${robot.slug}$ext"
        val historyPath = "$base/history/${robot.slug}-$timeStamp$ext"

        val commitMessage = "Robot ${robot.displayNumber} — $dateStamp — ${comment.trim()}"

        val client = GitHubClient(
            token = settings.token,
            owner = settings.owner,
            repo = settings.repo,
            branch = settings.branch
        )

        return try {
            // 1) Latest (overwritten each session — this file's history is the timeline).
            val latest = client.putFile(latestPath, fileBytes, commitMessage)

            // 2) Immutable timestamped snapshot (covers in-between commits too).
            client.putFile(historyPath, fileBytes, commitMessage)

            // 3) Readable extracted files for diffing (best-effort).
            val extracted = LlspExtractor.extract(fileBytes)
            for (item in extracted) {
                val extractedPath = "$base/${item.subPath}"
                runCatching {
                    client.putFile(extractedPath, item.bytes, commitMessage)
                }
            }

            val extractedNote = if (extracted.isNotEmpty()) {
                " (+${extracted.size} readable file${if (extracted.size == 1) "" else "s"})"
            } else ""

            Outcome(
                success = true,
                message = "Saved Robot ${robot.displayNumber} to GitHub$extractedNote.",
                commitUrl = latest.htmlUrl
            )
        } catch (e: Exception) {
            Outcome(false, e.message ?: "Push failed.", null)
        }
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
        private val TS_FMT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    }
}

package com.fll.pushtogithub

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal GitHub REST client for committing files via the Contents API and
 * querying project folder listings and commit history.
 *
 * Uses only standard REST endpoints — no native git on the tablet.
 */
class GitHubClient(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Result of a single file commit. */
    data class CommitResult(val contentPath: String, val htmlUrl: String?)

    /** A project folder entry from the repo. */
    data class ProjectFolder(
        val name: String,
        val path: String,
        val sha: String
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
        var projectItemId: String? = null,
        var projectId: String? = null,
        var statusFieldId: String? = null,
        var ownerFieldId: String? = null
    )

    /**
     * Verify the token can see the repo. Returns null on success, or a
     * human-readable error message on failure.
     */
    fun testConnection(): String? {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 401 -> "Token was rejected (401). Check the token."
                    resp.code == 404 ->
                        "Repo not found (404). Check owner/repo, and that the token can access it."
                    else -> "GitHub returned ${resp.code}: ${resp.message}"
                }
            }
        } catch (e: IOException) {
            "Network error: ${e.message}"
        }
    }

    /**
     * List sub-folders under [basePath] in the repo. Each folder represents a
     * project (e.g. "Run 1", "Run 2", "Gyro Test").
     */
    fun listFolders(basePath: String): List<ProjectFolder> {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(basePath)}" +
            "?ref=${enc(branch)}"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                val array = JSONArray(text)
                val folders = mutableListOf<ProjectFolder>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.optString("type") == "dir") {
                        folders += ProjectFolder(
                            name = item.optString("name"),
                            path = item.optString("path"),
                            sha = item.optString("sha")
                        )
                    }
                }
                folders.sortedBy { it.name.lowercase() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get the commit history for a specific file path.
     * Returns commits touching that file, most recent first.
     */
    fun getFileHistory(filePath: String, perPage: Int = 30): List<CommitInfo> {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/commits" +
            "?path=${enc(filePath)}&sha=${enc(branch)}&per_page=$perPage"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                val array = JSONArray(text)
                val commits = mutableListOf<CommitInfo>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val commit = item.getJSONObject("commit")
                    val committer = commit.optJSONObject("committer")
                    commits += CommitInfo(
                        sha = item.optString("sha"),
                        message = commit.optString("message"),
                        date = committer?.optString("date").orEmpty(),
                        committerName = committer?.optString("name").orEmpty()
                    )
                }
                commits
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Download a file's content at a specific commit SHA.
     * Returns the raw bytes of the file, or null if not found.
     */
    fun getFileAtCommit(filePath: String, commitSha: String): ByteArray? {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(filePath)}" +
            "?ref=${enc(commitSha)}"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)
                val content = obj.optString("content").replace("\\n", "").replace("\\r", "")
                if (content.isBlank()) return null
                Base64.decode(content, Base64.DEFAULT)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Try downloading the preview thumbnail (icon.png / project.png)
     * for a project folder.
     */
    fun getProjectPreviewImage(basePath: String, projectName: String): ByteArray? {
        return getProjectPreviewImageAtCommit(basePath, projectName, branch)
    }

    /**
     * Try downloading the preview thumbnail (SVG / PNG)
     * for a project folder at a specific commit SHA.
     *
     * Falls back to extracting the preview directly from the raw .llsp3 ZIP
     * for earlier commits where extracted files were not committed separately.
     */
    fun getProjectPreviewImageAtCommit(basePath: String, projectName: String, commitSha: String): ByteArray? {
        val candidates = listOf(
            "$basePath/$projectName/extracted/icon.svg",
            "$basePath/$projectName/extracted/project.svg",
            "$basePath/$projectName/extracted/scratch/icon.svg",
            "$basePath/$projectName/extracted/icon.png",
            "$basePath/$projectName/extracted/project.png",
            "$basePath/$projectName/extracted/scratch/icon.png",
            "$basePath/$projectName/icon.svg",
            "$basePath/$projectName/icon.png"
        )
        for (path in candidates) {
            val bytes = getFileAtCommit(path, commitSha)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }

        // Fallback for earlier commits: download .llsp3 at commitSha and extract preview from ZIP
        val llsp3Path = "$basePath/$projectName/$projectName.llsp3"
        val llsp3Bytes = getFileAtCommit(llsp3Path, commitSha) ?: return null
        val extracted = LlspExtractor.extract(llsp3Bytes)

        val item = extracted.find { it.subPath == "extracted/icon.svg" }
            ?: extracted.find { it.subPath == "extracted/project.svg" }
            ?: extracted.find { it.subPath == "extracted/scratch/icon.svg" }
            ?: extracted.find { it.subPath == "extracted/icon.png" }
            ?: extracted.find { it.subPath == "extracted/project.png" }
            ?: extracted.find { it.subPath.endsWith(".svg", ignoreCase = true) }
            ?: extracted.find { it.subPath.endsWith(".png", ignoreCase = true) }

        return item?.bytes
    }

    /** Fetch the list of archived project names for [basePath]. */
    fun getArchivedProjectNames(basePath: String): List<String> {
        val path = "$basePath/.archived_projects.json"
        val bytes = getFileAtCommit(path, branch) ?: return emptyList()
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val array = JSONArray(json)
            val result = mutableListOf<String>()
            for (i in 0 until array.length()) {
                result.add(array.getString(i))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add [projectName] to the archived projects list on GitHub. */
    fun archiveProject(basePath: String, projectName: String): Boolean {
        val current = getArchivedProjectNames(basePath).toMutableList()
        if (!current.contains(projectName)) {
            current.add(projectName)
        }
        val array = JSONArray(current)
        val path = "$basePath/.archived_projects.json"
        return try {
            putFile(path, array.toString(2).toByteArray(Charsets.UTF_8), "Archive project: $projectName")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Remove [projectName] from the archived projects list on GitHub. */
    fun unarchiveProject(basePath: String, projectName: String): Boolean {
        val current = getArchivedProjectNames(basePath).toMutableList()
        current.remove(projectName)
        val array = JSONArray(current)
        val path = "$basePath/.archived_projects.json"
        return try {
            putFile(path, array.toString(2).toByteArray(Charsets.UTF_8), "Unarchive project: $projectName")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Delete a file at [path] from GitHub repository. */
    fun deleteFile(path: String, commitMessage: String): Boolean {
        val sha = getFileSha(path) ?: return true

        val body = JSONObject().apply {
            put("message", commitMessage)
            put("sha", sha)
            put("branch", branch)
        }

        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(path)}"
        val request = baseRequest(url)
            .delete(body.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** One-time migration: Move all files from [oldFolder] to [newFolder] on GitHub. */
    fun migrateFolder(oldFolder: String, newFolder: String) {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(oldFolder)}?ref=${enc(branch)}"
        val request = baseRequest(url).get().build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val text = resp.body?.string().orEmpty()
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val type = item.optString("type")
                    val itemPath = item.optString("path")
                    val itemName = item.optString("name")

                    if (type == "file") {
                        val bytes = getFileAtCommit(itemPath, branch)
                        if (bytes != null) {
                            val newPath = "$newFolder/$itemName"
                            putFile(newPath, bytes, "Migrate $itemName to $newFolder")
                            deleteFile(itemPath, "Remove legacy $itemPath after migration")
                        }
                    } else if (type == "dir") {
                        migrateFolder(itemPath, "$newFolder/$itemName")
                    }
                }
            }
        } catch (e: Exception) {
            // Folder does not exist or migration finished
        }
    }

    /** Ensure required Kanban labels (todo, doing, done) exist on GitHub. */
    fun ensureKanbanLabelsExist() {
        ensureLabelExists("todo", "F57F17", "Kanban Column: To-Do")
        ensureLabelExists("doing", "E65100", "Kanban Column: Doing")
        ensureLabelExists("done", "2E7D32", "Kanban Column: Done")
    }

    /** Execute a GraphQL query against GitHub's GraphQL API v4. */
    fun queryGraphQL(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        val json = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        val request = Request.Builder()
            .url("https://api.github.com/graphql")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(json.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                JSONObject(text)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ensure owner labels (owner:<Name>) exist for all team members. */
    fun ensureOwnerLabelsExist(teamRoster: List<String>) {
        val colors = listOf("1976D2", "7B1FA2", "C2185B", "D32F2F", "388E3C", "F57C00", "0097A7")
        for ((index, name) in teamRoster.withIndex()) {
            val color = colors[index % colors.size]
            ensureLabelExists("owner:$name", color, "Owner: $name")
        }
    }

    /** Create a label on GitHub if it doesn't exist yet. */
    fun ensureLabelExists(labelName: String, colorHex: String, description: String) {
        val checkUrl = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/labels/${enc(labelName)}"
        val checkReq = baseRequest(checkUrl).get().build()
        val exists = try {
            http.newCall(checkReq).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }

        if (!exists) {
            val json = JSONObject().apply {
                put("name", labelName)
                put("color", colorHex)
                put("description", description)
            }
            val createUrl = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/labels"
            val createReq = baseRequest(createUrl)
                .post(json.toString().toRequestBody(JSON))
                .build()
            try {
                http.newCall(createReq).execute().close()
            } catch (e: Exception) {
                // Ignore failure if token lacks label permissions
            }
        }
    }

    /**
     * Get the SHA of the latest commit that touched a specific file.
     * Returns null if the file does not exist or on error.
     */
    fun getLatestCommitSha(filePath: String): String? {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/commits" +
            "?path=${enc(filePath)}&sha=${enc(branch)}&per_page=1"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                val array = JSONArray(text)
                if (array.length() == 0) return null
                array.getJSONObject(0).optString("sha").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create or update a single file at [path] (repo-relative) with [bytes].
     * Looks up the existing file SHA first so updates don't fail.
     *
     * @throws IOException on network failure or a non-success API response.
     */
    fun putFile(path: String, bytes: ByteArray, commitMessage: String): CommitResult {
        val existingSha = getFileSha(path)

        val body = JSONObject().apply {
            put("message", commitMessage)
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }

        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(path)}"
        val request = baseRequest(url)
            .put(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val responseText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(describeError(resp.code, responseText))
            }
            val htmlUrl = runCatching {
                JSONObject(responseText)
                    .optJSONObject("content")
                    ?.optString("html_url")
            }.getOrNull()
            return CommitResult(path, htmlUrl)
        }
    }

    /** Get all Kanban cards / GitHub issues for this repository. */
    fun getKanbanCards(teamRoster: List<String> = emptyList()): List<KanbanCard> {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/issues?state=all&per_page=100"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                val array = JSONArray(text)
                val cards = mutableListOf<KanbanCard>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.has("pull_request")) continue

                    val number = obj.optInt("number")
                    val title = obj.optString("title")
                    val body = obj.optString("body")
                    val state = obj.optString("state")

                    val labelsArray = obj.optJSONArray("labels") ?: JSONArray()
                    val labelNames = mutableListOf<String>()
                    val owners = mutableListOf<String>()
                    val ownerColors = mutableMapOf<String, String>()

                    for (j in 0 until labelsArray.length()) {
                        val lblObj = labelsArray.getJSONObject(j)
                        val lbl = lblObj.optString("name")
                        val colorHex = lblObj.optString("color")
                        val hex = if (colorHex.isNotBlank()) "#${colorHex.removePrefix("#")}" else "#1976D2"

                        labelNames.add(lbl.lowercase())

                        if (lbl.startsWith("owner:", ignoreCase = true)) {
                            val ownerName = lbl.substring(6).trim()
                            owners.add(ownerName)
                            ownerColors[ownerName] = hex
                        } else if (teamRoster.any { it.equals(lbl, ignoreCase = true) }) {
                            val ownerName = lbl.trim()
                            owners.add(ownerName)
                            ownerColors[ownerName] = hex
                        }
                    }

                    val assignees = obj.optJSONArray("assignees")
                    if (assignees != null) {
                        for (j in 0 until assignees.length()) {
                            val login = assignees.getJSONObject(j).optString("login")
                            if (login.isNotBlank() && !owners.contains(login)) {
                                owners.add(login)
                            }
                        }
                    }

                    // Extract owners from issue body (e.g. "Owner: Fido, Whiskers")
                    if (body.isNotBlank()) {
                        val match = Regex("(?i)owners?:\\s*([^\\r\\n]+)").find(body)
                        if (match != null) {
                            val names = match.groupValues[1].split(",", ";").map { it.trim() }
                            for (n in names) {
                                if (n.isNotBlank() && !owners.contains(n)) {
                                    owners.add(n)
                                }
                            }
                        }
                    }

                    val allText = (title + " " + body + " " + labelNames.joinToString(" ")).lowercase()
                    val detectedColumn = when {
                        state.equals("closed", ignoreCase = true) ||
                            allText.contains("done") || allText.contains("complete") || allText.contains("finish") -> "done"

                        allText.contains("doing") || allText.contains("progress") || allText.contains("in progress") ||
                            allText.contains("wip") || allText.contains("working") || allText.contains("review") -> "doing"

                        else -> "todo"
                    }

                    val detectedCategory = when {
                        labelNames.contains("robot-game") || labelNames.contains("robot") || allText.contains("robot") -> "robot-game"
                        labelNames.contains("innovation-project") || labelNames.contains("innovation") || labelNames.contains("research") || allText.contains("innovation") -> "innovation-project"
                        else -> "general"
                    }

                    cards.add(
                        KanbanCard(
                            number = number,
                            title = title,
                            body = body,
                            state = state,
                            column = detectedColumn,
                            owners = owners.distinct().toMutableList(),
                            ownerColors = ownerColors,
                            category = detectedCategory
                        )
                    )
                }
                cards.sortedByDescending { it.number }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Create a new Kanban task / GitHub issue. */
    fun createKanbanCard(title: String, body: String, column: String, category: String, owners: List<String>): KanbanCard? {
        val labels = mutableListOf<String>()
        labels.add(column.lowercase())
        labels.add(category.lowercase())
        for (ownerName in owners) {
            labels.add("owner:$ownerName")
            labels.add(ownerName)
        }

        val ownerHeader = if (owners.isNotEmpty()) "Owner: ${owners.joinToString(", ")}\n\n" else ""
        val fullBody = if (body.isNotBlank()) "$ownerHeader$body" else ownerHeader.trim()

        val json = JSONObject().apply {
            put("title", title)
            put("body", fullBody)
            put("labels", JSONArray(labels.distinct()))
        }

        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/issues"
        val request = baseRequest(url)
            .post(json.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)
                KanbanCard(
                    number = obj.optInt("number"),
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    state = obj.optString("state"),
                    column = column.lowercase(),
                    owners = owners.toMutableList(),
                    category = category.lowercase()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Update an existing Kanban card's column / owners on GitHub. */
    fun updateKanbanCard(number: Int, column: String, category: String, owners: List<String>): Boolean {
        return updateKanbanCardFull(number, "", "", column, category, owners)
    }

    /** Full update for a Kanban card's title, body, column, category, and owners. */
    fun updateKanbanCardFull(number: Int, title: String, body: String, column: String, category: String, owners: List<String>): Boolean {
        val labels = mutableListOf<String>()
        labels.add(column.lowercase())
        if (category.isNotBlank()) labels.add(category.lowercase())
        for (ownerName in owners) {
            labels.add("owner:$ownerName")
            labels.add(ownerName)
        }

        val state = if (column.lowercase() == "done") "closed" else "open"
        val ownerHeader = if (owners.isNotEmpty()) "Owner: ${owners.joinToString(", ")}\n\n" else ""
        val cleanBody = body.replace(Regex("(?i)^owner:.*?\\n+"), "").trim()
        val fullBody = if (cleanBody.isNotBlank()) "$ownerHeader$cleanBody" else ownerHeader.trim()

        val json = JSONObject().apply {
            if (title.isNotBlank()) put("title", title)
            if (fullBody.isNotBlank()) put("body", fullBody)
            put("labels", JSONArray(labels.distinct()))
            put("state", state)
        }

        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/issues/$number"
        val request = baseRequest(url)
            .patch(json.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Initialize default README.md files for root, robot-game, innovation-project, and meeting-notes. */
    fun initializeDefaultReadmes(teamRoster: List<String>) {
        val rootPath = "README.md"
        val existingRootBytes = getFileAtCommit(rootPath, branch)
        val existingRootText = if (existingRootBytes != null) String(existingRootBytes, Charsets.UTF_8) else null
        val rootText = ReadmeTemplates.getRootReadme(owner, repo, teamRoster, existingRootText)
        if (existingRootText != rootText) {
            putFile(rootPath, rootText.toByteArray(Charsets.UTF_8), "Initialize root README workspace")
        }

        val robotPath = "robot-game/README.md"
        val robotText = ReadmeTemplates.getRobotGameReadme()
        val existingRobotBytes = getFileAtCommit(robotPath, branch)
        val existingRobotText = if (existingRobotBytes != null) String(existingRobotBytes, Charsets.UTF_8) else null
        if (existingRobotText == null || !existingRobotText.contains("# 🤖 Robot Game Workspace")) {
            putFile(robotPath, robotText.toByteArray(Charsets.UTF_8), "Initialize Robot Game README")
        }

        val innovationPath = "innovation-project/README.md"
        val innovationText = ReadmeTemplates.getInnovationProjectReadme()
        val existingInnovationBytes = getFileAtCommit(innovationPath, branch)
        val existingInnovationText = if (existingInnovationBytes != null) String(existingInnovationBytes, Charsets.UTF_8) else null
        if (existingInnovationText == null || !existingInnovationText.contains("# 💡 Innovation Project Workspace")) {
            putFile(innovationPath, innovationText.toByteArray(Charsets.UTF_8), "Initialize Innovation Project README")
        }

        val meetingPath = "meeting-notes/README.md"
        val meetingText = ReadmeTemplates.getMeetingNotesReadme()
        val existingMeetingBytes = getFileAtCommit(meetingPath, branch)
        val existingMeetingText = if (existingMeetingBytes != null) String(existingMeetingBytes, Charsets.UTF_8) else null
        if (existingMeetingText == null || !existingMeetingText.contains("# 📋 Meeting Notes & Team Journal")) {
            putFile(meetingPath, meetingText.toByteArray(Charsets.UTF_8), "Initialize Meeting Notes README")
        }
    }

    /** Delete / close a Kanban card on GitHub. */
    fun deleteKanbanCard(number: Int): Boolean {
        val json = JSONObject().apply {
            put("state", "closed")
        }
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/issues/$number"
        val request = baseRequest(url)
            .patch(json.toString().toRequestBody(JSON))
            .build()

        return try {
            http.newCall(request).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Return the blob SHA of an existing file, or null if it does not exist. */
    private fun getFileSha(path: String): String? {
        val url = "$API_BASE/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(path)}" +
            "?ref=${enc(branch)}"
        val request = baseRequest(url).get().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 404) return null
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                JSONObject(text).optString("sha").ifBlank { null }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun describeError(code: Int, responseText: String): String {
        val apiMessage = runCatching { JSONObject(responseText).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val hint = when (code) {
            401 -> "Token rejected. Regenerate it and re-enter in Setup."
            403 -> "Forbidden. The token may lack Contents: write on this repo."
            404 -> "Not found. Check owner/repo/branch."
            409 -> "Conflict. Someone pushed at the same time — try again."
            422 -> "Rejected by GitHub (422)."
            else -> "GitHub error $code."
        }
        return if (apiMessage != null) "$hint ($apiMessage)" else hint
    }

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PushToTeamGitHub-Android")

    companion object {
        private const val API_BASE = "https://api.github.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun enc(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** Encode a path but keep the slashes as path separators. */
        private fun encPath(path: String): String =
            path.split("/").joinToString("/") { enc(it) }
    }
}

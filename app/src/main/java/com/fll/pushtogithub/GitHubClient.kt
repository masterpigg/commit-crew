package com.fll.pushtogithub

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal GitHub REST client for committing files via the Contents API.
 *
 * Uses only the "Create or update file contents" endpoint:
 *   PUT /repos/{owner}/{repo}/contents/{path}
 *
 * This avoids needing native git on the tablet. Each committed file is a
 * separate API call; for this app that is one .llsp3 plus a couple of extracted
 * files per push, which is well within reason.
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

package io.github.masterpigg.commitcrew

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encrypted, on-device storage for the team's GitHub configuration.
 *
 * The GitHub token never leaves the tablet except as an Authorization header to
 * api.github.com. It is stored using [EncryptedSharedPreferences] so it is not
 * readable as plain text on disk.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── GitHub connection ────────────────────────────────────────────────

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var owner: String
        get() = prefs.getString(KEY_OWNER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OWNER, value.trim()).apply()

    var repo: String
        get() = prefs.getString(KEY_REPO, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REPO, value.trim()).apply()

    var branch: String
        get() = prefs.getString(KEY_BRANCH, "main").orEmpty().ifBlank { "main" }
        set(value) = prefs.edit().putString(KEY_BRANCH, value.trim()).apply()

    /** Folder inside the repo where robot code lives, e.g. "robot-game". */
    var basePath: String
        get() = prefs.getString(KEY_BASE_PATH, "robot-game").orEmpty().ifBlank { "robot-game" }
        set(value) = prefs.edit().putString(KEY_BASE_PATH, cleanPath(value)).apply()

    /**
     * Whether to automatically overwrite existing local .llsp3 files when
     * downloading from GitHub if content differs (prevents Slammer - 1, Slammer - 2 clutter).
     */
    var autoOverwrite: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OVERWRITE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OVERWRITE, value).apply()

    /** Task Card Colors (Hex Strings) */
    var colorRobotGame: String
        get() = prefs.getString("color_robot_game", "#FFFDE7").orEmpty().ifBlank { "#FFFDE7" }
        set(value) = prefs.edit().putString("color_robot_game", value).apply()

    var colorInnovation: String
        get() = prefs.getString("color_innovation", "#E8F5E9").orEmpty().ifBlank { "#E8F5E9" }
        set(value) = prefs.edit().putString("color_innovation", value).apply()

    var colorGeneral: String
        get() = prefs.getString("color_general", "#E3F2FD").orEmpty().ifBlank { "#E3F2FD" }
        set(value) = prefs.edit().putString("color_general", value).apply()

    // ── Device & team identity ───────────────────────────────────────────

    /** Friendly name for this tablet. Defaults to the device model on first read. */
    var tabletName: String
        get() = prefs.getString(KEY_TABLET_NAME, "").orEmpty()
            .ifBlank { Build.MODEL }
        set(value) = prefs.edit().putString(KEY_TABLET_NAME, value.trim()).apply()

    /**
     * Team roster — all members and coaches.
     * Stored as a comma-separated string; accessed as a list.
     */
    var teamRoster: List<String>
        get() = prefs.getString(KEY_TEAM_ROSTER, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(
            KEY_TEAM_ROSTER,
            value.joinToString(", ") { it.trim() }
        ).apply()

    /**
     * Optional robot nicknames (e.g. "Robot Alpha, Robot Beta").
     * Stored as a comma-separated string; accessed as a list.
     */
    var robotNicknames: List<String>
        get() = prefs.getString(KEY_ROBOT_NICKNAMES, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(
            KEY_ROBOT_NICKNAMES,
            value.joinToString(", ") { it.trim() }
        ).apply()

    // ── Session stickiness (per-tablet, per-day) ─────────────────────────

    /**
     * Contributor names selected on this tablet today.
     * Automatically returns an empty list if the stored date is not today
     * (midnight reset).
     */
    var sessionContributors: List<String>
        get() {
            val storedDate = prefs.getString(KEY_SESSION_DATE, "").orEmpty()
            if (storedDate != todayIso()) return emptyList()
            return prefs.getString(KEY_SESSION_CONTRIBUTORS, "").orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        set(value) = prefs.edit()
            .putString(KEY_SESSION_DATE, todayIso())
            .putString(
                KEY_SESSION_CONTRIBUTORS,
                value.joinToString(", ") { it.trim() }
            )
            .apply()

    // ── First-launch-of-day tracking ─────────────────────────────────────

    /**
     * Returns true if the app has not been opened today (or ever).
     * Calling this automatically marks today as "launched".
     */
    fun isFirstLaunchOfDay(): Boolean {
        val storedDate = prefs.getString(KEY_LAST_LAUNCH_DATE, "").orEmpty()
        val today = todayIso()
        if (storedDate == today) return false
        prefs.edit().putString(KEY_LAST_LAUNCH_DATE, today).apply()
        return true
    }

    // ── Project → Issue mapping (coach-managed) ──────────────────────────

    /** Get the GitHub issue number associated with a project folder, or null. */
    fun issueForProject(projectName: String): Int? {
        val map = loadProjectIssueMap()
        return map[projectName.trim()]
    }

    /** Associate a project folder with a GitHub issue number. */
    fun setIssueForProject(projectName: String, issueNumber: Int) {
        val map = loadProjectIssueMap().toMutableMap()
        map[projectName.trim()] = issueNumber
        saveProjectIssueMap(map)
    }

    /** Remove the issue association for a project folder. */
    fun clearIssueForProject(projectName: String) {
        val map = loadProjectIssueMap().toMutableMap()
        map.remove(projectName.trim())
        saveProjectIssueMap(map)
    }

    private fun loadProjectIssueMap(): Map<String, Int> {
        val json = prefs.getString(KEY_PROJECT_ISSUE_MAP, "{}").orEmpty()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, Int>()
            for (key in obj.keys()) {
                result[key] = obj.getInt(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveProjectIssueMap(map: Map<String, Int>) {
        val obj = JSONObject()
        for ((key, value) in map) {
            obj.put(key, value)
        }
        prefs.edit().putString(KEY_PROJECT_ISSUE_MAP, obj.toString()).apply()
    }

    // ── Configuration check ──────────────────────────────────────────────

    /** True when enough is configured to attempt a push. */
    val isConfigured: Boolean
        get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
            && teamRoster.isNotEmpty()

    // ── Helpers ──────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "team_github_secure_prefs"

        // GitHub connection
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_BASE_PATH = "base_path"
        private const val KEY_AUTO_OVERWRITE = "auto_overwrite"

        // Device & team identity
        private const val KEY_TABLET_NAME = "tablet_name"
        private const val KEY_TEAM_ROSTER = "team_roster"
        private const val KEY_ROBOT_NICKNAMES = "robot_nicknames"

        // Session stickiness
        private const val KEY_SESSION_DATE = "session_date"
        private const val KEY_SESSION_CONTRIBUTORS = "session_contributors"

        // First-launch-of-day
        private const val KEY_LAST_LAUNCH_DATE = "last_launch_date"

        // Project → Issue mapping
        private const val KEY_PROJECT_ISSUE_MAP = "project_issue_map"

        private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private fun todayIso(): String = ISO_DATE.format(Date())

        /** Trim slashes and whitespace from a repo-relative folder path. */
        fun cleanPath(value: String): String =
            value.trim().trim('/').replace(Regex("/+"), "/")

        /** Turn a friendly name into a safe folder segment. */
        fun slug(value: String): String {
            val cleaned = value.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return cleaned.ifBlank { "project" }
        }
    }
}

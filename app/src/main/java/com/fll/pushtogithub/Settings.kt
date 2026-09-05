package com.fll.pushtogithub

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    var robot1Name: String
        get() = prefs.getString(KEY_ROBOT1, "robot-1").orEmpty().ifBlank { "robot-1" }
        set(value) = prefs.edit().putString(KEY_ROBOT1, slug(value)).apply()

    var robot2Name: String
        get() = prefs.getString(KEY_ROBOT2, "robot-2").orEmpty().ifBlank { "robot-2" }
        set(value) = prefs.edit().putString(KEY_ROBOT2, slug(value)).apply()

    /** True when enough is configured to attempt a push. */
    val isConfigured: Boolean
        get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    companion object {
        private const val PREFS_NAME = "team_github_secure_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_BASE_PATH = "base_path"
        private const val KEY_ROBOT1 = "robot1"
        private const val KEY_ROBOT2 = "robot2"

        /** Trim slashes and whitespace from a repo-relative folder path. */
        fun cleanPath(value: String): String =
            value.trim().trim('/').replace(Regex("/+"), "/")

        /** Turn a friendly robot name into a safe folder segment. */
        fun slug(value: String): String {
            val cleaned = value.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return cleaned.ifBlank { "robot" }
        }
    }
}

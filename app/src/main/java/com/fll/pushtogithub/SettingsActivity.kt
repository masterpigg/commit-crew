package com.fll.pushtogithub

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Html
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One-time setup screen. An adult enters the team GitHub token, repo details,
 * tablet name, team roster, and optional robot nicknames. All values are stored
 * encrypted on the tablet. This is no longer the launcher activity — the Time
 * Machine is — but it's reachable from the overflow menu or when setup is
 * incomplete.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    private var activeVoiceTarget: EditText? = null

    private val voiceRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim()
            if (!spokenText.isNullOrBlank() && activeVoiceTarget != null) {
                val currentText = activeVoiceTarget?.text?.toString().orEmpty()
                val updatedText = if (currentText.isBlank()) spokenText else "$currentText $spokenText"
                activeVoiceTarget?.setText(updatedText)
                activeVoiceTarget?.setSelection(updatedText.length)
            }
        }
    }

    private fun launchVoiceInput(targetEditText: EditText) {
        activeVoiceTarget = targetEditText
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak into microphone…")
        }
        try {
            voiceRecognizerLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice recognition is not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        loadIntoFields()

        binding.buttonSave.setOnClickListener { saveFromFields(showToast = true) }
        binding.buttonTest.setOnClickListener { testConnection() }
        binding.buttonSyncRoster.setOnClickListener { syncRosterFromGitHub() }

        binding.layoutTabletName.setEndIconOnClickListener { launchVoiceInput(binding.inputTabletName) }
        binding.layoutTeamRoster.setEndIconOnClickListener { launchVoiceInput(binding.inputTeamRoster) }
        binding.layoutRobotNicknames.setEndIconOnClickListener { launchVoiceInput(binding.inputRobotNicknames) }

        // Clear stale status when the user edits anything.
        val clearStatus: (CharSequence?) -> Unit = { binding.statusText.text = "" }
        binding.inputToken.doAfterTextChanged(clearStatus)
        binding.inputOwner.doAfterTextChanged(clearStatus)
        binding.inputRepo.doAfterTextChanged(clearStatus)

        // Expandable security card toggle.
        binding.securityHeader.setOnClickListener { toggleSecurityCard() }
        binding.securityBody.text = Html.fromHtml(
            getString(R.string.security_instructions),
            Html.FROM_HTML_MODE_COMPACT
        )

        setupVersionDisplay()
    }

    private fun setupVersionDisplay() {
        val hash = BuildConfig.GIT_HASH
        val tag = BuildConfig.GIT_TAG
        val versionName = BuildConfig.VERSION_NAME

        val versionText = when {
            tag.isNotBlank() && hash.isNotBlank() -> "Release $tag ($hash)"
            tag.isNotBlank() -> "Release $tag"
            hash.isNotBlank() -> "Version $versionName ($hash)"
            else -> "Version $versionName"
        }

        binding.textVersion.text = versionText
        binding.textVersion.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                val clip = android.content.ClipData.newPlainText("App Version", versionText)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "Copied $versionText", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadIntoFields() {
        binding.inputToken.setText(settings.token)
        binding.inputOwner.setText(settings.owner)
        binding.inputRepo.setText(settings.repo)
        binding.inputBranch.setText(settings.branch)
        binding.inputBasePath.setText(settings.basePath)
        binding.inputTabletName.setText(settings.tabletName)
        binding.inputTeamRoster.setText(settings.teamRoster.joinToString(", "))
        binding.inputRobotNicknames.setText(settings.robotNicknames.joinToString(", "))
    }

    private fun saveFromFields(showToast: Boolean) {
        settings.token = binding.inputToken.text?.toString().orEmpty()
        settings.owner = binding.inputOwner.text?.toString().orEmpty()
        settings.repo = binding.inputRepo.text?.toString().orEmpty()
        settings.branch = binding.inputBranch.text?.toString().orEmpty()
        settings.basePath = binding.inputBasePath.text?.toString().orEmpty()
        settings.tabletName = binding.inputTabletName.text?.toString().orEmpty()

        // Parse comma-separated roster and robot names.
        settings.teamRoster = binding.inputTeamRoster.text?.toString().orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        settings.robotNicknames = binding.inputRobotNicknames.text?.toString().orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

        // Reflect the normalized values back so the user sees what was stored.
        loadIntoFields()

        // Auto-create Owner labels on GitHub for the team roster
        if (settings.isConfigured) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    GitHubClient(
                        token = settings.token,
                        owner = settings.owner,
                        repo = settings.repo,
                        branch = settings.branch
                    ).ensureOwnerLabelsExist(settings.teamRoster)
                }
            }
        }

        if (showToast) {
            if (settings.isConfigured) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Update Root README.md Roster?")
                    .setMessage("Would you like to update the Team Roster section in your repository's root README.md on GitHub with your new team member names?")
                    .setPositiveButton("Update README") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                val client = GitHubClient(
                                    token = settings.token,
                                    owner = settings.owner,
                                    repo = settings.repo,
                                    branch = settings.branch
                                )
                                client.initializeDefaultReadmes(settings.teamRoster)
                            }
                        }
                        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton("Keep Existing README") { _, _ ->
                        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .show()
            } else {
                setStatus(getString(R.string.need_setup_on_settings), isError = true)
            }
        }
    }

    private fun testConnection() {
        saveFromFields(showToast = false)
        if (settings.token.isBlank() || settings.owner.isBlank() || settings.repo.isBlank()) {
            setStatus(getString(R.string.need_connection_fields), isError = true)
            return
        }
        binding.buttonTest.isEnabled = false
        setStatus("Checking…", isError = false)

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                ).testConnection()
            }
            binding.buttonTest.isEnabled = true
            if (error == null) {
                setStatus("Connected. Repo is reachable.", isError = false)
            } else {
                setStatus(error, isError = true)
            }
        }
    }

    private fun syncRosterFromGitHub() {
        saveFromFields(showToast = false)
        if (settings.token.isBlank() || settings.owner.isBlank() || settings.repo.isBlank()) {
            setStatus("Please fill in Token, Owner, and Repo first.", isError = true)
            return
        }

        setStatus("Syncing roster from GitHub…", isError = false)
        binding.buttonSyncRoster.isEnabled = false

        lifecycleScope.launch {
            val discoveredOwners = withContext(Dispatchers.IO) {
                val client = GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                )
                val cards = client.getKanbanCards()
                cards.flatMap { it.owners }.distinct()
            }

            binding.buttonSyncRoster.isEnabled = true

            if (discoveredOwners.isNotEmpty()) {
                val currentText = binding.inputTeamRoster.text?.toString().orEmpty()
                val currentList = currentText.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

                for (name in discoveredOwners) {
                    if (name.isNotBlank() && !currentList.contains(name)) {
                        currentList.add(name)
                    }
                }

                val mergedRoster = currentList.joinToString(", ")
                binding.inputTeamRoster.setText(mergedRoster)
                setStatus("Roster synced! Found ${discoveredOwners.size} owner(s) on GitHub.", isError = false)
            } else {
                setStatus("No new owner names found on GitHub.", isError = false)
            }
        }
    }

    private fun toggleSecurityCard() {
        val body = binding.securityBody
        if (body.visibility == View.VISIBLE) {
            body.visibility = View.GONE
            binding.securityHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, android.R.drawable.arrow_down_float, 0
            )
        } else {
            body.visibility = View.VISIBLE
            binding.securityHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, android.R.drawable.arrow_up_float, 0
            )
        }
    }

    private fun setStatus(message: String, isError: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            getColor(if (isError) R.color.error else R.color.success)
        )
    }
}

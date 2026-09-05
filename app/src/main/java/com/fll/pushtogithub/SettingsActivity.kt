package com.fll.pushtogithub

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time setup screen. An adult enters the team GitHub token and repo details;
 * they are stored encrypted on the tablet. This is the launcher activity.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        loadIntoFields()

        binding.buttonSave.setOnClickListener { saveFromFields(showToast = true) }
        binding.buttonTest.setOnClickListener { testConnection() }

        // Clear stale status when the user edits anything.
        val clearStatus: (CharSequence?) -> Unit = { binding.statusText.text = "" }
        binding.inputToken.doAfterTextChanged(clearStatus)
        binding.inputOwner.doAfterTextChanged(clearStatus)
        binding.inputRepo.doAfterTextChanged(clearStatus)
    }

    private fun loadIntoFields() {
        binding.inputToken.setText(settings.token)
        binding.inputOwner.setText(settings.owner)
        binding.inputRepo.setText(settings.repo)
        binding.inputBranch.setText(settings.branch)
        binding.inputBasePath.setText(settings.basePath)
        binding.inputRobot1.setText(settings.robot1Name)
        binding.inputRobot2.setText(settings.robot2Name)
    }

    private fun saveFromFields(showToast: Boolean) {
        settings.token = binding.inputToken.text?.toString().orEmpty()
        settings.owner = binding.inputOwner.text?.toString().orEmpty()
        settings.repo = binding.inputRepo.text?.toString().orEmpty()
        settings.branch = binding.inputBranch.text?.toString().orEmpty()
        settings.basePath = binding.inputBasePath.text?.toString().orEmpty()
        settings.robot1Name = binding.inputRobot1.text?.toString().orEmpty()
        settings.robot2Name = binding.inputRobot2.text?.toString().orEmpty()

        // Reflect the normalized values back so the user sees what was stored.
        loadIntoFields()
        if (showToast) {
            setStatus(getString(R.string.settings_saved), isError = false)
        }
    }

    private fun testConnection() {
        saveFromFields(showToast = false)
        if (!settings.isConfigured) {
            setStatus(getString(R.string.need_setup), isError = true)
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

    private fun setStatus(message: String, isError: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            getColor(if (isError) R.color.error else R.color.success)
        )
    }
}

package com.fll.pushtogithub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivityShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives a file shared from the SPIKE app (Share -> Push to Team GitHub),
 * lets the kid pick a robot and add a comment, then commits & pushes.
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var settings: Settings

    private var fileBytes: ByteArray? = null
    private var fileName: String = "project.llsp3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        if (!settings.isConfigured) {
            // Not set up — send the user to setup instead of failing silently.
            setStatus(getString(R.string.need_setup), isError = true)
            binding.buttonPush.isEnabled = false
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        binding.buttonRobot1.text = displayName(settings.robot1Name, 1)
        binding.buttonRobot2.text = displayName(settings.robot2Name, 2)

        loadSharedFile()

        binding.buttonPush.setOnClickListener { onPushClicked() }
        binding.buttonViewCommit.setOnClickListener {
            (binding.buttonViewCommit.tag as? String)?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    private fun displayName(slug: String, number: Int): String {
        val friendly = slug.replace('-', ' ').trim()
        return if (friendly.equals("robot $number", true) || friendly.isBlank()) {
            "Robot $number"
        } else {
            "Robot $number\n$friendly"
        }
    }

    private fun loadSharedFile() {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent?.data
        }

        if (uri == null) {
            setStatus(getString(R.string.no_file), isError = true)
            binding.buttonPush.isEnabled = false
            return
        }

        try {
            fileName = queryDisplayName(uri) ?: fileName
            fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (fileBytes == null || fileBytes!!.isEmpty()) {
                setStatus(getString(R.string.no_file), isError = true)
                binding.buttonPush.isEnabled = false
            } else {
                val kb = (fileBytes!!.size + 1023) / 1024
                binding.fileNameText.text = "$fileName ($kb KB)"
            }
        } catch (e: Exception) {
            setStatus("Couldn't read the shared file: ${e.message}", isError = true)
            binding.buttonPush.isEnabled = false
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun selectedRobotNumber(): Int? = when (binding.robotToggle.checkedButtonId) {
        R.id.buttonRobot1 -> 1
        R.id.buttonRobot2 -> 2
        else -> null
    }

    private fun onPushClicked() {
        val bytes = fileBytes
        if (bytes == null || bytes.isEmpty()) {
            setStatus(getString(R.string.no_file), isError = true)
            return
        }

        val robotNumber = selectedRobotNumber()
        if (robotNumber == null) {
            setStatus(getString(R.string.need_robot), isError = true)
            return
        }

        val comment = binding.inputComment.text?.toString()?.trim().orEmpty()
        if (comment.isBlank()) {
            setStatus(getString(R.string.need_comment), isError = true)
            return
        }

        setBusy(true)
        setStatus(getString(R.string.pushing), isError = false)
        binding.buttonViewCommit.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PushService(settings).push(robotNumber, comment, fileName, bytes)
            }
            setBusy(false)
            if (outcome.success) {
                setStatus(outcome.message, isError = false)
                val url = outcome.commitUrl
                if (url != null) {
                    binding.buttonViewCommit.tag = url
                    binding.buttonViewCommit.visibility = android.view.View.VISIBLE
                }
            } else {
                setStatus("${getString(R.string.push_failed)}: ${outcome.message}", isError = true)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.buttonPush.isEnabled = !busy
        binding.buttonRobot1.isEnabled = !busy
        binding.buttonRobot2.isEnabled = !busy
        binding.inputComment.isEnabled = !busy
    }

    private fun setStatus(message: String, isError: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            getColor(if (isError) R.color.error else R.color.success)
        )
    }
}

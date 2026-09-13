package com.fll.pushtogithub

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivityDocUploadBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Handles uploading documentation files (photos, PDFs, text notes) to the team
 * repo. Triggered either from Android's share sheet (Camera, Photos, Keep,
 * Word, Drive, etc.) or from the Time Machine FAB.
 *
 * The user picks a category (Meeting Notes, Robot Design, Innovation Project),
 * adds a title, selects contributor chips, and commits.
 */
class DocUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocUploadBinding
    private lateinit var settings: Settings

    private var sharedFileUri: Uri? = null
    private var sharedText: String? = null
    private var originalFileName: String = ""
    private var fileBytes: ByteArray? = null
    private var detectedMimeType: String? = null

    private var isNavigating = false

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            binding.imagePreview.setImageBitmap(bitmap)
            binding.imagePreview.visibility = View.VISIBLE
            binding.textPreview.visibility = View.GONE
            binding.fileTypeIcon.visibility = View.GONE
            binding.actionOverlay.visibility = View.GONE

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            fileBytes = baos.toByteArray()
            detectedMimeType = "image/png"
            val timeStamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            originalFileName = "photo_$timeStamp.png"

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            binding.inputDocTitle.setText("${today}_photo_$timeStamp")
            binding.buttonUpload.isEnabled = true
            showStatus("Photo captured! Ready to push.", isError = false)
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            sharedFileUri = uri
            detectedMimeType = contentResolver.getType(uri) ?: "image/*"
            originalFileName = resolveFileName(uri)
            runCatching {
                fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (fileBytes != null) {
                showPreview()
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                binding.inputDocTitle.setText("${today}_$originalFileName")
                binding.buttonUpload.isEnabled = true
                showStatus("File loaded! Ready to push.", isError = false)
            }
            binding.actionOverlay.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        binding.mainNavToggle.check(R.id.navSnapNotes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        setupPrimaryNavToggle()
        binding.buttonDone.setOnClickListener { finish() }

        binding.btnTakePhoto.setOnClickListener {
            takePhotoLauncher.launch(null)
        }

        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        binding.imagePreview.setOnClickListener {
            binding.actionOverlay.visibility = View.VISIBLE
        }

        if (!settings.isConfigured) {
            showStatus(getString(R.string.need_setup), isError = true)
            binding.buttonUpload.isEnabled = false
            return
        }

        // ── Extract shared content from intent ───────────────────────────
        extractSharedContent()

        // ── Set default title to today's date ────────────────────────────
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val defaultTitle = if (originalFileName.isNotBlank()) {
            "${today}_$originalFileName"
        } else {
            today
        }
        binding.inputDocTitle.setText(defaultTitle)

        // ── Default category to Meeting Notes ────────────────────────────
        binding.categoryToggle.check(R.id.buttonMeetingNotes)

        // ── Populate contributor chips ────────────────────────────────────
        populateContributorChips()

        // ── Upload button ────────────────────────────────────────────────
        binding.buttonUpload.setOnClickListener { onUploadClicked() }
    }

    // ── Intent extraction ────────────────────────────────────────────────

    private fun extractSharedContent() {
        val action = intent?.action
        val type = intent?.type

        when {
            // Shared file (image, PDF, document)
            action == Intent.ACTION_SEND && intent.hasExtra(Intent.EXTRA_STREAM) -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    sharedFileUri = uri
                    detectedMimeType = type ?: contentResolver.getType(uri)
                    originalFileName = resolveFileName(uri)

                    try {
                        fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        showStatus("Cannot read shared file: ${e.message}", isError = true)
                        binding.buttonUpload.isEnabled = false
                        return
                    }

                    showPreview()
                }
            }

            // Shared text (from Keep, etc.)
            action == Intent.ACTION_SEND && type == "text/plain" -> {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    binding.textPreview.text = sharedText
                    binding.textPreview.visibility = View.VISIBLE
                    binding.imagePreview.visibility = View.GONE
                    fileBytes = sharedText!!.toByteArray(Charsets.UTF_8)
                    originalFileName = "note.txt"
                }
            }

            // Launched from FAB (no shared content) — user will take a photo
            else -> {
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📷"
                binding.fileTypeIcon.visibility = View.VISIBLE
                // For now, user needs to share from Camera app. Future: in-app camera.
                showStatus("Share a photo or file from another app, or tap 📷 in Camera and share here.", isError = false)
            }
        }
    }

    private fun showPreview() {
        val bytes = fileBytes ?: return
        val mimeType = detectedMimeType.orEmpty()

        when {
            mimeType.startsWith("image/") -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.imagePreview.visibility = View.VISIBLE
                } else {
                    binding.fileTypeIcon.text = "🖼️"
                    binding.fileTypeIcon.visibility = View.VISIBLE
                }
            }
            mimeType.contains("pdf") -> {
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📄"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
            mimeType.startsWith("text/") -> {
                val text = String(bytes, Charsets.UTF_8)
                binding.textPreview.text = if (text.length > 500) text.take(500) + "…" else text
                binding.textPreview.visibility = View.VISIBLE
                binding.imagePreview.visibility = View.GONE
            }
            else -> {
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📎"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
        }
    }

    // ── Contributor chips ────────────────────────────────────────────────

    private fun populateContributorChips() {
        val chipGroup = binding.contributorChips
        chipGroup.removeAllViews()
        val sessionSelection = settings.sessionContributors.toSet()

        for (name in settings.teamRoster) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = name in sessionSelection
            }
            chipGroup.addView(chip)
        }
    }

    private fun getSelectedContributors(): List<String> {
        val chipGroup = binding.contributorChips
        val selected = mutableListOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) selected += chip.text.toString()
        }
        return selected
    }

    // ── Upload logic ─────────────────────────────────────────────────────

    private fun onUploadClicked() {
        val contributors = getSelectedContributors()
        val title = binding.inputDocTitle.text?.toString()?.trim().orEmpty()
        val comment = binding.inputComment.text?.toString()?.trim().orEmpty()
        val bytes = fileBytes

        if (bytes == null) {
            showStatus("No file to upload. Share a photo or file from another app.", isError = true)
            return
        }
        if (contributors.isEmpty()) {
            showStatus(getString(R.string.need_contributors), isError = true)
            return
        }
        if (title.isBlank()) {
            showStatus("Please add a title.", isError = true)
            return
        }

        // Determine category path
        val categoryPath = when (binding.categoryToggle.checkedButtonId) {
            R.id.buttonMeetingNotes -> "meeting-notes"
            R.id.buttonDesign -> "robot-game/design"
            R.id.buttonInnovation -> "innovation-project"
            else -> "meeting-notes"
        }

        // Determine file extension
        val ext = guessExtension()
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "").trim()
        val repoPath = "$categoryPath/$safeTitle$ext"

        // Build commit message
        val categoryLabel = when (binding.categoryToggle.checkedButtonId) {
            R.id.buttonMeetingNotes -> "Meeting Notes"
            R.id.buttonDesign -> "Robot Design"
            R.id.buttonInnovation -> "Innovation Project"
            else -> "Documentation"
        }
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val contributorStr = contributors.joinToString(", ")
        val commentPart = if (comment.isNotBlank()) " — $comment" else ""
        val commitMessage = "$categoryLabel [$contributorStr] (${settings.tabletName}) — $dateStamp$commentPart"

        // Save session stickiness
        settings.sessionContributors = contributors

        setLoading(true)
        showStatus(getString(R.string.pushing), isError = false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitHubClient(
                        token = settings.token,
                        owner = settings.owner,
                        repo = settings.repo,
                        branch = settings.branch
                    ).putFile(repoPath, bytes, commitMessage)
                }
            }

            setLoading(false)
            result.fold(
                onSuccess = {
                    showStatus("✅ Saved to $categoryPath/", isError = false)
                    binding.buttonUpload.isEnabled = false
                    binding.buttonDone.visibility = View.VISIBLE

                    lifecycleScope.launch {
                        delay(1800)
                        finish()
                    }
                },
                onFailure = { e ->
                    showStatus("Upload failed: ${e.message}", isError = true)
                }
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun guessExtension(): String {
        // Try from original filename
        val dotIndex = originalFileName.lastIndexOf('.')
        if (dotIndex > 0) return originalFileName.substring(dotIndex)

        // Try from MIME type
        return when {
            detectedMimeType?.contains("jpeg") == true -> ".jpg"
            detectedMimeType?.contains("png") == true -> ".png"
            detectedMimeType?.contains("pdf") == true -> ".pdf"
            detectedMimeType?.startsWith("text/") == true -> ".txt"
            detectedMimeType?.contains("svg") == true -> ".svg"
            else -> ""
        }
    }

    private fun resolveFileName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(col)
                }
            }
        }
        return name ?: uri.lastPathSegment ?: ""
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            getColor(if (isError) R.color.error else R.color.success)
        )
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonUpload.isEnabled = !loading
    }

    private fun setupPrimaryNavToggle() {
        binding.mainNavToggle.check(R.id.navSnapNotes)
        binding.mainNavToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isNavigating) {
                when (checkedId) {
                    R.id.navTeamCode -> {
                        isNavigating = true
                        val intent = Intent(this, TimeMachineActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(intent)
                    }
                    R.id.navTaskBoard -> {
                        isNavigating = true
                        val intent = Intent(this, KanbanActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(intent)
                    }
                }
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }
}

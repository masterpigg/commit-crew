package com.fll.pushtogithub

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivityDocUploadBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

        binding.layoutDocTitle.setEndIconOnClickListener {
            launchVoiceInput(binding.inputDocTitle)
        }

        binding.layoutComment.setEndIconOnClickListener {
            launchVoiceInput(binding.inputComment)
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
        val ext = originalFileName.substringAfterLast('.', "").lowercase()

        when {
            mimeType.startsWith("image/") || ext in listOf("png", "jpg", "jpeg", "gif", "bmp", "svg") -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.imagePreview.visibility = View.VISIBLE
                    binding.fileTypeIcon.visibility = View.GONE
                    binding.textPreview.visibility = View.GONE
                } else {
                    binding.fileTypeIcon.text = "🖼️ $originalFileName"
                    binding.fileTypeIcon.visibility = View.VISIBLE
                    binding.imagePreview.visibility = View.GONE
                    binding.textPreview.visibility = View.GONE
                }
            }
            mimeType.contains("pdf") || ext == "pdf" -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📄 PDF Document\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
            mimeType.contains("word") || mimeType.contains("document") || ext in listOf("doc", "docx", "pages") -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📝 Word / Text Document\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
            mimeType.startsWith("text/") || ext in listOf("txt", "md", "csv", "json") -> {
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                binding.textPreview.text = "📝 $originalFileName:\n\n" + (if (text.length > 500) text.take(500) + "…" else text)
                binding.textPreview.visibility = View.VISIBLE
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.visibility = View.GONE
            }
            else -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.fileTypeIcon.text = "📎 Attached File\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
        }
    }

    // ── Contributor chips ────────────────────────────────────────────────

    private fun getOwnerColorHex(name: String): String {
        val lower = name.lowercase().trim()
        val defaultOptionsMap = mapOf(
            "fido" to "#E53935",          // Red
            "whiskers" to "#F57C00",         // Orange
            "polly" to "#FFB300",           // Yellow
            "bubbles" to "#43A047",            // Green
            "nibbles" to "#1E88E5",          // Blue
            "thumper" to "#8E24AA",         // Purple
            "patches" to "#D81B60",        // Pink
            "coach owl" to "#546E7A", // Slate
            "coach pigg" to "#546E7A"      // Slate
        )
        val defaultColor = defaultOptionsMap[lower]
        if (defaultColor != null) return defaultColor

        val palette = listOf(
            "#E53935", "#F57C00", "#FFB300", "#43A047",
            "#1E88E5", "#8E24AA", "#D81B60", "#546E7A"
        )
        val index = abs(lower.hashCode()) % palette.size
        return palette[index]
    }

    private fun populateContributorChips() {
        val chipGroup = binding.contributorChips
        chipGroup.removeAllViews()
        val sessionSelection = settings.sessionContributors.toSet()

        for (name in settings.teamRoster) {
            val hexColor = getOwnerColorHex(name)
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = name in sessionSelection

                runCatching {
                    val bg = Color.parseColor(hexColor)
                    chipBackgroundColor = ColorStateList.valueOf(bg)
                    val isDark = ColorUtils.calculateLuminance(bg) < 0.5
                    setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                }
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

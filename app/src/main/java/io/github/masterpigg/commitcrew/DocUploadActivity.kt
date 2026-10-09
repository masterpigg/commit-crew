package io.github.masterpigg.commitcrew

import io.github.masterpigg.commitcrew.shared.CoreValue
import io.github.masterpigg.commitcrew.shared.resolveOwnerColorHex
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.github.masterpigg.commitcrew.databinding.ActivityDocUploadBinding
import io.github.masterpigg.commitcrew.databinding.ItemSavedNoteBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            fileBytes = baos.toByteArray()
            detectedMimeType = "image/png"
            val timeStamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            originalFileName = "photo_$timeStamp.png"

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            binding.inputDocTitle.setText("${today}_photo_$timeStamp")
            binding.buttonUpload.isEnabled = true
            binding.actionOverlay.visibility = View.GONE

            showPreview()
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
        if (settings.isConfigured) {
            loadSavedNotes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root, binding.toolbar)

        settings = Settings(this)

        setupPrimaryNavToggle()
        binding.buttonDone.setOnClickListener { finish() }

        binding.btnTakePhoto.setOnClickListener {
            takePhotoLauncher.launch(null)
        }

        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        binding.btnTranscribeText.setOnClickListener {
            transcribeTextFromImage()
        }

        binding.btnRefreshSavedNotes.setOnClickListener {
            loadSavedNotes()
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

        // ── Populate contributor & core value chips ─────────────────────────
        populateContributorChips()
        populateCoreValueChips()

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

            // Launched from top nav (no shared content) — user will take a photo or pick a file
            else -> {
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.actionOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun showPreview() {
        val rawBytes = fileBytes ?: return
        val mimeType = detectedMimeType.orEmpty()
        val ext = originalFileName.substringAfterLast('.', "").lowercase()

        when {
            mimeType.startsWith("image/") || ext in listOf("png", "jpg", "jpeg", "gif", "bmp", "svg") -> {
                // Perform smart image compression for large photos
                fileBytes = ImageCompressor.compress(rawBytes)
                val compressedBytes = fileBytes!!

                val bitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
                if (bitmap != null) {
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.imagePreview.visibility = View.VISIBLE
                    binding.fileTypeIcon.visibility = View.GONE
                    binding.textPreview.visibility = View.GONE
                    binding.btnTranscribeText.visibility = View.VISIBLE
                } else {
                    binding.fileTypeIcon.text = "🖼️ $originalFileName"
                    binding.fileTypeIcon.visibility = View.VISIBLE
                    binding.imagePreview.visibility = View.GONE
                    binding.textPreview.visibility = View.GONE
                    binding.btnTranscribeText.visibility = View.GONE
                }
            }
            mimeType.contains("pdf") || ext == "pdf" -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.btnTranscribeText.visibility = View.GONE
                binding.fileTypeIcon.text = "📄 PDF Document\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
            mimeType.contains("word") || mimeType.contains("document") || ext in listOf("doc", "docx", "pages") -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.btnTranscribeText.visibility = View.GONE
                binding.fileTypeIcon.text = "📝 Word / Text Document\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
            mimeType.startsWith("text/") || ext in listOf("txt", "md", "csv", "json") -> {
                val text = runCatching { String(rawBytes, Charsets.UTF_8) }.getOrDefault("")
                binding.textPreview.text = "📝 $originalFileName:\n\n" + (if (text.length > 500) text.take(500) + "…" else text)
                binding.textPreview.visibility = View.VISIBLE
                binding.imagePreview.visibility = View.GONE
                binding.fileTypeIcon.visibility = View.GONE
                binding.btnTranscribeText.visibility = View.GONE
            }
            else -> {
                binding.imagePreview.visibility = View.GONE
                binding.textPreview.visibility = View.GONE
                binding.btnTranscribeText.visibility = View.GONE
                binding.fileTypeIcon.text = "📎 Attached File\n$originalFileName"
                binding.fileTypeIcon.visibility = View.VISIBLE
            }
        }
    }

    private fun transcribeTextFromImage() {
        val bytes = fileBytes ?: return
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

        showStatus("Sharpening image & transcribing handwriting…", isError = false)
        binding.btnTranscribeText.isEnabled = false

        val enhancedBitmap = enhanceHandwritingBitmap(rawBitmap)
        val image = InputImage.fromBitmap(enhancedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                binding.btnTranscribeText.isEnabled = true
                val resultText = visionText.text.trim()
                if (resultText.isNotBlank()) {
                    val currentComment = binding.inputComment.text?.toString().orEmpty()
                    val newComment = if (currentComment.isBlank()) {
                        "📝 Transcribed Notes:\n$resultText"
                    } else {
                        "$currentComment\n\n📝 Transcribed Notes:\n$resultText"
                    }
                    binding.inputComment.setText(newComment)
                    showStatus("✨ Transcribed ${resultText.length} characters from notes!", isError = false)
                } else {
                    showStatus("No readable text found. Try holding camera closer or using 🎙️ voice input.", isError = false)
                }
            }
            .addOnFailureListener { e ->
                binding.btnTranscribeText.isEnabled = true
                showStatus("Text recognition failed: ${e.message}", isError = true)
            }
    }

    private fun enhanceHandwritingBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val colorMatrix = ColorMatrix().apply {
            set(
                floatArrayOf(
                    1.8f, 0f, 0f, 0f, -80f,
                    0f, 1.8f, 0f, 0f, -80f,
                    0f, 0f, 1.8f, 0f, -80f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    // ── Contributor chips ────────────────────────────────────────────────

    private fun getOwnerColorHex(name: String): String = resolveOwnerColorHex(name)

    private fun styleSelectableChip(chip: Chip, hexColor: String, isCheckedByDefault: Boolean = false) {
        chip.isCheckable = true
        chip.isCheckedIconVisible = true
        chip.isChecked = isCheckedByDefault
        chip.isEnabled = true

        val baseColor = runCatching { Color.parseColor(hexColor) }.getOrDefault(Color.parseColor("#1976D2"))
        val contrastColor = Color.WHITE

        val bgColors = ColorStateList.valueOf(baseColor)

        val strokeColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT)
        )

        chip.chipBackgroundColor = bgColors
        chip.chipStrokeColor = strokeColors
        chip.chipStrokeWidth = 4f
        chip.setTextColor(contrastColor)
        chip.checkedIconTint = ColorStateList.valueOf(contrastColor)
    }

    private fun populateContributorChips() {
        val chipGroup = binding.contributorChips
        chipGroup.removeAllViews()
        val sessionSelection = settings.sessionContributors.toSet()

        for (name in settings.teamRoster) {
            val hexColor = getOwnerColorHex(name)
            val chip = Chip(this).apply {
                text = name
                styleSelectableChip(this, hexColor, isCheckedByDefault = name in sessionSelection)
            }
            chipGroup.addView(chip)
        }
    }

    private fun populateCoreValueChips() {
        val chipGroup = binding.coreValueChips
        chipGroup.removeAllViews()
        for (cv in CoreValue.entries) {
            val chip = Chip(this).apply {
                text = cv.displayName
                styleSelectableChip(this, cv.hexColor, isCheckedByDefault = false)
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
                R.id.action_refresh -> {
                    loadSavedNotes()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private val savedDocsList = mutableListOf<GitHubClient.DocFile>()

    private fun loadSavedNotes() {
        if (!settings.isConfigured) return
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) {
                GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                ).listSavedDocFiles()
            }
            savedDocsList.clear()
            savedDocsList.addAll(docs)
            binding.savedNotesList.adapter = SavedNoteAdapter(savedDocsList)
        }
    }

    private fun openSavedDocument(doc: GitHubClient.DocFile) {
        Toast.makeText(this, "Downloading ${doc.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                ).getFileAtCommit(doc.path, settings.branch)
            }

            if (bytes == null) {
                Toast.makeText(this@DocUploadActivity, "Failed to download ${doc.name}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dir = File(cacheDir, "docs").apply { if (!exists()) mkdirs() }
            val file = File(dir, doc.name)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(this@DocUploadActivity, "$packageName.fileprovider", file)
            val ext = doc.name.substringAfterLast('.', "").lowercase()
            val mimeType = when (ext) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "txt", "md" -> "text/plain"
                "doc", "docx" -> "application/msword"
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(Intent.createChooser(intent, "Open with…"))
            } catch (e: Exception) {
                Toast.makeText(this@DocUploadActivity, "No app found to open ${doc.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteSavedDocument(doc: GitHubClient.DocFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Document?")
            .setMessage("Are you sure you want to delete '${doc.name}' from GitHub?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).deleteFile(doc.path, "Delete ${doc.name}")
                    }
                    if (success) {
                        savedDocsList.remove(doc)
                        binding.savedNotesList.adapter?.notifyDataSetChanged()
                        Toast.makeText(this@DocUploadActivity, "Deleted ${doc.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DocUploadActivity, "Failed to delete ${doc.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class SavedNoteAdapter(
        private val items: List<GitHubClient.DocFile>
    ) : RecyclerView.Adapter<SavedNoteAdapter.VH>() {

        inner class VH(val binding: ItemSavedNoteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSavedNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val doc = items[position]
            val b = holder.binding

            val ext = doc.name.substringAfterLast('.', "").lowercase()
            b.textFileTypeIcon.text = when {
                ext in listOf("png", "jpg", "jpeg", "gif", "svg") -> "🖼️"
                ext == "pdf" -> "📄"
                ext in listOf("doc", "docx", "txt", "md") -> "📝"
                else -> "📎"
            }

            b.textDocTitle.text = doc.name
            b.badgeCategory.text = doc.category.replace("-", " ").replaceFirstChar { it.uppercase() }

            b.btnOpenDoc.setOnClickListener { openSavedDocument(doc) }
            b.btnDeleteDoc.setOnClickListener { deleteSavedDocument(doc) }
        }

        override fun getItemCount() = items.size
    }
}

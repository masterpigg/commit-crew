package com.fll.pushtogithub

import com.fll.pushtogithub.shared.CoreValue
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.fll.pushtogithub.databinding.ActivityShareBinding
import com.google.android.material.chip.Chip
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Receives files via Android's share sheet (single or multiple).
 *
 * If .llsp3 projects are included, shows the code-push interface (contributor
 * chips, robot selector, comment) to apply shared metadata across all selected
 * projects. Progress is shown per project ([x of y]) as files are extracted
 * and committed to GitHub.
 *
 * Non-.llsp3 files are routed to [DocUploadActivity].
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var settings: Settings

    data class SharedFile(
        val uri: Uri,
        val fileName: String,
        val bytes: ByteArray
    )

    private val sharedFiles = mutableListOf<SharedFile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.toolbar.setNavigationOnClickListener { goToTeamCode() }
        binding.buttonCancel.setOnClickListener { goToTeamCode() }
        binding.buttonDone.setOnClickListener { goToTeamCode() }

        if (!settings.isConfigured) {
            showStatus(getString(R.string.need_setup), isError = true)
            binding.buttonPush.isEnabled = false
            return
        }

        // ── Extract all shared file URIs (single or multiple) ────────────
        extractSharedFilesFromIntent()

        if (sharedFiles.isEmpty()) {
            showStatus(getString(R.string.no_file), isError = true)
            binding.buttonPush.isEnabled = false
            return
        }

        // ── Filter .llsp3 project files vs non-code files ────────────────
        val llspFiles = sharedFiles.filter { it.fileName.endsWith(".llsp3", ignoreCase = true) }

        if (llspFiles.isEmpty()) {
            // No .llsp3 files found — forward non-code files to DocUploadActivity
            val docIntent = Intent(this, DocUploadActivity::class.java).apply {
                action = intent.action
                type = intent.type
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(sharedFiles.map { it.uri })
                )
            }
            startActivity(docIntent)
            finish()
            return
        }

        // ── Update UI header & project name field ────────────────────────
        if (llspFiles.size == 1) {
            val single = llspFiles[0]
            val rawName = single.fileName
                .removeSuffix(".llsp3").removeSuffix(".LLSP3")
                .trim()
            val cleanProjectName = rawName.replace(Regex("\\s*-\\s*\\d+$"), "").trim().ifBlank { rawName }
            binding.fileNameText.text = "📁 ${single.fileName}"
            binding.inputProjectName.setText(cleanProjectName)

            val issue = settings.issueForProject(cleanProjectName)
            if (issue != null) {
                binding.chipLinkedIssue.text = getString(R.string.linked_to_issue, issue)
                binding.chipLinkedIssue.visibility = View.VISIBLE
            }
        } else {
            val fileListStr = llspFiles.joinToString("\n") { "• ${it.fileName}" }
            val headerTitle = getString(R.string.projects_selected, llspFiles.size)
            binding.fileNameText.text = "📁 $headerTitle\n$fileListStr"
            binding.inputProjectName.setText("Multiple Projects (${llspFiles.size})")
            binding.inputProjectName.isEnabled = false
        }

        // Populate metadata chips
        populateContributorChips()
        populateRobotChips()
        populateCoreValueChips()

        binding.buttonPush.setOnClickListener { onPushClicked(llspFiles) }
    }

    // ── Intent extraction ────────────────────────────────────────────────

    private fun extractSharedFilesFromIntent() {
        val uris = mutableListOf<Uri>()

        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )
            if (list != null) uris.addAll(list)
        } else {
            val single = IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )
            if (single != null) uris.add(single)
        }

        for (uri in uris) {
            val name = resolveFileName(uri)
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    sharedFiles.add(SharedFile(uri, name, bytes))
                }
            } catch (e: Exception) {
                // Ignore unreadable individual files
            }
        }
    }

    // ── Chip population ──────────────────────────────────────────────────

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

    private fun styleSelectableChip(chip: Chip, hexColor: String, isCheckedByDefault: Boolean = false) {
        chip.isCheckable = true
        chip.isCheckedIconVisible = true
        chip.isChecked = isCheckedByDefault
        chip.isEnabled = true

        val baseColor = runCatching { Color.parseColor(hexColor) }.getOrDefault(Color.parseColor("#1976D2"))
        val isDark = ColorUtils.calculateLuminance(baseColor) < 0.5
        val contrastColor = if (isDark) Color.WHITE else Color.BLACK

        val alphaUnchecked = ColorUtils.setAlphaComponent(baseColor, 50)

        val bgColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(baseColor, alphaUnchecked, baseColor)
        )

        val strokeColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(contrastColor, baseColor, baseColor)
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

    private fun populateRobotChips() {
        val chipGroup = binding.robotChips
        chipGroup.removeAllViews()

        val noneChip = Chip(this).apply {
            text = getString(R.string.label_no_robot)
            isCheckable = true
            isChecked = true
        }
        chipGroup.addView(noneChip)

        for (name in settings.robotNicknames) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
            }
            chipGroup.addView(chip)
        }
    }

    // ── Multi-project Push Execution ─────────────────────────────────────

    private fun onPushClicked(projectsToPush: List<SharedFile>) {
        val comment = binding.inputComment.text?.toString()?.trim().orEmpty()
        val selectedContributors = getSelectedContributors()
        val selectedRobot = getSelectedRobot()

        if (selectedContributors.isEmpty()) {
            showStatus(getString(R.string.need_contributors), isError = true)
            return
        }
        if (comment.isBlank()) {
            showStatus(getString(R.string.need_comment), isError = true)
            return
        }

        // Save session stickiness
        settings.sessionContributors = selectedContributors

        setLoading(true)
        binding.buttonCancel.visibility = View.GONE

        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0
            val errorList = mutableListOf<String>()

            for ((index, file) in projectsToPush.withIndex()) {
                val stepNum = index + 1
                val totalSteps = projectsToPush.size

                val projName = if (projectsToPush.size == 1) {
                    val inputName = binding.inputProjectName.text?.toString()?.trim()
                    if (!inputName.isNullOrBlank()) inputName else file.fileName.removeSuffix(".llsp3").replace(Regex("\\s*-\\s*\\d+$"), "").trim()
                } else {
                    file.fileName.removeSuffix(".llsp3").removeSuffix(".LLSP3").replace(Regex("\\s*-\\s*\\d+$"), "").trim()
                }

                // Step progress feedback for kids [x of y]
                val stepStatus = if (totalSteps == 1) {
                    "Pushing $projName to GitHub…\nExtracting Scratch files & committing…"
                } else {
                    "[$stepNum of $totalSteps] Pushing $projName…\nExtracting Scratch files & committing…"
                }
                showStatus(stepStatus, isError = false)

                val issueNumber = settings.issueForProject(projName)

                val outcome = withContext(Dispatchers.IO) {
                    PushService(settings).push(
                        projectName = projName,
                        contributors = selectedContributors,
                        tabletName = settings.tabletName,
                        robotNickname = selectedRobot,
                        comment = comment,
                        fileName = file.fileName,
                        fileBytes = file.bytes,
                        issueNumber = issueNumber
                    )
                }

                if (outcome.success) {
                    successCount++
                    val localDir = getExternalFilesDir(null) ?: cacheDir
                    if (!localDir.exists()) localDir.mkdirs()
                    runCatching { File(localDir, "$projName.llsp3").writeBytes(file.bytes) }
                } else {
                    failCount++
                    errorList.add("${file.fileName}: ${outcome.message}")
                }
            }

            setLoading(false)

            if (failCount == 0) {
                val finalMsg = if (projectsToPush.size == 1) {
                    "✅ Saved ${projectsToPush[0].fileName} to GitHub!"
                } else {
                    "✅ Successfully saved all $successCount projects to GitHub!"
                }
                showStatus(finalMsg, isError = false)
                binding.buttonPush.isEnabled = false
                binding.buttonDone.visibility = View.VISIBLE

                delay(1800)
                goToTeamCode()
            } else {
                val errorMsg = "⚠️ Pushed $successCount of ${projectsToPush.size} projects.\nErrors:\n" +
                    errorList.joinToString("\n")
                showStatus(errorMsg, isError = true)
                binding.buttonDone.visibility = View.VISIBLE
            }
        }
    }

    private fun goToTeamCode() {
        val intent = Intent(this, TimeMachineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun getSelectedContributors(): List<String> {
        val chipGroup = binding.contributorChips
        val selected = mutableListOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) selected += chip.text.toString()
        }
        return selected
    }

    private fun getSelectedRobot(): String? {
        val chipGroup = binding.robotChips
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                val text = chip.text.toString()
                return if (text == getString(R.string.label_no_robot)) null else text
            }
        }
        return null
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
        return name ?: uri.lastPathSegment ?: "project.llsp3"
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            getColor(if (isError) R.color.error else R.color.success)
        )
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonPush.isEnabled = !loading
    }
}

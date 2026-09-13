package com.fll.pushtogithub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import com.caverock.androidsvg.SVG
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fll.pushtogithub.databinding.ActivityTimeMachineBinding
import com.fll.pushtogithub.databinding.ItemCheckpointBinding
import com.fll.pushtogithub.databinding.ItemProjectCardBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The app's launcher screen — "Team Code" / Time Machine.
 *
 * Shows a list of project folders from the repo, lets kids open the latest
 * version in SPIKE, and browse past checkpoints with Scratch code previews.
 * Supports sorting by Most Recent First (default) or Alphabetical.
 */
class TimeMachineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeMachineBinding
    private lateinit var settings: Settings

    data class ProjectItem(
        val folder: GitHubClient.ProjectFolder,
        var lastCommitDate: String = "",
        var lastCommitSummary: String = "",
        var isArchived: Boolean = false
    )

    private var projectItems = mutableListOf<ProjectItem>()
    private var currentSortMode = "recent" // "recent", "name_asc", "name_desc"

    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeMachineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        // ── Main Section Toggle ──────────────────────────────────────────
        binding.mainNavToggle.check(R.id.navTeamCode)
        binding.mainNavToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && checkedId == R.id.navTaskBoard && !isNavigating) {
                isNavigating = true
                val intent = Intent(this, KanbanActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
        }

        // ── Toolbar menu ─────────────────────────────────────────────────
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // ── Sort chip listener ───────────────────────────────────────────
        binding.sortChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            currentSortMode = when (checkedIds[0]) {
                R.id.chipSortNameAsc -> "name_asc"
                R.id.chipSortNameDesc -> "name_desc"
                R.id.chipShowArchived -> "archived"
                else -> "recent"
            }
            sortAndRenderProjects()
        }

        // ── FAB → Doc Upload ─────────────────────────────────────────────
        binding.fabSnapNotes.setOnClickListener {
            startActivity(Intent(this, DocUploadActivity::class.java))
        }

        // ── Swipe to refresh ─────────────────────────────────────────────
        binding.swipeRefresh.setOnRefreshListener { loadProjects() }

        // ── First-launch-of-day sync prompt ──────────────────────────────
        if (settings.isConfigured && settings.isFirstLaunchOfDay()) {
            showSyncPrompt()
        }

        // ── Redirect to settings if not configured ───────────────────────
        if (!settings.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            Toast.makeText(this, getString(R.string.need_setup), Toast.LENGTH_LONG).show()
        } else {
            loadProjects()
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        binding.mainNavToggle.check(R.id.navTeamCode)
        if (settings.isConfigured) {
            loadProjects()
        }
    }

    // ── Data loading & sorting ────────────────────────────────────────────

    private fun loadProjects() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        lifecycleScope.launch {
            val client = GitHubClient(
                token = settings.token,
                owner = settings.owner,
                repo = settings.repo,
                branch = settings.branch
            )

            // One-time migration & README initialization
            withContext(Dispatchers.IO) {
                client.migrateFolder("research-project", "innovation-project")
                client.initializeDefaultReadmes(settings.teamRoster)
            }

            val folders = withContext(Dispatchers.IO) { client.listFolders(settings.basePath) }
            val archived = withContext(Dispatchers.IO) { client.getArchivedProjectNames(settings.basePath).toSet() }

            projectItems = folders.map {
                ProjectItem(
                    folder = it,
                    isArchived = archived.contains(it.name)
                )
            }.toMutableList()

            binding.progress.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            if (projectItems.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.projectList.adapter = null
            } else {
                binding.emptyText.visibility = View.GONE
                sortAndRenderProjects()

                // Fetch latest commit timestamps for each project in background to sort by Most Recent
                withContext(Dispatchers.IO) {
                    for (item in projectItems) {
                        val filePath = "${settings.basePath}/${item.folder.name}/${item.folder.name}.llsp3"
                        val history = client.getFileHistory(filePath, perPage = 1)
                        if (history.isNotEmpty()) {
                            val commit = history[0]
                            item.lastCommitDate = commit.date
                            item.lastCommitSummary = formatCommitSummary(commit)
                        }
                    }
                }
                sortAndRenderProjects()
            }
        }
    }

    private fun sortAndRenderProjects() {
        if (projectItems.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.projectList.adapter = null
            return
        }

        val displayList = if (currentSortMode == "archived") {
            projectItems.filter { it.isArchived }
        } else {
            projectItems.filter { !it.isArchived }
        }

        if (displayList.isEmpty()) {
            binding.emptyText.text = if (currentSortMode == "archived") "No archived projects." else getString(R.string.time_machine_empty)
            binding.emptyText.visibility = View.VISIBLE
            binding.projectList.adapter = null
            return
        }

        binding.emptyText.visibility = View.GONE
        val sortedList = when (currentSortMode) {
            "name_asc" -> displayList.sortedBy { it.folder.name.lowercase() }
            "name_desc" -> displayList.sortedByDescending { it.folder.name.lowercase() }
            "archived" -> displayList.sortedBy { it.folder.name.lowercase() }
            else -> displayList.sortedByDescending { it.lastCommitDate }
        }

        binding.projectList.adapter = ProjectAdapter(sortedList)
    }

    // ── Sync prompt dialog ───────────────────────────────────────────────

    private fun showSyncPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sync_prompt_title))
            .setMessage(getString(R.string.sync_prompt_message))
            .setPositiveButton(getString(R.string.sync_prompt_yes)) { dialog, _ ->
                dialog.dismiss()
                loadProjects()
            }
            .setNegativeButton(getString(R.string.sync_prompt_no)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    // ── RecyclerView Adapter: Project Cards ──────────────────────────────

    inner class ProjectAdapter(
        private val items: List<ProjectItem>
    ) : RecyclerView.Adapter<ProjectAdapter.VH>() {

        inner class VH(val binding: ItemProjectCardBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemProjectCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val project = item.folder
            val b = holder.binding

            b.projectName.text = project.name
            b.lastPushInfo.text = item.lastCommitSummary.ifBlank { "…" }

            val filePath = "${settings.basePath}/${project.name}/${project.name}.llsp3"
            val cacheKey = "proj_${project.name}_${item.lastCommitDate.ifBlank { "latest" }}"

            // Check L1/L2 Image Cache first (Instant 0ms load!)
            val cachedBitmap = ImageCache.get(this@TimeMachineActivity, cacheKey)
            if (cachedBitmap != null) {
                b.projectPreviewImage.setImageBitmap(cachedBitmap)
                b.previewCard.visibility = View.VISIBLE
                b.previewCard.setOnClickListener {
                    showFullScreenImageDialog(project.name, cachedBitmap) {
                        downloadAndOpen(project.name, filePath, null)
                    }
                }
            } else {
                // Fetch preview image thumbnail (PNG or SVG) asynchronously
                lifecycleScope.launch {
                    val imageBytes = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).getProjectPreviewImage(settings.basePath, project.name)
                    }
                    if (imageBytes != null) {
                        val bitmap = decodeImageBytes(imageBytes)
                        if (bitmap != null) {
                            ImageCache.put(this@TimeMachineActivity, cacheKey, bitmap)
                            b.projectPreviewImage.setImageBitmap(bitmap)
                            b.previewCard.visibility = View.VISIBLE
                            b.previewCard.setOnClickListener {
                                showFullScreenImageDialog(project.name, bitmap) {
                                    downloadAndOpen(project.name, filePath, null)
                                }
                            }
                        }
                    }
                }
            }

            if (item.isArchived) {
                b.badgeArchived.visibility = View.VISIBLE
                b.buttonArchive.text = "📤 Unarchive"
            } else {
                b.badgeArchived.visibility = View.GONE
                b.buttonArchive.text = "📦 Archive"
            }

            b.buttonArchive.setOnClickListener {
                toggleArchiveProject(item)
            }

            // Open Latest in SPIKE
            b.buttonOpenLatest.setOnClickListener {
                downloadAndOpen(project.name, filePath, null)
            }

            // View Checkpoints (toggle)
            var checkpointsLoaded = false
            b.buttonCheckpoints.setOnClickListener {
                if (b.checkpointList.visibility == View.VISIBLE) {
                    b.checkpointList.visibility = View.GONE
                    return@setOnClickListener
                }
                b.checkpointList.visibility = View.VISIBLE
                if (!checkpointsLoaded) {
                    checkpointsLoaded = true
                    loadCheckpoints(project.name, filePath, b.checkpointList)
                }
            }

            // Long press → Link to Issue
            b.root.setOnLongClickListener {
                showLinkIssueDialog(project.name)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private fun toggleArchiveProject(item: ProjectItem) {
        val projectName = item.folder.name
        val willArchive = !item.isArchived

        val title = if (willArchive) "Archive $projectName?" else "Unarchive $projectName?"
        val msg = if (willArchive) {
            "Do you want to archive '$projectName'? It will be hidden from the active project list, but you can view or restore it anytime."
        } else {
            "Do you want to restore '$projectName' back to the active project list?"
        }
        val btnText = if (willArchive) "Archive" else "Unarchive"

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(btnText) { _, _ ->
                binding.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        val client = GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        )
                        if (willArchive) {
                            client.archiveProject(settings.basePath, projectName)
                        } else {
                            client.unarchiveProject(settings.basePath, projectName)
                        }
                    }
                    binding.progress.visibility = View.GONE
                    if (success) {
                        item.isArchived = willArchive
                        sortAndRenderProjects()
                        val toastMsg = if (willArchive) "Archived $projectName" else "Restored $projectName to active list"
                        Toast.makeText(this@TimeMachineActivity, toastMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@TimeMachineActivity, "Failed to update archive status on GitHub.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Checkpoint loading & adapter ─────────────────────────────────────

    private fun loadCheckpoints(
        projectName: String,
        filePath: String,
        recycler: RecyclerView
    ) {
        recycler.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                ).getFileHistory(filePath, perPage = 30)
            }
            recycler.adapter = CheckpointAdapter(projectName, filePath, history)
        }
    }

    inner class CheckpointAdapter(
        private val projectName: String,
        private val filePath: String,
        private val items: List<GitHubClient.CommitInfo>
    ) : RecyclerView.Adapter<CheckpointAdapter.VH>() {

        inner class VH(val binding: ItemCheckpointBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCheckpointBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val commit = items[position]
            val b = holder.binding

            val isLatest = position == 0
            b.checkpointDate.text = buildString {
                append(formatRelativeDate(commit.date))
                if (isLatest) append(" (Latest)")
            }
            b.checkpointMessage.text = parseUserComment(commit.message)

            val cacheKey = "chk_${projectName}_${commit.sha}"
            val cachedBitmap = ImageCache.get(this@TimeMachineActivity, cacheKey)
            if (cachedBitmap != null) {
                b.checkpointPreviewImage.setImageBitmap(cachedBitmap)
                b.checkpointPreviewCard.visibility = View.VISIBLE
                b.checkpointPreviewCard.setOnClickListener {
                    val title = "$projectName (${formatRelativeDate(commit.date)})"
                    showFullScreenImageDialog(title, cachedBitmap, getString(R.string.open_this_version)) {
                        downloadAndOpen(projectName, filePath, commit.sha)
                    }
                }
            } else {
                // Fetch Scratch code preview diagram for this checkpoint SHA asynchronously
                lifecycleScope.launch {
                    val imageBytes = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).getProjectPreviewImageAtCommit(settings.basePath, projectName, commit.sha)
                    }
                    if (imageBytes != null) {
                        val bitmap = decodeImageBytes(imageBytes)
                        if (bitmap != null) {
                            ImageCache.put(this@TimeMachineActivity, cacheKey, bitmap)
                            b.checkpointPreviewImage.setImageBitmap(bitmap)
                            b.checkpointPreviewCard.visibility = View.VISIBLE
                            b.checkpointPreviewCard.setOnClickListener {
                                val title = "$projectName (${formatRelativeDate(commit.date)})"
                                showFullScreenImageDialog(title, bitmap, getString(R.string.open_this_version)) {
                                    downloadAndOpen(projectName, filePath, commit.sha)
                                }
                            }
                        }
                    }
                }
            }

            b.buttonOpenCheckpoint.setOnClickListener {
                downloadAndOpen(projectName, filePath, commit.sha)
            }
        }

        override fun getItemCount() = items.size
    }

    private fun showFullScreenImageDialog(
        title: String,
        bitmap: Bitmap,
        openButtonText: String = getString(R.string.open_in_spike),
        onOpenClicked: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_full_screen_image, null
        )

        val image = dialogView.findViewById<ImageView>(R.id.fullScreenImage)
        val textTitle = dialogView.findViewById<TextView>(R.id.textTitle)
        val btnClose = dialogView.findViewById<View>(R.id.buttonClose)
        val btnOpen = dialogView.findViewById<MaterialButton>(R.id.buttonOpenSpike)

        image.setImageBitmap(bitmap)
        textTitle.text = title
        btnOpen.text = openButtonText

        val dialog = MaterialAlertDialogBuilder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView)
            .create()

        dialog.show()

        // Expand dialog window to fill 100% of the tablet display
        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        image.setOnClickListener { dialog.dismiss() }
        btnOpen.setOnClickListener {
            dialog.dismiss()
            onOpenClicked()
        }
    }

    private fun decodeImageBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null

        // 1. Try decoding as PNG/JPEG/WEBP via BitmapFactory
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) return bitmap

        // 2. Try rendering as SVG via AndroidSVG at high resolution (1200x800+)
        return try {
            val svg = SVG.getFromInputStream(bytes.inputStream())
            val width = (svg.documentWidth * 2.5f).toInt().coerceAtLeast(1200)
            val height = (svg.documentHeight * 2.5f).toInt().coerceAtLeast(800)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            svg.renderToCanvas(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    // ── Download & open in SPIKE ─────────────────────────────────────────

    private fun getAppLocalFile(projectName: String): File {
        val dir = getExternalFilesDir(null) ?: cacheDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$projectName.llsp3")
    }

    private fun getSpikePackageName(): String? {
        val spikePackages = listOf(
            "com.lego.education.spikenext",
            "com.lego.common.spike",
            "com.lego.spike",
            "com.lego.education.spike",
            "com.lego.spike3"
        )
        for (pkg in spikePackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) return pkg
        }
        return null
    }

    private fun getSpikeLaunchIntent(): Intent? {
        val pkg = getSpikePackageName()
        return if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
    }

    private fun launchFileInSpike(file: File) {
        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

        val spikePkg = getSpikePackageName()

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (spikePkg != null) {
                setPackage(spikePkg)
            }
        }

        try {
            startActivity(openIntent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(fallbackIntent, "Open with…"))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "No app found to open .llsp3 files. Is SPIKE installed?",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun downloadAndOpen(projectName: String, filePath: String, commitSha: String?) {
        Toast.makeText(this, "Checking GitHub…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val remoteBytes = withContext(Dispatchers.IO) {
                val client = GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                )
                if (commitSha != null) {
                    client.getFileAtCommit(filePath, commitSha)
                } else {
                    client.getFileAtCommit(filePath, settings.branch)
                }
            }

            if (remoteBytes == null) {
                Toast.makeText(
                    this@TimeMachineActivity,
                    "Could not download $projectName from GitHub.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val remoteHash = computeSha256(remoteBytes)
            val localFile = getAppLocalFile(projectName)
            val localBytes = if (localFile.exists()) runCatching { localFile.readBytes() }.getOrNull() else null
            val localHash = if (localBytes != null) computeSha256(localBytes) else ""

            if (localBytes != null && localHash == remoteHash) {
                val spikeAppIntent = getSpikeLaunchIntent()
                if (spikeAppIntent != null) {
                    MaterialAlertDialogBuilder(this@TimeMachineActivity)
                        .setTitle("Open $projectName in SPIKE")
                        .setMessage("How would you like to open $projectName?\n\n" +
                            "• 'Switch to SPIKE App': Brings up SPIKE where $projectName is waiting on your projects screen. (Prevents $projectName - 1 duplicates!)\n\n" +
                            "• 'Re-import Canvas': Opens $projectName directly into the SPIKE editor canvas. (SPIKE will save it as $projectName - 1)")
                        .setPositiveButton("Switch to SPIKE App") { _, _ ->
                            Toast.makeText(
                                this@TimeMachineActivity,
                                "Switching to SPIKE App…",
                                Toast.LENGTH_SHORT
                            ).show()
                            startActivity(spikeAppIntent)
                        }
                        .setNegativeButton("Re-import Canvas") { _, _ ->
                            launchFileInSpike(localFile)
                        }
                        .show()
                } else {
                    launchFileInSpike(localFile)
                }
                return@launch
            }

            // Write or update file in app storage (guaranteed 100% permission success)
            try {
                localFile.writeBytes(remoteBytes)
            } catch (e: Exception) {
                Toast.makeText(
                    this@TimeMachineActivity,
                    "Error saving local file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val spikeAppIntent = getSpikeLaunchIntent()
            if (spikeAppIntent != null) {
                MaterialAlertDialogBuilder(this@TimeMachineActivity)
                    .setTitle("Open $projectName in SPIKE")
                    .setMessage("How would you like to open $projectName?\n\n" +
                        "• 'Switch to SPIKE App': Brings up SPIKE if $projectName is already in your SPIKE App. (Prevents $projectName - 1 duplicates!)\n\n" +
                        "• 'Import File': Opens $projectName directly into the SPIKE editor.")
                    .setPositiveButton("Switch to SPIKE App") { _, _ ->
                        Toast.makeText(
                            this@TimeMachineActivity,
                            "Switching to SPIKE App…",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(spikeAppIntent)
                    }
                    .setNegativeButton("Import File") { _, _ ->
                        launchFileInSpike(localFile)
                    }
                    .show()
            } else {
                launchFileInSpike(localFile)
            }
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ── Link to Issue dialog ─────────────────────────────────────────────

    private fun showLinkIssueDialog(projectName: String) {
        val currentIssue = settings.issueForProject(projectName)

        val input = EditText(this).apply {
            hint = getString(R.string.link_issue_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            if (currentIssue != null) setText(currentIssue.toString())
            setPadding(48, 32, 48, 16)
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.link_issue_title))
            .setView(input)
            .setPositiveButton(getString(R.string.link_issue_save)) { _, _ ->
                val num = input.text.toString().toIntOrNull()
                if (num != null) {
                    settings.setIssueForProject(projectName, num)
                    Toast.makeText(this, "$projectName linked to #$num", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)

        if (currentIssue != null) {
            builder.setNeutralButton(getString(R.string.link_issue_clear)) { _, _ ->
                settings.clearIssueForProject(projectName)
                Toast.makeText(this, "Removed link from $projectName", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
    }

    // ── Formatting helpers ───────────────────────────────────────────────

    private fun formatCommitSummary(commit: GitHubClient.CommitInfo): String {
        val dateStr = formatRelativeDate(commit.date)
        val comment = parseUserComment(commit.message)
        return "$dateStr — $comment"
    }

    private fun parseUserComment(message: String): String {
        val parts = message.split(" — ")
        val raw = if (parts.size >= 3) parts.last().trim()
        else if (parts.size == 2) parts.last().trim()
        else message.trim()
        return MarkdownUtils.cleanText(raw)
    }

    private fun formatRelativeDate(isoDate: String): String {
        val date = try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(isoDate) ?: return isoDate
        } catch (e: Exception) {
            return isoDate
        }

        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { time = date }

        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val timeStr = timeFmt.format(date)

        return when {
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) ->
                "TODAY $timeStr"

            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1 ->
                "YESTERDAY $timeStr"

            else -> {
                val dateFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
                dateFmt.format(date)
            }
        }
    }
}

package com.fll.pushtogithub

import com.fll.pushtogithub.shared.CoreValue
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.fll.pushtogithub.databinding.ActivityKanbanBinding
import com.fll.pushtogithub.databinding.ItemStickyNoteBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Kid-friendly Yellow Sticky Note Kanban Board.
 *
 * Mapped to GitHub Issues & Projects (To-Do 📌, Doing 🛠️, Done ✅).
 * Supports category filtering (Robot Game 🤖, Innovation Project 💡, General 📋),
 * owner filtering, configurable card colors, and 1-tap in-app editing.
 */
class KanbanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKanbanBinding
    private lateinit var settings: Settings
    private val allCards = mutableListOf<GitHubClient.KanbanCard>()

    private var selectedCategoryFilter = "all" // "all", "robot-game", "innovation-project", "general"
    private var selectedOwnerFilter: String? = null // null for All Owners, or specific owner name
    private var selectedCoreValueFilter: String? = null // null for All Core Values, or specific Core Value

    private var isNavigating = false

    override fun onResume() {
        super.onResume()
        isNavigating = false
        binding.mainNavToggle.check(R.id.navTaskBoard)
        if (::settings.isInitialized && settings.isConfigured) {
            loadCardsFromGitHub()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKanbanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.mainNavToggle.check(R.id.navTaskBoard)
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
                    R.id.navSnapNotes -> {
                        isNavigating = true
                        val intent = Intent(this, DocUploadActivity::class.java).apply {
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
                    loadCardsFromGitHub()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        binding.buttonNewStickyNote.setOnClickListener { showNewStickyNoteDialog() }

        setupDragAndDropListeners()
        setupFilterListeners()

        // 1. Instant 0ms load from local TaskCache
        val cached = TaskCache.get(this)
        if (cached.isNotEmpty()) {
            allCards.clear()
            allCards.addAll(cached)
            renderColumns()
        }
        populateOwnerFilterChips()
        populateCoreValueFilterChips()

        // 2. Background sync with GitHub
        if (!settings.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            Toast.makeText(this, getString(R.string.need_setup), Toast.LENGTH_LONG).show()
        } else {
            loadCardsFromGitHub()
        }
    }

    private fun setupFilterListeners() {
        binding.categoryFilterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            selectedCategoryFilter = when (checkedIds[0]) {
                R.id.chipCatRobot -> "robot-game"
                R.id.chipCatInnovation -> "innovation-project"
                R.id.chipCatGeneral -> "general"
                else -> "all"
            }
            renderColumns()
        }
    }

    private var repoLabelColors = mapOf<String, String>()

    private fun resolveOwnerColor(name: String, cardColors: Map<String, String> = emptyMap()): String {
        val lower = name.lowercase().trim()

        // 1. Check live repo label / Projects v2 option colors pulled directly from GitHub
        val repoColor = repoLabelColors[lower] ?: repoLabelColors["owner:$lower"]
        if (!repoColor.isNullOrBlank()) return repoColor

        // 2. Check card label colors
        val cardColor = cardColors[name] ?: cardColors[lower] ?: cardColors["owner:$lower"]
        if (!cardColor.isNullOrBlank() && cardColor != "#1976D2") return cardColor

        // 3. Fallback: Default options palette matching GitHub
        val defaultOptionsMap = mapOf(
            "polly" to "#BF3989",          // Pink
            "whiskers" to "#2563EB",         // Blue
            "bubbles" to "#8957E5",            // Purple
            "fido" to "#DA3633",          // Red
            "thumper" to "#F59E0B",         // Yellow
            "nibbles" to "#D97706",          // Orange
            "patches" to "#2EA043",        // Green
            "coach owl" to "#6E7681", // Gray
            "coach pigg" to "#6E7681"      // Gray
        )
        val defaultColor = defaultOptionsMap[lower]
        if (defaultColor != null) return defaultColor

        // 4. Fallback: Deterministic palette based on name hash
        val palette = listOf(
            "#BF3989", "#2563EB", "#8957E5", "#DA3633",
            "#F59E0B", "#D97706", "#2EA043", "#6E7681"
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

    private fun populateOwnerFilterChips() {
        val chipGroup = binding.ownerFilterChipGroup
        chipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text = "All Workers"
            isCheckable = true
            isChecked = selectedOwnerFilter == null
        }
        chipGroup.addView(allChip)

        for (name in settings.teamRoster) {
            val hexColor = resolveOwnerColor(name)
            val chip = Chip(this).apply {
                text = name
                styleSelectableChip(this, hexColor, isCheckedByDefault = selectedOwnerFilter.equals(name, ignoreCase = true))
            }
            chipGroup.addView(chip)
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                selectedOwnerFilter = null
            } else {
                val chip = group.findViewById<Chip>(checkedIds[0])
                val text = chip?.text?.toString()
                selectedOwnerFilter = if (text == "All Workers" || text == "All Owners" || text.isNullOrBlank()) null else text
            }
            renderColumns()
        }
    }

    private fun populateCoreValueFilterChips() {
        val chipGroup = binding.coreValueFilterChipGroup
        chipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text = "All Core Values"
            isCheckable = true
            isChecked = selectedCoreValueFilter == null
        }
        chipGroup.addView(allChip)

        for (cv in CoreValue.entries) {
            val chip = Chip(this).apply {
                text = cv.displayName
                styleSelectableChip(this, cv.hexColor, isCheckedByDefault = selectedCoreValueFilter.equals(cv.displayName, ignoreCase = true))
            }
            chipGroup.addView(chip)
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                selectedCoreValueFilter = null
            } else {
                val chip = group.findViewById<Chip>(checkedIds[0])
                val text = chip?.text?.toString()
                selectedCoreValueFilter = if (text == "All Core Values" || text.isNullOrBlank()) null else text
            }
            renderColumns()
        }
    }

    private lateinit var todoDragListener: View.OnDragListener
    private lateinit var doingDragListener: View.OnDragListener
    private lateinit var doneDragListener: View.OnDragListener

    private fun setupDragAndDropListeners() {
        todoDragListener = createColumnDragListener("todo", "#FFF176", "#FFF9C4")
        doingDragListener = createColumnDragListener("doing", "#FFD54F", "#FFE082")
        doneDragListener = createColumnDragListener("done", "#A5D6A7", "#C8E6C9")

        binding.columnTodo.setOnDragListener(todoDragListener)
        binding.recyclerTodo.setOnDragListener(todoDragListener)

        binding.columnDoing.setOnDragListener(doingDragListener)
        binding.recyclerDoing.setOnDragListener(doingDragListener)

        binding.columnDone.setOnDragListener(doneDragListener)
        binding.recyclerDone.setOnDragListener(doneDragListener)
    }

    private fun createColumnDragListener(
        columnName: String,
        activeColorHex: String,
        defaultColorHex: String
    ): View.OnDragListener {
        return View.OnDragListener { _, event ->
            val parentColumnView: View = when (columnName) {
                "todo" -> binding.columnTodo
                "doing" -> binding.columnDoing
                else -> binding.columnDone
            }

            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    parentColumnView.setBackgroundColor(Color.parseColor(activeColorHex))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    parentColumnView.setBackgroundColor(Color.parseColor(defaultColorHex))
                    true
                }
                DragEvent.ACTION_DROP -> {
                    parentColumnView.setBackgroundColor(Color.parseColor(defaultColorHex))
                    val card = event.localState as? GitHubClient.KanbanCard
                    if (card != null && card.column != columnName) {
                        moveCardColumn(card, columnName)
                        Toast.makeText(
                            this@KanbanActivity,
                            "Moved #${card.number} to ${columnName.replaceFirstChar { it.uppercase() }}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    parentColumnView.setBackgroundColor(Color.parseColor(defaultColorHex))
                    true
                }
                else -> false
            }
        }
    }

    private data class KanbanLoadResult(
        val cards: List<GitHubClient.KanbanCard>,
        val discoveredOwners: List<String>,
        val labelColors: Map<String, String>
    )

    private fun loadCardsFromGitHub() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                )

                // Auto-initialize Kanban, Owner labels, and default READMEs on GitHub
                client.ensureKanbanLabelsExist()
                client.ensureOwnerLabelsExist(settings.teamRoster)
                client.initializeDefaultReadmes(settings.teamRoster)

                val labelColors = client.getRepoLabelColors()
                val resultCards = client.getKanbanCards(settings.teamRoster)
                val allDiscovered = resultCards.flatMap { it.owners }.distinct()
                KanbanLoadResult(resultCards, allDiscovered, labelColors)
            }

            repoLabelColors = result.labelColors

            val currentRoster = settings.teamRoster.toMutableList()
            var rosterChanged = false
            for (ownerName in result.discoveredOwners) {
                if (ownerName.isNotBlank() && !currentRoster.contains(ownerName)) {
                    currentRoster.add(ownerName)
                    rosterChanged = true
                }
            }
            if (rosterChanged) {
                settings.teamRoster = currentRoster
            }

            allCards.clear()
            allCards.addAll(result.cards)
            TaskCache.put(this@KanbanActivity, result.cards)
            populateOwnerFilterChips()
            populateCoreValueFilterChips()
            binding.progress.visibility = View.GONE
            renderColumns()
        }
    }

    private fun renderColumns() {
        val filteredCards = allCards.filter { card ->
            val matchesCategory = when (selectedCategoryFilter) {
                "robot-game" -> card.category == "robot-game"
                "innovation-project" -> card.category == "innovation-project"
                "general" -> card.category == "general"
                else -> true
            }
            val matchesOwner = if (selectedOwnerFilter.isNullOrBlank()) {
                true
            } else {
                card.owners.any { it.equals(selectedOwnerFilter, ignoreCase = true) }
            }
            val matchesCoreValue = if (selectedCoreValueFilter.isNullOrBlank()) {
                true
            } else {
                val filterCv = CoreValue.entries.find { it.displayName.equals(selectedCoreValueFilter, ignoreCase = true) }
                card.coreValues.any { cvName ->
                    cvName.equals(selectedCoreValueFilter, ignoreCase = true) ||
                    (filterCv != null && cvName.contains(filterCv.labelKey, ignoreCase = true)) ||
                    (filterCv != null && filterCv.displayName.contains(cvName, ignoreCase = true))
                }
            }
            matchesCategory && matchesOwner && matchesCoreValue
        }

        val todoCards = filteredCards.filter { it.column == "todo" }
        val doingCards = filteredCards.filter { it.column == "doing" }
        val doneCards = filteredCards.filter { it.column == "done" }

        binding.recyclerTodo.adapter = StickyNoteAdapter(todoCards)
        binding.recyclerDoing.adapter = StickyNoteAdapter(doingCards)
        binding.recyclerDone.adapter = StickyNoteAdapter(doneCards)
    }

    private fun moveCardColumn(card: GitHubClient.KanbanCard, newColumn: String) {
        val oldColumn = card.column
        card.column = newColumn.lowercase()
        renderColumns()

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                GitHubClient(
                    token = settings.token,
                    owner = settings.owner,
                    repo = settings.repo,
                    branch = settings.branch
                ).updateKanbanCard(card.number, newColumn, card.category, card.owners, card.coreValues)
            }
            if (!success) {
                card.column = oldColumn
                renderColumns()
                Toast.makeText(this@KanbanActivity, "Could not update card on GitHub.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOwnerSelectionDialog(card: GitHubClient.KanbanCard) {
        val roster = settings.teamRoster
        if (roster.isEmpty()) {
            Toast.makeText(this, "Add team roster in Setup first to assign workers.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIndexes = roster.map { card.owners.contains(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Assign Worker(s) to #${card.number}")
            .setMultiChoiceItems(roster.toTypedArray(), selectedIndexes) { _, which, isChecked ->
                val name = roster[which]
                if (isChecked) {
                    if (!card.owners.contains(name)) card.owners.add(name)
                } else {
                    card.owners.remove(name)
                }
            }
            .setPositiveButton("Save Worker(s)") { _, _ ->
                renderColumns()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).updateKanbanCard(card.number, card.column, card.category, card.owners, card.coreValues)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCoreValueSelectionDialog(card: GitHubClient.KanbanCard) {
        val allCv = CoreValue.entries
        val names = allCv.map { it.displayName }.toTypedArray()
        val selectedIndexes = allCv.map { cv ->
            card.coreValues.any { it.equals(cv.displayName, ignoreCase = true) || it.contains(cv.labelKey, ignoreCase = true) }
        }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Assign Core Values to #${card.number}")
            .setMultiChoiceItems(names, selectedIndexes) { _, which, isChecked ->
                val cv = allCv[which]
                if (isChecked) {
                    if (!card.coreValues.contains(cv.displayName)) card.coreValues.add(cv.displayName)
                } else {
                    card.coreValues.removeAll { it.equals(cv.displayName, ignoreCase = true) || it.contains(cv.labelKey, ignoreCase = true) }
                }
            }
            .setPositiveButton("Save Core Values") { _, _ ->
                renderColumns()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).updateKanbanCard(card.number, card.column, card.category, card.owners, card.coreValues)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognition is not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNewStickyNoteDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_new_sticky_note, null
        )

        val inputTitle = dialogView.findViewById<EditText>(R.id.inputTaskTitle)
        val inputBody = dialogView.findViewById<EditText>(R.id.inputTaskBody)
        val layoutTitle = dialogView.findViewById<TextInputLayout>(R.id.layoutTaskTitle)
        val layoutBody = dialogView.findViewById<TextInputLayout>(R.id.inputTaskBodyLayout)
        val categoryGroup = dialogView.findViewById<RadioGroup>(R.id.categoryGroup)
        val columnGroup = dialogView.findViewById<RadioGroup>(R.id.columnGroup)
        val ownerChipsGroup = dialogView.findViewById<ChipGroup>(R.id.ownerChipsGroup)

        layoutTitle?.setEndIconOnClickListener { launchVoiceInput(inputTitle) }
        layoutBody?.setEndIconOnClickListener { launchVoiceInput(inputBody) }

        setupRichTextToolbar(dialogView, inputBody)

        val coreValueChipsGroup = dialogView.findViewById<ChipGroup>(R.id.coreValueChipsGroup)
        if (coreValueChipsGroup != null) {
            coreValueChipsGroup.removeAllViews()
            for (cv in CoreValue.entries) {
                val chip = Chip(this).apply {
                    text = cv.displayName
                    styleSelectableChip(this, cv.hexColor, isCheckedByDefault = false)
                }
                coreValueChipsGroup.addView(chip)
            }
        }

        ownerChipsGroup.removeAllViews()
        for (name in settings.teamRoster) {
            val hexColor = resolveOwnerColor(name)
            val chip = Chip(this).apply {
                text = name
                styleSelectableChip(this, hexColor, isCheckedByDefault = false)
            }
            ownerChipsGroup.addView(chip)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🟨 New Task")
            .setView(dialogView)
            .setPositiveButton("Add Task") { _, _ ->
                val title = inputTitle.text?.toString()?.trim().orEmpty()
                val body = inputBody.text?.toString()?.trim().orEmpty()

                if (title.isBlank()) {
                    Toast.makeText(this, "Please enter a task title.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val category = when (categoryGroup.checkedRadioButtonId) {
                    R.id.radioCatRobot -> "robot-game"
                    R.id.radioCatInnovation -> "innovation-project"
                    else -> "general"
                }

                val column = when (columnGroup.checkedRadioButtonId) {
                    R.id.radioDoing -> "doing"
                    R.id.radioDone -> "done"
                    else -> "todo"
                }

                val selectedOwners = mutableListOf<String>()
                for (i in 0 until ownerChipsGroup.childCount) {
                    val chip = ownerChipsGroup.getChildAt(i) as? Chip ?: continue
                    if (chip.isChecked) selectedOwners.add(chip.text.toString())
                }

                val selectedCoreValues = mutableListOf<String>()
                if (coreValueChipsGroup != null) {
                    for (i in 0 until coreValueChipsGroup.childCount) {
                        val chip = coreValueChipsGroup.getChildAt(i) as? Chip ?: continue
                        if (chip.isChecked) selectedCoreValues.add(chip.text.toString())
                    }
                }

                binding.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val newCard = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).createKanbanCard(title, body, column, category, selectedOwners, selectedCoreValues)
                    }

                    binding.progress.visibility = View.GONE
                    if (newCard != null) {
                        allCards.add(0, newCard)
                        renderColumns()
                        Toast.makeText(this@KanbanActivity, "Task added to GitHub!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@KanbanActivity, "Failed to create task.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditStickyNoteDialog(card: GitHubClient.KanbanCard) {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_edit_sticky_note, null
        )

        val inputTitle = dialogView.findViewById<EditText>(R.id.inputTaskTitle)
        val inputBody = dialogView.findViewById<EditText>(R.id.inputTaskBody)
        val layoutTitle = dialogView.findViewById<TextInputLayout>(R.id.layoutTaskTitle)
        val layoutBody = dialogView.findViewById<TextInputLayout>(R.id.inputTaskBodyLayout)
        val categoryGroup = dialogView.findViewById<RadioGroup>(R.id.categoryGroup)
        val columnGroup = dialogView.findViewById<RadioGroup>(R.id.columnGroup)
        val ownerChipsGroup = dialogView.findViewById<ChipGroup>(R.id.ownerChipsGroup)
        val btnDelete = dialogView.findViewById<View>(R.id.buttonDeleteCard)

        layoutTitle?.setEndIconOnClickListener { launchVoiceInput(inputTitle) }
        layoutBody?.setEndIconOnClickListener { launchVoiceInput(inputBody) }

        setupRichTextToolbar(dialogView, inputBody)

        inputTitle.setText(card.title)
        val cleanBody = card.body.replace(Regex("(?i)^(owners?|workers?|core values?):.*?\\n+"), "").trim()
        inputBody.setText(cleanBody)

        when (card.category) {
            "robot-game" -> categoryGroup.check(R.id.radioCatRobot)
            "innovation-project" -> categoryGroup.check(R.id.radioCatInnovation)
            else -> categoryGroup.check(R.id.radioCatGeneral)
        }

        when (card.column) {
            "doing" -> columnGroup.check(R.id.radioDoing)
            "done" -> columnGroup.check(R.id.radioDone)
            else -> columnGroup.check(R.id.radioTodo)
        }

        ownerChipsGroup.removeAllViews()
        for (name in settings.teamRoster) {
            val hexColor = resolveOwnerColor(name)
            val chip = Chip(this).apply {
                text = name
                styleSelectableChip(this, hexColor, isCheckedByDefault = card.owners.contains(name))
            }
            ownerChipsGroup.addView(chip)
        }

        val coreValueChipsGroup = dialogView.findViewById<ChipGroup>(R.id.coreValueChipsGroup)
        if (coreValueChipsGroup != null) {
            coreValueChipsGroup.removeAllViews()
            for (cv in CoreValue.entries) {
                val chip = Chip(this).apply {
                    text = cv.displayName
                    styleSelectableChip(this, cv.hexColor, isCheckedByDefault = card.coreValues.contains(cv.displayName))
                }
                coreValueChipsGroup.addView(chip)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit Task #${card.number}")
            .setView(dialogView)
            .setPositiveButton("Save Changes") { _, _ ->
                val newTitle = inputTitle.text?.toString()?.trim().orEmpty().ifBlank { card.title }
                val newBody = inputBody.text?.toString()?.trim().orEmpty()

                val newCategory = when (categoryGroup.checkedRadioButtonId) {
                    R.id.radioCatRobot -> "robot-game"
                    R.id.radioCatInnovation -> "innovation-project"
                    else -> "general"
                }

                val newColumn = when (columnGroup.checkedRadioButtonId) {
                    R.id.radioDoing -> "doing"
                    R.id.radioDone -> "done"
                    else -> "todo"
                }

                val selectedOwners = mutableListOf<String>()
                for (i in 0 until ownerChipsGroup.childCount) {
                    val chip = ownerChipsGroup.getChildAt(i) as? Chip ?: continue
                    if (chip.isChecked) selectedOwners.add(chip.text.toString())
                }

                val selectedCoreValues = mutableListOf<String>()
                if (coreValueChipsGroup != null) {
                    for (i in 0 until coreValueChipsGroup.childCount) {
                        val chip = coreValueChipsGroup.getChildAt(i) as? Chip ?: continue
                        if (chip.isChecked) selectedCoreValues.add(chip.text.toString())
                    }
                }

                card.title = newTitle
                card.body = newBody
                card.column = newColumn
                card.category = newCategory
                card.owners.clear()
                card.owners.addAll(selectedOwners)
                card.coreValues.clear()
                card.coreValues.addAll(selectedCoreValues)
                renderColumns()

                binding.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).updateKanbanCardFull(card.number, newTitle, newBody, newColumn, newCategory, selectedOwners, selectedCoreValues)
                    }
                    binding.progress.visibility = View.GONE
                    if (success) {
                        Toast.makeText(this@KanbanActivity, "Task updated on GitHub!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@KanbanActivity, "Failed to update task.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        btnDelete.setOnClickListener {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete Task #${card.number}?")
                .setMessage("Are you sure you want to delete '${card.title}'?")
                .setPositiveButton("Delete") { _, _ ->
                    allCards.remove(card)
                    renderColumns()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            GitHubClient(
                                token = settings.token,
                                owner = settings.owner,
                                repo = settings.repo,
                                branch = settings.branch
                            ).deleteKanbanCard(card.number)
                        }
                    }
                    Toast.makeText(this, "Deleted task #${card.number}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun setupRichTextToolbar(dialogView: View, inputEditText: EditText) {
        val inputLayout = dialogView.findViewById<View>(R.id.inputTaskBodyLayout)
        val previewTextView = dialogView.findViewById<TextView>(R.id.textTaskBodyPreview)
        val btnToggle = dialogView.findViewById<MaterialButton>(R.id.btnTogglePreview)
        val btnHeading = dialogView.findViewById<View>(R.id.btnFormatHeading)
        val btnBold = dialogView.findViewById<View>(R.id.btnFormatBold)
        val btnChecklist = dialogView.findViewById<View>(R.id.btnFormatChecklist)
        val btnBullet = dialogView.findViewById<View>(R.id.btnFormatBullet)

        var isPreviewMode = false

        btnToggle?.setOnClickListener {
            isPreviewMode = !isPreviewMode
            if (isPreviewMode) {
                val text = inputEditText.text?.toString().orEmpty()
                val rendered = MarkdownUtils.renderMarkdown(text)
                previewTextView?.text = if (rendered.isNotBlank()) rendered else "(No description)"
                previewTextView?.visibility = View.VISIBLE
                inputLayout?.visibility = View.GONE
                btnToggle.text = "✏️ Edit"
            } else {
                previewTextView?.visibility = View.GONE
                inputLayout?.visibility = View.VISIBLE
                btnToggle.text = "👁️ Preview"
                inputEditText.requestFocus()
            }
        }

        btnHeading?.setOnClickListener {
            insertFormatting(inputEditText, prefix = "### ", suffix = "", defaultText = "Heading")
        }

        btnBold?.setOnClickListener {
            insertFormatting(inputEditText, prefix = "**", suffix = "**", defaultText = "bold text")
        }

        btnChecklist?.setOnClickListener {
            insertFormatting(inputEditText, prefix = "- [ ] ", suffix = "", defaultText = "Task item")
        }

        btnBullet?.setOnClickListener {
            insertFormatting(inputEditText, prefix = "- ", suffix = "", defaultText = "List item")
        }
    }

    private fun insertFormatting(
        editText: EditText,
        prefix: String,
        suffix: String = "",
        defaultText: String = ""
    ) {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val text = editText.text ?: return

        if (start != end) {
            val selected = text.substring(start, end)
            val replacement = "$prefix$selected$suffix"
            text.replace(start, end, replacement)
            editText.setSelection(start + prefix.length, start + prefix.length + selected.length)
        } else {
            val newLinePrefix = if (start > 0 && text[start - 1] != '\n') "\n" else ""
            val insertion = "$newLinePrefix$prefix$defaultText$suffix"
            text.insert(start, insertion)
            val selectionPos = start + newLinePrefix.length + prefix.length
            editText.setSelection(selectionPos, selectionPos + defaultText.length)
        }
        editText.requestFocus()
    }

    // ── RecyclerView Adapter: Yellow Sticky Notes ─────────────────────────

    inner class StickyNoteAdapter(
        private val items: List<GitHubClient.KanbanCard>
    ) : RecyclerView.Adapter<StickyNoteAdapter.VH>() {

        inner class VH(val binding: ItemStickyNoteBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemStickyNoteBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val card = items[position]
            val b = holder.binding

            b.textNumber.text = "#${card.number}"
            b.textTitle.text = card.title

            // Apply configurable category card background color
            val cardColorHex = when (card.category) {
                "robot-game" -> settings.colorRobotGame
                "innovation-project" -> settings.colorInnovation
                else -> settings.colorGeneral
            }
            runCatching {
                b.cardRoot.setCardBackgroundColor(Color.parseColor(cardColorHex))
            }

            val cleanBody = card.body.replace(Regex("(?i)^(owners?|workers?|core values?):.*?(\\n+|$)", RegexOption.MULTILINE), "").trim()
            val renderedBody = MarkdownUtils.renderMarkdown(cleanBody)
            if (renderedBody.isNotBlank()) {
                b.textBody.text = renderedBody
                b.textBody.visibility = View.VISIBLE
            } else {
                b.textBody.visibility = View.GONE
            }

            // Attach column drag listener to card item so drops anywhere in column (even on top of cards) succeed
            val currentColumnListener = when (card.column) {
                "todo" -> todoDragListener
                "doing" -> doingDragListener
                else -> doneDragListener
            }
            b.cardRoot.setOnDragListener(currentColumnListener)
            holder.itemView.setOnDragListener(currentColumnListener)

            // Drag and Drop OnLongClickListener
            val startDrag: (View) -> Boolean = { v ->
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                v.parent?.requestDisallowInterceptTouchEvent(true)

                val clipData = ClipData.newPlainText("card_number", card.number.toString())
                val shadowBuilder = View.DragShadowBuilder(b.cardRoot)
                val success = b.cardRoot.startDragAndDrop(clipData, shadowBuilder, card, 0)
                if (!success) {
                    @Suppress("DEPRECATION")
                    b.cardRoot.startDrag(clipData, shadowBuilder, card, 0)
                }
                true
            }

            val openEditDialog = View.OnClickListener {
                showEditStickyNoteDialog(card)
            }

            val cardViews = listOf(
                b.cardRoot,
                b.textTitle,
                b.textNumber,
                b.textBody,
                b.dragHandle,
                holder.itemView
            )
            for (v in cardViews) {
                v.isClickable = true
                v.isLongClickable = true
                v.setOnClickListener(openEditDialog)
                v.setOnLongClickListener(startDrag)
            }

            // Render assigned owner badges with GitHub field/label colors
            b.ownerChips.removeAllViews()
            for (owner in card.owners) {
                val hexColor = resolveOwnerColor(owner, card.ownerColors)
                val baseColor = runCatching { Color.parseColor(hexColor) }.getOrDefault(Color.parseColor("#1976D2"))
                val contrastColor = Color.WHITE

                val badge = TextView(this@KanbanActivity).apply {
                    text = owner
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(contrastColor)
                    setPadding(18, 6, 18, 6)

                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(baseColor)
                    }
                    background = shape

                    val params = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 8, 4)
                    }
                    layoutParams = params
                }
                b.ownerChips.addView(badge)
            }

            // Render Core Value badges on task cards
            b.coreValueChips.removeAllViews()
            for (cvName in card.coreValues) {
                val cv = CoreValue.entries.find { it.displayName == cvName || it.displayName.contains(cvName) }
                val hexColor = cv?.hexColor ?: "#F57C00"
                val baseColor = runCatching { Color.parseColor(hexColor) }.getOrDefault(Color.parseColor("#F57C00"))
                val isDark = ColorUtils.calculateLuminance(baseColor) < 0.5
                val contrastColor = if (isDark) Color.WHITE else Color.BLACK

                val badge = TextView(this@KanbanActivity).apply {
                    text = cvName
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(contrastColor)
                    setPadding(14, 4, 14, 4)

                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 14f
                        setColor(baseColor)
                    }
                    background = shape

                    val params = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 8, 4)
                    }
                    layoutParams = params
                }
                b.coreValueChips.addView(badge)
            }

            b.buttonAddOwner.setOnClickListener {
                showOwnerSelectionDialog(card)
            }
            b.buttonAddCoreValue.setOnClickListener {
                showCoreValueSelectionDialog(card)
            }

            // Column move buttons
            when (card.column) {
                "todo" -> {
                    b.buttonMoveLeft.visibility = View.GONE
                    b.buttonMoveRight.text = "➔ Doing"
                    b.buttonMoveRight.visibility = View.VISIBLE
                    b.buttonMoveRight.setOnClickListener { moveCardColumn(card, "doing") }
                }
                "doing" -> {
                    b.buttonMoveLeft.text = "⬅ To-Do"
                    b.buttonMoveLeft.visibility = View.VISIBLE
                    b.buttonMoveLeft.setOnClickListener { moveCardColumn(card, "todo") }

                    b.buttonMoveRight.text = "➔ Done"
                    b.buttonMoveRight.visibility = View.VISIBLE
                    b.buttonMoveRight.setOnClickListener { moveCardColumn(card, "done") }
                }
                "done" -> {
                    b.buttonMoveLeft.text = "⬅ Doing"
                    b.buttonMoveLeft.visibility = View.VISIBLE
                    b.buttonMoveLeft.setOnClickListener { moveCardColumn(card, "doing") }

                    b.buttonMoveRight.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = items.size
    }
}

package com.fll.pushtogithub

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.fll.pushtogithub.databinding.ActivityKanbanBinding
import com.fll.pushtogithub.databinding.ItemStickyNoteBinding
import com.google.android.material.chip.Chip
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

    private var isNavigating = false

    override fun onResume() {
        super.onResume()
        isNavigating = false
        binding.mainNavToggle.check(R.id.navTaskBoard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKanbanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)

        binding.mainNavToggle.check(R.id.navTaskBoard)
        binding.mainNavToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && checkedId == R.id.navTeamCode && !isNavigating) {
                isNavigating = true
                finish()
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
        binding.buttonNewStickyNote.setOnClickListener { showNewStickyNoteDialog() }

        setupDragAndDropListeners()
        setupFilterListeners()

        // 1. Instant 0ms load from local TaskCache
        val cached = TaskCache.get(this)
        if (cached.isNotEmpty()) {
            allCards.clear()
            allCards.addAll(cached)
            populateOwnerFilterChips()
            renderColumns()
        }

        // 2. Background sync with GitHub
        loadCardsFromGitHub()
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

        // 1. Check card label colors
        val cardColor = cardColors[name] ?: cardColors[lower] ?: cardColors["owner:$lower"]
        if (!cardColor.isNullOrBlank() && cardColor != "#1976D2") return cardColor

        // 2. Check repo label / Projects v2 option colors from GitHub
        val repoColor = repoLabelColors[lower] ?: repoLabelColors["owner:$lower"]
        if (!repoColor.isNullOrBlank()) return repoColor

        // 3. Fallback: Exact GitHub Projects v2 field options palette mapping
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

        // 4. Fallback: Deterministic palette based on name hash
        val palette = listOf(
            "#E53935", // Red
            "#F57C00", // Orange
            "#FFB300", // Yellow
            "#43A047", // Green
            "#1E88E5", // Blue
            "#8E24AA", // Purple
            "#D81B60", // Pink
            "#546E7A"  // Slate
        )
        val index = abs(lower.hashCode()) % palette.size
        return palette[index]
    }

    private fun populateOwnerFilterChips() {
        val chipGroup = binding.ownerFilterChipGroup
        chipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text = "All Owners"
            isCheckable = true
            isChecked = selectedOwnerFilter == null
        }
        chipGroup.addView(allChip)

        for (name in settings.teamRoster) {
            val hexColor = resolveOwnerColor(name)

            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = selectedOwnerFilter.equals(name, ignoreCase = true)

                runCatching {
                    val bg = Color.parseColor(hexColor)
                    chipBackgroundColor = ColorStateList.valueOf(bg)
                    val isDark = ColorUtils.calculateLuminance(bg) < 0.5
                    setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                }
            }
            chipGroup.addView(chip)
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                selectedOwnerFilter = null
            } else {
                val chip = group.findViewById<Chip>(checkedIds[0])
                val text = chip?.text?.toString()
                selectedOwnerFilter = if (text == "All Owners" || text.isNullOrBlank()) null else text
            }
            renderColumns()
        }
    }

    private fun setupDragAndDropListeners() {
        val todoListener = createColumnDragListener("todo", "#FFF176", "#FFF9C4")
        val doingListener = createColumnDragListener("doing", "#FFD54F", "#FFE082")
        val doneListener = createColumnDragListener("done", "#A5D6A7", "#C8E6C9")

        binding.columnTodo.setOnDragListener(todoListener)
        binding.recyclerTodo.setOnDragListener(todoListener)

        binding.columnDoing.setOnDragListener(doingListener)
        binding.recyclerDoing.setOnDragListener(doingListener)

        binding.columnDone.setOnDragListener(doneListener)
        binding.recyclerDone.setOnDragListener(doneListener)
    }

    private fun createColumnDragListener(
        columnName: String,
        activeColorHex: String,
        defaultColorHex: String
    ): View.OnDragListener {
        return View.OnDragListener { view, event ->
            val parentColumnView = when (view.id) {
                R.id.columnTodo, R.id.recyclerTodo -> binding.columnTodo
                R.id.columnDoing, R.id.recyclerDoing -> binding.columnDoing
                R.id.columnDone, R.id.recyclerDone -> binding.columnDone
                else -> view
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
                            this,
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

    private fun loadCardsFromGitHub() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (cards, discoveredOwners) = withContext(Dispatchers.IO) {
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

                val resultCards = client.getKanbanCards(settings.teamRoster)
                val allDiscovered = resultCards.flatMap { it.owners }.distinct()
                Pair(resultCards, allDiscovered)
            }

            val currentRoster = settings.teamRoster.toMutableList()
            var rosterChanged = false
            for (ownerName in discoveredOwners) {
                if (ownerName.isNotBlank() && !currentRoster.contains(ownerName)) {
                    currentRoster.add(ownerName)
                    rosterChanged = true
                }
            }
            if (rosterChanged) {
                settings.teamRoster = currentRoster
            }

            allCards.clear()
            allCards.addAll(cards)
            TaskCache.put(this@KanbanActivity, cards)
            populateOwnerFilterChips()
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
            matchesCategory && matchesOwner
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
                ).updateKanbanCard(card.number, newColumn, card.category, card.owners)
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
            Toast.makeText(this, "Add team roster in Setup first to assign owners.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIndexes = roster.map { card.owners.contains(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Assign Owner to #${card.number}")
            .setMultiChoiceItems(roster.toTypedArray(), selectedIndexes) { _, which, isChecked ->
                val name = roster[which]
                if (isChecked) {
                    if (!card.owners.contains(name)) card.owners.add(name)
                } else {
                    card.owners.remove(name)
                }
            }
            .setPositiveButton("Save Owners") { _, _ ->
                renderColumns()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).updateKanbanCard(card.number, card.column, card.category, card.owners)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewStickyNoteDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_new_sticky_note, null
        )

        val inputTitle = dialogView.findViewById<EditText>(R.id.inputTaskTitle)
        val inputBody = dialogView.findViewById<EditText>(R.id.inputTaskBody)
        val categoryGroup = dialogView.findViewById<RadioGroup>(R.id.categoryGroup)
        val columnGroup = dialogView.findViewById<RadioGroup>(R.id.columnGroup)
        val ownerChipsGroup = dialogView.findViewById<ChipGroup>(R.id.ownerChipsGroup)

        for (name in settings.teamRoster) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
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

                binding.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val newCard = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).createKanbanCard(title, body, column, category, selectedOwners)
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
        val categoryGroup = dialogView.findViewById<RadioGroup>(R.id.categoryGroup)
        val columnGroup = dialogView.findViewById<RadioGroup>(R.id.columnGroup)
        val ownerChipsGroup = dialogView.findViewById<ChipGroup>(R.id.ownerChipsGroup)
        val btnDelete = dialogView.findViewById<View>(R.id.buttonDeleteCard)

        inputTitle.setText(card.title)
        val cleanBody = card.body.replace(Regex("(?i)^owner:.*?\\n+"), "").trim()
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
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = card.owners.contains(name)
            }
            ownerChipsGroup.addView(chip)
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

                card.title = newTitle
                card.body = newBody
                card.column = newColumn
                card.category = newCategory
                card.owners.clear()
                card.owners.addAll(selectedOwners)
                renderColumns()

                binding.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        GitHubClient(
                            token = settings.token,
                            owner = settings.owner,
                            repo = settings.repo,
                            branch = settings.branch
                        ).updateKanbanCardFull(card.number, newTitle, newBody, newColumn, newCategory, selectedOwners)
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

            val renderedBody = MarkdownUtils.renderMarkdown(card.body)
            if (renderedBody.isNotBlank()) {
                b.textBody.text = renderedBody
                b.textBody.visibility = View.VISIBLE
            } else {
                b.textBody.visibility = View.GONE
            }

            // Drag and Drop OnLongClickListener
            val startDrag: (View) -> Boolean = { v ->
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                v.parent?.requestDisallowInterceptTouchEvent(true)

                val clipData = ClipData.newPlainText("card_number", card.number.toString())
                val shadowBuilder = View.DragShadowBuilder(b.cardRoot)
                val success = v.startDragAndDrop(clipData, shadowBuilder, card, View.DRAG_FLAG_GLOBAL)
                if (!success) {
                    v.startDragAndDrop(clipData, shadowBuilder, card, 0)
                }
                true
            }

            b.cardRoot.isLongClickable = true
            b.cardRoot.setOnLongClickListener(startDrag)
            b.dragHandle.isLongClickable = true
            b.dragHandle.setOnLongClickListener(startDrag)
            b.textTitle.isLongClickable = true
            b.textTitle.setOnLongClickListener(startDrag)
            b.textNumber.isLongClickable = true
            b.textNumber.setOnLongClickListener(startDrag)
            holder.itemView.isLongClickable = true
            holder.itemView.setOnLongClickListener(startDrag)

            b.cardRoot.setOnClickListener {
                showEditStickyNoteDialog(card)
            }

            // Render assigned owner chips with GitHub field/label colors
            b.ownerChips.removeAllViews()
            for (owner in card.owners) {
                val hexColor = resolveOwnerColor(owner, card.ownerColors)
                val chip = Chip(this@KanbanActivity).apply {
                    text = owner
                    textSize = 11f
                    chipMinHeight = 24f
                    runCatching {
                        val bg = Color.parseColor(hexColor)
                        chipBackgroundColor = ColorStateList.valueOf(bg)
                        val isDark = ColorUtils.calculateLuminance(bg) < 0.5
                        setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                    }
                }
                b.ownerChips.addView(chip)
            }

            b.buttonAddOwner.setOnClickListener {
                showOwnerSelectionDialog(card)
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

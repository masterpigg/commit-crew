package com.fll.pushtogithub

import com.fll.pushtogithub.shared.KanbanCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCacheTest {

    private fun card() = KanbanCard(
        number = 7,
        title = "Tune gyro turn",
        body = "- [ ] test on mat",
        state = "open",
        column = "doing",
        owners = mutableListOf("Fido", "Coach Owl"),
        ownerColors = mutableMapOf("Fido" to "#2563EB"),
        category = "robot-game",
        coreValues = mutableListOf("🤝 Teamwork", "🎉 Fun")
    )

    @Test
    fun roundTripKeepsCachedFields() {
        val decoded = TaskCache.decode(TaskCache.encode(listOf(card())))
        assertEquals(listOf(card()), decoded)
    }

    @Test
    fun oldCacheWithoutNewFieldsUsesDefaults() {
        val decoded = TaskCache.decode("""[{"number":3,"title":"Old","body":"","state":"open"}]""")
        val c = decoded.single()
        assertEquals(3, c.number)
        assertEquals("todo", c.column)
        assertEquals("general", c.category)
        assertTrue(c.owners.isEmpty())
        assertTrue(c.coreValues.isEmpty())
    }

    @Test(expected = Exception::class)
    fun malformedJsonThrows() {
        TaskCache.decode("not json")
    }
}

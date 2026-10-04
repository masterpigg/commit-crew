package io.github.masterpigg.commitcrew.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerColorTest {

    private val palette = setOf(
        "#BF3989", "#2563EB", "#8957E5", "#DA3633",
        "#F59E0B", "#D97706", "#2EA043", "#6E7681"
    )

    @Test
    fun repoLabelColorWinsOverCardColor() {
        val color = resolveOwnerColorHex(
            name = "Fido",
            cardColors = mapOf("Fido" to "#111111"),
            repoLabelColors = mapOf("fido" to "#222222")
        )
        assertEquals("#222222", color)
    }

    @Test
    fun repoLabelColorMatchesOwnerPrefix() {
        assertEquals("#333333", resolveOwnerColorHex("Polly", repoLabelColors = mapOf("owner:polly" to "#333333")))
    }

    @Test
    fun cardColorUsedWhenNoRepoColor() {
        assertEquals("#111111", resolveOwnerColorHex("Fido", cardColors = mapOf("Fido" to "#111111")))
        assertEquals("#111111", resolveOwnerColorHex("Fido", cardColors = mapOf("owner:fido" to "#111111")))
    }

    @Test
    fun defaultBlueCardColorIsIgnored() {
        val color = resolveOwnerColorHex("Whiskers", cardColors = mapOf("Whiskers" to "#1976D2"))
        assertEquals(resolveOwnerColorHex("Whiskers"), color)
    }

    @Test
    fun coachesAreGray() {
        assertEquals("#6E7681", resolveOwnerColorHex("Coach Owl"))
        assertEquals("#6E7681", resolveOwnerColorHex("  coach owl "))
    }

    @Test
    fun fallbackIsStableAndFromPalette() {
        for (name in listOf("Fido", "Whiskers", "Polly", "Bubbles", "Nibbles", "Thumper", "Patches")) {
            val color = resolveOwnerColorHex(name)
            assertTrue(color in palette, "$name got $color")
            assertEquals(color, resolveOwnerColorHex(name.uppercase()))
        }
    }
}

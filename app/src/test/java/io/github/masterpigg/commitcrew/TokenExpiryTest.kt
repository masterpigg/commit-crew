package io.github.masterpigg.commitcrew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TokenExpiryTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val chicago = TimeZone.getTimeZone("America/Chicago")

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    // 2026-12-31 00:00:00 UTC
    private val expiresAt = 1798675200000L

    @Test
    fun parsesTheFormatsGitHubSends() {
        assertEquals(expiresAt, TokenExpiry.parse("2026-12-31 00:00:00 UTC"))
        assertEquals(expiresAt, TokenExpiry.parse("2026-12-31 00:00:00 +0000"))
        assertEquals(expiresAt, TokenExpiry.parse("2026-12-30 17:00:00 -0700"))
        assertEquals(expiresAt, TokenExpiry.parse("  2026-12-31 00:00:00 UTC "))
    }

    @Test
    fun missingOrGarbledHeaderIsUnknown() {
        assertNull(TokenExpiry.parse(null))
        assertNull(TokenExpiry.parse(""))
        assertNull(TokenExpiry.parse("next Tuesday"))
        assertNull(TokenExpiry.parse("2026-13-45 00:00:00 UTC"))
    }

    @Test
    fun countsCalendarDaysInTheTabletsTimeZone() {
        assertEquals(30, TokenExpiry.daysLeft(expiresAt, expiresAt - 30 * day, utc))
        assertEquals(0, TokenExpiry.daysLeft(expiresAt, expiresAt + hour, utc))
        assertEquals(-2, TokenExpiry.daysLeft(expiresAt, expiresAt + 2 * day, utc))
        // Midnight UTC on Dec 31 is still Dec 30 in Chicago, so one hour earlier
        // there it expires "today", not "tomorrow".
        assertEquals(0, TokenExpiry.daysLeft(expiresAt, expiresAt - hour, chicago))
        assertEquals(1, TokenExpiry.daysLeft(expiresAt, expiresAt - hour, utc))
    }

    @Test
    fun noWarningWhileMoreThanTwoWeeksAreLeft() {
        assertNull(TokenExpiry.warning(expiresAt, expiresAt - 15 * day, utc))
        assertTrue(TokenExpiry.warning(expiresAt, expiresAt - 14 * day, utc)!!.contains("in 14 days"))
    }

    @Test
    fun warningWordingCountsDown() {
        assertEquals(
            "⚠️ The team's GitHub token expires in 3 days (Dec 31, 2026). " +
                "Coach: make a new token and enter it in Setup ⚙️.",
            TokenExpiry.warning(expiresAt, expiresAt - 3 * day, utc)
        )
        assertTrue(TokenExpiry.warning(expiresAt, expiresAt - day, utc)!!.contains("expires tomorrow (Dec 31, 2026)"))
        assertTrue(TokenExpiry.warning(expiresAt, expiresAt - hour, chicago)!!.contains("expires today"))
    }

    @Test
    fun expiredTokenSaysSavingWillNotWork() {
        assertFalse(TokenExpiry.isExpired(expiresAt, expiresAt - 1))
        assertTrue(TokenExpiry.isExpired(expiresAt, expiresAt))
        val warning = TokenExpiry.warning(expiresAt, expiresAt + 5 * day, utc)!!
        assertTrue(warning.contains("expired on Dec 31, 2026"))
        assertTrue(warning.contains("won't work"))
    }

    @Test
    fun describeCoversEveryCase() {
        assertEquals("This token has no expiration date.", TokenExpiry.describe(null, expiresAt, utc))
        assertEquals(
            "Token expires on Dec 31, 2026 (60 days from now).",
            TokenExpiry.describe(expiresAt, expiresAt - 60 * day, utc)
        )
        assertTrue(TokenExpiry.describe(expiresAt, expiresAt - 2 * day, utc).startsWith("⚠️"))
    }
}

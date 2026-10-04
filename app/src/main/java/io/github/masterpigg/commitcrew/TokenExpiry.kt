package io.github.masterpigg.commitcrew

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the expiration date GitHub reports for the team token and turns it into
 * a coach-friendly warning.
 *
 * GitHub sends a `github-authentication-token-expiration` header on every API
 * response made with a token that has an expiration date, e.g.
 * `2026-12-31 00:00:00 UTC`. Tokens without an expiration date get no header.
 */
object TokenExpiry {

    const val HEADER = "github-authentication-token-expiration"

    /** Start warning this many days before the token stops working. */
    const val WARN_DAYS = 14

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    // GitHub has sent both "UTC" and numeric offsets ("+0000", "-0700") here.
    private val HEADER_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss z", "yyyy-MM-dd HH:mm:ss Z")

    /** Parse the header value into epoch millis, or null if it is missing or unreadable. */
    fun parse(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (pattern in HEADER_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            try {
                return format.parse(text)?.time
            } catch (_: ParseException) {
                // Try the next pattern.
            }
        }
        return null
    }

    /**
     * Calendar days from today until the expiration day in [timeZone]:
     * 0 means it expires today, negative means the day has already passed.
     */
    fun daysLeft(expiresAt: Long, now: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
        localDay(expiresAt, timeZone) - localDay(now, timeZone)

    /** True once the expiration moment has passed. */
    fun isExpired(expiresAt: Long, now: Long): Boolean = now >= expiresAt

    /**
     * A warning to show on the Task Board and in Setup, or null while the token
     * still has more than [WARN_DAYS] days left.
     */
    fun warning(expiresAt: Long, now: Long, timeZone: TimeZone = TimeZone.getDefault()): String? {
        val date = formatDate(expiresAt, timeZone)
        if (isExpired(expiresAt, now)) {
            return "⚠️ The team's GitHub token expired on $date. Saving to GitHub won't work " +
                "until a coach makes a new token and enters it in Setup ⚙️."
        }
        val days = daysLeft(expiresAt, now, timeZone)
        val whenText = when {
            days > WARN_DAYS -> return null
            days <= 0L -> "today"
            days == 1L -> "tomorrow ($date)"
            else -> "in $days days ($date)"
        }
        return "⚠️ The team's GitHub token expires $whenText. " +
            "Coach: make a new token and enter it in Setup ⚙️."
    }

    /** One line for the Setup screen after a successful connection test. */
    fun describe(expiresAt: Long?, now: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (expiresAt == null) return "This token has no expiration date."
        warning(expiresAt, now, timeZone)?.let { return it }
        val days = daysLeft(expiresAt, now, timeZone)
        return "Token expires on ${formatDate(expiresAt, timeZone)} ($days days from now)."
    }

    fun formatDate(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("MMM d, yyyy", Locale.US).apply { this.timeZone = timeZone }.format(millis)

    private fun localDay(millis: Long, timeZone: TimeZone): Long =
        Math.floorDiv(millis + timeZone.getOffset(millis), DAY_MILLIS)
}

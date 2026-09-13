package com.fll.pushtogithub

import android.os.Build
import android.text.Html
import android.text.Spanned
import android.text.SpannedString

/**
 * Utility for formatting and rendering text for kids in the app.
 *
 * Renders Markdown headers (#), checklists (☐ / ☑), bold, italic,
 * and lists in crisp, high-contrast, bold styling.
 */
object MarkdownUtils {

    /**
     * Clean raw Markdown or null strings into styled Spanned text for UI display.
     */
    fun renderMarkdown(text: String?): Spanned {
        if (text == null || text.isBlank() || text.trim().equals("null", ignoreCase = true)) {
            return SpannedString("")
        }

        var md = text.trim()
            .replace(Regex("(?i)^null\\s*"), "")

        // Convert Markdown checklists [ ] and [x]
        md = md.replace(Regex("(?m)^\\s*[-*]\\s*\\[\\s*\\]\\s*"), "<b>☐</b> ")
        md = md.replace(Regex("(?m)^\\s*[-*]\\s*\\[[xX]\\]\\s*"), "<b>☑</b> ")

        // Convert Markdown headers #, ##, ###
        md = md.replace(Regex("(?m)^#\\s*(.*?)$"), "<b><font size=\"5\" color=\"#000000\">$1</font></b><br/>")
        md = md.replace(Regex("(?m)^##\\s*(.*?)$"), "<b><font size=\"4\" color=\"#000000\">$1</font></b><br/>")
        md = md.replace(Regex("(?m)^###+\\s*(.*?)$"), "<b><font color=\"#000000\">$1</font></b><br/>")

        // Convert bullet lists
        md = md.replace(Regex("(?m)^\\s*[*\\-+]\t*"), "• ")

        // Convert bold & italic
        md = md.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        md = md.replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
        md = md.replace(Regex("_(.*?)_"), "<i>$1</i>")

        // Convert newlines to HTML breaks
        md = md.replace("\n", "<br/>")

        return if (Build.VERSION.SDK_INT >= 24) {
            Html.fromHtml("<font color=\"#111111\">$md</font>", Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml("<font color=\"#111111\">$md</font>")
        }
    }

    /** Clean text into plain string. */
    fun cleanText(text: String?): String {
        return renderMarkdown(text).toString().trim()
    }
}

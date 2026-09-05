package com.fll.pushtogithub

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A .llsp3 SPIKE project is a ZIP archive containing a manifest, a preview
 * image, and an embedded Scratch 3 project (project.json inside an .sb3, or a
 * top-level scratch.sb3 / project.json depending on version).
 *
 * This extractor pulls out the readable pieces so the repo gets human-diffable
 * files alongside the raw binary .llsp3. Diffs on project.json let mentors and
 * judges see what actually changed between sessions.
 */
object LlspExtractor {

    /** One extracted file: repo-relative sub-path (under the robot folder) + bytes. */
    data class Extracted(val subPath: String, val bytes: ByteArray)

    /**
     * Extract readable/interesting entries from a .llsp3 byte array.
     *
     * Returns an empty list if the bytes are not a valid ZIP (in which case the
     * caller should still commit the raw file). Never throws for bad input.
     */
    fun extract(llspBytes: ByteArray): List<Extracted> {
        val results = mutableListOf<Extracted>()
        try {
            ZipInputStream(ByteArrayInputStream(llspBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val bytes = zip.readBytes()
                        when {
                            // The Scratch program itself is the most valuable diff.
                            name.endsWith("project.json", ignoreCase = true) ->
                                results += Extracted("extracted/project.json", prettyJsonOrRaw(bytes))

                            // Project manifest (name, type, hardware, etc.).
                            name.equals("manifest.json", ignoreCase = true) ||
                                name.endsWith("/manifest.json", ignoreCase = true) ->
                                results += Extracted("extracted/manifest.json", prettyJsonOrRaw(bytes))

                            // Embedded .sb3 is itself a ZIP; dig one level for its project.json.
                            name.endsWith(".sb3", ignoreCase = true) ->
                                extractProjectJsonFromSb3(bytes)?.let {
                                    results += Extracted("extracted/project.json", it)
                                }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            // Not a valid zip or unreadable; caller commits the raw .llsp3 anyway.
            return emptyList()
        }
        // De-duplicate by subPath, preferring the first (project.json found directly
        // wins over one dug out of an .sb3 if both somehow appear).
        return results.distinctBy { it.subPath }
    }

    private fun extractProjectJsonFromSb3(sb3Bytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(sb3Bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("project.json", true)) {
                        return prettyJsonOrRaw(zip.readBytes())
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pretty-print JSON with a stable 2-space indent so diffs are readable and
     * meaningful line-by-line. Falls back to the raw bytes if parsing fails.
     */
    private fun prettyJsonOrRaw(bytes: ByteArray): ByteArray {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            val pretty = JSONObject(text).toString(2)
            pretty.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            bytes
        }
    }
}

package com.fll.pushtogithub

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A .llsp3 SPIKE project is a ZIP archive containing a manifest, preview
 * images, and an embedded Scratch 3 project (either as a top-level
 * project.json or inside a nested .sb3 ZIP).
 *
 * This extractor pulls out ALL files from the archive so the repo gets a
 * complete, human-readable mirror of the project alongside the raw binary
 * .llsp3. Zipping up the extracted folder should produce a functionally
 * equivalent .llsp3 (minus compression differences).
 *
 * JSON files are pretty-printed with 2-space indent for readable diffs.
 * Nested .sb3 archives are recursively extracted.
 */
object LlspExtractor {

    /** One extracted file: repo-relative sub-path (under the robot folder) + bytes. */
    data class Extracted(val subPath: String, val bytes: ByteArray)

    /**
     * Extract all contents from a .llsp3 byte array.
     *
     * Returns an empty list if the bytes are not a valid ZIP (in which case the
     * caller should still commit the raw file). Never throws for bad input.
     */
    fun extract(llspBytes: ByteArray): List<Extracted> {
        val results = mutableListOf<Extracted>()
        try {
            extractZip(llspBytes, "extracted", results)
        } catch (e: Exception) {
            // Not a valid zip or unreadable; caller commits the raw .llsp3 anyway.
            return emptyList()
        }
        // De-duplicate by subPath, preferring the first occurrence.
        return results.distinctBy { it.subPath }
    }

    /**
     * Recursively extract all entries from a ZIP byte array.
     *
     * @param zipBytes The raw ZIP bytes to extract.
     * @param pathPrefix The prefix to prepend to each entry's path (e.g. "extracted"
     *                   for the top-level, or "extracted/scratch" for a nested .sb3).
     * @param results Accumulator for extracted files.
     */
    private fun extractZip(
        zipBytes: ByteArray,
        pathPrefix: String,
        results: MutableList<Extracted>
    ) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name
                    val bytes = zip.readBytes()
                    val outputPath = "$pathPrefix/$entryName"

                    when {
                        // Nested .sb3 archives — recursively extract for their project.json
                        entryName.endsWith(".sb3", ignoreCase = true) -> {
                            // Store the raw .sb3 as well
                            results += Extracted(outputPath, bytes)
                            // Recursively extract the .sb3 contents
                            val sb3Prefix = "$pathPrefix/${stripExtension(entryName)}"
                            runCatching { extractZip(bytes, sb3Prefix, results) }
                        }
                        // JSON files — pretty-print for readable diffs
                        entryName.endsWith(".json", ignoreCase = true) -> {
                            results += Extracted(outputPath, prettyPrintJson(bytes))
                        }
                        // All other files (images, SVGs, etc.) — pass through as-is
                        else -> {
                            results += Extracted(outputPath, bytes)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Pretty-print JSON with a stable 2-space indent so diffs are readable and
     * meaningful line-by-line. Falls back to the raw bytes if parsing fails.
     *
     * Handles both JSON objects and JSON arrays.
     */
    private fun prettyPrintJson(bytes: ByteArray): ByteArray {
        return try {
            val text = String(bytes, Charsets.UTF_8).trim()
            val pretty = when {
                text.startsWith("{") -> JSONObject(text).toString(2)
                text.startsWith("[") -> JSONArray(text).toString(2)
                else -> return bytes // Not recognizable JSON
            }
            pretty.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            bytes
        }
    }

    /** Strip the file extension from a filename (e.g. "scratch.sb3" → "scratch"). */
    private fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}

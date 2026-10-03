package com.fll.pushtogithub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LlspExtractorTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun extractsFilesAndPrettyPrintsJson() {
        val png = byteArrayOf(1, 2, 3)
        val result = LlspExtractor.extract(
            zip("manifest.json" to """{"name":"Run 1","type":"word-blocks"}""".toByteArray(), "icon.png" to png)
        ).associate { it.subPath to it.bytes }

        val manifest = String(result.getValue("extracted/manifest.json"))
        assertTrue(manifest.contains("\n  \"name\": \"Run 1\""))
        assertEquals("word-blocks", JSONObject(manifest).getString("type"))
        assertArrayEquals(png, result.getValue("extracted/icon.png"))
    }

    @Test
    fun extractsNestedSb3() {
        val sb3 = zip("project.json" to """{"targets":[]}""".toByteArray())
        val paths = LlspExtractor.extract(zip("scratch.sb3" to sb3)).map { it.subPath }

        assertTrue("extracted/scratch.sb3" in paths)
        assertTrue("extracted/scratch/project.json" in paths)
    }

    @Test
    fun invalidJsonIsKeptAsIs() {
        val bad = "{not json".toByteArray()
        val result = LlspExtractor.extract(zip("broken.json" to bad)).single()
        assertArrayEquals(bad, result.bytes)
    }

    @Test
    fun notAZipReturnsEmpty() {
        assertTrue(LlspExtractor.extract("hello".toByteArray()).isEmpty())
    }
}

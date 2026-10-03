package com.fll.pushtogithub.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadmeTemplatesTest {

    @Test
    fun newRootReadmeLinksEveryTeamMember() {
        val readme = ReadmeTemplates.getRootReadme("octo", "team-repo", listOf("Fido", "Coach Owl"))
        assertTrue("* [**Fido**](https://github.com/octo/team-repo/issues?q=is%3Aissue+owner%3AFido)" in readme)
        assertTrue("[**Coach Owl**]" in readme)
        assertTrue("## 📂 Team Sections & Folder Structure" in readme)
    }

    @Test
    fun existingRootReadmeKeepsNotesAndReplacesRoster() {
        val existing = "# Our Team\n\nKid notes here.\n\n## 👥 Team Roster\n* [**Old**](x)\n\n## Next\nMore notes.\n"
        val readme = ReadmeTemplates.getRootReadme("octo", "team-repo", listOf("Polly"), existing)
        assertTrue(readme.startsWith("# Our Team\n\nKid notes here."))
        assertTrue("[**Polly**]" in readme)
        assertFalse("[**Old**]" in readme)
        assertTrue(readme.contains("## Next\nMore notes."))
    }

    @Test
    fun existingReadmeWithoutRosterIsRegenerated() {
        val readme = ReadmeTemplates.getRootReadme("octo", "team-repo", listOf("Polly"), "just some text")
        assertEquals(ReadmeTemplates.getRootReadme("octo", "team-repo", listOf("Polly")), readme)
    }
}

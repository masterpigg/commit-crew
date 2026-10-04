package io.github.masterpigg.commitcrew

import io.github.masterpigg.commitcrew.shared.CommitInfo
import io.github.masterpigg.commitcrew.shared.ProjectFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Runs [GitHubClient] against canned GitHub API responses served by a local
 * MockWebServer, so parsing and request-building regressions fail in CI.
 */
class GitHubClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubClient(
            token = "test-token",
            owner = "fll-team",
            repo = "season repo",
            branch = "main",
            apiBase = server.url("").toString().removeSuffix("/")
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(code: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(1, TimeUnit.SECONDS) ?: error("expected another request")

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // --- testConnection ---------------------------------------------------

    @Test
    fun testConnectionSucceedsAndSendsAuthHeaders() {
        respond(200, "{}")
        assertNull(client.testConnection())

        val req = takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/repos/fll-team/season%20repo", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertEquals("2022-11-28", req.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun testConnectionExplainsCommonFailures() {
        respond(401)
        respond(404)
        respond(500)
        assertTrue(client.testConnection()!!.contains("401"))
        assertTrue(client.testConnection()!!.contains("404"))
        assertTrue(client.testConnection()!!.contains("500"))
    }

    @Test
    fun recordsTokenExpirationFromResponseHeader() {
        assertNull(client.tokenExpiresAt)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("{}")
                .addHeader("github-authentication-token-expiration", "2026-12-31 00:00:00 UTC")
        )
        respond(200, "{}") // later response without the header keeps the known date
        assertNull(client.testConnection())
        assertEquals(1798675200000L, client.tokenExpiresAt)
        assertNull(client.testConnection())
        assertEquals(1798675200000L, client.tokenExpiresAt)
    }

    @Test
    fun tokenWithoutExpirationLeavesItUnknown() {
        respond(200, "{}")
        assertNull(client.testConnection())
        assertNull(client.tokenExpiresAt)
    }

    // --- folders, history and file contents --------------------------------

    @Test
    fun listFoldersKeepsOnlyDirectoriesSortedByName() {
        respond(
            200,
            """[
              {"type":"dir","name":"run 2","path":"robot-game/run 2","sha":"b"},
              {"type":"file","name":"README.md","path":"robot-game/README.md","sha":"x"},
              {"type":"dir","name":"Gyro Test","path":"robot-game/Gyro Test","sha":"a"}
            ]"""
        )

        val folders = client.listFolders("robot-game")

        assertEquals(
            listOf(
                ProjectFolder("Gyro Test", "robot-game/Gyro Test", sha = "a"),
                ProjectFolder("run 2", "robot-game/run 2", sha = "b")
            ),
            folders
        )
        assertEquals("/repos/fll-team/season%20repo/contents/robot-game?ref=main", takeRequest().path)
    }

    @Test
    fun listFoldersEncodesEachPathSegment() {
        respond(200, "[]")
        client.listFolders("robot-game/Run 1")
        assertEquals("/repos/fll-team/season%20repo/contents/robot-game/Run%201?ref=main", takeRequest().path)
    }

    @Test
    fun listFoldersReturnsEmptyOnErrorOrBadJson() {
        respond(404, """{"message":"Not Found"}""")
        respond(200, "not json")
        assertTrue(client.listFolders("robot-game").isEmpty())
        assertTrue(client.listFolders("robot-game").isEmpty())
    }

    @Test
    fun getFileHistoryParsesCommits() {
        respond(
            200,
            """[
              {"sha":"abc123","commit":{"message":"Fido: faster turn","committer":{"name":"Tablet 1","date":"2026-09-30T18:00:00Z"}}},
              {"sha":"def456","commit":{"message":"First save"}}
            ]"""
        )

        val history = client.getFileHistory("robot-game/Run 1/Run 1.llsp3", perPage = 5)

        assertEquals(
            listOf(
                CommitInfo("abc123", "Fido: faster turn", "2026-09-30T18:00:00Z", "Tablet 1"),
                CommitInfo("def456", "First save", "", "")
            ),
            history
        )
        assertEquals(
            "/repos/fll-team/season%20repo/commits?path=robot-game%2FRun%201%2FRun%201.llsp3&sha=main&per_page=5",
            takeRequest().path
        )
    }

    @Test
    fun getFileAtCommitDecodesWrappedBase64() {
        val text = "x".repeat(100)
        val wrapped = b64(text).chunked(60).joinToString("\n")
        respond(200, JSONObject().put("content", wrapped).toString())

        val bytes = client.getFileAtCommit("notes.md", "abc123")

        assertEquals(text, String(bytes!!, Charsets.UTF_8))
        assertEquals("/repos/fll-team/season%20repo/contents/notes.md?ref=abc123", takeRequest().path)
    }

    @Test
    fun getFileAtCommitReturnsNullWhenMissingOrEmpty() {
        respond(404)
        respond(200, """{"content":""}""")
        assertNull(client.getFileAtCommit("missing.md", "main"))
        assertNull(client.getFileAtCommit("empty.md", "main"))
    }

    @Test
    fun getLatestCommitShaReadsFirstEntry() {
        respond(200, """[{"sha":"newest"},{"sha":"older"}]""")
        respond(200, "[]")
        assertEquals("newest", client.getLatestCommitSha("a.llsp3"))
        assertNull(client.getLatestCommitSha("a.llsp3"))
    }

    @Test
    fun getArchivedProjectNamesReadsJsonList() {
        respond(200, JSONObject().put("content", b64("""["Old Run","Gyro Test"]""")).toString())
        assertEquals(listOf("Old Run", "Gyro Test"), client.getArchivedProjectNames("robot-game"))
        assertEquals(
            "/repos/fll-team/season%20repo/contents/robot-game/.archived_projects.json?ref=main",
            takeRequest().path
        )
    }

    @Test
    fun listSavedDocFilesSkipsReadmesAndHiddenFiles() {
        respond(
            200,
            """[
              {"type":"file","name":"2026-09-01 kickoff.md","path":"meeting-notes/2026-09-01 kickoff.md","html_url":"https://example/1"},
              {"type":"file","name":"README.md","path":"meeting-notes/README.md"},
              {"type":"file","name":".keep","path":"meeting-notes/.keep"},
              {"type":"dir","name":"photos","path":"meeting-notes/photos"}
            ]"""
        )
        respond(404)
        respond(200, """[{"type":"file","name":"arm.png","path":"robot-game/design/arm.png","html_url":"https://example/2"}]""")

        val docs = client.listSavedDocFiles()

        assertEquals(
            listOf(
                GitHubClient.DocFile("arm.png", "robot-game/design/arm.png", "robot-game/design", "https://example/2"),
                GitHubClient.DocFile(
                    "2026-09-01 kickoff.md", "meeting-notes/2026-09-01 kickoff.md", "meeting-notes", "https://example/1"
                )
            ),
            docs
        )
    }

    // --- writing files -----------------------------------------------------

    @Test
    fun putFileCreatesNewFileWithoutSha() {
        respond(404)
        respond(201, """{"content":{"html_url":"https://github.com/fll-team/x/blob/main/a.txt"}}""")

        val result = client.putFile("notes/a b.txt", "hello".toByteArray(), "Save notes")

        assertEquals("notes/a b.txt", result.path)
        assertEquals("https://github.com/fll-team/x/blob/main/a.txt", result.htmlUrl)
        assertEquals("GET", takeRequest().method)
        val put = takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/repos/fll-team/season%20repo/contents/notes/a%20b.txt", put.path)
        val body = JSONObject(put.body.readUtf8())
        assertEquals("Save notes", body.getString("message"))
        assertEquals(b64("hello"), body.getString("content"))
        assertEquals("main", body.getString("branch"))
        assertFalse(body.has("sha"))
    }

    @Test
    fun putFileUpdatesExistingFileWithItsSha() {
        respond(200, """{"sha":"oldsha"}""")
        respond(200, """{"content":{}}""")

        client.putFile("a.txt", "v2".toByteArray(), "Update")

        takeRequest()
        val body = JSONObject(takeRequest().body.readUtf8())
        assertEquals("oldsha", body.getString("sha"))
    }

    @Test
    fun putFileThrowsReadableErrorOnConflict() {
        respond(404)
        respond(409, """{"message":"is at abc but expected def"}""")

        try {
            client.putFile("a.txt", "x".toByteArray(), "Save")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("Conflict."))
            assertTrue(e.message!!.contains("is at abc but expected def"))
        }
    }

    @Test
    fun deleteFileSkipsDeleteWhenFileIsAlreadyGone() {
        respond(404)
        assertTrue(client.deleteFile("gone.txt", "Remove"))
        takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteFileSendsShaOfExistingFile() {
        respond(200, """{"sha":"abc"}""")
        respond(200, "{}")

        assertTrue(client.deleteFile("old.txt", "Remove old"))

        takeRequest()
        val delete = takeRequest()
        assertEquals("DELETE", delete.method)
        val body = JSONObject(delete.body.readUtf8())
        assertEquals("abc", body.getString("sha"))
        assertEquals("Remove old", body.getString("message"))
    }

    @Test
    fun archiveProjectAppendsNameOnce() {
        respond(200, JSONObject().put("content", b64("""["Old Run"]""")).toString())
        respond(200, """{"sha":"s1"}""")
        respond(200, "{}")

        assertTrue(client.archiveProject("robot-game", "Gyro Test"))

        takeRequest()
        takeRequest()
        val put = JSONObject(takeRequest().body.readUtf8())
        val saved = String(Base64.getDecoder().decode(put.getString("content")), Charsets.UTF_8)
        assertEquals(listOf("Old Run", "Gyro Test"), JSONArray(saved).let { a -> List(a.length()) { a.getString(it) } })
        assertEquals("Archive project: Gyro Test", put.getString("message"))
    }

    // --- Kanban cards (issues) ---------------------------------------------

    @Test
    fun getKanbanCardsParsesOwnersCoreValuesColumnsAndCategories() {
        respond(
            200,
            """[
              {"number":1,"title":"Fix arm","body":"","state":"closed","labels":[]},
              {"number":2,"title":"Linked PR","body":"","state":"open","labels":[],"pull_request":{}},
              {"number":3,"title":"Tune gyro turn","body":"Workers: Polly, Bubbles\n\nCheck the mat","state":"open",
               "labels":[{"name":"doing","color":"E65100"},{"name":"Fido","color":"2563EB"},
                         {"name":"owner:Coach Owl","color":"6E7681"},{"name":"core-value:teamwork","color":"C2185B"},
                         {"name":"robot-game","color":""}],
               "assignees":[{"login":"octo-kid"}]},
              {"number":4,"title":"Call an expert","body":"Core Values: 🎉 Fun","state":"open",
               "labels":[{"name":"research","color":"ffffff"}]}
            ]"""
        )

        val cards = client.getKanbanCards(teamRoster = listOf("Fido", "Whiskers"))

        assertEquals(listOf(4, 3, 1), cards.map { it.number })

        val gyro = cards.first { it.number == 3 }
        assertEquals("doing", gyro.column)
        assertEquals("robot-game", gyro.category)
        assertEquals(listOf("Fido", "Coach Owl", "octo-kid", "Polly", "Bubbles"), gyro.owners)
        assertEquals(mapOf("Fido" to "#2563EB", "Coach Owl" to "#6E7681"), gyro.ownerColors)
        assertEquals(listOf("🤝 Teamwork"), gyro.coreValues)

        val expert = cards.first { it.number == 4 }
        assertEquals("todo", expert.column)
        assertEquals("innovation-project", expert.category)
        assertEquals(listOf("🎉 Fun"), expert.coreValues)

        val arm = cards.first { it.number == 1 }
        assertEquals("done", arm.column)
        assertEquals("general", arm.category)

        assertEquals("/repos/fll-team/season%20repo/issues?state=all&per_page=100", takeRequest().path)
    }

    @Test
    fun getKanbanCardsReturnsEmptyOnError() {
        respond(403, """{"message":"rate limited"}""")
        assertTrue(client.getKanbanCards().isEmpty())
    }

    @Test
    fun createKanbanCardSendsLabelsAndHeaderLines() {
        respond(201, """{"number":12,"title":"Build attachment","body":"ignored","state":"open"}""")

        val card = client.createKanbanCard(
            title = "Build attachment",
            body = "Use the long beam",
            column = "ToDo",
            category = "robot-game",
            owners = listOf("Fido", "Nibbles"),
            coreValues = listOf("🤝 Teamwork")
        )!!

        assertEquals(12, card.number)
        assertEquals("todo", card.column)
        assertEquals(listOf("Fido", "Nibbles"), card.owners)

        val req = takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/fll-team/season%20repo/issues", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Build attachment", body.getString("title"))
        assertEquals(
            "Core Values: 🤝 Teamwork\nWorkers: Fido, Nibbles\n\nUse the long beam",
            body.getString("body")
        )
        val labels = body.getJSONArray("labels")
        assertEquals(
            listOf("todo", "robot-game", "Fido", "Nibbles", "core-value:teamwork"),
            List(labels.length()) { labels.getString(it) }
        )
    }

    @Test
    fun createKanbanCardReturnsNullOnFailure() {
        respond(422, """{"message":"Validation Failed"}""")
        assertNull(client.createKanbanCard("t", "b", "todo", "general", emptyList()))
    }

    @Test
    fun updateKanbanCardFullClosesDoneCardsAndReplacesHeader() {
        respond(200, "{}")

        val ok = client.updateKanbanCardFull(
            number = 7,
            title = "Tune gyro turn",
            body = "Workers: Fido\n\nCheck the mat",
            column = "done",
            category = "",
            owners = listOf("Thumper")
        )

        assertTrue(ok)
        val req = takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/repos/fll-team/season%20repo/issues/7", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("closed", body.getString("state"))
        assertEquals("Workers: Thumper\n\nCheck the mat", body.getString("body"))
        val labels = body.getJSONArray("labels")
        assertEquals(listOf("done", "Thumper"), List(labels.length()) { labels.getString(it) })
    }

    @Test
    fun updateKanbanCardKeepsTitleAndBodyUntouched() {
        respond(200, "{}")

        assertTrue(client.updateKanbanCard(5, "doing", "general", emptyList()))

        val body = JSONObject(takeRequest().body.readUtf8())
        assertFalse(body.has("title"))
        assertFalse(body.has("body"))
        assertEquals("open", body.getString("state"))
    }

    @Test
    fun deleteKanbanCardClosesIssue() {
        respond(200, "{}")
        respond(404)
        assertTrue(client.deleteKanbanCard(9))
        assertFalse(client.deleteKanbanCard(9))
        val body = JSONObject(takeRequest().body.readUtf8())
        assertEquals("closed", body.getString("state"))
    }

    // --- labels and Projects v2 colors -------------------------------------

    @Test
    fun getRepoLabelColorsMergesLabelsWithProjectColors() {
        respond(
            200,
            """[
              {"name":"owner:Patches","color":"112233"},
              {"name":"Fido","color":"445566"},
              {"name":"no-color","color":""}
            ]"""
        )
        respond(
            200,
            """{"data":{
              "repository":{"projectsV2":{"nodes":[{"fields":{"nodes":[
                {"name":"Status","options":[{"name":"Fido","color":"PURPLE"}]},
                {}
              ]}}]}},
              "user":{"projectsV2":{"nodes":[{"fields":{"nodes":[
                {"name":"Workers","multiSelectOptions":[{"name":"Polly","color":"GREEN"},{"name":"Odd","color":"TEAL"}]}
              ]}}]}},
              "organization":null
            }}"""
        )

        val colors = client.getRepoLabelColors()

        assertEquals("#112233", colors["owner:patches"])
        assertEquals("#112233", colors["patches"])
        assertEquals("#8957E5", colors["fido"]) // Projects v2 color wins over the label
        assertEquals("#8957E5", colors["owner:fido"])
        assertEquals("#2EA043", colors["polly"])
        assertEquals("#6E7681", colors["odd"])
        assertFalse(colors.containsKey("no-color"))

        assertEquals("/repos/fll-team/season%20repo/labels?per_page=100", takeRequest().path)
        val graphql = takeRequest()
        assertEquals("/graphql", graphql.path)
        val variables = JSONObject(graphql.body.readUtf8()).getJSONObject("variables")
        assertEquals("fll-team", variables.getString("owner"))
        assertEquals("season repo", variables.getString("repo"))
    }

    @Test
    fun getProjectV2OptionColorsIsEmptyWhenGraphQLFails() {
        respond(502)
        assertTrue(client.getProjectV2OptionColors().isEmpty())
    }

    @Test
    fun ensureLabelExistsOnlyCreatesMissingLabels() {
        respond(200, "{}")
        respond(404)
        respond(201, "{}")

        client.ensureLabelExists("todo", "F57F17", "Kanban Column: To-Do")
        client.ensureLabelExists("core-value:fun", "FBC02D", "FLL Core Value: 🎉 Fun")

        assertEquals("/repos/fll-team/season%20repo/labels/todo", takeRequest().path)
        assertEquals("/repos/fll-team/season%20repo/labels/core-value%3Afun", takeRequest().path)
        val create = takeRequest()
        assertEquals("POST", create.method)
        val body = JSONObject(create.body.readUtf8())
        assertEquals("core-value:fun", body.getString("name"))
        assertEquals("FBC02D", body.getString("color"))
        assertEquals(3, server.requestCount)
    }
}

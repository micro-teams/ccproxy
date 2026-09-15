package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DumpTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `disabled when dumpDir is blank`() {
        val dump = Dump("", mapper)
        assertFalse(dump.enabled)
    }

    @Test
    fun `writes one HAR-shaped NDJSON line per exchange, appending on repeat writes`() {
        val dir = Files.createTempDirectory("dump-test").toFile()
        try {
            val dump = Dump(dir.absolutePath, mapper)
            assertTrue(dump.enabled)

            dump.writeAsync(
                machine = "m1",
                method = "POST",
                path = "/v1/messages",
                host = "api.anthropic.com",
                reqHeaders =
                    linkedMapOf(
                        "Authorization" to "Bearer fake-token",
                        "Content-Type" to "application/json",
                    ),
                reqBody = """{"model":"claude-sonnet-5"}""".toByteArray(),
                statusLine = "HTTP/1.1 200 OK",
                respHeaders = linkedMapOf("anthropic-ratelimit-unified-7d-utilization" to "0.42"),
                respBody = "data: {\"type\":\"message_start\"}\n\n".toByteArray(),
            )
            dump.writeAsync(
                machine = "m1",
                method = "GET",
                path = "/v1/models",
                host = "api.anthropic.com",
                reqHeaders = emptyMap(),
                reqBody = null,
                statusLine = "404 Not Found",
                respHeaders = emptyMap(),
                respBody = null,
            )

            val file = waitForFile(dir, "m1")
            val lines = waitForLines(file, 2)
            // The two writeAsync calls each fire on their own daemon thread, so file order across
            // separate exchanges is not guaranteed (the per-file lock only prevents interleaving
            // WITHIN a single line's write) — match by content instead of by line index.
            val entries = lines.map { mapper.readValue(it, DumpEntry::class.java) }
            val entry1 = entries.first { it.request.method == "POST" }
            assertEquals("m1", entry1.machine)
            assertEquals("https://api.anthropic.com/v1/messages", entry1.request.url)
            assertEquals(
                "Bearer fake-token",
                entry1.request.headers.first { it.name == "Authorization" }.value,
            )
            assertEquals(200, entry1.response.status)
            assertEquals("OK", entry1.response.statusText)
            assertTrue(entry1.response.content!!.text.contains("message_start"))

            val entry2 = entries.first { it.request.method == "GET" }
            assertEquals(404, entry2.response.status)
            assertEquals("Not Found", entry2.response.statusText)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `captures a body far past the old truncation cap, whole and byte-exact`() {
        val dir = Files.createTempDirectory("dump-test").toFile()
        try {
            val dump = Dump(dir.absolutePath, mapper)
            val bigBody = "x".repeat(1024 * 1024) // 1MB, well past the old 256KB cap
            dump.writeAsync(
                machine = "m2",
                method = "POST",
                path = "/v1/messages",
                host = "api.anthropic.com",
                reqHeaders = emptyMap(),
                reqBody = "short".toByteArray(),
                statusLine = "HTTP/1.1 200 OK",
                respHeaders = emptyMap(),
                respBody = bigBody.toByteArray(),
            )

            val file = waitForFile(dir, "m2")
            val lines = waitForLines(file, 1)
            val entry = mapper.readValue(lines[0], DumpEntry::class.java)
            assertEquals(bigBody.length, entry.response.content!!.size)
            assertEquals(bigBody, entry.response.content.text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sanitizes the machine directory segment`() {
        val dir = Files.createTempDirectory("dump-test").toFile()
        try {
            val dump = Dump(dir.absolutePath, mapper)
            dump.writeAsync(
                machine = "m../../etc",
                method = "GET",
                path = "/",
                host = "api.anthropic.com",
                reqHeaders = emptyMap(),
                reqBody = null,
                statusLine = "200 OK",
                respHeaders = emptyMap(),
                respBody = null,
            )
            val deadline = System.currentTimeMillis() + 3000
            var found: File? = null
            while (System.currentTimeMillis() < deadline && found == null) {
                found = dir.listFiles()?.firstOrNull { it.isDirectory }
                if (found == null) Thread.sleep(20)
            }
            requireNotNull(found) { "no dump subdirectory created" }
            assertFalse(found.name.contains(".."))
            assertFalse(found.name.contains("/"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun waitForFile(dir: File, machine: String): File {
        val deadline = System.currentTimeMillis() + 3000
        var file: File?
        do {
            file = File(dir, machine).listFiles()?.firstOrNull { it.name.endsWith(".ndjson") }
            if (file == null) Thread.sleep(20)
        } while (file == null && System.currentTimeMillis() < deadline)
        return requireNotNull(file) { "dump file was never created" }
    }

    private fun waitForLines(file: File, count: Int): List<String> {
        val deadline = System.currentTimeMillis() + 3000
        var lines: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size >= count) break
            Thread.sleep(20)
        }
        assertEquals(count, lines.size)
        return lines
    }
}

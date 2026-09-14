package app.microteams.ccproxy.dataplane

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ModelGateTest {
    @Test
    fun `empty family list never rejects`() {
        assertNull(ModelGate.reject("claude-opus-5", emptyList()))
    }

    @Test
    fun `blocked family substring match rejects a request, case-insensitively`() {
        val families = ModelGate.parseBlockedFamilies("opus, fable")
        assertEquals(listOf("opus", "fable"), families)
        assertNotNull(ModelGate.reject("claude-Opus-5-20260101", families))
        assertNotNull(ModelGate.reject("fable-preview", families))
    }

    @Test
    fun `an allowed model is never rejected`() {
        val families = ModelGate.parseBlockedFamilies("opus,fable")
        assertNull(ModelGate.reject("claude-sonnet-5", families))
    }

    @Test
    fun `a model that can't be determined fails open, never rejecting`() {
        val families = ModelGate.parseBlockedFamilies("opus")
        assertNull(ModelGate.reject(null, families))
    }

    @Test
    fun `extractModel finds the model field within a bounded prefix`() {
        val body = """{"model":"claude-opus-5-20260101","messages":[]}"""
        assertEquals("claude-opus-5-20260101", ModelGate.extractModel(body.toByteArray()))
    }

    @Test
    fun `extractModel returns null when the model name is outside the peeked prefix`() {
        val padding = "x".repeat(5000)
        val body = """{"padding":"$padding","model":"claude-opus-5"}"""
        val prefix = body.toByteArray().copyOfRange(0, 4096) // simulate a bounded sniff cap
        assertNull(ModelGate.extractModel(prefix))
    }

    @Test
    fun `end-to-end gate rejects a blocked model before any body details matter`() {
        val families = ModelGate.parseBlockedFamilies("opus")
        val body = """{"model":"claude-opus-5","messages":[{"role":"user","content":"hi"}]}"""
        val model = ModelGate.extractModel(body.toByteArray())
        val reject = ModelGate.reject(model, families)
        assertNotNull(reject)
        assert(reject.contains("sonnet"))
    }
}

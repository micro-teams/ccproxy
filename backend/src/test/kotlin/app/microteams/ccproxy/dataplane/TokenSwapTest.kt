package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TokenSwapTest {
    private val mapper = ObjectMapper()

    @Test
    fun `capture mints a fake pair once and keeps it stable across a simulated refresh`() {
        val sess = Session("pw", "http://proxy:1", "m1")

        val first = mapper.createObjectNode()
        first.put("access_token", "real-access-1")
        first.put("refresh_token", "real-refresh-1")
        first.put("expires_in", 3600)
        first.put("scope", "user:inference")
        TokenSwap.captureRealTokens(sess, first, mapper)

        val fakeAccess1 = sess.fakeAccess
        val fakeRefresh1 = sess.fakeRefresh
        assertNotNull(fakeAccess1)
        assertNotNull(fakeRefresh1)
        assertEquals("real-access-1", sess.realAccess)

        // Simulate the real chain being refreshed upstream: a new real pair arrives.
        val second = mapper.createObjectNode()
        second.put("access_token", "real-access-2")
        second.put("refresh_token", "real-refresh-2")
        second.put("expires_in", 3600)
        TokenSwap.captureRealTokens(sess, second, mapper)

        assertEquals("real-access-2", sess.realAccess)
        assertEquals("real-refresh-2", sess.realRefresh)
        // The fake pair handed to the machine must NEVER rotate (#67).
        assertEquals(fakeAccess1, sess.fakeAccess)
        assertEquals(fakeRefresh1, sess.fakeRefresh)
    }

    @Test
    fun `a refresh response omitting refresh_token keeps the previous real refresh`() {
        val sess = Session("pw", null, "m1")
        val first = mapper.createObjectNode()
        first.put("access_token", "real-access-1")
        first.put("refresh_token", "real-refresh-1")
        TokenSwap.captureRealTokens(sess, first, mapper)

        val second = mapper.createObjectNode()
        second.put("access_token", "real-access-2")
        // no refresh_token field
        TokenSwap.captureRealTokens(sess, second, mapper)

        assertEquals("real-refresh-1", sess.realRefresh)
    }

    @Test
    fun `swapRequestBody replaces fake access and refresh with real`() {
        val sess = Session("pw", null, "m1")
        sess.fakeAccess = "fake-a"
        sess.realAccess = "real-a"
        sess.fakeRefresh = "fake-r"
        sess.realRefresh = "real-r"
        val body = """{"grant_type":"refresh_token","refresh_token":"fake-r"}"""
        val out = TokenSwap.swapRequestBody(body, sess)
        assertTrue(out!!.contains("real-r"))
        assertTrue(!out.contains("fake-r"))
    }

    @Test
    fun `swapRequestBody returns the original reference when nothing matched`() {
        val sess = Session("pw", null, "m1")
        val body = """{"foo":"bar"}"""
        assertEquals(body, TokenSwap.swapRequestBody(body, sess))
    }

    @Test
    fun `swapResponseBody captures tokens on a token response and hands back the fake pair`() {
        val sess = Session("pw", null, "m1")
        val body = """{"access_token":"real-x","refresh_token":"real-y","expires_in":3600}"""
        val out = TokenSwap.swapResponseBody(body, sess, mapper)
        val node = mapper.readTree(out) as ObjectNode
        assertEquals(sess.fakeAccess, node.get("access_token").asText())
        assertEquals(sess.fakeRefresh, node.get("refresh_token").asText())
        assertEquals("real-x", sess.realAccess)
    }

    @Test
    fun `swapResponseBody never leaks a real token in steady state`() {
        val sess = Session("pw", null, "m1")
        sess.realAccess = "real-a"
        sess.fakeAccess = "fake-a"
        val body = """{"echo":"real-a"}"""
        val out = TokenSwap.swapResponseBody(body, sess, mapper)
        assertTrue(out!!.contains("fake-a"))
        assertTrue(!out.contains("real-a"))
    }

    @Test
    fun `swapAuthHeader rewrites fake bearer to real`() {
        val sess = Session("pw", null, "m1")
        sess.fakeAccess = "fake-a"
        sess.realAccess = "real-a"
        val out = TokenSwap.swapAuthHeader("Bearer fake-a", sess)
        assertEquals("Bearer real-a", out)
    }
}

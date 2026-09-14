package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RefreshAbsorptionTest {
    private val mapper = ObjectMapper()

    private fun sessionWithFakePair(): Session {
        val sess = Session("pw", null, "m1")
        sess.fakeAccess = "fake-access"
        sess.fakeRefresh = "fake-refresh"
        sess.realAccess = "real-access"
        sess.realRefresh = "real-refresh"
        return sess
    }

    @Test
    fun `parseRefreshGrant recognizes a JSON grant carrying the fake refresh token`() {
        val sess = sessionWithFakePair()
        val body = """{"grant_type":"refresh_token","refresh_token":"fake-refresh"}"""
        assertTrue(RefreshAbsorption.parseRefreshGrant(body, sess, mapper))
    }

    @Test
    fun `parseRefreshGrant recognizes a form-encoded grant`() {
        val sess = sessionWithFakePair()
        val body = "grant_type=refresh_token&refresh_token=fake-refresh"
        assertTrue(RefreshAbsorption.parseRefreshGrant(body, sess, mapper))
    }

    @Test
    fun `parseRefreshGrant rejects a grant carrying a different refresh token`() {
        val sess = sessionWithFakePair()
        val body = """{"grant_type":"refresh_token","refresh_token":"someone-elses"}"""
        assertFalse(RefreshAbsorption.parseRefreshGrant(body, sess, mapper))
    }

    @Test
    fun `absorbRefresh answers locally when the real chain is fresh, without calling upstream`() {
        val sess = sessionWithFakePair()
        val now = Instant.now().epochSecond
        sess.expiresAt = now + 7200 // well above the margin
        var upstreamCalled = false
        val resp =
            RefreshAbsorption.absorbRefresh(
                sess,
                refreshMarginSeconds = 3600,
                refreshBackoffSeconds = 30,
                mapper,
                nowFn = { now },
            ) {
                upstreamCalled = true
                null
            }
        assertFalse(upstreamCalled)
        val obj = mapper.readTree(resp.body)
        assertEquals("fake-access", obj.get("access_token").asText())
        assertEquals("fake-refresh", obj.get("refresh_token").asText())
    }

    @Test
    fun `absorbRefresh calls upstream and captures a new real pair when near expiry`() {
        val sess = sessionWithFakePair()
        val now = Instant.now().epochSecond
        sess.expiresAt = now + 100 // inside the margin
        var upstreamCalled = false
        val resp =
            RefreshAbsorption.absorbRefresh(
                sess,
                refreshMarginSeconds = 3600,
                refreshBackoffSeconds = 30,
                mapper,
                nowFn = { now },
            ) {
                upstreamCalled = true
                Triple(
                    "200 OK",
                    mutableMapOf("Content-Type" to "application/json"),
                    """{"access_token":"real-access-2","refresh_token":"real-refresh-2","expires_in":3600}""",
                )
            }
        assertTrue(upstreamCalled)
        assertEquals("real-access-2", sess.realAccess)
        // fake pair stays stable even though the real chain rotated
        assertEquals("fake-access", sess.fakeAccess)
        val obj = mapper.readTree(resp.body)
        assertEquals("fake-access", obj.get("access_token").asText())
    }

    @Test
    fun `absorbRefresh answers locally during backoff after a recent upstream failure`() {
        val sess = sessionWithFakePair()
        val now = Instant.now().epochSecond
        sess.expiresAt = now + 100 // near expiry, would normally go upstream
        sess.lastRefreshFail = now - 5 // 5s ago, inside the 30s backoff
        var upstreamCalled = false
        RefreshAbsorption.absorbRefresh(
            sess,
            refreshMarginSeconds = 3600,
            refreshBackoffSeconds = 30,
            mapper,
            nowFn = { now },
        ) {
            upstreamCalled = true
            null
        }
        assertFalse(upstreamCalled)
    }

    @Test
    fun `absorbRefresh falls back locally and records the failure when upstream transport fails`() {
        val sess = sessionWithFakePair()
        val now = Instant.now().epochSecond
        sess.expiresAt = now + 100
        val resp =
            RefreshAbsorption.absorbRefresh(
                sess,
                refreshMarginSeconds = 3600,
                refreshBackoffSeconds = 30,
                mapper,
                nowFn = { now },
            ) {
                null // transport failure
            }
        assertEquals(now, sess.lastRefreshFail)
        val obj = mapper.readTree(resp.body)
        assertEquals("fake-access", obj.get("access_token").asText())
    }
}

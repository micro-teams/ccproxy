package app.microteams.ccproxy.dataplane

import app.microteams.ccproxy.account.Account
import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.credential.Credential
import app.microteams.ccproxy.credential.CredentialRepository
import app.microteams.ccproxy.credential.CredentialScope
import app.microteams.ccproxy.machine.Machine
import app.microteams.ccproxy.machine.MachineRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SessionStoreTest {
    private val registry = SessionRegistry()
    private val credentialRepository = mockk<CredentialRepository>(relaxed = true)
    private val machineRepository = mockk<MachineRepository>()
    private val accountRepository = mockk<AccountRepository>()
    private val config = CCProxyConfig()
    private val store =
        SessionStore(registry, credentialRepository, machineRepository, accountRepository, config)

    private fun machine(proxyUser: String, password: String = "pw", accountId: Long? = 1L) =
        Machine(proxyUser = proxyUser, proxyPassword = password, accountId = accountId)

    @Test
    fun `loadAll rebuilds the registry from every live machine's credential row (restart recovery)`() {
        val row =
            Credential(scope = CredentialScope.SESSION, credKey = "m1").apply {
                proxyPassword = "pw1"
                accountProxy = "http://egress:1"
                realAccess = "real-a"
                realRefresh = "real-r"
                fakeAccess = "fake-a"
                fakeRefresh = "fake-r"
                expiresAt = 1234L
            }
        every { credentialRepository.findAllByScope(CredentialScope.SESSION) } returns listOf(row)
        every { machineRepository.findByProxyUser("m1") } returns machine("m1")

        store.loadAll()

        val sess = registry.get("m1")
        assertNotNull(sess)
        assertEquals("real-a", sess.realAccess)
        assertEquals("fake-a", sess.fakeAccess)
        assertEquals(1234L, sess.expiresAt)
    }

    @Test
    fun `loadAll skips a credential row whose machine no longer exists (revoked ticket)`() {
        val row =
            Credential(scope = CredentialScope.SESSION, credKey = "gone").apply {
                fakeAccess = "fake"
            }
        every { credentialRepository.findAllByScope(CredentialScope.SESSION) } returns listOf(row)
        every { machineRepository.findByProxyUser("gone") } returns null

        store.loadAll()

        assertNull(registry.get("gone"))
    }

    @Test
    fun `loadOne is the lazy cache-miss self-heal for a single known proxyUser`() {
        every { machineRepository.findByProxyUser("m2") } returns
            machine("m2", "pw2", accountId = 5L)
        every { accountRepository.findById(5L) } returns
            Optional.of(Account(proxy = "http://egress:2"))
        val row =
            Credential(scope = CredentialScope.SESSION, credKey = "m2").apply {
                fakeAccess = "fake-a2"
                realAccess = "real-a2"
            }
        every { credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, "m2") } returns
            row

        assertNull(registry.get("m2")) // not cached yet
        val sess = store.loadOne("m2")

        assertNotNull(sess)
        assertEquals("pw2", sess.proxyPassword)
        assertEquals("http://egress:2", sess.accountProxy)
        assertEquals("fake-a2", sess.fakeAccess)
        assertEquals(sess, registry.get("m2")) // now cached
    }

    @Test
    fun `loadOne returns null for an unknown or revoked machine`() {
        every { machineRepository.findByProxyUser("unknown") } returns null
        assertNull(store.loadOne("unknown"))
    }

    @Test
    fun `persist writes the session's current tokens through to the credential table`() {
        val sess = Session("pw3", "http://egress:3", "m3")
        sess.realAccess = "real-a3"
        sess.fakeAccess = "fake-a3"
        every { machineRepository.findByProxyUser("m3") } returns machine("m3")
        every { credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, "m3") } returns
            null
        val saved = slot<Credential>()
        every { credentialRepository.save(capture(saved)) } answers { saved.captured }

        store.persist(sess)

        assertEquals("real-a3", saved.captured.realAccess)
        assertEquals("fake-a3", saved.captured.fakeAccess)
        verify { credentialRepository.save(any()) }
    }

    @Test
    fun `persist refuses to write for a machine that no longer exists (revoked)`() {
        val sess = Session("pw4", null, "m4")
        every { machineRepository.findByProxyUser("m4") } returns null

        store.persist(sess)

        verify(exactly = 0) { credentialRepository.save(any()) }
    }
}

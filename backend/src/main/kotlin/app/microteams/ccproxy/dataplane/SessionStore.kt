/*
 *  Description: DB-backed persistence for the Kotlin data plane's session registry. Since the MITM
 *               engine now runs IN this backend process, this replaces ccproxy_engine.py's HTTP calls
 *               to the internal credential endpoints with direct JPA reads/writes against the same
 *               `credential`
 *               table (CredentialEntity.kt) the Python engine already writes through the backend's
 *               ingest controller. Same three operations, same reasoning:
 *                 - persist(): synchronous write-through on every capture, so the DB is never behind
 *                   the fake tokens a machine already holds (mirrors persist_session).
 *                 - loadAll(): on backend startup, rebuild the in-memory registry from every
 *                   SESSION-scope credential row whose machine still exists — a restart must not log
 *                   machines out (mirrors load_all_from_db).
 *                 - loadOne(): lazy self-heal on a cache miss for a known proxyUser (mirrors
 *                   load_db_tokens / fetch_session), also pulling proxy-auth + egress proxy from the
 *                   Machine/Account tables when the session isn't in memory yet at all.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.credential.Credential
import app.microteams.ccproxy.credential.CredentialRepository
import app.microteams.ccproxy.credential.CredentialScope
import app.microteams.ccproxy.machine.MachineRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class SessionStore(
    private val registry: SessionRegistry,
    private val credentialRepository: CredentialRepository,
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val config: CCProxyConfig,
) {
    private val log = LoggerFactory.getLogger(SessionStore::class.java)

    /**
     * Write-through persist of a session's captured/injected tokens. Best-effort: a DB failure only
     * logs — the in-memory session keeps the machine working until the next write, matching
     * Python's persist_session which never lets a persistence failure affect the live swap.
     */
    @Transactional
    fun persist(sess: Session) {
        try {
            // A machine that no longer exists is a revoked ticket; refuse the write exactly like
            // CredentialIngestController.upsertSession does for the Python engine's dual-write.
            machineRepository.findByProxyUser(sess.user) ?: return
            val existing =
                credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, sess.user)
            val row = existing ?: Credential(scope = CredentialScope.SESSION, credKey = sess.user)
            row.proxyPassword = sess.proxyPassword
            row.accountProxy = sess.accountProxy
            row.realAccess = sess.realAccess
            row.realRefresh = sess.realRefresh
            row.fakeAccess = sess.fakeAccess
            row.fakeRefresh = sess.fakeRefresh
            row.expiresAt = sess.expiresAt
            credentialRepository.save(row)
        } catch (e: Exception) {
            log.warn("persist session ${sess.user} failed: ${e.message?.take(80)}")
        }
    }

    private fun applyRow(sess: Session, row: Credential) {
        row.realAccess?.let { sess.realAccess = it }
        row.realRefresh?.let { sess.realRefresh = it }
        row.fakeAccess?.let { sess.fakeAccess = it }
        row.fakeRefresh?.let { sess.fakeRefresh = it }
        row.expiresAt?.let { sess.expiresAt = it }
    }

    /**
     * On backend startup: rebuild the registry from every live machine's session credential row.
     */
    @PostConstruct
    @Transactional
    fun loadAll() {
        try {
            val rows = credentialRepository.findAllByScope(CredentialScope.SESSION)
            var n = 0
            for (row in rows) {
                val user = row.credKey
                if (machineRepository.findByProxyUser(user) == null) continue // revoked machine
                val sess = registry.put(user, row.proxyPassword, row.accountProxy)
                applyRow(sess, row)
                n++
            }
            log.info("dataplane: loaded $n session(s) from DB")
        } catch (e: Exception) {
            log.warn("dataplane: DB session load failed: ${e.message?.take(120)}")
        }
    }

    /**
     * Cache-miss self-heal for one proxyUser: pull proxy-auth + egress proxy from Machine/Account,
     * overlay any captured tokens from the credential row, and cache the result in the registry.
     * Returns null if the machine is unknown/revoked. Mirrors fetch_session + load_db_tokens.
     */
    @Transactional
    fun loadOne(user: String): Session? {
        try {
            val machine = machineRepository.findByProxyUser(user) ?: return null
            val password = machine.proxyPassword ?: return null
            val accountProxy =
                machine.accountId?.let { accountRepository.findById(it).orElse(null)?.proxy }
                    ?: config.engine.defaultAccountProxy
            val sess = registry.put(user, password, accountProxy)
            val row = credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, user)
            if (row != null) applyRow(sess, row)
            return sess
        } catch (e: Exception) {
            log.warn("dataplane: session load for $user failed: ${e.message?.take(120)}")
            return null
        }
    }
}

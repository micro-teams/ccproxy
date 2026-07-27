/*
 *  Description: Mint / list / revoke opaque secrets for either realm. The plaintext value is
 *               returned ONCE (on creation) and stored only as a SHA-256 hash. Shared by the tenant
 *               and login-operator controllers.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class SecretService(private val authSecretRepository: AuthSecretRepository) {

    fun list(ownerId: IdType, role: AuthSecretRole): List<AuthSecret> =
        authSecretRepository.findByOwnerIdAndRole(ownerId, role).sortedBy { it.id }

    /** Returns the plaintext secret alongside the persisted row; the plaintext is never stored. */
    fun create(ownerId: IdType, role: AuthSecretRole, label: String?): Pair<AuthSecret, String> {
        val secret = randomSecret()
        val saved =
            authSecretRepository.save(
                AuthSecret(
                    ownerId = ownerId,
                    role = role,
                    secretHash = sha256Hex(secret),
                    label = label,
                )
            )
        return saved to secret
    }

    fun revoke(ownerId: IdType, role: AuthSecretRole, secretId: IdType) {
        val secret =
            authSecretRepository.findById(secretId).orElseThrow {
                NotFoundError("auth_secret", secretId)
            }
        if (secret.ownerId != ownerId || secret.role != role) {
            throw NotFoundError("auth_secret", secretId)
        }
        secret.status = AuthSecretStatus.REVOKED
        secret.deletedAt = LocalDateTime.now()
        authSecretRepository.save(secret)
    }

    private fun randomSecret(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rng = SecureRandom()
        return (1..40).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
    }
}

/*
 *  Description: Provisioning helpers a tenant reads before/while registering a machine: the operator
 *               SSH public key to inject, and the MITM CA certificate to trust.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.provisioning

import app.microteams.ccproxy.api.ProvisioningApi
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.machine.OperatorSsh
import app.microteams.ccproxy.model.CaCertResponseDTO
import app.microteams.ccproxy.model.SshPubkeyResponseDTO
import java.io.File
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.common.error.NotFoundError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class ProvisioningController(
    private val operatorSsh: OperatorSsh,
    private val config: CCProxyConfig,
) : ProvisioningApi {

    @Guard("get-ssh-pubkey", "provisioning")
    override fun getSshPubkey(): ResponseEntity<SshPubkeyResponseDTO> {
        val key = operatorSsh.publicKey() ?: throw NotFoundError("ssh_pubkey", 0)
        return ResponseEntity.ok(SshPubkeyResponseDTO(publicKey = key))
    }

    @Guard("get-ca-cert", "provisioning")
    override fun getCaCert(): ResponseEntity<CaCertResponseDTO> {
        val path = config.ca.certPath ?: throw NotFoundError("ca_cert", 0)
        val f = File(path)
        if (!f.isFile) throw NotFoundError("ca_cert", 0)
        return ResponseEntity.ok(CaCertResponseDTO(pem = f.readText()))
    }
}

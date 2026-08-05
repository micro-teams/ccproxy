/*
 *  Description: CCProxy's own configuration (prefix `ccproxy`), separate from the borrowed
 *               org.rucca.cheese ApplicationConfig. Covers the super-admin identity, the operator
 *               SSH key CCProxy logs into machines with, the MITM CA, and how the backend reaches
 *               the proxy-engine.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component

@Component
@EnableAsync
@ConfigurationProperties(prefix = "ccproxy")
class CCProxyConfig {
    /** The platform operator's password. Injected at runtime; never committed. */
    lateinit var superadminPassword: String

    /** The synthetic user id the super-admin's tokens are minted under. */
    var superadminId: Long = 1

    /** Operator SSH identity CCProxy uses to log into machines and install/drive Claude Code. */
    var provisioning: Provisioning = Provisioning()

    /**
     * How the backend reaches the proxy-engine's control API and what machines point HTTPS_PROXY
     * at.
     */
    var engine: Engine = Engine()

    /** The MITM CA the machines must trust; installed over SSH during provisioning. */
    var ca: Ca = Ca()

    class Provisioning {
        /**
         * Operator SSH public key the tenant injects into a machine before registering it. If
         * blank, read from `${sshPrivateKeyPath}.pub`.
         */
        var sshPublicKey: String? = null
        /**
         * Operator SSH private key used to log into machines. Defaults to the bundle's mounted key.
         */
        var sshPrivateKeyPath: String? = "/keys/operator"
        /** How long to wait for a machine to accept SSH (TCP :22) during the one-shot bootstrap. */
        var sshReadyTimeoutSeconds: Long = 60
    }

    class Engine {
        /** Base URL of the proxy-engine control API (compose service). */
        var controlUrl: String = "http://proxy-engine:9000"
        /** Shared secret the backend authenticates to the proxy-engine with. */
        var controlSecret: String? = null
        /**
         * The host:port a machine's HTTPS_PROXY points at (the proxy-engine's MITM listener,
         * reachable from the machine's network). Per-machine credentials are prefixed at
         * provisioning time.
         */
        var proxyEndpoint: String = "proxy-engine:3128"
        /**
         * The default upstream egress proxy stamped onto new accounts (the bundle's egress-proxy).
         */
        var defaultAccountProxy: String = "http://egress-proxy:7890"
    }

    class Ca {
        /** PEM CA certificate the machines trust. Defaults to the bundle's mounted CA. */
        var certPath: String? = "/keys/ca.crt"
    }
}

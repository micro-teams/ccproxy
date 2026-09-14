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

    /**
     * Settings for the in-process Kotlin data-plane (`dataplane` package) — the port of
     * proxy-engine/ccproxy_engine.py into this backend. Disabled by default: today's deployment
     * still runs the Python proxy-engine as the real MITM data plane, and this in-process engine is
     * a parallel implementation meant for shadow-mode comparison before any cutover. Flip
     * `ccproxy.dataplane.enabled=true` to actually bind its listener.
     */
    var dataplane: Dataplane = Dataplane()

    class Dataplane {
        /** Whether to start the in-process MITM proxy listener at all. */
        var enabled: Boolean = false
        /** Port the MITM proxy listens on. Same default as proxy-engine's CCPROXY_PROXY_PORT. */
        var proxyPort: Int = 3128
        /** PEM CA cert/key used to sign per-domain leaf certs (CCPROXY_CA_CERT/CCPROXY_CA_KEY). */
        var caCertPath: String = "/keys/ca.crt"
        var caKeyPath: String = "/keys/ca.key"
        /** Where generated per-domain leaf key+cert pairs are cached on disk. */
        var certsDir: String = "/tmp/ccproxy-certs-kt"
        var mitmDomains: Set<String> = setOf("api.anthropic.com", "platform.claude.com")
        var clientIdleTimeoutMs: Int = 900_000
        var clientActiveTimeoutMs: Int = 120_000
        var upstreamTimeoutMs: Int = 300_000
        var upstreamConnectAttempts: Int = 6
        var upstreamRetryBackoffMs: Long = 250
        var refreshMarginSeconds: Long = 3600
        var refreshBackoffSeconds: Long = 30
        var streamBlock: Int = 65536
        var meterCap: Int = 8 * 1024 * 1024
        var modelSniffCap: Int = 4096
        /**
         * Comma-separated model-family substrings to reject before opening an upstream connection.
         * Empty = gate off. Live-toggleable via DataplaneControlService.setBlockedModelFamilies.
         */
        var blockedModelFamilies: String = ""
    }

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
         * Comma-separated hosts a machine must reach WITHOUT going through the MITM proxy, written
         * as NO_PROXY/no_proxy in the machine's settings.json env. Loopback is the safe default so
         * local services (MCP servers, the connector's own endpoints, health checks) aren't routed
         * through :3128. Add internal hosts here if a deployment needs them to bypass the proxy.
         * Note: node/undici matches NO_PROXY by hostname/suffix, not CIDR — list hostnames/IPs, not
         * ranges.
         */
        var noProxy: String = "localhost,127.0.0.1,::1"
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

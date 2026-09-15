/*
 *  Description: The MultiPath line registry, as deployment configuration.
 *
 *               Which public routes exist is a property of how this instance is deployed — an
 *               operator adds a tunnel or a CDN route and it becomes true — so it is configuration
 *               rather than data. Maintained by hand on purpose: whether a route is worth keeping is
 *               a judgement about cost and trust, not something a health check should decide.
 *
 *               Mirror of micro-teams' LineRegistryProperties.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.transport

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ccproxy.multipath")
data class LineRegistryProperties(val lines: List<Line> = emptyList()) {

    /**
     * Reject a malformed registry at startup rather than serving it — a typo here would otherwise
     * quietly switch multi-line off and leave every connector on a single route with nothing to say
     * why.
     */
    @PostConstruct
    fun validate() {
        val seen = mutableSetOf<String>()
        lines.forEachIndexed { index, line ->
            require(line.id.isNotBlank()) {
                "ccproxy.multipath.lines[$index].id must not be empty"
            }
            require(seen.add(line.id)) {
                "ccproxy.multipath.lines[$index].id is a duplicate: \"${line.id}\""
            }
            require(line.url.isEmpty() || ORIGIN.matches(line.url)) {
                "ccproxy.multipath.lines[$index].url must be an absolute origin with no path and " +
                    "no trailing slash (or empty for same-origin), got \"${line.url}\""
            }
        }
    }

    private companion object {
        /** Mirrors the Go/TS registry parsers: scheme + host, nothing else. */
        val ORIGIN = Regex("^https?://[^/]+$")
    }

    data class Line(
        val id: String,
        val url: String = "",
        val transport: String? = null,
        val weight: Int? = null,
        val foreignOrigin: Boolean = false,
    )
}

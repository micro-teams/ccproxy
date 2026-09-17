package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param id Stable identifier for this path -- \"origin\", \"cf\", \"ipv6-1\".
 * @param url Absolute origin for this line, with no path and no trailing slash, or the empty string
 *   meaning \"wherever this page came from\". A single-origin deployment is one empty entry.
 * @param transport How the Go connector encapsulates its link to this line: \"tls\" (raw TLS
 *   straight to the origin's own listener) or \"wss\" (WebSocket-over-HTTPS, for a line whose edge
 *   -- a CDN, a tunnel, a reverse proxy -- terminates TLS and forwards only HTTP), or
 *   \"tcp\"/\"ws\" for the plaintext equivalents. Empty infers \"tls\"/\"tcp\" from the URL scheme,
 *   which is only correct for a line that reaches the origin's own listener directly. NOT a
 *   free-form label: any other value is a configuration error the connector rejects at dial time. A
 *   line that goes through this deployment's own nginx (every line except one exposing the origin's
 *   raw port directly) needs \"wss\", regardless of how descriptive a name like \"cf\" or \"frp-1\"
 *   might suggest otherwise.
 * @param weight Static preference, higher first. Only breaks ties between lines that measure the
 *   same.
 * @param foreignOrigin True when this line is not under our own domain -- a free proxy that cannot
 *   be CNAME'd.
 */
data class LineDTO(
    @Schema(
        required = true,
        description = "Stable identifier for this path -- \"origin\", \"cf\", \"ipv6-1\".",
    )
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.String,
    @Schema(
        required = true,
        description =
            "Absolute origin for this line, with no path and no trailing slash, or the empty string meaning \"wherever this page came from\". A single-origin deployment is one empty entry. ",
    )
    @param:JsonProperty("url")
    @get:JsonProperty("url", required = true)
    val url: kotlin.String,
    @Schema(
        description =
            "How the Go connector encapsulates its link to this line: \"tls\" (raw TLS straight to the origin's own listener) or \"wss\" (WebSocket-over-HTTPS, for a line whose edge -- a CDN, a tunnel, a reverse proxy -- terminates TLS and forwards only HTTP), or \"tcp\"/\"ws\" for the plaintext equivalents. Empty infers \"tls\"/\"tcp\" from the URL scheme, which is only correct for a line that reaches the origin's own listener directly. NOT a free-form label: any other value is a configuration error the connector rejects at dial time. A line that goes through this deployment's own nginx (every line except one exposing the origin's raw port directly) needs \"wss\", regardless of how descriptive a name like \"cf\" or \"frp-1\" might suggest otherwise. "
    )
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("transport")
    @get:JsonProperty("transport")
    val transport: kotlin.String? = null,
    @Schema(
        description =
            "Static preference, higher first. Only breaks ties between lines that measure the same. "
    )
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("weight")
    @get:JsonProperty("weight")
    val weight: kotlin.Int? = null,
    @Schema(
        description =
            "True when this line is not under our own domain -- a free proxy that cannot be CNAME'd. "
    )
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("foreignOrigin")
    @get:JsonProperty("foreignOrigin")
    val foreignOrigin: kotlin.Boolean? = null,
) {}

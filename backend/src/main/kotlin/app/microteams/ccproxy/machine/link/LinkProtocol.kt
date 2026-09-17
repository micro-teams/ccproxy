/*
 *  Description: The micro-connector wire protocol, ccproxy side. One flat JSON union with a `t`
 *               field naming the type; only the fields a type uses are set (NON_NULL, mirroring the
 *               Go `omitempty`), and unknown types/fields are ignored so an older connector keeps
 *               working when we learn something new. This is the frozen contract in
 *               micro-connector/docs/protocol.md — do not rename fields.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine.link

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Bumped only when the message set changes in a way an older peer cannot survive. */
const val PROTOCOL_VERSION: Int = 1

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkMsg(
    @get:JsonProperty("t") val t: String,
    @get:JsonProperty("v") val v: Int? = null, // protocol version (hello / welcome)
    @get:JsonProperty("sid") val sid: String? = null,
    @get:JsonProperty("name") val name: String? = null,
    @get:JsonProperty("value") val value: Any? = null,
    @get:JsonProperty("id") val id: String? = null,
    @get:JsonProperty("args") val args: List<Any?>? = null,
    @get:JsonProperty("error") val error: String? = null,
    @get:JsonProperty("command") val command: List<String>? = null,
    @get:JsonProperty("env") val env: Map<String, String>? = null,
    @get:JsonProperty("screen") val screen: String? = null,
    @get:JsonProperty("cols") val cols: Int? = null,
    @get:JsonProperty("rows") val rows: Int? = null,
    @get:JsonProperty("source") val source: String? = null,
    @get:JsonProperty("adopt") val adopt: Boolean? = null,
    @get:JsonProperty("data") val data: String? = null, // base64 terminal bytes
    @get:JsonProperty("dir") val dir: String? = null,
    @get:JsonProperty("cwd") val cwd: String? = null,
    @get:JsonProperty("stdin") val stdin: String? = null,
    @get:JsonProperty("timeout") val timeout: Int? = null,
    @get:JsonProperty("stdout") val stdout: String? = null,
    @get:JsonProperty("stderr") val stderr: String? = null,
    @get:JsonProperty("exit") val exit: Int? = null,
    @get:JsonProperty("truncated") val truncated: Boolean? = null,
    // Structured, shell-free file access: file.write/file.read/file.remove/homedir. path is the
    // file operated on for a request; for a homedir.result it carries the resolved directory
    // instead of a dedicated field, since that's exactly what it is. See docs/protocol.md.
    @get:JsonProperty("path") val path: String? = null,
)

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exit: Int,
    val truncated: Boolean,
)

/**
 * The result of a file.write / file.read / file.remove / homedir. `error` null (or blank) means
 * success. `data` is base64 file content (file.read only); `path` is the resolved home directory
 * (homedir only) — see LinkMsg.path's doc comment for why it is reused rather than a new field.
 */
data class FileResult(val error: String?, val data: String? = null, val path: String? = null) {
    val ok: Boolean
        get() = error.isNullOrBlank()
}

/*
 *  Description: Reads the screen-driver applets the control plane sends to a connector (currently
 *               just claude.js — the shared micro-connector driver that understands Claude Code's
 *               TUI). The applet is a versioned build ARTIFACT, not compiled into the jar: a driver
 *               fix ships by swapping the file, no rebuild. `application.applets-dir` points at the
 *               deployed directory (bind-mounted from the bundle's connector applets); the classpath
 *               fallback (applets/<name>) is only for tests / a standalone jar.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.connector

import java.io.File
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class AppletStore(@Value("\${application.applets-dir:}") private val appletsDir: String) {

    fun read(name: String): String? {
        if (appletsDir.isNotBlank()) {
            val f = File(appletsDir, name)
            return if (f.isFile) f.readText() else null
        }
        val res = ClassPathResource("applets/$name")
        return if (res.exists()) res.inputStream.bufferedReader().use { it.readText() } else null
    }

    fun require(name: String): String =
        read(name)
            ?: error(
                "applet '$name' not found: set application.applets-dir to the deployed applets " +
                    "directory (bundle: /app/applets) or put it on the classpath under applets/"
            )
}

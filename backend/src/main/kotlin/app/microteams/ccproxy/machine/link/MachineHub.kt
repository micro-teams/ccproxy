/*
 *  Description: The machine control-plane hub. Holds each dialed-in machine's outbound transport and
 *               writes command frames down it; correlates exec/rpc responses by id; tracks screens
 *               and their mirrored variables. I/O-free and transport-agnostic — LinkHandler feeds it
 *               a MachineTransport (a WebSocket) and its inbound frames. Ported from the MicroTeams
 *               hub, trimmed of the browser-viewer machinery ccproxy's login flow does not need.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine.link

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** How the hub writes a frame to one machine (a WebSocket send). Not concurrent-write safe. */
fun interface MachineTransport {
    fun sendJson(msg: LinkMsg)
}

/** A server-side function an applet may `rpc.call`. */
fun interface ScreenFn {
    fun call(hub: MachineHub, screen: HubScreen, args: List<Any?>): Any?
}

enum class ScreenState {
    UNKNOWN,
    STARTING,
    LIVE,
    DEAD,
}

/** Notified when a screen's state changes (STARTING→LIVE→DEAD). */
fun interface ScreenLifecycleListener {
    fun onScreenState(screen: HubScreen, previous: ScreenState)
}

/** Notified when an applet-owned variable is pushed up. */
fun interface ScreenVarListener {
    fun onScreenVar(screen: HubScreen, name: String, value: Any?)
}

class HubScreen(
    val sid: String,
    val machineId: String,
    val command: List<String>,
    val token: String,
    val kind: String,
) {
    val vars: MutableMap<String, Any?> = ConcurrentHashMap()

    @Volatile var state: ScreenState = ScreenState.UNKNOWN

    @Volatile var deadReason: String? = null
}

class HubMachine(val machineId: String) {
    @Volatile var transport: MachineTransport? = null

    @Volatile var proto: Int? = null

    @Volatile var origin: String? = null

    val screens: MutableMap<String, HubScreen> = ConcurrentHashMap()
    val execSeq = AtomicInteger(0)
    val callSeq = AtomicInteger(0)
    val execPending: MutableMap<String, CompletableFuture<ExecResult>> = ConcurrentHashMap()
    val callPending: MutableMap<String, CompletableFuture<Any?>> = ConcurrentHashMap()
    private val sendLock = ReentrantLock()

    fun send(msg: LinkMsg) {
        // A WebSocket session is not safe for concurrent writes; serialize every frame to a
        // machine.
        sendLock.withLock { transport?.sendJson(msg) }
    }
}

@Component
class MachineHub {
    private val logger = LoggerFactory.getLogger(MachineHub::class.java)
    private val machines: MutableMap<String, HubMachine> = ConcurrentHashMap()
    private val screens: MutableMap<String, HubScreen> = ConcurrentHashMap() // sid -> screen
    private val fns: MutableMap<String, ScreenFn> = ConcurrentHashMap()
    private val lifecycleListeners = CopyOnWriteArrayList<ScreenLifecycleListener>()
    private val varListeners = CopyOnWriteArrayList<ScreenVarListener>()

    init {
        // The applet announces itself once it is running; that is our LIVE signal.
        fns["screenReady"] = ScreenFn { _, screen, _ ->
            markScreen(screen.sid, ScreenState.LIVE)
            mapOf("ok" to true)
        }
    }

    private fun machine(machineId: String): HubMachine =
        machines.computeIfAbsent(machineId) { HubMachine(it) }

    // --- connection lifecycle -------------------------------------------------

    fun attachMachine(machineId: String, transport: MachineTransport, origin: String? = null) {
        val machine = machine(machineId)
        machine.transport = transport
        if (origin != null) machine.origin = origin
        machine.send(LinkMsg(t = "welcome", v = PROTOCOL_VERSION))
    }

    fun detachMachine(machineId: String, transport: MachineTransport) {
        val machine = machines[machineId]
        // Identity check: a reconnect installs a new transport before the old one closes; do not
        // let
        // the late close of the old socket clobber the live one.
        if (machine != null && machine.transport === transport) {
            machine.transport = null
        }
    }

    fun isOnline(machineId: String): Boolean = machines[machineId]?.transport != null

    fun originOf(machineId: String): String? = machines[machineId]?.origin

    fun onlineMachineIds(): List<String> =
        machines.values.filter { it.transport != null }.map { it.machineId }

    // --- listeners / server functions ----------------------------------------

    fun addScreenLifecycleListener(listener: ScreenLifecycleListener) {
        lifecycleListeners.add(listener)
    }

    fun addScreenVarListener(listener: ScreenVarListener) {
        varListeners.add(listener)
    }

    fun registerFn(name: String, fn: ScreenFn) {
        fns[name] = fn
    }

    fun screen(sid: String): HubScreen? = screens[sid]

    // --- screens --------------------------------------------------------------

    fun openScreen(
        machineId: String,
        command: List<String>,
        kind: String,
        appletSource: String? = null,
        env: Map<String, String>? = null,
        cols: Int = 120,
        rows: Int = 32,
    ): HubScreen {
        val machine = machine(machineId)
        val sid = "s" + UUID.randomUUID().toString().replace("-", "").take(8)
        val screen =
            HubScreen(
                sid = sid,
                machineId = machineId,
                command = command,
                token = UUID.randomUUID().toString().replace("-", ""),
                kind = kind,
            )
        machine.screens[sid] = screen
        screens[sid] = screen
        screen.state = ScreenState.STARTING
        machine.send(
            LinkMsg(
                t = "session.create",
                sid = sid,
                command = command,
                screen = screen.token,
                cols = cols,
                rows = rows,
                source = appletSource,
                env = env?.ifEmpty { null },
            )
        )
        return screen
    }

    fun closeScreen(machineId: String, sid: String) {
        val machine = machines[machineId] ?: return
        machine.send(LinkMsg(t = "session.close", sid = sid))
        machine.screens.remove(sid)
        screens.remove(sid)
    }

    /** Set an applet-observed variable (e.g. `mode` = "login"). */
    fun setVar(machineId: String, sid: String, name: String, value: Any?) {
        machine(machineId).send(LinkMsg(t = "var.set", sid = sid, name = name, value = value))
    }

    /** Fire-and-forget call of an applet function (e.g. `say`). */
    fun callScreen(machineId: String, sid: String, name: String, args: List<Any?>) {
        val machine = machine(machineId)
        val id = "srv" + machine.callSeq.incrementAndGet()
        machine.send(LinkMsg(t = "rpc.call", sid = sid, id = id, name = name, args = args))
    }

    /** Call an applet function and wait for its `rpc.result`. Null on timeout / offline / error. */
    fun callScreenAwait(
        machineId: String,
        sid: String,
        name: String,
        args: List<Any?> = emptyList(),
        timeoutSeconds: Long = 3,
    ): Any? {
        val machine = machines[machineId] ?: return null
        if (machine.transport == null) return null
        val id = "srv" + machine.callSeq.incrementAndGet()
        val fut = CompletableFuture<Any?>()
        machine.callPending[id] = fut
        try {
            machine.send(LinkMsg(t = "rpc.call", sid = sid, id = id, name = name, args = args))
            return fut.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            machine.callPending.remove(id)
        }
    }

    /** Run one command on the machine and wait for its result. */
    fun exec(
        machineId: String,
        argv: List<String>,
        cwd: String? = null,
        env: Map<String, String>? = null,
        timeoutSeconds: Long = 60,
        stdin: String? = null,
    ): ExecResult {
        val machine = machine(machineId)
        val eid = "e" + machine.execSeq.incrementAndGet()
        val fut = CompletableFuture<ExecResult>()
        machine.execPending[eid] = fut
        machine.send(
            LinkMsg(
                t = "exec",
                id = eid,
                command = argv,
                timeout = timeoutSeconds.toInt(),
                cwd = cwd,
                env = env?.ifEmpty { null },
                stdin = stdin,
            )
        )
        try {
            return fut.get(timeoutSeconds + 5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            machine.send(LinkMsg(t = "exec.cancel", id = eid))
            throw e
        } finally {
            machine.execPending.remove(eid)
        }
    }

    // --- inbound --------------------------------------------------------------

    fun onMachineMessage(machineId: String, m: LinkMsg) {
        val machine = machine(machineId)
        val sid = m.sid ?: ""
        val screen = machine.screens[sid]

        when (m.t) {
            "hello" -> {
                machine.proto = m.v
                if (m.v != null && m.v != PROTOCOL_VERSION) {
                    logger.warn(
                        "machine {} speaks protocol {} (we are {})",
                        machineId,
                        m.v,
                        PROTOCOL_VERSION,
                    )
                }
            }
            "heartbeat",
            "session.ready" -> {
                // session.ready is a no-op; the applet confirms LIVE via the screenReady rpc.
            }
            "session.error" -> {
                if (screen != null) markScreen(sid, ScreenState.DEAD, m.error ?: "session error")
            }
            "exec.result" -> {
                val fut = m.id?.let { machine.execPending[it] }
                if (fut != null && !fut.isDone) {
                    fut.complete(
                        ExecResult(
                            stdout = m.stdout ?: "",
                            stderr = m.stderr ?: "",
                            exit = m.exit ?: 0,
                            truncated = m.truncated ?: false,
                        )
                    )
                }
            }
            "var.push" -> {
                if (screen != null && m.name != null) {
                    screen.vars[m.name] = m.value
                    varListeners.forEach { l -> safely { l.onScreenVar(screen, m.name, m.value) } }
                }
            }
            "rpc.result" -> {
                val fut = m.id?.let { machine.callPending[it] }
                if (fut != null && !fut.isDone) {
                    if (m.error.isNullOrEmpty()) fut.complete(m.value)
                    else fut.completeExceptionally(RuntimeException(m.error))
                }
            }
            "rpc.call" -> handleScreenCall(machine, screen, m) // applet -> server; MUST answer
        }
    }

    private fun handleScreenCall(machine: HubMachine, screen: HubScreen?, m: LinkMsg) {
        val name = m.name ?: ""
        val args = m.args ?: emptyList()
        var value: Any? = null
        var error = ""
        val fn = fns[name]
        if (fn == null || screen == null) {
            error = "unknown function '$name'"
        } else {
            try {
                value = fn.call(this, screen, args)
            } catch (exc: Exception) {
                error = exc.message ?: exc.javaClass.simpleName
            }
        }
        // Protocol rule: every rpc.call must be answered, or the applet waits forever.
        machine.send(
            LinkMsg(
                t = "rpc.result",
                sid = m.sid,
                id = m.id,
                value = value,
                error = error.ifEmpty { null },
            )
        )
    }

    fun markScreen(sid: String, state: ScreenState, reason: String? = null) {
        val screen = screens[sid] ?: return
        val previous = screen.state
        if (previous == state) return // idempotent — no re-trigger
        screen.state = state
        screen.deadReason = if (state == ScreenState.DEAD) reason else null
        lifecycleListeners.forEach { l -> safely { l.onScreenState(screen, previous) } }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn("hub listener threw", e)
        }
    }
}

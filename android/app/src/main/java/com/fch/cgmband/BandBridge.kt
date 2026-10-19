package com.fch.cgmband

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.fch.cgmband.core.Alert
import com.fch.cgmband.core.BandMessage
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.tasks.Task
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the band app through Xiaomi's wearable SDK, which relays via 小米运动健康.
 * The band app must use the same package name and signing certificate as this app.
 *
 * Threading: every SDK Task callback, the timeout handler and all state below run on the
 * main thread. Only the incoming-message callback arrives on a binder thread; it is
 * immediately posted to the main thread.
 *
 * SDK quirks (xms-wearable-lib 1.4, verified by decompiling):
 * - Tasks never complete while 小米运动健康 is not bound, so every call has a timeout.
 * - The client keeps ONE message-listener slot per process; addListener fails with
 *   "you have registered" until removeListener succeeds, even if 小米运动健康 restarted
 *   and forgot the remote registration. We therefore use one stable listener object,
 *   always remove before re-adding, and keep pushing proactively so a lost sync channel
 *   only delays the band by one CGM refresh.
 */
object BandBridge {

    data class BandStatus(
        val node: Node?,
        val permissionGranted: Boolean?,
        val appInstalled: Boolean?,
        val notifyGranted: Boolean? = null,
    )

    private const val BAND_APP_URI = "/pages/index"
    private const val TASK_TIMEOUT_MS = 10_000L
    private const val LISTENER_RETRY_INTERVAL_MS = 10 * 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var nodeId: String? = null
    private var syncHandler: (() -> Unit)? = null
    private var serviceListenerRegistered = false

    private var listenerNodeId: String? = null
    private var attaching = false
    private var pendingForcedAttach = false
    private var lastAttachAttempt = 0L

    private val syncListener = OnMessageReceivedListener { _, bytes ->
        if (BandMessage.isSyncRequest(String(bytes, Charsets.UTF_8))) {
            mainHandler.post { syncHandler?.invoke() }
        }
    }

    /** Start answering the band's {"type":"sync"} requests with [onSync]. Call on the main thread. */
    fun start(context: Context, onSync: () -> Unit) {
        val app = context.applicationContext
        syncHandler = onSync
        registerServiceListener(app)
        ensureListener(app, force = false)
    }

    /** Push [message] to the band app. Call on the main thread. */
    fun send(context: Context, message: String, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        withNode(app) { id ->
            if (id == null) {
                onResult(false)
                return@withNode
            }
            await(
                Wearable.getMessageApi(app).sendMessage(id, message.toByteArray(Charsets.UTF_8)),
                onSuccess = {
                    retryListenerIfDue(app)
                    onResult(true)
                },
                onFailure = { e ->
                    forgetNodeUnlessPermanent(e)
                    EventLog.add("发送到手环失败: ${describe(e)}")
                    onResult(false)
                },
            )
        }
    }

    fun status(context: Context, onResult: (BandStatus) -> Unit) {
        val app = context.applicationContext
        refreshNode(app) { node ->
            if (node == null) {
                onResult(BandStatus(null, null, null))
                return@refreshNode
            }
            await(
                Wearable.getNodeApi(app).isWearAppInstalled(node.id),
                onSuccess = { installed ->
                    if (installed != true) {
                        onResult(BandStatus(node, null, false))
                        return@await
                    }
                    await(
                        Wearable.getAuthApi(app).checkPermissions(
                            node.id,
                            arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY),
                        ),
                        onSuccess = { granted -> onResult(BandStatus(node, granted[0], true, granted[1])) },
                        onFailure = { onResult(BandStatus(node, null, true)) },
                    )
                },
                onFailure = { e ->
                    EventLog.add("查询手环应用失败: ${describe(e)}")
                    onResult(BandStatus(node, null, null))
                },
            )
        }
    }

    fun requestPermission(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        withNode(app) { id ->
            if (id == null) {
                onResult(false)
                return@withNode
            }
            // NOTIFY is needed for the vibrating high/low glucose alerts.
            await(
                Wearable.getAuthApi(app).requestPermission(id, Permission.DEVICE_MANAGER, Permission.NOTIFY),
                onSuccess = { granted ->
                    val names = granted.map { it.name }
                    val ok = Permission.DEVICE_MANAGER.name in names
                    EventLog.add(if (ok) "手环通信授权成功" else "手环通信授权被拒绝")
                    if (Permission.NOTIFY.name !in names) EventLog.add("手环通知权限未授予，血糖提醒将无法送达")
                    if (ok) ensureListener(app, force = false)
                    onResult(ok)
                },
                onFailure = { e ->
                    EventLog.add("申请手环权限失败: ${describe(e)}")
                    onResult(false)
                },
            )
        }
    }

    /** Show a vibrating notification on the band (system UI, independent of the band app). */
    fun notify(context: Context, alert: Alert, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        withNode(app) { id ->
            if (id == null) {
                onResult(false)
                return@withNode
            }
            await(
                Wearable.getNotifyApi(app).sendNotify(id, alert.title, alert.message),
                onSuccess = { status ->
                    if (!status.isSuccess) EventLog.add("手环通知被拒绝 code=${status.code}")
                    onResult(status.isSuccess)
                },
                onFailure = { e ->
                    forgetNodeUnlessPermanent(e)
                    EventLog.add("发送手环通知失败: ${describe(e)}")
                    onResult(false)
                },
            )
        }
    }

    fun launchBandApp(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        withNode(app) { id ->
            if (id == null) {
                onResult(false)
                return@withNode
            }
            await(
                Wearable.getNodeApi(app).launchWearApp(id, BAND_APP_URI),
                onSuccess = { onResult(true) },
                onFailure = { e ->
                    EventLog.add("打开手环应用失败: ${describe(e)}")
                    onResult(false)
                },
            )
        }
    }

    // ---- node resolution -------------------------------------------------------------

    private fun withNode(context: Context, block: (String?) -> Unit) {
        val cached = nodeId
        if (cached != null) {
            block(cached)
            return
        }
        refreshNode(context) { node -> block(node?.id) }
    }

    private fun refreshNode(context: Context, onResult: (Node?) -> Unit) {
        await(
            Wearable.getNodeApi(context).connectedNodes,
            onSuccess = { nodes ->
                val node = nodes.firstOrNull()
                nodeId = node?.id
                if (node == null) EventLog.add("没有已连接的手环（请确认小米运动健康已连上手环）")
                onResult(node)
            },
            onFailure = { e ->
                nodeId = null
                EventLog.add("获取手环失败: ${describe(e)}")
                onResult(null)
            },
        )
    }

    private fun forgetNodeUnlessPermanent(e: Exception) {
        val permanent = e is AppNotInstalledException ||
            e is PermissionDeniedException ||
            e is SignatureVerifyFailedException
        if (!permanent) nodeId = null
    }

    // ---- sync listener state machine ---------------------------------------------------

    private fun retryListenerIfDue(context: Context) {
        val due = SystemClock.elapsedRealtime() - lastAttachAttempt > LISTENER_RETRY_INTERVAL_MS
        if (listenerNodeId == null && due) ensureListener(context, force = false)
    }

    private fun ensureListener(context: Context, force: Boolean) {
        if (syncHandler == null) return
        if (attaching) {
            pendingForcedAttach = pendingForcedAttach || force
            return
        }
        attaching = true
        lastAttachAttempt = SystemClock.elapsedRealtime()
        withNode(context) { id ->
            when {
                id == null -> finishAttach(context)
                !force && listenerNodeId == id -> finishAttach(context)
                else -> replaceListener(context, id)
            }
        }
    }

    private fun replaceListener(context: Context, id: String) {
        val previous = listenerNodeId
        if (previous == null) {
            addListener(context, id)
            return
        }
        // Remove first: the SDK refuses a second addListener until the slot is cleared.
        await(
            Wearable.getMessageApi(context).removeListener(previous),
            onSuccess = { addListener(context, id) },
            onFailure = { addListener(context, id) },
        )
    }

    private fun addListener(context: Context, id: String) {
        await(
            Wearable.getMessageApi(context).addListener(id, syncListener),
            onSuccess = {
                listenerNodeId = id
                EventLog.add("已监听手环同步请求")
                finishAttach(context)
            },
            onFailure = { e ->
                // "you have registered": the client slot still holds our stable listener.
                val alreadyRegistered = e is IllegalStateException && e.message?.contains("registered") == true
                listenerNodeId = if (alreadyRegistered) id else null
                if (!alreadyRegistered) EventLog.add("监听手环消息失败: ${describe(e)}")
                finishAttach(context)
            },
        )
    }

    private fun finishAttach(context: Context) {
        attaching = false
        if (pendingForcedAttach) {
            pendingForcedAttach = false
            ensureListener(context, force = true)
        }
    }

    private fun registerServiceListener(context: Context) {
        if (serviceListenerRegistered) return
        serviceListenerRegistered = true
        Wearable.getServiceApi(context).registerServiceConnectionListener(object : OnServiceConnectionListener {
            override fun onServiceConnected() {
                mainHandler.post {
                    EventLog.add("小米运动健康服务已连接")
                    nodeId = null
                    ensureListener(context, force = true)
                    // Do not rely on the band asking: re-send what it should be showing.
                    syncHandler?.invoke()
                }
            }

            override fun onServiceDisconnected() {
                mainHandler.post {
                    EventLog.add("小米运动健康服务断开")
                    nodeId = null
                }
            }
        })
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Completes exactly once: with the task result, or with a timeout after [TASK_TIMEOUT_MS]. */
    private fun <T> await(task: Task<T>, onSuccess: (T) -> Unit, onFailure: (Exception) -> Unit) {
        val done = AtomicBoolean(false)
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) onFailure(TimeoutException("小米运动健康无响应"))
        }
        mainHandler.postDelayed(timeout, TASK_TIMEOUT_MS)
        task
            .addOnSuccessListener { result ->
                if (done.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    onSuccess(result)
                }
            }
            .addOnFailureListener { e ->
                if (done.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    onFailure(e)
                }
            }
    }

    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"
}

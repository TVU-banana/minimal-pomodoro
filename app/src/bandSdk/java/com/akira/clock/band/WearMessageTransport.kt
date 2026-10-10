package com.akira.clock.band

import android.content.Context
import android.util.Log
import com.akira.clock.alarm.TimerCoordinator
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun createBandConnection(context: Context): BandConnection = WearMessageTransport(context)

/**
 * Official SDK 1.4 adapter, compiled only with -PbandSdk=true.
 *
 * Listener lifetime is the application process, not the settings view. It accepts only the
 * selected authorized node; SDK send success is never treated as a timer commit. Package/signing
 * and actual 9 Pro compatibility still require the documented device probe.
 */
internal class WearMessageTransport(context: Context) : BandConnection {
    private val context = context.applicationContext
    private val messages by lazy { Wearable.getMessageApi(this.context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var nodeId: String? = null
    override val available = true

    private val listener = OnMessageReceivedListener { receivedNode, bytes ->
        val selected = nodeId
        if (selected != null && receivedNode == selected && bytes.size in 1..4096) {
            // Copy callback-owned memory before crossing threads. Never log task names or payloads.
            val message = bytes.copyOf()
            scope.launch {
                try {
                    val reply = TimerCoordinator.get(this@WearMessageTransport.context)
                        .handleBandMessage(message)
                    if (nodeId == selected) messages.sendMessage(selected, reply).awaitResult()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("ClockBand", "Band request failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    override suspend fun connect(): Unit = mutex.withLock {
        val nodes = Wearable.getNodeApi(context).connectedNodes.awaitResult()
        require(nodes.size == 1) { "Expected exactly one paired band" }
        val selected = nodes.single().id
        val auth = Wearable.getAuthApi(context)
        if (!auth.checkPermission(selected, Permission.DEVICE_MANAGER).awaitResult()) {
            val granted = auth.requestPermission(selected, Permission.DEVICE_MANAGER).awaitResult()
            require(granted.contains(Permission.DEVICE_MANAGER)) { "Band permission denied" }
        }
        nodeId?.let { previous ->
            nodeId = null
            messages.removeListener(previous).awaitResult()
        }
        nodeId = selected
        try {
            messages.addListener(selected, listener).awaitResult()
        } catch (error: Throwable) {
            nodeId = null
            throw error
        }
    }
}

/** SDK tasks have no cancel hook; late completion must not resume an inactive continuation. */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}

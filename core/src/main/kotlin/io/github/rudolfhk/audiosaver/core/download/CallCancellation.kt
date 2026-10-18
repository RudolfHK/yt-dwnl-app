package io.github.rudolfhk.audiosaver.core.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call

/** The OkHttp calls belonging to one cancellable operation. Calls registered after cancellation die at once. */
class CallGroup {
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    fun register(call: Call) {
        val cancelNow = synchronized(this) { cancelled.also { if (!it) calls += call } }
        if (cancelNow) call.cancel()
    }

    fun unregister(call: Call) {
        synchronized(this) { calls -= call }
    }

    fun cancelAll() {
        val toCancel = synchronized(this) {
            cancelled = true
            calls.toList().also { calls.clear() }
        }
        toCancel.forEach(Call::cancel)
    }
}

/**
 * Runs blocking OkHttp work so that cancelling the coroutine aborts the calls in [group] *immediately*,
 * even while the thread is stuck in a socket read. (Job.invokeOnCompletion would only fire after the
 * blocked child returned, and OkHttp doesn't reliably react to thread interrupts.) Must be called from
 * a dispatcher with more than one thread, e.g. Dispatchers.IO, so the watcher can run.
 */
suspend fun <T> cancelOnCancellation(group: CallGroup, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            group.cancelAll()
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}

suspend fun <T> cancelOnCancellation(call: Call, block: suspend () -> T): T =
    cancelOnCancellation(CallGroup().apply { register(call) }, block)

package com.jev.probe.jev

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** One analysis owns its connections; cancelling never disconnects another round. */
class RequestCancellation {
    @Volatile var cancelled = false
        private set
    private val connections = mutableSetOf<HttpURLConnection>()

    @Synchronized fun attach(connection: HttpURLConnection) {
        if (cancelled) throw CancellationException("本次分析已停止")
        connections.add(connection)
    }

    @Synchronized fun detach(connection: HttpURLConnection) { connections.remove(connection) }

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            connections.toList().also { connections.clear() }
        }
        // Some Android network stacks block inside disconnect; keep it off the UI thread.
        active.forEach { connection -> disconnectWorker.execute { runCatching { connection.disconnect() } } }
    }

    companion object {
        private val disconnectWorker = Executors.newCachedThreadPool { task ->
            Thread(task, "analysis-disconnect").apply { isDaemon = true }
        }
    }
}

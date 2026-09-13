package com.omnihub.source.core

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * One transaction owns a provider WebView at a time.
 * A request never waits indefinitely behind another transaction.
 */
object ProviderRelayQueue {
    private const val TAG = "WEB_QUEUE"
    private const val MAX_QUEUE_WAIT_MS = 1_500L
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun mutex(providerId: String): Mutex =
        locks.getOrPut(providerId) { Mutex() }

    suspend fun <T> withProviderLock(providerId: String, block: suspend (queueWaitMs: Long) -> T): T {
        val m = mutex(providerId)
        val start = System.currentTimeMillis()
        if (m.isLocked) Log.i(TAG, "[$providerId] QUEUED")
        return withTimeout(MAX_QUEUE_WAIT_MS) {
            m.withLock {
                val waitMs = System.currentTimeMillis() - start
                if (waitMs > 30) Log.i(TAG, "[$providerId] DEQUEUED after ${waitMs}ms")
                block(waitMs)
            }
        }
    }
}

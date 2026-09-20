package io.github.ksaye.tabloauto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The channel list, kept somewhere the car can read it instantly.
 *
 * Browsing in a car is not a moment to wait on a request over a cell connection, and a list that
 * arrives after the screen has been drawn is worse than a slightly stale one. So the last list is
 * written to disk and served immediately, then refreshed in the background. If the refresh fails —
 * no signal, server off, sign-in expired — the cached list still browses and the channels still
 * play, because playing is a different request that may well succeed.
 *
 * "What is on now" ages, which is the only reason there is a TTL at all; the line-up itself
 * changes about never.
 */
class ChannelRepository(private val context: Context, private val client: TabloClient) {

    private val cacheFile = File(context.filesDir, "channels.json")
    private val lock = Mutex()

    @Volatile
    private var memory: List<Channel> = emptyList()

    @Volatile
    private var fetchedAtMs: Long = 0L

    /**
     * The channel list. Returns the cached one when it is fresh enough, and — importantly — when
     * a refresh fails.
     */
    suspend fun channels(forceRefresh: Boolean = false): List<Channel> = lock.withLock {
        if (memory.isEmpty()) memory = readCache()

        val age = System.currentTimeMillis() - fetchedAtMs
        val fresh = memory.isNotEmpty() && age < TTL_MS
        if (fresh && !forceRefresh) return@withLock memory

        try {
            val fetched = client.channels()
            if (fetched.isNotEmpty()) {
                memory = fetched
                fetchedAtMs = System.currentTimeMillis()
                writeCache(fetched)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh the channel list (${e.message}); using the cached one")
        }
        memory
    }

    fun find(channelId: String): Channel? = memory.firstOrNull { it.id == channelId }

    private suspend fun readCache(): List<Channel> = withContext(Dispatchers.IO) {
        try {
            if (cacheFile.exists()) Channel.fromJson(cacheFile.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun writeCache(channels: List<Channel>) = withContext(Dispatchers.IO) {
        try {
            cacheFile.writeText(Channel.toJson(channels))
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the channel cache: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "TabloRepo"
        const val TTL_MS = 5 * 60 * 1000L
    }
}

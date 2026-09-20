package io.github.ksaye.tabloauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps playing across a dead spot.
 *
 * A car drives out of coverage mid-sentence and back into it a mile later, and the driver should
 * not have to touch anything. Three things can happen in between and all of them are handled here:
 *
 *  - **The player gives up.** A load fails often enough and ExoPlayer raises an error and stops.
 *    We prepare it again, backing off 1s, 2s, 4s… to 30s so a long tunnel does not turn into a
 *    thousand pointless requests.
 *  - **The player hangs instead.** A stream whose server-side session was reaped while the phone
 *    was offline can leave the player buffering forever rather than erroring, so a watchdog looks
 *    for a position that has stopped advancing and forces the same recovery.
 *  - **Signal comes back during a backoff.** Sitting out the rest of a 30-second wait when the
 *    network is demonstrably up is just silence for no reason, so a network callback retries at
 *    once.
 *
 * Recovery is always the same move: prepare the current media item again. That works because the
 * item's URL is the server's stable per-channel address (`/api/audio/channel/…`) rather than a
 * particular transcode session — the session we were playing is certainly gone by now, and asking
 * the stable address for it again produces a new one, at the live edge.
 */
class Reconnector(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope
) : Player.Listener {

    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    private var attempt = 0

    /** The next time playback becomes ready, jump to the live edge rather than start where told. */
    private var wantsLiveEdge = true

    /** True while *we* are seeking, so the driver's own seek can be told apart from ours. */
    private var seekingToEdge = false

    private var lastPositionMs = C.TIME_UNSET
    private var lastProgressAt = SystemClock.elapsedRealtime()

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val broken = player.playerError != null ||
                player.playbackState == Player.STATE_IDLE ||
                retryJob?.isActive == true
            if (broken && player.playWhenReady) {
                Log.i(TAG, "Network is back; reconnecting now")
                scope.launch { retryNow() }
            }
        }
    }

    fun start() {
        player.addListener(this)
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the network: ${e.message}")
        }
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                checkForStall()
            }
        }
    }

    fun stop() {
        retryJob?.cancel()
        watchdogJob?.cancel()
        player.removeListener(this)
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Never registered, or already gone.
        }
    }

    /** A channel has just been chosen: start at the live edge, not wherever the player was. */
    fun expectLiveEdge() {
        wantsLiveEdge = true
    }

    // ---------------------------------------------------------------- listening

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
        if (player.playWhenReady) scheduleRetry(error.errorCodeName)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            attempt = 0
            retryJob?.cancel()
            noteProgress()
            jumpToLiveEdgeIfWanted()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Changing channel — including by pressing next on the wheel — starts at the live edge.
        wantsLiveEdge = true
        noteProgress()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        // Somebody deliberately went back into the buffer: leave them there rather than yanking
        // the stream forward to live the moment it becomes ready again.
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !seekingToEdge) wantsLiveEdge = false
        noteProgress()
    }

    // ---------------------------------------------------------------- recovery

    private fun checkForStall() {
        val now = SystemClock.elapsedRealtime()
        val position = player.currentPosition
        if (position != lastPositionMs) {
            lastPositionMs = position
            lastProgressAt = now
            return
        }

        // Paused on purpose is not a stall.
        if (!player.playWhenReady) {
            lastProgressAt = now
            return
        }

        if (player.playbackState == Player.STATE_ENDED) {
            // A live stream does not end; the session underneath it was taken away.
            if (retryJob?.isActive != true) scheduleRetry("the stream ended")
            return
        }

        if (player.playbackState == Player.STATE_READY && player.isPlaying) return

        if (now - lastProgressAt > STALL_LIMIT_MS && retryJob?.isActive != true) {
            scheduleRetry("no progress for ${STALL_LIMIT_MS / 1000}s")
        }
    }

    private fun noteProgress() {
        lastPositionMs = player.currentPosition
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun scheduleRetry(why: String) {
        retryJob?.cancel()
        val wait = BACKOFF_MS[min(attempt, BACKOFF_MS.lastIndex)]
        attempt++
        Log.i(TAG, "Reconnecting in ${wait}ms — $why (attempt $attempt)")
        retryJob = scope.launch {
            delay(wait)
            if (!online()) {
                scheduleRetry("still no network")
                return@launch
            }
            reprepare()
        }
    }

    private suspend fun retryNow() {
        retryJob?.cancel()
        attempt = 0
        reprepare()
    }

    private fun reprepare() {
        if (player.mediaItemCount == 0) return
        Log.i(TAG, "Preparing ${player.currentMediaItem?.mediaId} again")
        wantsLiveEdge = true
        noteProgress()
        // Back to the start of whatever window the new session hands us; the old position belongs
        // to a timeline that no longer exists.
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    private fun jumpToLiveEdgeIfWanted() {
        if (!wantsLiveEdge) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return

        // Deliberately a little way behind the edge rather than on it: those few seconds are
        // already downloaded, so a short dropout is covered by the buffer instead of a silence.
        val target = max(0L, duration - EDGE_BACK_MS)
        if (target > player.currentPosition + EDGE_TOLERANCE_MS) {
            seekingToEdge = true
            player.seekTo(target)
            seekingToEdge = false
        }
        wantsLiveEdge = false
    }

    private fun online(): Boolean = try {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true // If we cannot tell, try anyway: a failed request is cheaper than a wrong "offline".
    }

    private companion object {
        const val TAG = "TabloReconnect"
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val STALL_LIMIT_MS = 40_000L
        const val EDGE_BACK_MS = 24_000L
        const val EDGE_TOLERANCE_MS = 30_000L
    }
}

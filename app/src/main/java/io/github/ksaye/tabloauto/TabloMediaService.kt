package io.github.ksaye.tabloauto

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * The app, as far as a car is concerned.
 *
 * Android Auto talks to a media service and nothing else: it browses a tree of items, plays the
 * one that is tapped, and draws its own screens. So everything the driver sees lives here, and the
 * phone activity is only somewhere to type an address and sign in.
 *
 * Two decisions shape the rest:
 *
 *  - **A channel's URL is stable.** Items point at `/api/audio/channel/{id}.m3u8`, which joins or
 *    starts a transcode session on the server and redirects to it. Nothing here has to know a
 *    session id, which is what makes reconnecting after a dead spot a simple `prepare()` — see
 *    [Reconnector].
 *  - **The queue is the channel group.** Tapping one channel loads all of its neighbours around
 *    it, so "next" on the steering wheel is channel up. It is the one control worth having while
 *    driving, and it costs nothing: only the current item is ever fetched.
 */
@OptIn(UnstableApi::class)
class TabloMediaService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var client: TabloClient
    private lateinit var repo: ChannelRepository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var reconnector: Reconnector

    override fun onCreate() {
        super.onCreate()
        client = TabloClient(this)
        repo = ChannelRepository(this, client)

        // The player fetches through the same OkHttp client as the API calls, so the sign-in
        // cookie is attached in exactly one place.
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(client.http))
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
            // A phone on a weak cell drops requests that a desktop never would. Retrying a load
            // several times inside the player is far cheaper than letting it fail and rebuilding
            // the whole stream from the server.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 120_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 5_000
                    )
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Unplugging the car should pause, not blare out of the phone speaker.
            .setHandleAudioBecomingNoisy(true)
            // The stream is a network one; keep the radio alive with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openAppIntent())
            .build()

        reconnector = Reconnector(this, player, scope)
        reconnector.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the phone app away should not stop the car. Only shut down when nothing is
        // actually playing.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        reconnector.stop()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ------------------------------------------------------------------ the browse tree

    private fun rootItem(): MediaItem = browsableItem(ROOT_ID, getString(R.string.app_name))

    private fun browsableItem(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    /**
     * One channel, labelled by what is on it.
     *
     * The programme is the title and the channel is the line underneath, because that is what a
     * driver is choosing between — a screen full of call signs asks them to remember what is on
     * each one. Where the guide knows nothing, the channel becomes the title instead.
     */
    private fun channelItem(channel: Channel, playable: Boolean): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.displayTitle)
            .setSubtitle(channel.displaySubtitle)
            .setArtist(channel.displaySubtitle)
            .setStation(channel.label)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(channelMediaId(channel))
            .setMediaMetadata(metadata)

        if (playable) {
            builder.setUri(client.audioUrl(channel))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    private fun childrenOf(parentId: String, channels: List<Channel>): List<MediaItem> {
        val antenna = channels.filter { !it.isFast }
        val fast = channels.filter { it.isFast }

        return when {
            parentId == ROOT_ID -> when {
                // Only one kind of channel: skip the menu and list them, so the driver's first
                // tap is a channel rather than a folder.
                antenna.isEmpty() -> fast.map { channelItem(it, playable = false) }
                fast.isEmpty() -> antenna.map { channelItem(it, playable = false) }
                else -> listOf(
                    browsableItem(GROUP_ANTENNA, getString(R.string.channel_group_antenna)),
                    browsableItem(GROUP_FAST, getString(R.string.channel_group_fast))
                )
            }
            parentId == GROUP_ANTENNA -> antenna.map { channelItem(it, playable = false) }
            parentId == GROUP_FAST -> fast.map { channelItem(it, playable = false) }
            else -> emptyList()
        }
    }

    /** Ask Android Auto for plain lists. A grid of identical placeholder tiles helps nobody. */
    private fun browseHints(params: LibraryParams?): LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY)
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        return LibraryParams.Builder().setExtras(extras).build()
    }

    // ------------------------------------------------------------------ session callbacks

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), browseHints(params)))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val channels = repo.channels()
            if (channels.isEmpty()) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            } else {
                LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId, channels)), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val channel = channelFor(mediaId, repo.channels())
            if (channel == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(channelItem(channel, playable = false), null)
        }

        /** A channel was tapped, or asked for by voice. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            queueFor(mediaItems, startIndex)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            queueFor(mediaItems, C.INDEX_UNSET).mediaItems.toMutableList()
        }

        /**
         * The car has just been started and something pressed play without choosing anything, or
         * the system is filling in what would play if it were. Either way the answer is the
         * channel that was on last.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val channels = repo.channels()
            val last = Settings.lastChannel(this@TabloMediaService)
                ?.let { id -> channels.firstOrNull { it.id == id } }
                ?: channels.firstOrNull()
            if (last == null) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
            } else {
                queueFor(mutableListOf(channelItem(last, playable = false)), 0)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val hits = repo.channels().count { it.matches(query) }
            session.notifySearchResultChanged(browser, query, hits, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val hits = repo.channels().filter { it.matches(query) }
            LibraryResult.ofItemList(
                ImmutableList.copyOf(hits.map { channelItem(it, playable = false) }),
                params
            )
        }
    }

    /**
     * Turn whatever was asked for into a queue.
     *
     * The requested item usually arrives as a media id and nothing else — the browse tree does not
     * carry URLs — so this is where a channel becomes something playable. The whole of that
     * channel's group goes into the queue so next and previous change channel, and only the
     * current item is ever fetched, so the other hundred cost nothing.
     */
    private suspend fun queueFor(
        requested: List<MediaItem>,
        startIndex: Int
    ): MediaSession.MediaItemsWithStartPosition {
        val channels = repo.channels()
        if (channels.isEmpty()) {
            Log.w(TAG, "Nothing to play: no channel list yet")
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }

        val askedFor = requested.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            ?: requested.firstOrNull()
        val wanted = askedFor?.let { channelFor(it.mediaId, channels) }
            // A spoken request can arrive as a search string rather than an id.
            ?: askedFor?.requestMetadata?.searchQuery?.let { q -> channels.firstOrNull { it.matches(q) } }
            ?: channels.first()

        val group = channels.filter { it.isFast == wanted.isFast }
        val index = group.indexOfFirst { it.id == wanted.id }.coerceAtLeast(0)

        Settings.setLastChannel(this, wanted.id)
        reconnector.expectLiveEdge()

        return MediaSession.MediaItemsWithStartPosition(
            group.map { channelItem(it, playable = true) },
            index,
            C.TIME_UNSET
        )
    }

    private fun channelFor(mediaId: String, channels: List<Channel>): Channel? {
        if (!mediaId.startsWith(CHANNEL_PREFIX)) return null
        val id = mediaId.removePrefix(CHANNEL_PREFIX)
        return channels.firstOrNull { it.id == id }
    }

    private fun channelMediaId(channel: Channel) = "$CHANNEL_PREFIX${channel.id}"

    private companion object {
        const val TAG = "TabloMedia"
        const val ROOT_ID = "root"
        const val GROUP_ANTENNA = "group/antenna"
        const val GROUP_FAST = "group/fast"
        const val CHANNEL_PREFIX = "channel/"
        const val LOAD_RETRIES = 6
    }
}

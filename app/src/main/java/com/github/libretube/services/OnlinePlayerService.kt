package com.github.libretube.services

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.C.WAKE_MODE_NETWORK
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.github.libretube.LibreTubeApp.Companion.PLAYER_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.StreamsExtractor
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.enums.NotificationId
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.checkForSegments
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.PlayerNotificationData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PauseableTimer
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlinePlayerService : AbstractPlayerService() {
    /**
     * PlaylistId/ChannelId for autoplay
     */
    private var playlistId: String? = null
    private var channelId: String? = null
    private var startTimestamp: Long? = null

    /**
     * The response that gets when called the Api.
     */
    var streams: Streams? = null
        private set

    /**
     * SponsorBlock Segment data
     */
    private var segments = listOf<Segment>()
    private var sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()

    /**
     * Used for connecting to the AudioPlayerFragment
     */
    private val binder = LocalBinder()

    /**
     * Listener for passing playback state changes to the AudioPlayerFragment
     */
    var onStateOrPlayingChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onNewVideo: ((streams: Streams, videoId: String) -> Unit)? = null

    override suspend fun onServiceCreated(intent: Intent) {
        // reset the playing queue listeners
        PlayingQueue.resetToDefaults()

        val playerData = intent.parcelableExtra<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }

        // get the intent arguments
        videoId = playerData.videoId
        playlistId = playerData.playlistId
        startTimestamp = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        PlayingQueue.setOnQueueTapListener { streamItem ->
            streamItem.url?.toID()?.let { playNextVideo(it) }
        }
    }

    override suspend fun startPlaybackAndUpdateNotification() {
        val timestamp = startTimestamp ?: 0L
        startTimestamp = null

        isTransitioning = true

        streams = withContext(Dispatchers.IO) {
            try {
                StreamsExtractor.extractStreams(videoId)
            } catch (e: Exception) {
                val errorMessage = StreamsExtractor.getExtractorErrorMessageString(this@OnlinePlayerService, e)
                this@OnlinePlayerService.toastFromMainDispatcher(errorMessage)
                return@withContext null
            }
        } ?: return

        if (PlayingQueue.isEmpty()) {
            PlayingQueue.updateQueue(
                streams!!.toStreamItem(videoId),
                playlistId,
                channelId,
                streams!!.relatedStreams
            )
        } else if (PlayingQueue.isLast() && playlistId == null && channelId == null) {
            PlayingQueue.insertRelatedStreams(streams!!.relatedStreams)
        }

        // save the current stream to the queue
        streams?.toStreamItem(videoId)?.let {
            PlayingQueue.updateCurrent(it)
        }

        withContext(Dispatchers.Main) {
            playAudio(timestamp)
        }
    }

    private fun playAudio(seekToPosition: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            setMediaItem()

            withContext(Dispatchers.Main) {
                // seek to the previous position if available
                if (seekToPosition != 0L) {
                    player?.seekTo(seekToPosition)
                } else if (PlayerHelper.watchPositionsAudio) {
                    PlayerHelper.getStoredWatchPosition(videoId, streams?.duration)?.let {
                        player?.seekTo(it)
                    }
                }
            }
        }

        val playerNotificationData = PlayerNotificationData(
            streams?.title,
            streams?.uploader,
            streams?.thumbnailUrl
        )
        nowPlayingNotification?.updatePlayerNotification(videoId, playerNotificationData)
        streams?.let { onNewVideo?.invoke(it, videoId) }

        player?.apply {
            playWhenReady = PlayerHelper.playAutomatically
            prepare()
        }

        if (PlayerHelper.sponsorBlockEnabled) fetchSponsorBlockSegments()
    }

    /**
     * Plays the next video from the queue
     */
    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null && PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
            player?.seekTo(0)
            return
        }

        saveWatchPosition()

        if (!PlayerHelper.isAutoPlayEnabled(playlistId != null) && nextId == null) return

        val nextVideo = nextId ?: PlayingQueue.getNext() ?: return

        // play new video on background
        this.videoId = nextVideo
        this.streams = null
        this.segments = emptyList()

        lifecycleScope.launch {
            startPlaybackAndUpdateNotification()
        }
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [player]
     */
    private suspend fun setMediaItem() {
        val streams = streams ?: return

        val (uri, mimeType) =
            if (!PlayerHelper.useHlsOverDash && streams.audioStreams.isNotEmpty()) {
                PlayerHelper.createDashSource(streams, this) to MimeTypes.APPLICATION_MPD
            } else {
                ProxyHelper.unwrapStreamUrl(streams.hls.orEmpty())
                    .toUri() to MimeTypes.APPLICATION_M3U8
            }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setMetadata(streams)
            .build()
        withContext(Dispatchers.Main) { player?.setMediaItem(mediaItem) }
    }

    /**
     * fetch the segments for SponsorBlock
     */
    private fun fetchSponsorBlockSegments() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (sponsorBlockConfig.isEmpty()) return@runCatching
                segments = RetrofitInstance.api.getSegments(
                    videoId,
                    JsonHelper.json.encodeToString(sponsorBlockConfig.keys)
                ).segments
                checkForSegments()
            }
        }
    }

    /**
     * check for SponsorBlock segments
     */
    private fun checkForSegments() {
        handler.postDelayed(this::checkForSegments, 100)

        player?.checkForSegments(this, segments, sponsorBlockConfig)
    }

    inner class LocalBinder : Binder() {
        // Return this instance of [BackgroundMode] so clients can call public methods
        fun getService(): OnlinePlayerService = this@OnlinePlayerService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        onStateOrPlayingChanged?.invoke(player?.isPlaying ?: false)

        when (playbackState) {
            Player.STATE_ENDED -> {
                if (!isTransitioning) playNextVideo()
            }

            Player.STATE_IDLE -> {
                onDestroy()
            }

            Player.STATE_BUFFERING -> {}
            Player.STATE_READY -> {
                isTransitioning = false

                // save video to watch history when the video starts playing or is being resumed
                // waiting for the player to be ready since the video can't be claimed to be watched
                // while it did not yet start actually, but did buffer only so far
                lifecycleScope.launch(Dispatchers.IO) {
                    streams?.let { DatabaseHelper.addToWatchHistory(videoId, it) }
                }
            }
        }
    }
}

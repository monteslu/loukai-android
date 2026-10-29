package com.loukai.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * A Media3 Player over [AudioEngine], so the media session (notification,
 * lock screen, Bluetooth buttons and Android Auto) drives our own mixer. The
 * playlist is MediaItems whose mediaId is a [Library] song id.
 */
@OptIn(UnstableApi::class)
class KaraokePlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private class Loaded(
        val engine: AudioEngine,
        val title: String,
        val artist: String,
        val lyrics: List<LyricLine>,
        val cdg: CdgDecoder?,
    )

    private val loader = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val mainExecutor = context.mainExecutor
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var engine: AudioEngine? = null
    private var loadToken = 0
    private var resumeOnFocusGain = false

    /** Called when the vocals state changes so the session can refresh its button. */
    var onVocalsChanged: () -> Unit = {}

    var vocalsOn: Boolean
        get() = NowPlaying.vocalsOn
        set(value) {
            NowPlaying.vocalsOn = value
            engine?.vocalsOn = value
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("vocals", value).apply()
            NowPlaying.changed()
            onVocalsChanged()
        }

    init {
        NowPlaying.vocalsOn = context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("vocals", false)
    }

    override fun getState(): State {
        val current = engine
        val playlist = items.mapIndexed { i, item ->
            val data = MediaItemData.Builder(item.mediaId).setMediaItem(item)
            if (i == index && current != null && current.durationUs > 0) {
                data.setDurationUs(current.durationUs)
                if (NowPlaying.title.isNotEmpty()) {
                    data.setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setTitle(NowPlaying.title)
                            .setArtist(NowPlaying.artist)
                            .build()
                    )
                }
            }
            data.build()
        }
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
        ).build()
        val state = if (items.isEmpty()) {
            if (playbackState == STATE_ENDED) STATE_ENDED else STATE_IDLE
        } else {
            playbackState
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (items.isEmpty()) 0 else index)
            .setContentPositionMs { (engine?.positionUs ?: 0L) / 1000 }
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, items.size - 1))
        val pos = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        return loadCurrent(pos)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val list = items.toMutableList()
        list.addAll(index.coerceIn(0, list.size), mediaItems)
        if (items.isNotEmpty() && index <= this.index) this.index += mediaItems.size
        val wasEmpty = items.isEmpty()
        items = list
        return if (wasEmpty) loadCurrent(0) else Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val list = items.toMutableList()
        val removingCurrent = index in fromIndex until toIndex
        list.subList(fromIndex, toIndex).clear()
        items = list
        if (index >= toIndex) index -= (toIndex - fromIndex)
        if (items.isEmpty()) {
            unload()
            playbackState = STATE_IDLE
            return Futures.immediateVoidFuture()
        }
        if (removingCurrent) {
            index = index.coerceAtMost(items.size - 1)
            return loadCurrent(0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playbackState == STATE_IDLE && items.isNotEmpty() && engine == null) return loadCurrent(0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        val e = engine
        if (playWhenReady) {
            if (playbackState == STATE_ENDED && e != null) {
                e.seek(0)
                playbackState = STATE_READY
            }
            if (requestFocus()) {
                registerNoisy()
                e?.play()
            }
        } else {
            e?.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = if (positionMs == C.TIME_UNSET) 0L else positionMs
        if (mediaItemIndex != index && mediaItemIndex in items.indices) {
            index = mediaItemIndex
            return loadCurrent(pos)
        }
        engine?.seek(pos * 1000)
        if (playbackState == STATE_ENDED) playbackState = STATE_READY
        if (playWhenReady) engine?.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine?.pause()
        playWhenReady = false
        playbackState = STATE_IDLE
        unload()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        unload()
        loader.shutdown()
        abandonFocus()
        return Futures.immediateVoidFuture()
    }

    fun toggleVocals() {
        vocalsOn = !vocalsOn
    }

    private fun unload() {
        loadToken++
        engine?.release()
        engine = null
        NowPlaying.engine = null
        NowPlaying.lyrics = emptyList()
        NowPlaying.cdg = null
        NowPlaying.changed()
    }

    /** Load items[index] on the loader thread, then swap it in on the main thread. */
    private fun loadCurrent(startMs: Long): ListenableFuture<*> {
        unload()
        val item = items.getOrNull(index) ?: return Futures.immediateVoidFuture()
        val song = Library.get(item.mediaId) ?: return Futures.immediateVoidFuture()
        val token = loadToken
        playbackState = STATE_BUFFERING
        val vocals = vocalsOn
        val work = loader.submit<Loaded?> {
            runCatching { loadSong(song, startMs, vocals) }.getOrNull()
        }
        return Futures.transform(work, { loaded ->
            if (token != loadToken) {
                loaded?.engine?.release()
                return@transform null
            }
            if (loaded == null) {
                // Unplayable file: move on rather than stall the queue.
                if (index < items.size - 1) {
                    index++
                    mainExecutor.execute {
                        loadCurrent(0)
                        invalidateState()
                    }
                } else {
                    playbackState = STATE_ENDED
                }
                return@transform null
            }
            engine = loaded.engine
            NowPlaying.engine = loaded.engine
            NowPlaying.title = loaded.title
            NowPlaying.artist = loaded.artist
            NowPlaying.lyrics = loaded.lyrics
            NowPlaying.cdg = loaded.cdg
            NowPlaying.hasVocalStem = loaded.engine.hasVocalStem
            NowPlaying.changed()
            playbackState = STATE_READY
            if (playWhenReady && requestFocus()) {
                registerNoisy()
                loaded.engine.play()
            }
            null
        }, mainExecutor)
    }

    private fun loadSong(song: Song, startMs: Long, vocals: Boolean): Loaded {
        val engine = AudioEngine(context) { onSongEnded() }
        engine.vocalsOn = vocals
        return when (song.type) {
            SongType.STEMS -> {
                val meta = Mp4Reader.read(context, song.uri)
                val lyrics = meta.kara?.let(Kara::parse) ?: emptyList()
                engine.punchWindows = lyrics.filter { it.isPunchthrough }
                    .flatMap { listOf((it.start * 1e6).toLong(), (it.end * 1e6).toLong()) }
                    .toLongArray()
                // No stem atom but a kara atom still means a Loukai file: assume NI order.
                val stemNames = meta.stemNames.ifEmpty { if (meta.kara != null) emptyList() else null }
                engine.load(song.uri, stemNames, startMs * 1000)
                Loaded(
                    engine,
                    meta.title?.takeIf { it.isNotBlank() } ?: song.title,
                    meta.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                    lyrics,
                    null,
                )
            }
            SongType.CDG -> {
                val cdgBytes = context.contentResolver.openInputStream(song.cdgUri!!)!!.use { it.readBytes() }
                engine.load(song.uri, null, startMs * 1000)
                Loaded(engine, song.title, song.artist, emptyList(), CdgDecoder(cdgBytes))
            }
        }
    }

    private fun onSongEnded() {
        if (index < items.size - 1) {
            index++
            loadCurrent(0)
        } else {
            playbackState = STATE_ENDED
            playWhenReady = false
            engine?.pause()
            NowPlaying.changed()
        }
        invalidateState()
    }

    // --- audio focus and headphone unplug ---------------------------------

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocusGain = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocusGain = playWhenReady
                    pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnFocusGain) play()
                    resumeOnFocusGain = false
                }
            }
        }
        .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        unregisterNoisy()
    }

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        context.registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisyReceiver)
        noisyRegistered = false
    }

    companion object {
        fun songItem(song: Song): MediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }
}

package com.loukai.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The media session everything talks to: the phone UI, the notification, and
 * Android Auto. Android Auto browses root -> A..Z folders -> songs, can search
 * by voice, and shows a Vocals button that toggles the vocal stem.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    companion object {
        const val ACTION_TOGGLE_VOCALS = "com.loukai.player.TOGGLE_VOCALS"
        val TOGGLE_VOCALS = SessionCommand(ACTION_TOGGLE_VOCALS, Bundle.EMPTY)
        private const val ROOT = "root"
        private const val LETTER = "letter:"
    }

    private lateinit var player: KaraokePlayer
    private var session: MediaLibrarySession? = null
    private var lastSearch: List<Song> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Library.load(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        player = KaraokePlayer(this)
        player.onVocalsChanged = { session?.setMediaButtonPreferences(buttons()) }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons())
            .build()
    }

    private fun buttons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (player.vocalsOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
            .setDisplayName(if (player.vocalsOn) "Vocals on" else "Vocals off")
            .setSessionCommand(TOGGLE_VOCALS)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
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

    private fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return list
        val from = page * pageSize
        if (from >= list.size) return emptyList()
        return list.subList(from, minOf(list.size, from + pageSize))
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon().add(TOGGLE_VOCALS).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_VOCALS) {
                player.toggleVocals()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT, "Loukai"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Library.load(this@PlaybackService)
            val children: List<MediaItem> = when {
                parentId == ROOT -> {
                    if (Library.songs.isEmpty()) {
                        listOf(folderItem("empty", "Pick a folder in Loukai on your phone"))
                    } else {
                        Library.songs.map { it.letter }.distinct().sorted()
                            .map { folderItem(LETTER + it, it) }
                    }
                }
                parentId.startsWith(LETTER) -> {
                    val letter = parentId.removePrefix(LETTER)
                    Library.songs.filter { it.letter == letter }.map(KaraokePlayer::songItem)
                }
                else -> emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(page(children, page, pageSize)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val song = Library.get(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(KaraokePlayer.songItem(song), null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            lastSearch = Library.search(query)
            session.notifySearchResultChanged(browser, query, lastSearch.size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val results = Library.search(query).map(KaraokePlayer::songItem)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(page(results, page, pageSize)), params)
            )
        }

        /** Resolve ids (or a voice search query) to full song items. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            Library.load(this@PlaybackService)
            val resolved = mediaItems.mapNotNull { item ->
                val song = Library.get(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { Library.search(it).firstOrNull() }
                song?.let(KaraokePlayer::songItem)
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        /**
         * Picking one song queues the whole library from that song on, so
         * next/previous keep working from the steering wheel.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Library.load(this@PlaybackService)
            if (mediaItems.size == 1) {
                val item = mediaItems[0]
                val song = Library.get(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { Library.search(it).firstOrNull() }
                val songs = Library.songs
                val at = song?.let { s -> songs.indexOfFirst { it.id == s.id } } ?: -1
                if (at >= 0) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            songs.map(KaraokePlayer::songItem), at, startPositionMs,
                        )
                    )
                }
            }
            val resolved = mediaItems.mapNotNull { Library.get(it.mediaId)?.let(KaraokePlayer::songItem) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }
    }
}

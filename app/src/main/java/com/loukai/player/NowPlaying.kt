package com.loukai.player

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the lyric/CDG screens draw: the loaded song's lyrics or CDG stream and
 * its audio clock. Everything here lives in the app process, so the phone UI
 * and the car's lyrics screen read it directly instead of going through the
 * media session (which only carries metadata and transport state).
 */
object NowPlaying {
    @Volatile var title: String = ""
    @Volatile var artist: String = ""
    @Volatile var lyrics: List<LyricLine> = emptyList()
    @Volatile var cdg: CdgDecoder? = null
    @Volatile var engine: AudioEngine? = null
    @Volatile var vocalsOn: Boolean = false
    @Volatile var hasVocalStem: Boolean = false

    /** Fired on the main thread when the song or the vocals state changes. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    val positionSec: Double get() = (engine?.positionUs ?: 0L) / 1_000_000.0
    val durationSec: Double get() = (engine?.durationUs ?: 0L) / 1_000_000.0
    val isPlaying: Boolean get() = engine?.isPlaying == true

    fun changed() = listeners.forEach { it() }
}

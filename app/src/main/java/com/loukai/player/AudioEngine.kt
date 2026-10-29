package com.loukai.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder

/**
 * Decodes one song's audio tracks in lockstep and mixes them into a single
 * AudioTrack. For a stem file that is drums/bass/other/vocals (the master track
 * is skipped, it already contains the vocals), and turning vocals off is a gain
 * ramp on the vocal stem, the same idea as kaiPlayer.js. For an mp3 it is one
 * track with nothing to toggle.
 *
 * One engine per loaded song; release() it when switching.
 */
class AudioEngine(
    private val context: Context,
    private val onEnded: () -> Unit,
) {
    private class Track(val extractor: MediaExtractor, val codec: MediaCodec, val isVocals: Boolean) {
        var channels = 2
        var inputDone = false
        var outputDone = false
        var skipUntilUs = 0L
        var fifo = FloatArray(16384) // interleaved stereo
        var fifoLen = 0
        val frames get() = fifoLen / 2
    }

    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    private val info = MediaCodec.BufferInfo()
    private var tracks: List<Track> = emptyList()
    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null
    private var running = false
    @Volatile private var playing = false
    val isPlaying: Boolean get() = playing
    private var ended = false
    private var generation = 0

    private var sampleRate = 44100
    @Volatile var durationUs = 0L
        private set
    @Volatile var hasVocalStem = false
        private set

    /** Target state of the vocal stem; the mix ramps to it over 50 ms. */
    @Volatile var vocalsOn = false
    private var vocalGain = 0f

    /** backup:PA windows in microseconds, [start0, end0, start1, end1, ...]. */
    @Volatile var punchWindows = LongArray(0)

    // Position bookkeeping, read without the lock by UI and car render threads.
    @Volatile private var basePositionUs = 0L
    @Volatile private var headBase = 0L
    @Volatile private var framesWritten = 0L
    private var framesMixed = 0L
    private var dump: java.io.OutputStream? = null

    val positionUs: Long
        get() {
            val at = audioTrack ?: return basePositionUs
            val head = at.playbackHeadPosition.toLong() and 0xffffffffL
            val played = (head - headBase).coerceIn(0, framesWritten)
            return minOf(basePositionUs + played * 1_000_000 / sampleRate, durationUs)
        }

    /**
     * Open [uri] and get ready to play from [startUs]. [stemNames] are the NI
     * Stems names for tracks 1..n; when there are no stems the first audio
     * track plays alone. Blocking; call off the main thread.
     */
    fun load(uri: Uri, stemNames: List<String>?, startUs: Long) {
        val probe = MediaExtractor().apply { setDataSource(context, uri, null) }
        val audioIdx = (0 until probe.trackCount).filter {
            probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        probe.release()
        if (audioIdx.isEmpty()) throw IllegalStateException("No audio tracks")

        // Stem files: track 0 is the master mix, tracks 1..n are stems[0..n-1].
        val useStems = stemNames != null && audioIdx.size >= 2
        val picks = if (useStems) audioIdx.drop(1) else listOf(audioIdx[0])
        val vocalPos = if (useStems) findVocalStem(stemNames!!, picks.size) else -1

        tracks = picks.mapIndexed { i, idx ->
            val ex = MediaExtractor().apply { setDataSource(context, uri, null); selectTrack(idx) }
            val fmt = ex.getTrackFormat(idx)
            if (i == 0) {
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
            }
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            Track(ex, codec, i == vocalPos).apply {
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        hasVocalStem = vocalPos >= 0
        // Debug builds: `adb shell run-as com.loukai.player touch files/dump` writes the
        // mixed output as raw 16-bit stereo PCM to files/mix-<song>.pcm for checking the mix.
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            java.io.File(context.filesDir, "dump").exists()
        ) {
            val name = "mix-" + (uri.lastPathSegment ?: "song").substringAfterLast('/').replace(Regex("[^A-Za-z0-9.]+"), "_") + ".pcm"
            dump = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(context.filesDir, name)))
        }
        vocalGain = if (vocalsOn) 1f else 0f

        audioTrack = buildAudioTrack()
        seekLocked(startUs, flushCodecs = false)
        running = true
        thread = Thread(::loop, "loukai-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun findVocalStem(names: List<String>, count: Int): Int {
        val keywords = listOf("vocal", "voice", "lead", "singing", "vox")
        val byName = names.take(count).indexOfFirst { n -> keywords.any { n.lowercase().contains(it) } }
        // NI Stems order puts vocals last; trust that when the names say nothing.
        return if (byName >= 0) byName else if (count == 4) 3 else -1
    }

    private fun buildAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate)) // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun play() {
        synchronized(lock) {
            if (playing) return
            playing = true
            audioTrack?.play()
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            playing = false
            audioTrack?.pause()
            dump?.flush()
        }
    }

    fun seek(positionUs: Long) {
        synchronized(lock) {
            seekLocked(positionUs.coerceIn(0, durationUs), flushCodecs = true)
            lock.notifyAll()
        }
    }

    private fun seekLocked(us: Long, flushCodecs: Boolean) {
        generation++
        val at = audioTrack
        at?.pause()
        at?.flush()
        for (t in tracks) {
            if (flushCodecs) t.codec.flush()
            t.extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            t.inputDone = false
            t.outputDone = false
            t.fifoLen = 0
            t.skipUntilUs = us
        }
        basePositionUs = us
        framesWritten = 0
        framesMixed = 0
        headBase = (at?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
        ended = false
        if (playing) at?.play()
    }

    fun release() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        thread?.join(1000)
        thread = null
        for (t in tracks) {
            runCatching { t.codec.stop() }
            runCatching { t.codec.release() }
            runCatching { t.extractor.release() }
        }
        tracks = emptyList()
        audioTrack?.release()
        audioTrack = null
        dump?.close()
        dump = null
    }

    private fun loop() {
        val chunk = 2048
        val out = ShortArray(chunk * 2)
        while (true) {
            val gen: Int
            val at: AudioTrack
            var n = 0
            synchronized(lock) {
                while (running && (!playing || ended)) lock.wait()
                if (!running) return
                gen = generation
                at = audioTrack ?: return
                n = fillAndMix(out, chunk)
                if (n == 0) ended = true
            }

            if (n == 0) {
                // Let what is queued finish playing before reporting the end.
                while (running && gen == generation && playing &&
                    ((at.playbackHeadPosition.toLong() and 0xffffffffL) - headBase) < framesWritten
                ) {
                    Thread.sleep(20)
                }
                if (running && gen == generation) main.post(onEnded)
                continue
            }

            dump?.let { d ->
                for (i in 0 until n * 2) {
                    val v = out[i].toInt()
                    d.write(v and 0xff); d.write((v shr 8) and 0xff)
                }
            }

            // Non-blocking writes so a seek or pause never leaves stale audio queued.
            var off = 0
            while (off < n * 2 && running && gen == generation) {
                val w = at.write(out, off, n * 2 - off, AudioTrack.WRITE_NON_BLOCKING)
                if (w < 0) break
                off += w
                if (off < n * 2) Thread.sleep(5)
            }
            synchronized(lock) {
                if (gen == generation) framesWritten += off / 2
            }
        }
    }

    /** Decode until every live track has a chunk, then mix it into [out]. Returns frames mixed. */
    private fun fillAndMix(out: ShortArray, chunk: Int): Int {
        for (t in tracks) {
            var spins = 0
            while (!t.outputDone && t.frames < chunk && spins++ < 500) pump(t)
        }
        val live = tracks.filter { !it.outputDone }
        val n = if (live.isNotEmpty()) {
            minOf(chunk, live.minOf { it.frames })
        } else {
            minOf(chunk, tracks.maxOfOrNull { it.frames } ?: 0)
        }
        if (n <= 0) return 0

        val chunkStartUs = basePositionUs + framesMixed * 1_000_000 / sampleRate
        val target = if (vocalsOn || inPunch(chunkStartUs)) 1f else 0f
        val step = 1f / (sampleRate * 0.05f)

        for (f in 0 until n) {
            if (vocalGain < target) vocalGain = minOf(target, vocalGain + step)
            else if (vocalGain > target) vocalGain = maxOf(target, vocalGain - step)
            var l = 0f
            var r = 0f
            for (t in tracks) {
                if (f >= t.frames) continue
                val g = if (t.isVocals) vocalGain else 1f
                l += t.fifo[f * 2] * g
                r += t.fifo[f * 2 + 1] * g
            }
            out[f * 2] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out[f * 2 + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        for (t in tracks) {
            val used = minOf(n, t.frames) * 2
            System.arraycopy(t.fifo, used, t.fifo, 0, t.fifoLen - used)
            t.fifoLen -= used
        }
        framesMixed += n
        return n
    }

    private fun inPunch(us: Long): Boolean {
        val w = punchWindows
        var i = 0
        while (i + 1 < w.size) {
            if (us >= w[i] && us <= w[i + 1]) return true
            i += 2
        }
        return false
    }

    private fun pump(t: Track) {
        if (!t.inputDone) {
            val ii = t.codec.dequeueInputBuffer(0)
            if (ii >= 0) {
                val buf = t.codec.getInputBuffer(ii)!!
                val size = t.extractor.readSampleData(buf, 0)
                if (size < 0) {
                    t.codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    t.inputDone = true
                } else {
                    t.codec.queueInputBuffer(ii, 0, size, t.extractor.sampleTime, 0)
                    t.extractor.advance()
                }
            }
        }
        val oi = t.codec.dequeueOutputBuffer(info, 2000)
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            t.channels = t.codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else if (oi >= 0) {
            if (info.size > 0) {
                val buf = t.codec.getOutputBuffer(oi)!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                append(t, buf.order(ByteOrder.nativeOrder()).asShortBuffer(), info.presentationTimeUs)
            }
            t.codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) t.outputDone = true
        }
    }

    private fun append(t: Track, pcm: java.nio.ShortBuffer, ptsUs: Long) {
        val ch = maxOf(1, t.channels)
        val frames = pcm.remaining() / ch
        // After a seek, drop audio before the target so all stems start on the same sample.
        var drop = 0
        if (ptsUs < t.skipUntilUs) {
            drop = ((t.skipUntilUs - ptsUs) * sampleRate / 1_000_000).toInt().coerceAtMost(frames)
        }
        val need = t.fifoLen + (frames - drop) * 2
        if (need > t.fifo.size) t.fifo = t.fifo.copyOf(maxOf(need, t.fifo.size * 2))
        for (f in drop until frames) {
            val left = pcm.get(f * ch) / 32768f
            val right = if (ch > 1) pcm.get(f * ch + 1) / 32768f else left
            t.fifo[t.fifoLen++] = left
            t.fifo[t.fifoLen++] = right
        }
    }
}

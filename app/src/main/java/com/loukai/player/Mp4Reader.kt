package com.loukai.player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the bits of a Loukai .stem.mp4 the player needs, straight from the MP4
 * boxes: title/artist (ilst ©nam/©ART), the NI Stems track names (moov/udta/stem)
 * and the karaoke JSON (ilst ----:com.stems:kara). Only the moov box is loaded;
 * Loukai writes it after mdat, so it is found by skipping box headers.
 */
object Mp4Reader {
    class Meta(val title: String?, val artist: String?, val stemNames: List<String>, val kara: String?)

    private class Box(val type: String, val body: Int, val end: Int)

    fun read(ctx: Context, uri: Uri): Meta {
        val moov = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { findMoov(it) }
        } ?: return Meta(null, null, emptyList(), null)

        var title: String? = null
        var artist: String? = null
        var stemNames = emptyList<String>()
        var kara: String? = null

        val udta = boxes(moov, 0, moov.limit()).find { it.type == "udta" }
        if (udta != null) {
            for (child in boxes(moov, udta.body, udta.end)) {
                when (child.type) {
                    "stem" -> stemNames = runCatching {
                        val stems = JSONObject(string(moov, child.body, child.end)).getJSONArray("stems")
                        (0 until stems.length()).map { stems.getJSONObject(it).optString("name") }
                    }.getOrDefault(emptyList())
                    "meta" -> {
                        // ISO full box (4 bytes version/flags) unless it is QuickTime-style.
                        val start = if (type(moov, child.body + 4) == "hdlr") child.body else child.body + 4
                        val ilst = boxes(moov, start, child.end).find { it.type == "ilst" } ?: continue
                        for (item in boxes(moov, ilst.body, ilst.end)) {
                            when (item.type) {
                                "©nam" -> title = dataString(moov, item)
                                "©ART" -> artist = dataString(moov, item)
                                "----" -> {
                                    val parts = boxes(moov, item.body, item.end)
                                    val name = parts.find { it.type == "name" }?.let { string(moov, it.body + 4, it.end) }
                                    if (name == "kara") kara = dataString(moov, item)
                                }
                            }
                        }
                    }
                }
            }
        }
        return Meta(title, artist, stemNames, kara)
    }

    private fun findMoov(ch: FileChannel): ByteBuffer? {
        val size = ch.size()
        val hdr = ByteBuffer.allocate(16)
        var pos = 0L
        while (pos + 8 <= size) {
            hdr.clear()
            readFully(ch, hdr, pos)
            var boxSize = hdr.getInt(0).toLong() and 0xffffffffL
            val boxType = type(hdr, 4)
            var hdrLen = 8
            if (boxSize == 1L) {
                boxSize = hdr.getLong(8)
                hdrLen = 16
            } else if (boxSize == 0L) {
                boxSize = size - pos
            }
            if (boxSize < hdrLen) return null
            if (boxType == "moov") {
                val buf = ByteBuffer.allocate((boxSize - hdrLen).toInt())
                readFully(ch, buf, pos + hdrLen)
                buf.flip()
                return buf
            }
            pos += boxSize
        }
        return null
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, at: Long) {
        var p = at
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
    }

    private fun boxes(b: ByteBuffer, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = b.getInt(p).toLong() and 0xffffffffL
            var hl = 8
            if (size == 1L) {
                size = b.getLong(p + 8)
                hl = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < hl || p + size > end) break
            out += Box(type(b, p + 4), p + hl, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun type(b: ByteBuffer, at: Int): String {
        if (at + 4 > b.limit()) return ""
        val chars = CharArray(4) { (b.get(at + it).toInt() and 0xff).toChar() }
        return String(chars)
    }

    private fun string(b: ByteBuffer, start: Int, end: Int): String {
        val bytes = ByteArray(maxOf(0, end - start))
        for (i in bytes.indices) bytes[i] = b.get(start + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** The payload of an ilst item's `data` child (after its 4-byte type and 4-byte locale). */
    private fun dataString(b: ByteBuffer, item: Box): String? {
        val data = boxes(b, item.body, item.end).find { it.type == "data" } ?: return null
        return string(b, data.body + 8, data.end)
    }
}

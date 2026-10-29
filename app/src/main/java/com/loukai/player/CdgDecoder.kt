package com.loukai.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.min

/**
 * CD+G decoder, ported from Loukai's src/renderer/lib/cdgraphics.js.
 * Packets play at 300/sec (75 sectors x 4); render(t) executes every packet up
 * to t, restarting from the top on a backward seek.
 */
class CdgDecoder(private val bytes: ByteArray) {
    companion object {
        const val WIDTH = 300
        const val HEIGHT = 216
        private const val TILE_W = 6
        private const val TILE_H = 12
        private const val BOUND_L = 6
        private const val BOUND_T = 12
        private const val BOUND_R = 294
        private const val BOUND_B = 204
    }

    private val numPackets = bytes.size / 24
    private var pc = -1

    private var hOffset = 0
    private var vOffset = 0
    private var borderColor = -1
    private val clut = IntArray(16)
    private var pixels = ByteArray(WIDTH * HEIGHT)
    private var buffer = ByteArray(WIDTH * HEIGHT)
    private val argb = IntArray(WIDTH * HEIGHT)
    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false } // pixel-perfect, like Loukai
    private val dest = RectF()

    private fun reset() {
        hOffset = 0
        vOffset = 0
        borderColor = -1
        clut.fill(0)
        pixels.fill(0)
        buffer.fill(0)
    }

    /** Draw the frame at [t] seconds, scaled to fit [w]x[h]. Safe to call from any thread. */
    @Synchronized
    fun drawTo(canvas: Canvas, w: Float, h: Float, t: Double) {
        if (render(t)) bitmap.setPixels(argb, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val scale = min(w / WIDTH, h / HEIGHT)
        val dw = WIDTH * scale
        val dh = HEIGHT * scale
        dest.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        canvas.drawBitmap(bitmap, null, dest, paint)
    }

    private fun render(t: Double): Boolean {
        val target = floor(300 * maxOf(0.0, t)).toInt()
        var changed = false
        if (pc > target) {
            pc = -1
            reset()
            changed = true
        }
        while (pc < target && pc < numPackets - 1) {
            pc++
            if (execute(pc * 24)) changed = true
        }
        if (changed || pc < 0) renderFrame()
        return changed || pc < 0
    }

    private fun b(off: Int, i: Int) = bytes[off + i].toInt() and 0xff

    private fun execute(off: Int): Boolean {
        if (b(off, 0) and 63 != 9) return false
        when (b(off, 1) and 63) {
            1 -> { // memory preset
                val color = (b(off, 4) and 15).toByte()
                pixels.fill(color)
                borderColor = -1
                hOffset = 0
                vOffset = 0
            }
            2 -> borderColor = b(off, 4) and 15
            6 -> tile(off, xor = false)
            38 -> tile(off, xor = true)
            20 -> scroll(off, wrap = false)
            24 -> scroll(off, wrap = true)
            28 -> Unit // transparent key color only matters for overlays
            30 -> loadClut(off, 0)
            31 -> loadClut(off, 8)
            else -> return false
        }
        return true
    }

    private fun tile(off: Int, xor: Boolean) {
        val c0 = b(off, 4) and 15
        val c1 = b(off, 5) and 15
        val row = b(off, 6) and 31
        val col = b(off, 7) and 63
        val x = col * TILE_W
        val y = row * TILE_H
        if (x + TILE_W > WIDTH || y + TILE_H > HEIGHT) return
        for (i in 0 until TILE_H) {
            val bits = b(off, 8 + i)
            for (n in 0 until TILE_W) {
                val color = if ((bits shr (5 - n)) and 1 == 1) c1 else c0
                val idx = x + n + (y + i) * WIDTH
                pixels[idx] = if (xor) (pixels[idx].toInt() xor color).toByte() else color.toByte()
            }
        }
    }

    private fun scroll(off: Int, wrap: Boolean) {
        val color = (b(off, 4) and 15).toByte()
        val h = b(off, 5) and 63
        val v = b(off, 6) and 63
        val hCmd = (h and 48) shr 4
        val vCmd = (v and 48) shr 4
        hOffset = min(h and 7, 5)
        vOffset = min(v and 15, 11)
        val dx = when (hCmd) { 2 -> TILE_W; 1 -> -TILE_W; else -> 0 }
        val dy = when (vCmd) { 2 -> TILE_H; 1 -> -TILE_H; else -> 0 }
        if (dx == 0 && dy == 0) return
        for (x in 0 until WIDTH) {
            for (y in 0 until HEIGHT) {
                var sx = x + dx
                var sy = y + dy
                buffer[x + y * WIDTH] = if (wrap) {
                    sx = (sx + WIDTH) % WIDTH
                    sy = (sy + HEIGHT) % HEIGHT
                    pixels[sx + sy * WIDTH]
                } else if (sx > 0 && sx < WIDTH && sy > 0 && sy < HEIGHT) {
                    pixels[sx + sy * WIDTH]
                } else {
                    color
                }
            }
        }
        val swap = pixels
        pixels = buffer
        buffer = swap
    }

    private fun loadClut(off: Int, base: Int) {
        for (s in 0 until 8) {
            val o = 4 + 2 * s
            val v = ((b(off, o) and 63) shl 6) + (b(off, o + 1) and 63)
            val r = (v shr 8) * 17
            val g = ((v and 240) shr 4) * 17
            val bl = (v and 15) * 17
            clut[base + s] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    private fun renderFrame() {
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val inBorder = x < BOUND_L || y < BOUND_T || x >= BOUND_R || y >= BOUND_B
                val c = if (borderColor >= 0 && inBorder) {
                    borderColor
                } else {
                    val idx = (x + hOffset) + (y + vOffset) * WIDTH
                    if (idx < pixels.size) pixels[idx].toInt() and 15 else 0
                }
                argb[x + y * WIDTH] = clut[c] or (0xff shl 24)
            }
        }
    }
}

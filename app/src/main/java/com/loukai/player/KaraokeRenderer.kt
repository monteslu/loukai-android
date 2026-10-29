package com.loukai.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Port of the lyric half of Loukai's src/renderer/js/karaokeRenderer.js
 * (everything except Butterchurn, waveforms, mic, QR and queue overlays).
 * It draws on Loukai's fixed 1920x1080 canvas, scaled to fit the target, so
 * every size, offset and color matches the desktop app.
 *
 * Stateful (upcoming-line lock, slide transitions, backup fades): use one
 * instance per surface. The phone view and the car screen each own one.
 */
class KaraokeRenderer(private val logo: Bitmap? = null, private val idleHint: String = "Pick a song") {
    private companion object {
        const val WIDTH = 1920f
        const val HEIGHT = 1080f

        const val FONT_SIZE = 80f
        const val LINE_HEIGHT = 140f
        const val TEXT_COLOR = Color.WHITE
        const val ACTIVE_COLOR = 0xFF00BFFF.toInt()
        const val UPCOMING_COLOR = 0xFF888888.toInt()
        const val BACKUP_ACTIVE_COLOR = 0xFFFFD700.toInt()
        const val SINGER_B_COLOR = 0xFFEF4444.toInt()
        const val DUET_COLOR = 0xFF22C55E.toInt()
        const val BACKUP_PA_COLOR = 0xFFFFA500.toInt()
        const val PROGRESS_BAR_HEIGHT = 30f
        const val PROGRESS_BAR_COLOR = 0xFF007ACC.toInt()
        const val PROGRESS_BAR_BG = 0xFF333333.toInt()
        const val PROGRESS_BAR_MARGIN = 100f
        const val TRANSITION_DURATION = 0.3
        const val TRANSITION_START_BEFORE = 0.3
        const val BACKUP_FADE_DURATION = 0.8
        const val BACKUP_MAX_ALPHA = 0.5f
        const val BACKUP_MIN_ALPHA = 0f
        const val MIN_GAP_SEC = 5.0
        const val UPCOMING_LOOKAHEAD = 5.0
    }

    private class Transition(val startTime: Double, var progress: Double, val startY: Float, val endY: Float)
    private class BackupAnim(var alpha: Float, var fadeDirection: Int, var lastStateChange: Double)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = FONT_SIZE
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(166, 0, 0, 0) }
    private val fill = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()
    private val rect = RectF()

    private var lyrics: List<LyricLine> = emptyList()
    private var lockedUpcomingIndex: Int? = null
    private val lyricTransitions = LinkedHashMap<Int, Transition>()
    private val hiddenDuringTransition = HashSet<Int>()
    private val backupAnimations = HashMap<Int, BackupAnim>()
    private var lastActiveLyricsBottom: Float? = null
    private var lastTime = 0.0

    /** Draw the frame for time [t] (seconds) into a [w]x[h] area of [c]. */
    fun draw(c: Canvas, w: Float, h: Float, t: Double) {
        c.drawColor(Color.BLACK)

        val cdg = NowPlaying.cdg
        if (cdg != null) {
            cdg.drawTo(c, w, h, t)
            return
        }

        // Song changed, or a seek: drop position-bound state (Loukai's resetTimelineState).
        val current = NowPlaying.lyrics
        if (current !== lyrics) {
            lyrics = current
            resetTimelineState()
            backupAnimations.clear()
        } else if (abs(t - lastTime) > 1.0) {
            resetTimelineState()
        }
        lastTime = t

        val scale = min(w / WIDTH, h / HEIGHT)
        c.save()
        c.translate((w - WIDTH * scale) / 2, (h - HEIGHT * scale) / 2)
        c.scale(scale, scale)
        drawFrame(c, t)
        c.restore()
    }

    private fun resetTimelineState() {
        lockedUpcomingIndex = null
        lyricTransitions.clear()
        hiddenDuringTransition.clear()
    }

    private fun drawFrame(c: Canvas, now: Double) {
        if (NowPlaying.engine == null) {
            drawIdle(c)
            return
        }
        if (!NowPlaying.isPlaying && NowPlaying.title.isNotEmpty()) {
            drawSongInfo(c)
            return
        }
        if (lyrics.isEmpty()) return

        if (isInInstrumentalIntro(now)) {
            drawInstrumentalIntro(c, now)
            return
        }
        if (isInInstrumentalOutro(now)) {
            drawInstrumentalOutro(c, now)
            return
        }

        val currentLineIndex = findCurrentMainLine(now)
        if (currentLineIndex >= 0) {
            if (isInInstrumentalGap(currentLineIndex, now)) {
                drawInstrumentalProgressBar(c, currentLineIndex, now)
            } else {
                drawActiveLines(c, now)
            }
        } else if (isInShortIntro(now)) {
            // Lead-in too short for a progress bar: show the first line coming up.
            drawActiveLines(c, now)
        } else {
            drawBackupOnlyProgressBar(c, now)
        }
    }

    // --- timeline queries ----------------------------------------------------

    private fun isMain(line: LyricLine) = !line.isBackup

    private fun firstMainIndex() = lyrics.indexOfFirst(::isMain)

    private fun lastMainLine() = lyrics.lastOrNull(::isMain)

    private fun isInInstrumentalIntro(now: Double): Boolean {
        val first = firstMainIndex()
        if (first < 0) return false
        val firstStart = lyrics[first].start
        return now < firstStart && firstStart > MIN_GAP_SEC
    }

    private fun isInShortIntro(now: Double): Boolean {
        val first = firstMainIndex()
        return first >= 0 && now < lyrics[first].start
    }

    private fun isInInstrumentalOutro(now: Double): Boolean {
        val duration = NowPlaying.durationSec
        if (duration <= 0) return false
        val last = lastMainLine() ?: return false
        return now > last.end && duration - last.end > 0
    }

    private fun findCurrentMainLine(now: Double): Int {
        val active = lyrics.indexOfFirst { isMain(it) && now >= it.start && now <= it.end }
        if (active >= 0) return active
        val upcoming = lyrics.indexOfFirst { isMain(it) && now < it.start }
        if (upcoming >= 0) {
            for (j in upcoming - 1 downTo 0) if (isMain(lyrics[j])) return j
            return -1
        }
        return lyrics.indexOfLast(::isMain)
    }

    private fun nextMainAfter(index: Int): LyricLine? {
        for (i in index + 1 until lyrics.size) if (isMain(lyrics[i])) return lyrics[i]
        return null
    }

    private fun isInInstrumentalGap(currentLineIndex: Int, now: Double): Boolean {
        val currentLine = lyrics[currentLineIndex]
        val next = nextMainAfter(currentLineIndex) ?: return false
        if (next.start - currentLine.end <= MIN_GAP_SEC) return false
        return now >= currentLine.end && now <= next.start
    }

    // --- screens -------------------------------------------------------------

    private fun drawSongInfo(c: Canvas) {
        val centerY = HEIGHT * 0.35f
        text.textSize = 72f
        text.color = TEXT_COLOR
        text.setShadowLayer(4f, 2f, 2f, Color.argb(204, 0, 0, 0))
        drawMiddle(c, NowPlaying.title.ifEmpty { "Unknown Title" }, centerY - 50)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = 48f
        text.color = 0xFFCCCCCC.toInt()
        drawMiddle(c, NowPlaying.artist.ifEmpty { "Unknown Artist" }, centerY + 50)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.clearShadowLayer()
        text.textSize = FONT_SIZE
    }

    /** Nothing loaded: the Loukai robot and a hint. */
    private fun drawIdle(c: Canvas) {
        logo?.let {
            val size = 520f
            rect.set(WIDTH / 2 - size / 2, 140f, WIDTH / 2 + size / 2, 140f + size)
            c.drawBitmap(it, null, rect, fill)
        }
        text.textSize = 72f
        text.color = TEXT_COLOR
        drawMiddle(c, "Loukai", 760f)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = 44f
        text.color = 0xFF9CA3AF.toInt()
        drawMiddle(c, idleHint, 850f)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = FONT_SIZE
    }

    /** fillText with textBaseline = 'middle'. */
    private fun drawMiddle(c: Canvas, s: String, y: Float) {
        val fm = text.fontMetrics
        c.drawText(s, WIDTH / 2, y - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawInstrumentalIntro(c: Canvas, now: Double) {
        val first = firstMainIndex()
        if (first < 0) return
        val firstLine = lyrics[first]
        val progress = now / firstLine.start

        val barWidth = WIDTH * 0.8f
        val barY = 80f
        drawProgressBar(c, (WIDTH - barWidth) / 2, barY, barWidth, progress)

        val upcomingY = barY + PROGRESS_BAR_MARGIN
        if (lockedUpcomingIndex != first) lockedUpcomingIndex = first
        startTransitionAnimations(c, emptyList(), upcomingY, now)
        if (!lyricTransitions.containsKey(first)) {
            drawUpcomingLyricsPreview(c, firstLine, progress, upcomingY)
        }
        drawActiveLines(c, now, skipUpcoming = true)
    }

    private fun drawInstrumentalOutro(c: Canvas, now: Double) {
        val last = lastMainLine() ?: return
        val outroLength = NowPlaying.durationSec - last.end
        val progress = ((now - last.end) / outroLength).coerceIn(0.0, 1.0)
        drawProgressBar(c, 50f, 50f, WIDTH - 100, progress)
        text.textSize = FONT_SIZE
        text.color = TEXT_COLOR
        drawTextWithBackground(c, "♫ Instrumental Outro ♫", WIDTH / 2, HEIGHT / 2)
        // Backup lines can come after the last sung line (an outro chant); Loukai's
        // outro screen skips them, but they belong at the bottom like in the intro.
        drawActiveLines(c, now, skipUpcoming = true)
    }

    private fun drawInstrumentalProgressBar(c: Canvas, currentLineIndex: Int, now: Double) {
        val currentLine = lyrics[currentLineIndex]
        val next = nextMainAfter(currentLineIndex) ?: return
        val gap = next.start - currentLine.end
        if (gap <= MIN_GAP_SEC) return
        if (now < currentLine.end || now > next.start) return

        val progress = (now - currentLine.end) / gap
        val barWidth = WIDTH * 0.8f
        val barY = 80f
        drawProgressBar(c, (WIDTH - barWidth) / 2, barY, barWidth, progress)
        drawUpcomingLyricsPreview(c, next, progress, barY + PROGRESS_BAR_MARGIN)
        drawActiveLines(c, now, skipUpcoming = true)
    }

    private fun drawBackupOnlyProgressBar(c: Canvas, now: Double) {
        if (lyrics.any { isMain(it) && now >= it.start && now <= it.end }) return
        val next = lyrics.firstOrNull { isMain(it) && now < it.start } ?: return
        val gapStart = lyrics.lastOrNull { isMain(it) && it.end <= now }?.end ?: 0.0
        val gap = next.start - gapStart
        if (gap <= MIN_GAP_SEC) return

        val progress = (now - gapStart) / gap
        val barWidth = WIDTH * 0.8f
        val barY = 80f
        drawProgressBar(c, (WIDTH - barWidth) / 2, barY, barWidth, progress)
        drawUpcomingLyricsPreview(c, next, progress, barY + PROGRESS_BAR_MARGIN)
        drawActiveLines(c, now, skipUpcoming = true)
    }

    // --- active lines, upcoming line, transitions ----------------------------

    private class Active(val line: LyricLine, val index: Int)

    private fun drawActiveLines(c: Canvas, now: Double, skipUpcoming: Boolean = false) {
        updateBackupAnimations(now)

        val activeLines = ArrayList<Active>()
        for ((i, line) in lyrics.withIndex()) {
            if (lyricTransitions.containsKey(i) || hiddenDuringTransition.contains(i)) continue
            if (now >= line.start && now <= line.end) activeLines += Active(line, i)
        }
        val mainLines = activeLines.filter { !it.line.isBackup }
        val backupLines = activeLines.filter { it.line.isBackup }

        val lineSpacing = LINE_HEIGHT * 1.2f
        val totalHeight = maxOf(1, mainLines.size) * lineSpacing
        var currentY = HEIGHT / 2 - totalHeight / 2 + lineSpacing - 180

        for (a in mainLines) {
            currentY = drawSingleLine(c, a.line, currentY, isBackup = false) ?: (currentY + lineSpacing)
        }

        if (backupLines.isNotEmpty()) {
            setItalic(true)
            var totalBackupHeight = 0f
            for (a in backupLines) totalBackupHeight += wrap(a.line.text).size * LINE_HEIGHT * 0.8f
            var backupY = HEIGHT - 10 - totalBackupHeight
            for (a in backupLines) {
                val alpha = backupAnimations[a.index]?.alpha ?: BACKUP_MAX_ALPHA
                backupY = drawSingleLine(c, a.line, backupY, isBackup = true, alpha = alpha)
                    ?: (backupY + LINE_HEIGHT * 0.8f)
            }
            setItalic(false)
        }

        if (activeLines.isNotEmpty()) lastActiveLyricsBottom = currentY

        var upcomingY = (lastActiveLyricsBottom ?: currentY) + 10
        if (activeLines.isEmpty() && lyricTransitions.isNotEmpty()) {
            var lowest = 0f
            for (tr in lyricTransitions.values) {
                lowest = maxOf(lowest, tr.startY + (tr.endY - tr.startY) * tr.progress.toFloat())
            }
            if (lowest > 0) upcomingY = lowest + LINE_HEIGHT * 1.5f
        }

        startTransitionAnimations(c, activeLines, upcomingY, now)

        for ((index, tr) in lyricTransitions) {
            lyrics.getOrNull(index)?.let { drawTransitioningLine(c, it, tr) }
        }

        if (!skipUpcoming && lyricTransitions.isEmpty()) drawUpcomingLyrics(c, upcomingY, now)
    }

    private fun drawUpcomingLyrics(c: Canvas, startY: Float, now: Double) {
        lockedUpcomingIndex?.let { idx ->
            val locked = lyrics.getOrNull(idx)
            if (locked == null || (now >= locked.start && !lyricTransitions.containsKey(idx))) {
                lockedUpcomingIndex = null
            }
        }
        if (lockedUpcomingIndex == null && lyricTransitions.isEmpty()) {
            var next: Int? = null
            var closest = Double.MAX_VALUE
            for ((i, line) in lyrics.withIndex()) {
                if (!line.isBackup && line.start > now && line.start <= now + UPCOMING_LOOKAHEAD &&
                    line.start < closest
                ) {
                    next = i
                    closest = line.start
                }
            }
            lockedUpcomingIndex = next
        }

        val idx = lockedUpcomingIndex ?: return
        val locked = lyrics.getOrNull(idx) ?: run { lockedUpcomingIndex = null; return }
        if (now >= locked.start) {
            lockedUpcomingIndex = null
            return
        }
        if (lyricTransitions.containsKey(idx)) return

        text.textSize = FONT_SIZE
        text.color = 0xFF999999.toInt()
        text.alpha = (0.8f * 255).toInt()
        val glow = if (locked.singer != null) singerColor(locked) else null
        drawWrappedText(c, locked.text, WIDTH / 2, startY, WIDTH * 0.9f, glow)
        text.alpha = 255
    }

    private fun drawWrappedText(c: Canvas, s: String, x: Float, y: Float, maxWidth: Float, glow: Int?) {
        var rowY = y
        for (row in wrap(s, maxWidth)) {
            drawTextWithBackground(c, row, x, rowY, glow)
            rowY += FONT_SIZE * 1.2f
        }
    }

    private fun startTransitionAnimations(c: Canvas, activeLines: List<Active>, upcomingY: Float, now: Double) {
        val idx = lockedUpcomingIndex
        val upcoming = idx?.let { lyrics.getOrNull(it) }
        if (idx != null && upcoming != null) {
            val untilActive = upcoming.start - now
            if (untilActive <= TRANSITION_START_BEFORE && untilActive > 0 && !lyricTransitions.containsKey(idx)) {
                val lineSpacing = LINE_HEIGHT * 1.2f
                val totalLines = maxOf(1, activeLines.count { !it.line.isBackup })
                val activeY = HEIGHT / 2 - totalLines * lineSpacing / 2 + lineSpacing - 180
                lyricTransitions[idx] = Transition(now, 0.0, upcomingY, activeY)

                // Hide active main lines that end during the slide so they don't overlap it.
                for ((i, line) in lyrics.withIndex()) {
                    if (line.isBackup || i == idx) continue
                    if (now >= line.start && now <= line.end) {
                        val untilEnd = line.end - now
                        if (untilEnd > 0 && untilEnd <= TRANSITION_DURATION) hiddenDuringTransition += i
                    }
                }
            }
        }

        val it = lyricTransitions.entries.iterator()
        while (it.hasNext()) {
            val tr = it.next().value
            tr.progress = min(1.0, (now - tr.startTime) / TRANSITION_DURATION)
            if (tr.progress >= 1.0) it.remove()
        }
        hiddenDuringTransition.removeAll { i ->
            val line = lyrics.getOrNull(i)
            line == null || now > line.end || now < line.start
        }
    }

    private fun drawTransitioningLine(c: Canvas, line: LyricLine, tr: Transition) {
        val p = tr.progress.toFloat().coerceIn(0f, 1f)
        val y = tr.startY + (tr.endY - tr.startY) * p
        val target = singerColor(line)
        val r = (136 + (Color.red(target) - 136) * p).toInt()
        val g = (136 + (Color.green(target) - 136) * p).toInt()
        val b = (136 + (Color.blue(target) - 136) * p).toInt()
        text.textSize = FONT_SIZE
        text.color = Color.argb(((0.8f + 0.2f * p) * 255).toInt(), r, g, b)
        val glow = if (line.singer != null) target else null
        var rowY = y
        for (row in wrap(line.text)) {
            drawTextWithBackground(c, row, WIDTH / 2, rowY, glow)
            rowY += LINE_HEIGHT * 0.8f
        }
    }

    private fun drawUpcomingLyricsPreview(c: Canvas, line: LyricLine, progress: Double, startY: Float) {
        text.textSize = FONT_SIZE
        text.color = if (progress >= 1.0) ACTIVE_COLOR else UPCOMING_COLOR
        var y = startY + 60
        for (row in wrap(line.text)) {
            drawTextWithBackground(c, row, WIDTH / 2, y)
            y += LINE_HEIGHT * 0.8f
        }
    }

    // --- lines -----------------------------------------------------------------

    private fun singerColor(line: LyricLine) = when (line.singer) {
        "B" -> SINGER_B_COLOR
        "duet" -> DUET_COLOR
        "backup:PA" -> BACKUP_PA_COLOR
        "backup" -> BACKUP_ACTIVE_COLOR
        else -> ACTIVE_COLOR
    }

    /** backup:PA uses a brighter color instead of a prefix (the audio is the indicator). */
    private fun singerPrefix(line: LyricLine) = if (line.singer == "backup") "♪ " else ""

    /** Returns the y below the drawn rows, or null if there was nothing to draw. */
    private fun drawSingleLine(c: Canvas, line: LyricLine, y: Float, isBackup: Boolean, alpha: Float = 1f): Float? {
        if (line.text.isBlank()) return null
        text.textSize = FONT_SIZE
        text.color = singerColor(line)
        if (isBackup) text.alpha = (alpha * 255).toInt()
        val prefix = singerPrefix(line)
        val glow = if (line.singer != null) singerColor(line) else null
        var finalY = y
        for ((i, row) in wrap(line.text).withIndex()) {
            val rowY = y + i * LINE_HEIGHT * 0.8f
            finalY = rowY + LINE_HEIGHT * 0.8f
            drawTextWithBackground(c, if (i == 0 && prefix.isNotEmpty()) prefix + row else row, WIDTH / 2, rowY, glow, alpha)
        }
        text.alpha = 255
        return finalY
    }

    private fun updateBackupAnimations(now: Double) {
        for ((i, line) in lyrics.withIndex()) {
            if (!line.isBackup) {
                backupAnimations.remove(i)
                continue
            }
            val active = now >= line.start && now <= line.end
            val anim = backupAnimations.getOrPut(i) { BackupAnim(BACKUP_MIN_ALPHA, 0, now) }
            val target = if (active) BACKUP_MAX_ALPHA else BACKUP_MIN_ALPHA
            val direction = when {
                active && anim.alpha < BACKUP_MAX_ALPHA -> 1
                !active && anim.alpha > BACKUP_MIN_ALPHA -> -1
                else -> 0
            }
            if (direction != anim.fadeDirection) {
                anim.fadeDirection = direction
                anim.lastStateChange = now
            }
            if (anim.fadeDirection != 0) {
                val progress = min((now - anim.lastStateChange) / BACKUP_FADE_DURATION, 1.0)
                val eased = (1 - (1 - progress).pow(3)).toFloat()
                anim.alpha = if (anim.fadeDirection == 1) {
                    BACKUP_MIN_ALPHA + (BACKUP_MAX_ALPHA - BACKUP_MIN_ALPHA) * eased
                } else {
                    BACKUP_MAX_ALPHA - (BACKUP_MAX_ALPHA - BACKUP_MIN_ALPHA) * eased
                }
                if (progress >= 1.0) {
                    anim.fadeDirection = 0
                    anim.alpha = target
                }
            }
        }
    }

    // --- drawing primitives ---------------------------------------------------

    private fun setItalic(italic: Boolean) {
        text.typeface = Typeface.create(Typeface.SANS_SERIF, if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
    }

    private fun wrap(s: String, maxWidth: Float = WIDTH * 0.9f): List<String> {
        val rows = ArrayList<String>()
        var row = ""
        for (word in s.split(' ')) {
            val test = if (row.isEmpty()) word else "$row $word"
            if (text.measureText(test) <= maxWidth) {
                row = test
            } else if (row.isNotEmpty()) {
                rows += row
                row = word
            } else {
                rows += word
            }
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    /** Rounded dark box behind the text, then the text with an optional singer glow. */
    private fun drawTextWithBackground(c: Canvas, s: String, x: Float, y: Float, glow: Int? = null, alpha: Float = 1f) {
        val width = text.measureText(s)
        text.getTextBounds(s, 0, s.length, bounds)
        val ascent = if (bounds.top < 0) -bounds.top.toFloat() else FONT_SIZE * 0.8f
        val descent = if (bounds.bottom > 0) bounds.bottom.toFloat() else FONT_SIZE * 0.2f
        val textHeight = ascent + descent
        val padding = 12f
        val bgWidth = (width + padding * 2) * 1.1f
        val bgHeight = (textHeight + padding) * 1.1f
        val bgX = x - bgWidth / 2
        val bgY = y - ascent - (bgHeight - textHeight) / 2
        bg.alpha = (166 * alpha).toInt()
        rect.set(bgX, bgY, bgX + bgWidth, bgY + bgHeight)
        c.drawRoundRect(rect, 12f, 12f, bg)

        if (glow != null) text.setShadowLayer(12f, 0f, 0f, glow)
        c.drawText(s, x, y, text)
        if (glow != null) text.clearShadowLayer()
    }

    private fun drawProgressBar(c: Canvas, x: Float, y: Float, width: Float, progress: Double) {
        fill.color = PROGRESS_BAR_BG
        c.drawRect(x, y, x + width, y + PROGRESS_BAR_HEIGHT, fill)
        fill.color = PROGRESS_BAR_COLOR
        c.drawRect(x, y, x + width * progress.toFloat().coerceIn(0f, 1f), y + PROGRESS_BAR_HEIGHT, fill)
    }
}

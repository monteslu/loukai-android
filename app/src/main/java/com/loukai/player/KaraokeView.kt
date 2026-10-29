package com.loukai.player

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View

/** The phone's lyric/CDG display; redraws every frame while attached. */
class KaraokeView(context: Context) : View(context) {
    private val renderer = KaraokeRenderer(BitmapFactory.decodeResource(resources, R.drawable.loukai_robot))

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width.toFloat(), height.toFloat(), NowPlaying.positionSec)
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}

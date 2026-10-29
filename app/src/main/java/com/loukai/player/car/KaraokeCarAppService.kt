package com.loukai.player.car

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.loukai.player.KaraokeRenderer
import com.loukai.player.NowPlaying
import com.loukai.player.PlaybackService
import com.loukai.player.R

/**
 * The "Loukai Lyrics" app on the car screen. It registers as a navigation app
 * because that is the only kind of car app that gets a surface to draw on;
 * we draw the karaoke screen there instead of a map. Pick songs in the
 * regular Loukai media app (or on the phone); this screen follows playback.
 */
class KaraokeCarAppService : CarAppService() {
    // Sideloaded personal app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = PassengerScreen(carContext)
    }
}

/**
 * Shown every time the car app starts: the lyrics screen is for passengers,
 * so whoever is using it has to confirm they are not driving.
 */
class PassengerScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(
        "The lyrics screen is for passengers only. Drivers must keep their eyes on the road.\n\n" +
            "Confirm that you are a passenger and not the driver of this vehicle.",
    )
        .setTitle("Passengers only")
        .setHeaderAction(Action.APP_ICON)
        .addAction(
            Action.Builder()
                .setTitle("I'm a passenger")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { screenManager.push(LyricsScreen(carContext)) }
                .build()
        )
        .addAction(
            Action.Builder()
                .setTitle("Exit")
                .setOnClickListener { carContext.finishCarApp() }
                .build()
        )
        .build()
}

class LyricsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val renderer = KaraokeRenderer(
        android.graphics.BitmapFactory.decodeResource(carContext.resources, R.drawable.loukai_robot),
        "Pick a song in the Loukai app",
    )
    private val renderThread = HandlerThread("loukai-car-render").apply { start() }
    private val render = Handler(renderThread.looper)
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var visible = Rect()

    private val frame = object : Runnable {
        override fun run() {
            drawFrame()
            render.postDelayed(this, 33)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = invalidate()
    }
    private val nowPlayingListener = { invalidate() }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface
            surfaceW = container.width
            surfaceH = container.height
            if (visible.isEmpty) visible = Rect(0, 0, container.width, container.height)
            render.removeCallbacks(frame)
            render.post(frame)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            visible = Rect(visibleArea)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            render.removeCallbacks(frame)
            surface = null
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        val token = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(playerListener)
            invalidate()
        }, carContext.mainExecutor)
        NowPlaying.listeners += nowPlayingListener
    }

    override fun onDestroy(owner: LifecycleOwner) {
        NowPlaying.listeners -= nowPlayingListener
        render.removeCallbacks(frame)
        renderThread.quitSafely()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private fun drawFrame() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val area = visible
            canvas.save()
            canvas.translate(area.left.toFloat(), area.top.toFloat())
            canvas.clipRect(0, 0, area.width(), area.height())
            renderer.draw(canvas, area.width().toFloat(), area.height().toFloat(), NowPlaying.positionSec)
            canvas.restore()
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val c = controller
        val playing = c?.isPlaying == true
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener { c?.let { if (it.isPlaying) it.pause() else it.play() } }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_skip_next))
                    .setOnClickListener { c?.seekToNext() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(if (NowPlaying.vocalsOn) R.drawable.ic_mic else R.drawable.ic_mic_off))
                    .setOnClickListener { c?.sendCustomCommand(PlaybackService.TOGGLE_VOCALS, Bundle.EMPTY) }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}

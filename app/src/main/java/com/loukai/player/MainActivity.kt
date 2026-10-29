package com.loukai.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Phone UI: a searchable song list with a mini player bar, and a full-screen
 * player (lyrics or CDG, seek bar, transport, vocals toggle). Playback goes
 * through a MediaController to [PlaybackService], same as Android Auto.
 */
class MainActivity : Activity() {
    private companion object {
        const val PICK_FOLDER = 1
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var shown: List<Song> = emptyList()
    /** A song tapped before the controller connected; played once it does. */
    private var pendingSong: Song? = null

    private lateinit var listScreen: LinearLayout
    private lateinit var playerScreen: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniVocals: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var playerTitle: TextView
    private lateinit var playPause: ImageButton
    private lateinit var vocals: ImageButton
    private var seeking = false

    private val libraryListener = { runOnUiThread { refreshList() } }
    private val nowPlayingListener = { runOnUiThread { refreshTransport() } }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshTransport()
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateSeek()
            seek.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.load(this)
        setContentView(buildUi())

        Library.listeners += libraryListener
        NowPlaying.listeners += nowPlayingListener
        refreshList()

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        if (Library.folder(this) == null) pickFolder()
        else if (Library.songs.isEmpty()) rescan()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (playerScreen.visibility == View.VISIBLE) showPlayer(false) else finish()
            }
        }
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        if (playerScreen.visibility == View.VISIBLE) showPlayer(false) else super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(playerListener)
            pendingSong?.let { pendingSong = null; playSong(it) }
            refreshTransport()
        }, mainExecutor)
        seek.post(ticker)
    }

    override fun onStop() {
        seek.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        Library.listeners -= libraryListener
        NowPlaying.listeners -= nowPlayingListener
        super.onDestroy()
    }

    // --- actions ------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
    }

    @Deprecated("Activity result API needs androidx.activity; this app avoids the dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && tree != null) {
            Library.setFolder(this, tree)
            rescan()
        }
    }

    private fun rescan() {
        status.text = "Scanning…"
        Thread {
            runCatching {
                Library.scan(applicationContext) { n ->
                    if (n % 50 == 0) runOnUiThread { status.text = "Scanning… $n songs" }
                }
            }.onFailure { e -> runOnUiThread { status.text = "Scan failed: ${e.message}" } }
        }.start()
    }

    private fun playSong(song: Song) {
        val c = controller ?: run {
            pendingSong = song
            return
        }
        c.setMediaItem(KaraokePlayer.songItem(song))
        c.prepare()
        c.play()
        showPlayer(true)
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun toggleVocals() {
        controller?.sendCustomCommand(PlaybackService.TOGGLE_VOCALS, Bundle.EMPTY)
    }

    // --- view updates -------------------------------------------------------

    private fun refreshList() {
        shown = Library.search(search.text.toString())
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.display })
        status.text = when {
            Library.folder(this) == null -> "Tap Folder to choose where your karaoke songs are"
            Library.songs.isEmpty() -> "No songs found yet"
            else -> "${shown.size} of ${Library.songs.size} songs"
        }
    }

    private fun refreshTransport() {
        val c = controller
        val playing = c?.isPlaying == true
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        playPause.setImageResource(icon)
        miniPlay.setImageResource(icon)
        val vIcon = if (NowPlaying.vocalsOn) R.drawable.ic_mic else R.drawable.ic_mic_off
        vocals.setImageResource(vIcon)
        miniVocals.setImageResource(vIcon)
        vocals.alpha = if (NowPlaying.hasVocalStem) 1f else 0.3f
        val title = if (NowPlaying.artist.isEmpty()) NowPlaying.title else "${NowPlaying.title} — ${NowPlaying.artist}"
        val hasSong = c != null && c.mediaItemCount > 0 && title.isNotEmpty()
        miniTitle.text = if (hasSong) title else "Nothing playing"
        playerTitle.text = title
    }

    private fun updateSeek() {
        val c = controller ?: return
        val dur = c.duration
        if (dur > 0 && !seeking) {
            seek.max = (dur / 1000).toInt()
            seek.progress = (c.currentPosition / 1000).toInt()
        }
    }

    private fun showPlayer(show: Boolean) {
        playerScreen.visibility = if (show) View.VISIBLE else View.GONE
        listScreen.visibility = if (show) View.GONE else View.VISIBLE
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- layout -------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    private fun loukaiButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.loukai_blue))
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.loukai_bg)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // List screen
        search = EditText(this).apply {
            hint = "Search songs"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshList()
            })
        }
        val folder = loukaiButton("Folder") { pickFolder() }
        val rescanButton = loukaiButton("Rescan") { rescan() }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(2))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.loukai_robot) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@MainActivity).apply {
                text = "Loukai"
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(10), 0, 0, 0)
            })
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(rescanButton)
            addView(folder)
        }
        status = TextView(this).apply {
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        list = ListView(this).apply {
            isFastScrollEnabled = true
            divider = android.graphics.drawable.ColorDrawable(getColor(R.color.loukai_border))
            dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let(::playSong) }
        }
        miniTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine()
            setPadding(dp(16), 0, dp(8), 0)
            setOnClickListener { if (controller?.mediaItemCount ?: 0 > 0) showPlayer(true) }
        }
        miniVocals = iconButton(R.drawable.ic_mic_off) { toggleVocals() }
        miniPlay = iconButton(R.drawable.ic_play) { togglePlay() }
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.loukai_panel))
            addView(miniTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(miniVocals)
            addView(miniPlay)
        }
        listScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(top)
            addView(status)
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(mini)
        }

        // Player screen
        playerTitle = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setSingleLine()
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    controller?.seekTo(sb.progress * 1000L)
                }
            })
        }
        playPause = iconButton(R.drawable.ic_play) { togglePlay() }
        vocals = iconButton(R.drawable.ic_mic_off) { toggleVocals() }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(iconButton(R.drawable.ic_list) { showPlayer(false) })
            addView(iconButton(R.drawable.ic_skip_previous) { controller?.seekToPrevious() })
            addView(playPause)
            addView(iconButton(R.drawable.ic_skip_next) { controller?.seekToNext() })
            addView(vocals)
        }
        playerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(playerTitle)
            addView(KaraokeView(this@MainActivity), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(seek)
            addView(transport)
        }

        root.addView(listScreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(playerScreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }
}

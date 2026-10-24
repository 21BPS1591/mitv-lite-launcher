package dev.lite.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

private const val MAX_TILE_DP = 160
private const val MAX_ASPECT = 16f / 9
private const val MIN_ASPECT = 16f / 9
private const val TILE_GAP_DP = 10
private const val UPDATE_CHECK_MS = 6 * 60 * 60_000L
private const val PAUSE_GRACE_MS = 15 * 60_000L
private const val DEFAULT_TILE = 0xFF262D3B.toInt()

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var area: FrameLayout
    private lateinit var title: TextView
    private lateinit var prefs: Prefs

    private var apps: List<App> = emptyList()
    private val visible = mutableListOf<App>()
    private var moving: App? = null

    private lateinit var connectivity: ConnectivityManager
    private lateinit var wifiIcon: View
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { updateNetwork() }
        override fun onLost(network: Network) = runOnUiThread { updateNetwork(lost = network) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            runOnUiThread { updateNetwork() }
    }

    private lateinit var mediaBar: View
    private lateinit var mediaArt: ImageView
    private lateinit var mediaTitleBox: View
    private lateinit var mediaTitle: TextView
    private var ticker: ObjectAnimator? = null
    private lateinit var mediaArtist: TextView
    private lateinit var mediaPlay: ImageView
    private var controller: MediaController? = null
    private var shownTitle: String? = null
    private var shownArtist: String? = null
    private var shownArt: Bitmap? = null
    private var placeholderShown = false
    private var shownPlaying: Boolean? = null
    private var browser: MediaBrowser? = null
    private lateinit var updateButton: View
    private var pendingUpdate: Update? = null
    private var lastUpdateCheck = 0L
    private val hideBar = Runnable { mediaBar.visibility = View.GONE }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showTrack()
        override fun onPlaybackStateChanged(state: PlaybackState?) = showTrack()
        override fun onSessionDestroyed() = dropBrowser()
    }

    private val io = Executors.newSingleThreadExecutor()
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        grid = findViewById(R.id.grid)
        area = findViewById(R.id.area)
        area.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) fit()
        }
        title = findViewById(R.id.title)
        prefs = Prefs(this)

        findViewById<View>(R.id.settings).setOnClickListener { launch(Intent(Settings.ACTION_SETTINGS)) }
        findViewById<View>(R.id.hidden).setOnClickListener { showHidden() }
        listOf(R.id.settings, R.id.hidden).forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { v, focused ->
                if (focused) title.text = v.contentDescription
            }
        }

        mediaBar = findViewById(R.id.media_bar)
        mediaArt = findViewById(R.id.media_art)
        mediaTitle = findViewById(R.id.media_title)
        mediaArtist = findViewById(R.id.media_artist)
        mediaPlay = findViewById(R.id.media_play)
        mediaArt.clipToOutline = true
        mediaTitleBox = findViewById(R.id.media_title_box)
        mediaTitleBox.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) updateTitle()
        }
        findViewById<View>(R.id.media_prev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        findViewById<View>(R.id.media_next).setOnClickListener { controller?.transportControls?.skipToNext() }
        mediaPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }

        updateButton = findViewById(R.id.update)
        updateButton.setOnClickListener { installUpdate() }

        wifiIcon = findViewById(R.id.net_wifi)
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        updateNetwork()

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reload()
    }

    // Reconnect every time we return in case Spotify wasn't running (or was closed) while we were away.
    override fun onResume() {
        super.onResume()
        updateTitle()
        connectSpotify()
        checkForUpdate()
        showWhatsNew()
    }

    /** After an update, tell the user what changed once; a fresh install just records the version. */
    private fun showWhatsNew() {
        val current = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        val seen = prefs.seenVersion
        if (seen == current) return
        if (seen == null) {
            prefs.seenVersion = current
            return
        }
        io.execute {
            val notes = Updater.notes(current)
            runOnUiThread {
                prefs.seenVersion = current
                if (notes == null || isFinishing) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.whats_new, current))
                    .setMessage(notes)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** At most one GitHub check every few hours; the header icon appears only when a newer release exists. */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_CHECK_MS) return
        lastUpdateCheck = now
        io.execute {
            val found = Updater.check(this)
            runOnUiThread {
                pendingUpdate = found
                updateButton.visibility = if (found != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun installUpdate() {
        val update = pendingUpdate ?: return
        Toast.makeText(this, R.string.updating, Toast.LENGTH_LONG).show()
        io.execute {
            if (!Updater.install(this, update)) {
                runOnUiThread { Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onPause() {
        ticker?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        ticker?.cancel()
        controller?.unregisterCallback(controllerCallback)
        browser?.disconnect()
        connectivity.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(packageReceiver)
        io.shutdown()
        super.onDestroy()
    }

    // Home pressed while already home: jump back to the first app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (moving != null) endMove()
        grid.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() {
        if (moving != null) endMove()
    }

    /** Show the Wi-Fi icon while connected over Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun updateNetwork(lost: Network? = null) {
        var wifi = false
        for (network in connectivity.allNetworks) {
            if (network == lost) continue
            if (connectivity.getNetworkInfo(network)?.isConnected != true) continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
        }
        wifiIcon.visibility = if (wifi) View.VISIBLE else View.GONE
    }

    /** Spotify's own browse service hands out its media session to any app that connects, no permission needed. */
    private fun connectSpotify() {
        if (browser != null) return
        val service = ComponentName("com.spotify.tv.android", "com.spotify.tv.android.service.SpotifyMediaBrowseService")
        browser = MediaBrowser(this, service, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                browser?.let { pick(listOf(MediaController(this@MainActivity, it.sessionToken))) }
            }
            override fun onConnectionFailed() = dropBrowser()
            override fun onConnectionSuspended() = dropBrowser()
        }, null).also { it.connect() }
    }

    private fun dropBrowser() {
        browser?.disconnect()
        browser = null
        pick(emptyList())
    }

    /** Follow Spotify when it has a session, otherwise whatever else is playing. */
    private fun pick(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.packageName.startsWith("com.spotify") } ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback)
        }
        showTrack()
    }

    /** Long titles scroll as a steady ticker; Android's own marquee stalls at the start of every loop. */
    private fun updateTitle() {
        val name = shownTitle ?: return
        val boxWidth = mediaTitleBox.width
        if (boxWidth == 0) return
        ticker?.cancel()
        ticker = null
        val paint = mediaTitle.paint
        val fits = paint.measureText(name) <= boxWidth
        val gap = "     "
        mediaTitle.text = if (fits) name else name + gap + name
        mediaTitle.layoutParams = mediaTitle.layoutParams.apply {
            width = if (fits) ViewGroup.LayoutParams.WRAP_CONTENT else paint.measureText(mediaTitle.text.toString()).toInt() + 2
        }
        mediaTitle.translationX = 0f
        if (fits) return
        val shift = paint.measureText(name + gap)
        val speed = 45 * resources.displayMetrics.density
        ticker = ObjectAnimator.ofFloat(mediaTitle, View.TRANSLATION_X, 0f, -shift).apply {
            duration = (shift / speed * 1000).toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun showTrack() {
        val meta = controller?.metadata
        val name = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (name == null) {
            mediaBar.visibility = View.GONE
            return
        }
        // Spotify keeps its last session alive after playback ends. Only a playing session opens the bar;
        // once shown, a pause keeps it for a while so it can be resumed, then it goes away again.
        mediaBar.removeCallbacks(hideBar)
        val playingNow = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!playingNow) {
            if (mediaBar.visibility != View.VISIBLE) return
            mediaBar.postDelayed(hideBar, PAUSE_GRACE_MS)
        }
        // Playback updates arrive constantly; touching the views only on real changes keeps the marquee
        // running and stops the cover flickering when an update comes without art.
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        if (name != shownTitle) {
            shownTitle = name
            updateTitle()
        }
        if (artist != shownArtist) {
            shownArtist = artist
            mediaArtist.text = artist
        }
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) {
            if (art !== shownArt) {
                shownArt = art
                mediaArt.setPadding(0, 0, 0, 0)
                mediaArt.setImageBitmap(art)
            }
        } else if (shownArt == null && !placeholderShown) {
            placeholderShown = true
            val pad = (12 * resources.displayMetrics.density).toInt()
            mediaArt.setPadding(pad, pad, pad, pad)
            mediaArt.setImageResource(R.drawable.ic_music)
        }
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing != shownPlaying) {
            shownPlaying = playing
            mediaPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        mediaBar.visibility = View.VISIBLE
    }

    private fun reload() {
        io.execute {
            val loaded = Apps.load(this)
            runOnUiThread {
                apps = Apps.sort(loaded, prefs.order)
                render((currentFocus?.tag as? App)?.pkg)
            }
        }
    }

    private fun render(focusPkg: String?) {
        val hidden = prefs.hidden
        visible.clear()
        visible += apps.filterNot { it.pkg in hidden }
        grid.removeAllViews()
        visible.forEach { grid.addView(tile(it)) }

        val target = visible.indexOfFirst { it.pkg == focusPkg }.coerceAtLeast(0)
        fit()
        grid.getChildAt(target)?.requestFocus()
    }

    /** Everything must fit on one screen: pick the column count and 16:9 tile size that gives the biggest tiles. */
    private fun fit() {
        val n = grid.childCount
        val availW = area.width - area.paddingLeft - area.paddingRight
        val availH = area.height - area.paddingTop - area.paddingBottom
        if (n == 0 || availW <= 0 || availH <= 0) return
        val d = resources.displayMetrics.density
        val gap = (TILE_GAP_DP * d).toInt()
        var bestW = 0
        var bestH = 0
        var bestCols = 1
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            var w = availW / cols - 2 * gap
            var h = availH / rows - 2 * gap
            if (w > h * MAX_ASPECT) w = (h * MAX_ASPECT).toInt()
            if (w < h * MIN_ASPECT) h = (w / MIN_ASPECT).toInt()
            val cap = (MAX_TILE_DP * d).toInt()
            if (w > cap) { h = h * cap / w; w = cap }
            if (w * h >= bestW * bestH) { bestW = w; bestH = h; bestCols = cols }
        }
        val h = bestH
        grid.columnCount = bestCols
        for (i in 0 until n) {
            val v = grid.getChildAt(i)
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = bestW
            lp.height = h
            lp.setMargins(gap, gap, gap, gap)
            v.layoutParams = lp
            val icon = v.findViewById<ImageView>(R.id.icon)
            icon.layoutParams = icon.layoutParams.apply { width = h * 71 / 100; height = h * 71 / 100 }
            v.findViewById<TextView>(R.id.label).setTextSize(TypedValue.COMPLEX_UNIT_PX, (h * 0.17f).coerceIn(10 * d, 15 * d))
        }
    }

    private fun tile(app: App): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile, grid, false)
        v.tag = app
        v.clipToOutline = true
        val banner = v.findViewById<ImageView>(R.id.banner)
        if (app.banner != null) {
            banner.setImageDrawable(app.banner)
            banner.visibility = View.VISIBLE
            v.findViewById<View>(R.id.fallback).visibility = View.GONE
        } else {
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            v.findViewById<TextView>(R.id.label).text = app.label
            v.background = GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(tint(app.icon))
            }
        }

        v.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.1f else 1f
            view.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 8f else 0f).setDuration(120).start()
            if (focused) title.text = if (moving === app) getString(R.string.move_hint) else app.label
        }
        v.setOnClickListener { if (moving != null) endMove() else launch(app.intent) }
        v.setOnLongClickListener { if (moving == null) showMenu(app); true }
        return v
    }

    /** Dark version of the icon's average color, so icon-only tiles don't look empty. */
    private fun tint(icon: Drawable): Int {
        val size = 16
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = icon.constantState?.newDrawable()?.mutate() ?: return DEFAULT_TILE
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until size) for (y in 0 until size) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0) return DEFAULT_TILE
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 0.8f).coerceAtMost(0.7f)
        hsv[2] = 0.38f
        return Color.HSVToColor(hsv)
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(app: App) {
        val actions = mutableListOf(
            getString(R.string.move), getString(R.string.hide), getString(R.string.app_info),
        )
        // System apps can't be uninstalled, so don't offer it.
        if (!app.system) actions += getString(R.string.uninstall)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions.toTypedArray()) { _, which ->
                val uri = Uri.fromParts("package", app.pkg, null)
                when (which) {
                    0 -> startMove(app)
                    1 -> hideApp(app)
                    2 -> launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    3 -> launch(Intent(Intent.ACTION_DELETE, uri))
                }
            }
            .show()
    }

    private fun hideApp(app: App) {
        prefs.hidden = prefs.hidden + app.pkg
        render(null)
    }

    private fun showHidden() {
        val hidden = apps.filter { it.pkg in prefs.hidden }
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.unhide)
            .setItems(hidden.map { it.label }.toTypedArray()) { _, which ->
                val app = hidden[which]
                prefs.hidden = prefs.hidden - app.pkg
                render(app.pkg)
            }
            .show()
    }

    private fun tileOf(app: App): View? = (0 until grid.childCount).map { grid.getChildAt(it) }.find { it.tag === app }

    private fun startMove(app: App) {
        moving = app
        tileOf(app)?.isSelected = true
        title.setText(R.string.move_hint)
    }

    private fun endMove() {
        val app = moving ?: return
        moving = null
        tileOf(app)?.isSelected = false
        title.text = app.label
        val hiddenPkgs = apps.map { it.pkg }.filter { it in prefs.hidden }
        prefs.order = visible.map { it.pkg } + hiddenPkgs
        apps = Apps.sort(apps, prefs.order)
    }

    // While moving, D-pad shifts the tile instead of the focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = moving ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val from = visible.indexOf(app)
        val cols = grid.columnCount
        val to = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
            KeyEvent.KEYCODE_DPAD_UP -> from - cols
            KeyEvent.KEYCODE_DPAD_DOWN -> from + cols
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                endMove()
                return true
            }
            else -> return true
        }
        if (to in visible.indices) {
            visible.removeAt(from)
            visible.add(to, app)
            val v = grid.getChildAt(from)
            grid.removeViewAt(from)
            grid.addView(v, to)
            v.requestFocus()
        }
        return true
    }
}

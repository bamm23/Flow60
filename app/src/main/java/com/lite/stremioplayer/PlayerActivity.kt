package com.lite.stremioplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import java.util.Locale

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var surfaceView: MemcGLSurfaceView
    private lateinit var subtitleView: SubtitleView
    private lateinit var tvStatus: TextView
    private lateinit var tvQuickFeedback: TextView
    private lateinit var osdContainer: LinearLayout
    private lateinit var tvTime: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnPlayPause: Button
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitles: Button
    private lateinit var btnAspect: Button
    private lateinit var btnMemc: Button
    private lateinit var btnImage: Button
    private lateinit var btnSubSize: Button
    private lateinit var btnSubBg: Button
    private lateinit var btnReloadSubs: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownPositionMs: Long = 0L
    private var currentStreamKey: String = ""
    private var currentVideoUri: Uri? = null
    private var backPressedTime: Long = 0L
    private var isVerticalStretch = true
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0

    // Control de búsqueda progresiva (toque corto 10s, toque continuo más rápido)
    private var isHoldingSeek = false
    private var pendingSeekMs = 0L
    private var totalSeekDeltaMs = 0L

    // Estado de interpolación MEMC y control térmico
    private var userWantsMemc = true
    private var memcBypassReason: String? = null
    private var thermalTickCounter = 0

    // Ajustes editables de post-procesado de imagen (Nitidez CAS, Contraste, Saturación)
    private val sharpValues = floatArrayOf(0.0f, 0.25f, 0.50f, 0.80f)
    private val sharpLabels = arrayOf("OFF", "Baja", "Media", "Alta")
    private var sharpIndex = 0

    private val contrastValues = floatArrayOf(1.0f, 1.06f, 1.12f, 1.18f, 0.94f)
    private val contrastLabels = arrayOf("Original", "+6%", "+12%", "+18%", "-6%")
    private var contrastIndex = 0

    private val satValues = floatArrayOf(1.0f, 1.08f, 1.16f, 1.25f, 0.90f)
    private val satLabels = arrayOf("Original", "+8%", "+16%", "+25%", "-10%")
    private var satIndex = 0

    // Lista en memoria de subtítulos externos (Intent + Addons de Stremio)
    private val loadedExternalSubs = mutableListOf<MediaItem.SubtitleConfiguration>()

    // Preferencias de apariencia de subtítulos
    private val subSizeValues = floatArrayOf(18f, 22f, 26f, 32f, 38f)
    private val subSizePercentLabels = arrayOf("80%", "100%", "120%", "145%", "170%")
    private var subSizeIndex = 2 // Por defecto: 120% (26sp)

    private val subBgValues = intArrayOf(
        Color.TRANSPARENT,
        Color.argb(102, 0, 0, 0), // 40%
        Color.argb(166, 0, 0, 0), // 65%
        Color.BLACK               // 100%
    )
    private val subBgPercentLabels = arrayOf("0%", "40%", "65%", "100%")
    private var subBgIndex = 0

    private val progressRunnable = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && exo.isPlaying) {
                val pos = exo.currentPosition
                val dur = exo.duration
                if (pos > 0) {
                    lastKnownPositionMs = pos
                    savePosition(pos)
                }
                if (osdContainer.visibility == View.VISIBLE && dur > 0) {
                    tvTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    progressBar.progress = ((pos * 1000) / dur).toInt()
                }
            }

            thermalTickCounter++
            if (thermalTickCounter >= 15) { // 15 * 2s = 30 segundos
                thermalTickCounter = 0
                checkThermalStatus()
            }

            handler.postDelayed(this, 2000)
        }
    }

    private val hideOsdRunnable = Runnable {
        osdContainer.visibility = View.GONE
    }

    private val hideFeedbackRunnable = Runnable {
        tvQuickFeedback.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        surfaceView = findViewById(R.id.surfaceView)
        subtitleView = findViewById(R.id.subtitleView)
        tvStatus = findViewById(R.id.tvStatus)
        tvQuickFeedback = findViewById(R.id.tvQuickFeedback)
        osdContainer = findViewById(R.id.osdContainer)
        tvTime = findViewById(R.id.tvTime)
        progressBar = findViewById(R.id.progressBar)

        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnAudio = findViewById(R.id.btnAudio)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnAspect = findViewById(R.id.btnAspect)
        btnMemc = findViewById(R.id.btnMemc)
        btnImage = findViewById(R.id.btnImage)
        btnSubSize = findViewById(R.id.btnSubSize)
        btnSubBg = findViewById(R.id.btnSubBg)
        btnReloadSubs = findViewById(R.id.btnReloadSubs)

        // Estilizado visual limpio para TV (esquinas redondeadas y foco destacado)
        styleUiElements()

        // Cargar preferencias guardadas
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        subSizeIndex = prefs.getInt("sub_size_index", 2).coerceIn(0, subSizeValues.lastIndex)
        subBgIndex = prefs.getInt("sub_bg_index", 0).coerceIn(0, subBgValues.lastIndex)
        userWantsMemc = prefs.getBoolean("memc_enabled", true)
        sharpIndex = prefs.getInt("img_sharp_idx", 0).coerceIn(0, sharpValues.lastIndex)
        contrastIndex = prefs.getInt("img_contrast_idx", 0).coerceIn(0, contrastValues.lastIndex)
        satIndex = prefs.getInt("img_sat_idx", 0).coerceIn(0, satValues.lastIndex)

        applySubtitleStyle()
        applyImageEnhancements()
        applyMemcState()

        // Vincular el Surface OpenGL con ExoPlayer cuando el contexto EGL esté listo
        surfaceView.setOnSurfaceReadyListener { surface ->
            player?.setVideoSurface(surface)
        }

        // Eventos de botones
        btnPlayPause.setOnClickListener {
            togglePlayPause(showFeedback = false)
            resetOsdTimer()
        }
        btnAudio.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_AUDIO, "Seleccionar Audio")
        }
        btnSubtitles.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_TEXT, "Seleccionar Subtítulos")
        }
        btnAspect.setOnClickListener {
            isVerticalStretch = !isVerticalStretch
            btnAspect.text = if (isVerticalStretch) "⛶ 16:9" else "⛶ Original"
            applyAspectRatio()
            resetOsdTimer()
        }
        btnMemc.setOnClickListener {
            if (memcBypassReason != null) {
                Toast.makeText(
                    this,
                    "60 FPS desactivado automáticamente ($memcBypassReason)",
                    Toast.LENGTH_LONG
                ).show()
                resetOsdTimer()
                return@setOnClickListener
            }
            userWantsMemc = !userWantsMemc
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("memc_enabled", userWantsMemc)
                .apply()
            applyMemcState()
            resetOsdTimer()
        }
        btnImage.setOnClickListener {
            showImageSettingsDialog()
            resetOsdTimer()
        }
        btnSubSize.setOnClickListener {
            subSizeIndex = (subSizeIndex + 1) % subSizeValues.size
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("sub_size_index", subSizeIndex)
                .apply()
            applySubtitleStyle()
            resetOsdTimer()
        }
        btnSubBg.setOnClickListener {
            subBgIndex = (subBgIndex + 1) % subBgValues.size
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("sub_bg_index", subBgIndex)
                .apply()
            applySubtitleStyle()
            resetOsdTimer()
        }
        btnReloadSubs.setOnClickListener {
            fetchSubtitlesFromStremioAddons(isManualReload = true)
            resetOsdTimer()
        }

        handleIntent(intent)
    }

    private fun styleUiElements() {
        val density = resources.displayMetrics.density
        val radius = 10f * density
        val padH = (14 * density).toInt()

        // Fondo degradado oscuro para el OSD inferior
        osdContainer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(180, 10, 10, 14), Color.argb(242, 8, 8, 12))
        )

        // Fondo redondeado para el indicador rápido flotante
        tvQuickFeedback.background = GradientDrawable().apply {
            setColor(Color.argb(215, 16, 16, 22))
            cornerRadius = 24f * density
            setStroke((1.5f * density).toInt(), Color.argb(90, 255, 255, 255))
        }

        val allButtons = listOf(
            btnPlayPause, btnAudio, btnSubtitles, btnAspect, btnMemc, btnImage,
            btnSubSize, btnSubBg, btnReloadSubs
        )

        for (btn in allButtons) {
            val focusedDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#FF9800"))
                cornerRadius = radius
                setStroke((2f * density).toInt(), Color.WHITE)
            }
            val normalDrawable = GradientDrawable().apply {
                setColor(Color.argb(65, 255, 255, 255))
                cornerRadius = radius
                setStroke((1f * density).toInt(), Color.argb(85, 255, 255, 255))
            }

            val selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
                addState(intArrayOf(android.R.attr.state_pressed), focusedDrawable)
                addState(intArrayOf(), normalDrawable)
            }

            btn.background = selector
            btn.setTextColor(Color.WHITE)
            btn.setPadding(padH, 0, padH, 0)
            btn.setOnFocusChangeListener { v, hasFocus ->
                val b = v as Button
                b.setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
                v.animate().scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(100)
                    .start()
            }
        }
    }

    private fun applySubtitleStyle() {
        val bgColor = subBgValues[subBgIndex]
        val edgeType = if (bgColor == Color.TRANSPARENT) {
            CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else {
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        }

        // Desactivar estilos embebidos para que el tamaño y la opacidad elegidos SIEMPRE se apliquen
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)
        subtitleView.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                bgColor,
                Color.TRANSPARENT,
                edgeType,
                Color.BLACK,
                null
            )
        )
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subSizeValues[subSizeIndex])
        subtitleView.invalidate()

        btnSubSize.text = "Aa ${subSizePercentLabels[subSizeIndex]}"
        btnSubBg.text = "◫ Fondo: ${subBgPercentLabels[subBgIndex]}"
    }

    private fun applyImageEnhancements() {
        surfaceView.setImageEnhancements(
            sharpValues[sharpIndex],
            contrastValues[contrastIndex],
            satValues[satIndex]
        )
        val hasCustom = sharpIndex != 0 || contrastIndex != 0 || satIndex != 0
        btnImage.text = if (hasCustom) "🎨 Imagen *" else "🎨 Imagen"
    }

    private fun showImageSettingsDialog() {
        val options = arrayOf(
            "Nitidez (CAS): ${sharpLabels[sharpIndex]}  [Cambiar]",
            "Contraste: ${contrastLabels[contrastIndex]}  [Cambiar]",
            "Color / Saturación: ${satLabels[satIndex]}  [Cambiar]",
            "Restablecer todo a Original"
        )

        AlertDialog.Builder(this)
            .setTitle("Ajustes de Imagen (En vivo)")
            .setItems(options) { _, which ->
                val editor = getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit()
                when (which) {
                    0 -> {
                        sharpIndex = (sharpIndex + 1) % sharpValues.size
                        editor.putInt("img_sharp_idx", sharpIndex).apply()
                    }
                    1 -> {
                        contrastIndex = (contrastIndex + 1) % contrastValues.size
                        editor.putInt("img_contrast_idx", contrastIndex).apply()
                    }
                    2 -> {
                        satIndex = (satIndex + 1) % satValues.size
                        editor.putInt("img_sat_idx", satIndex).apply()
                    }
                    3 -> {
                        sharpIndex = 0
                        contrastIndex = 0
                        satIndex = 0
                        editor.putInt("img_sharp_idx", 0)
                            .putInt("img_contrast_idx", 0)
                            .putInt("img_sat_idx", 0)
                            .apply()
                    }
                }
                applyImageEnhancements()
                resetOsdTimer()
                // Reabrir el diálogo para seguir ajustando cómodamente sin cerrar
                if (which != 3) {
                    showImageSettingsDialog()
                } else {
                    Toast.makeText(this, "Imagen restablecida a Original", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cerrar") { _, _ -> resetOsdTimer() }
            .show()
    }

    private fun togglePlayPause(showFeedback: Boolean) {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            btnPlayPause.text = "▶"
            if (showFeedback) showQuickFeedback("⏸ Pausado")
        } else {
            exo.play()
            btnPlayPause.text = "⏸"
            if (showFeedback) showQuickFeedback("▶ Reproduciendo")
        }
    }

    private fun showQuickFeedback(text: String, durationMs: Long = 1300L) {
        tvQuickFeedback.text = text
        tvQuickFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedbackRunnable)
        handler.postDelayed(hideFeedbackRunnable, durationMs)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val videoUri: Uri? = intent.data
        if (videoUri == null) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Esperando enlace desde Stremio...\nSelecciona 'Reproducir en reproductor externo' en Stremio."
            return
        }

        currentVideoUri = videoUri
        currentStreamKey = videoUri.path ?: videoUri.toString()

        val intentPos = intent.getIntExtra("position", -1).toLong()
        val savedPos = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .getLong(currentStreamKey, 0L)
        lastKnownPositionMs = if (intentPos > 0) intentPos else savedPos

        loadedExternalSubs.clear()
        loadedExternalSubs.addAll(extractExternalSubtitles(intent))

        initPlayer(videoUri, loadedExternalSubs)

        if (loadedExternalSubs.isEmpty()) {
            fetchSubtitlesFromStremioAddons(isManualReload = false)
        }
    }

    private fun fetchSubtitlesFromStremioAddons(isManualReload: Boolean) {
        val uri = currentVideoUri ?: return

        val intentSubs = extractExternalSubtitles(intent)
        if (intentSubs.isNotEmpty() && loadedExternalSubs.isEmpty()) {
            loadedExternalSubs.addAll(intentSubs)
            injectExternalSubtitlesIntoPlayer(uri)
            Toast.makeText(this, "Subtítulos del Intent cargados (${intentSubs.size})", Toast.LENGTH_SHORT).show()
            return
        }

        if (isManualReload) {
            Toast.makeText(this, "Buscando subtítulos en addons de Stremio...", Toast.LENGTH_SHORT).show()
        }

        StremioSubFetcher.fetchSubtitlesAsync(intent, uri) { newSubs, statusMessage ->
            if (newSubs.isNotEmpty()) {
                loadedExternalSubs.clear()
                loadedExternalSubs.addAll(intentSubs)
                loadedExternalSubs.addAll(newSubs)
                injectExternalSubtitlesIntoPlayer(uri)
                Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
            } else if (isManualReload) {
                Toast.makeText(this, statusMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun injectExternalSubtitlesIntoPlayer(uri: Uri) {
        val exo = player ?: return
        val currentPos = exo.currentPosition.coerceAtLeast(lastKnownPositionMs)
        val wasPlaying = exo.playWhenReady

        val updatedMediaItem = MediaItem.Builder()
            .setUri(uri)
            .setSubtitleConfigurations(loadedExternalSubs.toList())
            .build()

        surfaceView.resetFrames()
        exo.setMediaItem(updatedMediaItem, currentPos)
        exo.prepare()
        exo.playWhenReady = wasPlaying
    }

    @Suppress("DEPRECATION")
    private fun extractExternalSubtitles(intent: Intent): List<MediaItem.SubtitleConfiguration> {
        val configs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val subsArray = intent.getParcelableArrayExtra("subs")
        val subsNames = intent.getStringArrayExtra("subs.name")

        subsArray?.forEachIndexed { index, parcelable ->
            if (parcelable is Uri) {
                val label = subsNames?.getOrNull(index) ?: "Subtítulo Externo ${index + 1}"
                val uriStr = parcelable.toString().lowercase(Locale.ROOT)
                val mime = when {
                    uriStr.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                    uriStr.endsWith(".ass") || uriStr.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                configs.add(
                    MediaItem.SubtitleConfiguration.Builder(parcelable)
                        .setMimeType(mime)
                        .setLanguage(label)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }

        val singleSub = intent.getStringExtra("subtitles_location")
        if (!singleSub.isNullOrEmpty()) {
            configs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(singleSub))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("Subtítulo Stremio")
                    .setLabel("Subtítulo Stremio")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }
        return configs
    }

    private fun initPlayer(videoUri: Uri, subs: List<MediaItem.SubtitleConfiguration>) {
        releasePlayer()

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(4_000, 12_000, 1_500, 3_000)
            .setTargetBufferBytes(14 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        surfaceView.resetFrames()
        memcBypassReason = null
        thermalTickCounter = 0

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                surfaceView.getVideoSurface()?.let { setVideoSurface(it) }
                addListener(playerListener)

                val mediaItem = MediaItem.Builder()
                    .setUri(videoUri)
                    .setSubtitleConfigurations(subs.toList())
                    .build()

                setMediaItem(mediaItem)
                if (lastKnownPositionMs > 0) {
                    seekTo(lastKnownPositionMs)
                }
                prepare()
                playWhenReady = true
            }

        checkThermalStatus()
        applyMemcState()
        applyImageEnhancements()
        handler.post(progressRunnable)
    }

    private val playerListener = object : Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            currentVideoWidth = videoSize.width
            currentVideoHeight = videoSize.height
            surfaceView.setVideoSize(videoSize.width, videoSize.height)
            evaluateAutoBypass()
            applyAspectRatio()
        }

        override fun onTracksChanged(tracks: Tracks) {
            evaluateAutoBypass()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            surfaceView.setPlaying(isPlaying)
            btnPlayPause.text = if (isPlaying) "⏸" else "▶"
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                surfaceView.resetFrames()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Cargando búfer..."
                }
                Player.STATE_READY -> {
                    tvStatus.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    savePosition(0L)
                    finish()
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            if (exo.currentPosition > 0) {
                lastKnownPositionMs = exo.currentPosition
                savePosition(lastKnownPositionMs)
            }
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Microcorte detectado. Reconectando en ${formatTime(lastKnownPositionMs)}..."

            handler.postDelayed({
                player?.let {
                    surfaceView.resetFrames()
                    it.seekTo(lastKnownPositionMs)
                    it.prepare()
                    it.playWhenReady = true
                }
            }, 2500)
        }
    }

    private fun evaluateAutoBypass() {
        val format = player?.videoFormat
        val is4k = currentVideoWidth > 1920 || currentVideoHeight > 1080
        val isHighFps = format != null && format.frameRate > 45f
        val isHdr = format?.colorInfo != null && ColorInfo.isTransferHdr(format.colorInfo)

        val newReason = when {
            memcBypassReason == "Temperatura alta" -> "Temperatura alta"
            is4k -> "Video 4K"
            isHdr -> "Video HDR"
            isHighFps -> "Ya es 50/60 FPS"
            else -> null
        }
        memcBypassReason = newReason
        applyMemcState()
    }

    private fun checkThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val status = pm.currentThermalStatus

        if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            if (memcBypassReason != "Temperatura alta") {
                memcBypassReason = "Temperatura alta"
                applyMemcState()
                Toast.makeText(
                    this,
                    "Temperatura alta: 60 FPS pausado para proteger el dispositivo",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (status <= PowerManager.THERMAL_STATUS_LIGHT && memcBypassReason == "Temperatura alta") {
            memcBypassReason = null
            evaluateAutoBypass()
        }
    }

    private fun applyMemcState() {
        val active = userWantsMemc && memcBypassReason == null
        surfaceView.setMemcEnabled(active)
        btnMemc.text = when {
            memcBypassReason != null -> "⚡ 60 FPS: Auto-OFF"
            active -> "⚡ 60 FPS: ON"
            else -> "⚡ 60 FPS: OFF"
        }
    }

    private fun applyAspectRatio() {
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (isVerticalStretch || currentVideoWidth == 0 || currentVideoHeight == 0) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
        } else {
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val videoRatio = currentVideoWidth.toFloat() / currentVideoHeight.toFloat()
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
            if (videoRatio > screenRatio) {
                params.width = screenWidth
                params.height = (screenWidth / videoRatio).toInt()
            } else {
                params.height = screenHeight
                params.width = (screenHeight * videoRatio).toInt()
            }
            params.gravity = Gravity.CENTER
        }
        surfaceView.layoutParams = params
    }

    private fun showTrackDialog(trackType: @C.TrackType Int, title: String) {
        val exo = player ?: return
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Desactivar subtítulos")
            actions.add {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            labels.add("↻ Buscar / Recargar subtítulos de Addons (Stremio)")
            actions.add {
                fetchSubtitlesFromStremioAddons(isManualReload = true)
            }
        }

        val groups = exo.currentTracks.groups
        for (group in groups) {
            if (group.type == trackType) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language?.let { Locale(it).displayLanguage } ?: ""
                        val label = format.label ?: ""
                        val codecs = format.sampleMimeType?.substringAfter("/") ?: ""
                        val selected = if (group.isTrackSelected(i)) "✓ " else ""
                        val displayName = "$selected${listOf(label, lang, codecs).filter { it.isNotEmpty() }.joinToString(" - ")}"
                            .ifBlank { "${selected}Pista ${labels.size + 1}" }

                        labels.add(displayName)
                        actions.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                        }
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
                resetOsdTimer()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exo = player ?: return super.onKeyDown(keyCode, event)
        val isOsdVisible = osdContainer.visibility == View.VISIBLE

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!isOsdVisible) {
                    if (event == null || event.repeatCount == 0) {
                        togglePlayPause(showFeedback = true)
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event == null || event.repeatCount == 0) {
                    togglePlayPause(showFeedback = !isOsdVisible)
                }
                if (isOsdVisible) resetOsdTimer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isOsdVisible) {
                    showOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOsdVisible) {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1L else -1L
                    val repeat = event?.repeatCount ?: 0
                    val dur = exo.duration

                    if (repeat == 0) {
                        isHoldingSeek = true
                        pendingSeekMs = exo.currentPosition
                        totalSeekDeltaMs = 0L
                    }

                    // Toque corto = 10s; toque continuo = acelera progresivamente (15s -> 30s -> 60s)
                    val stepMs = when {
                        repeat == 0 -> 10_000L
                        repeat <= 4 -> 15_000L
                        repeat <= 12 -> 30_000L
                        else -> 60_000L
                    }

                    val nextPos = pendingSeekMs + direction * stepMs
                    pendingSeekMs = if (dur > 0) nextPos.coerceIn(0L, dur) else nextPos.coerceAtLeast(0L)
                    totalSeekDeltaMs += direction * stepMs

                    val arrow = if (direction > 0) "⏩" else "⏪"
                    val sign = if (totalSeekDeltaMs >= 0) "+" else "-"
                    val absSec = kotlin.math.abs(totalSeekDeltaMs) / 1000L
                    val durText = if (dur > 0) " / ${formatTime(dur)}" else ""
                    showQuickFeedback("$arrow ${sign}${absSec}s  •  ${formatTime(pendingSeekMs)}$durText", 1800L)
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isOsdVisible) {
                    osdContainer.visibility = View.GONE
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - backPressedTime < 2000) {
                    savePosition(exo.currentPosition)
                    finish()
                } else {
                    backPressedTime = now
                    Toast.makeText(this, "Presiona ATRÁS otra vez para salir a Stremio", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        if (isOsdVisible) resetOsdTimer()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isHoldingSeek && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            isHoldingSeek = false
            player?.let { exo ->
                surfaceView.resetFrames()
                exo.seekTo(pendingSeekMs)
                lastKnownPositionMs = pendingSeekMs
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showOsd() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur > 0) {
            tvTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
            progressBar.progress = ((pos * 1000) / dur).toInt()
        }
        btnPlayPause.text = if (exo.isPlaying) "⏸" else "▶"
        osdContainer.visibility = View.VISIBLE
        btnPlayPause.requestFocus()
        resetOsdTimer()
    }

    private fun resetOsdTimer() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 5000)
    }

    private fun savePosition(pos: Long) {
        if (currentStreamKey.isNotEmpty() && pos > 0) {
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(currentStreamKey, pos)
                .apply()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(hideFeedbackRunnable)
        player?.let {
            if (it.currentPosition > 0) savePosition(it.currentPosition)
            it.clearVideoSurface()
            it.release()
        }
        player = null
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        player?.let {
            if (it.currentPosition > 0) savePosition(it.currentPosition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceView.releaseResources()
        releasePlayer()
    }
}

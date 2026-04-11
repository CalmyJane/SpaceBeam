package com.calmyjane.spacebeam

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.*

abstract class SourcePropertyControl(
    id: String,
    label: String,
    defaultValue: Int,
    val sourceId: String,
    val mainActivity: MainActivity
) : PropertyControl(
    id = id,
    label = label,
    defaultValue = defaultValue,
    outMin = 0f,
    outMax = 1f,
    hasModulation = true,
    includeInPreset = false,
    layoutStyle = LayoutStyle.ROW,
    iconResId = android.R.drawable.presence_video_online,
    defaultLocked = true
) {

    override fun addCategoryControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource(sourceId) ?: return
        buildTransformCategory(panel, context, channel)
    }

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource(sourceId) ?: return

        // Inject after dropdown
        panel.addView(TextView(context).apply {
            text = "INJECT AFTER"; textSize = 10f; setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 0)
        })
        val effectChain = mainActivity.effectChain
        val injectOptions = mutableListOf<Pair<String, String>>()
        injectOptions.add(Pair("FX_MIXER", "MIXER (default)"))
        effectChain.effects.forEach { effect ->
            if (effect.id != "FX_MIXER") injectOptions.add(Pair(effect.id, effect.name))
        }
        val currentIdx = injectOptions.indexOfFirst { it.first == channel.injectionPoint }.coerceAtLeast(0)
        val injectSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, injectOptions.map { it.second }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(currentIdx)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 10 }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    (parent?.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
                    channel.injectionPoint = injectOptions[pos].first
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        panel.addView(injectSpinner)

        // Blend mode dropdown
        panel.addView(TextView(context).apply {
            text = "BLEND MODE"; textSize=10f; setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 0)
        })
        val spinner = Spinner(context).apply {
            val modes = BlendMode.entries.map { it.label }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, modes).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(channel.blendMode.ordinal)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 10 }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    (parent?.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
                    channel.blendMode = BlendMode.entries[pos]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        panel.addView(spinner)

        val removeBtn = Button(context).apply {
            text = "REMOVE SOURCE"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#882222"))
                cornerRadius = 10f
                setStroke(1, Color.RED)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply {
                topMargin = 10
                bottomMargin = 10
            }
            // Deletion logic moved directly here
            setOnClickListener {
                mainActivity.removeSource(this@SourcePropertyControl)
            }
        }
        panel.addView(removeBtn)
    }

    abstract fun onRemove()
}

class ShaderSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        // First add the standard geometry controls (Flips, etc)
        super.addExtraControls(panel, context)

        // Create the EDIT button styled like the RESET button
        val editBtn = Button(context).apply {
            text = "EDIT SHADER"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110).apply {
                topMargin = 10
                bottomMargin = 10
            }
            setOnClickListener {
                val channel = mainActivity.getRendererSource(sourceId)
                val currentCode = channel?.customShaderCode ?: ""

                // Open the dialog in "Editing" mode
                mainActivity.showShaderSourceDialog(existingCode = currentCode, isEditing = true) { newCode ->
                    channel?.let {
                        it.customShaderCode = newCode
                        // Signal the renderer to re-compile the program on next frame
                        mainActivity.glView.queueEvent { it.isReady = false }
                    }
                }
            }
        }

        // Insert above the "REMOVE SOURCE" button (which is the last child)
        if (panel.childCount > 0) {
            panel.addView(editBtn, panel.childCount - 1)
        } else {
            panel.addView(editBtn)
        }
    }

    override fun onRemove() {
        // GL resources handled by SourceChannel.release()
    }
}


class CameraSourceControl(val mainActivity: MainActivity) : PropertyControl(
    id = "CAM_MAIN",
    label = "CAMERA",
    defaultValue = 1000,
    outMin = 0f,
    outMax = 1f,
    hasModulation = true,
    includeInPreset = true,
    layoutStyle = LayoutStyle.ROW,
    iconResId = android.R.drawable.ic_menu_camera,
    defaultLocked = true
) {
    override fun addCategoryControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource("CAM_MAIN") ?: return
        buildTransformCategory(panel, context, channel)
    }

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource("CAM_MAIN") ?: return

        // Inject after dropdown
        panel.addView(TextView(context).apply {
            text = "INJECT AFTER"; textSize = 10f; setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 0)
        })
        val effectChain = mainActivity.effectChain
        val injectOptions = mutableListOf<Pair<String, String>>()
        injectOptions.add(Pair("FX_MIXER", "MIXER (default)"))
        effectChain.effects.forEach { effect ->
            if (effect.id != "FX_MIXER") injectOptions.add(Pair(effect.id, effect.name))
        }
        val currentIdx = injectOptions.indexOfFirst { it.first == channel.injectionPoint }.coerceAtLeast(0)
        val injectSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, injectOptions.map { it.second }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(currentIdx)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 100).apply { bottomMargin = 10 }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    (parent?.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
                    channel.injectionPoint = injectOptions[pos].first
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        panel.addView(injectSpinner)
    }
}

class MediaSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity,
    private val playlist: MutableList<PlaylistItem>
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    // Thread-safe variables shared between Main Thread & GL Thread
    @Volatile private var activeLayerIndex = 0 // 0 = A, 1 = B
    @Volatile private var currentIndex = 0
    @Volatile private var nextIndex = 0
    @Volatile private var timeInCurrentItem = 0f
    @Volatile private var isTransitioning = false
    @Volatile private var currentCrossfadeDuration = 1f
    @Volatile private var crossfadeProgress = 0f

    var onPlaylistUpdated: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackChecker = object : Runnable {
        override fun run() {
            checkPlayback()
            mainHandler.postDelayed(this, 50)
        }
    }

    init {
        mainActivity.runOnUiThread {
            playerA = createPlayer()
            // playerB is created lazily in requirePlayerB() — only when a second layer is actually needed
            forceResync()
            mainHandler.post(playbackChecker)
        }
    }

    // Only call from UI thread. Creates playerB on first use (multi-item playlist or crossfading single item).
    private fun requirePlayerB(): ExoPlayer {
        return playerB ?: createPlayer().also { playerB = it }
    }

    private fun createPlayer(): ExoPlayer {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 3000, 500, 500)
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(mainActivity).setLoadControl(loadControl).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0) {
                        val ch = mainActivity.getRendererSource(sourceId)
                        val layer = if (this@apply == playerA) ch?.layerA else ch?.layerB
                        layer?.width = videoSize.width
                        layer?.height = videoSize.height
                        layer?.rotation = if (videoSize.height > videoSize.width) -90f else 0f
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    startTransition(0f)
                }
            })
        }
    }

    fun addItems(newItems: List<PlaylistItem>) {
        synchronized(playlist) {
            val wasEmpty = playlist.isEmpty()
            val oldSize = playlist.size
            playlist.addAll(newItems)
            subtitle = "${playlist.size} Items"

            if (wasEmpty) {
                forceResync()
            } else if (oldSize == 1 && playlist.size > 1) {
                nextIndex = 1
                preloadItem(nextIndex, if (activeLayerIndex == 0) 1 else 0, autoPlay = false)
            }
        }
        mainActivity.runOnUiThread { onPlaylistUpdated?.invoke() }
    }

    private fun forceResync() {
        synchronized(playlist) {
            val ch = mainActivity.getRendererSource(sourceId)
            if (playlist.isEmpty()) {
                ch?.isEmpty = true
                playerA?.pause()
                playerB?.pause()
                return
            }

            ch?.isEmpty = false
            if (currentIndex >= playlist.size) currentIndex = 0
            nextIndex = if (playlist.size > 1) (currentIndex + 1) % playlist.size else 0

            isTransitioning = false
            ch?.topLayerAlpha = 0f
            timeInCurrentItem = 0f

            preloadItem(currentIndex, activeLayerIndex, autoPlay = true)

            if (playlist.size > 1 || playlist.firstOrNull()?.crossfade ?: 0f > 0f) {
                preloadItem(nextIndex, if (activeLayerIndex == 0) 1 else 0, autoPlay = false)
            }
        }
    }

    private fun preloadItem(index: Int, targetLayer: Int, autoPlay: Boolean) {
        synchronized(playlist) {
            if (playlist.isEmpty() || index >= playlist.size) return
            val item = playlist[index]
            val ch = mainActivity.getRendererSource(sourceId) ?: return
            val layer = if (targetLayer == 0) ch.layerA else ch.layerB
            val player = if (targetLayer == 0) playerA else requirePlayerB()

            layer.isVideo = item.isVideo
            if (item.isVideo) {
                mainActivity.glView.queueEvent {
                    if (!ch.isReady) ch.init()
                    mainActivity.runOnUiThread {
                        player?.setVideoSurface(layer.surface)

                        // FIX: Unconditionally force a fresh 'prepare' to fully reset the decoder buffer.
                        // This prevents ExoPlayer from holding onto the last frame from a previous loop.
                        player?.setMediaItem(MediaItem.fromUri(item.uri))
                        player?.prepare()
                        player?.seekTo(0)

                        if (autoPlay) {
                            player?.play()
                        } else {
                            player?.pause()
                        }
                    }
                }
            } else {
                val bmp = mainActivity.loadScaledBitmap(item.uri)
                if (bmp != null) {
                    layer.bitmap = bmp
                    layer.imageUploaded = false
                    layer.width = bmp.width
                    layer.height = bmp.height
                    layer.rotation = if (bmp.height > bmp.width) -90f else 0f
                }
            }
        }
    }

    // Runs strictly on the Main UI Thread
    private fun checkPlayback() {
        synchronized(playlist) {
            if (playlist.isEmpty() || isTransitioning) return

            if (currentIndex >= playlist.size) {
                forceResync()
                return
            }

            val currentItem = playlist[currentIndex]
            var timeRemaining = 0f
            var safeCrossfade = 0f
            val player = if (activeLayerIndex == 0) playerA else playerB

            if (currentItem.isVideo) {
                val state = player?.playbackState
                val durLong = player?.duration ?: 0L

                if (state == Player.STATE_IDLE || state == Player.STATE_BUFFERING || durLong < 0L) return

                var targetDurationMs = durLong.toFloat() * currentItem.durationVal
                if (targetDurationMs < 500f) targetDurationMs = 500f

                val currentPos = player?.currentPosition?.toFloat() ?: 0f
                timeRemaining = (targetDurationMs - currentPos) / 1000f

                safeCrossfade = min(currentItem.crossfade, (targetDurationMs / 1000f) - 0.1f).coerceAtLeast(0f)

                if (state == Player.STATE_ENDED) timeRemaining = 0f
            } else {
                timeRemaining = currentItem.durationVal - timeInCurrentItem
                safeCrossfade = min(currentItem.crossfade, currentItem.durationVal - 0.1f).coerceAtLeast(0f)
            }

            if (playlist.size == 1 && safeCrossfade == 0f) {
                if (timeRemaining <= 0f) {
                    if (currentItem.isVideo) {
                        player?.seekTo(0)
                        player?.play()
                    }
                    timeInCurrentItem = 0f
                }
                return
            }

            // Normal layer transition logic
            if (timeRemaining <= safeCrossfade && safeCrossfade > 0f) {
                startTransition(safeCrossfade)
            } else if (timeRemaining <= 0f) {
                startTransition(0f)
            }
        }
    }

    // Runs strictly on the GL Rendering Thread
    override fun update(deltaTime: Float) {
        super.update(deltaTime)

        synchronized(playlist) {
            if (playlist.isEmpty() || currentIndex >= playlist.size) return

            val currentItem = playlist[currentIndex]

            if (!currentItem.isVideo && !isTransitioning) {
                timeInCurrentItem += deltaTime
            }

            if (isTransitioning) {
                val ch = mainActivity.getRendererSource(sourceId)
                crossfadeProgress += deltaTime / currentCrossfadeDuration

                if (crossfadeProgress >= 1f) {
                    isTransitioning = false

                    val nextActiveLayer = if (activeLayerIndex == 0) 1 else 0
                    activeLayerIndex = nextActiveLayer
                    ch?.baseLayerIndex = nextActiveLayer
                    ch?.topLayerAlpha = 0f

                    mainActivity.runOnUiThread { endTransition(nextActiveLayer) }
                } else {
                    ch?.topLayerAlpha = crossfadeProgress
                }
            }
        }
    }

    private fun startTransition(duration: Float) {
        isTransitioning = true
        currentCrossfadeDuration = duration.coerceAtLeast(0.01f)
        crossfadeProgress = 0f

        synchronized(playlist) {
            if (playlist.isEmpty() || nextIndex >= playlist.size) return
            val nextItem = playlist[nextIndex]

            if (nextItem.isVideo) {
                val inactivePlayer = if (activeLayerIndex == 0) playerB else playerA
                inactivePlayer?.play()
            }
        }
    }

    private fun endTransition(newActiveLayer: Int) {
        val oldPlayer = if (newActiveLayer == 1) playerA else playerB
        oldPlayer?.pause()

        currentIndex = nextIndex
        timeInCurrentItem = 0f

        synchronized(playlist) {
            if (playlist.isNotEmpty()) {
                nextIndex = (currentIndex + 1) % playlist.size
                preloadItem(nextIndex, if (newActiveLayer == 0) 1 else 0, autoPlay = false)
            }
        }
    }

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        super.addExtraControls(panel, context)

        val editBtn = Button(context).apply {
            text = "EDIT PLAYLIST"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#444444"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110).apply {
                topMargin = 10; bottomMargin = 10
            }
            setOnClickListener { showPlaylistEditor() }
        }

        if (panel.childCount > 0) {
            panel.addView(editBtn, panel.childCount - 1)
        } else {
            panel.addView(editBtn)
        }
    }

    private fun showPlaylistEditor() {
        mainActivity.activePlaylistEditor = this
        val dialog = android.app.Dialog(mainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val rootLayout = FrameLayout(mainActivity).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            isClickable = true
        }

        val closeBtn = Button(mainActivity).apply {
            text = "✕"; textSize = 24f; setTextColor(Color.GRAY); background = null
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 10; rightMargin = 10
            }
            setOnClickListener {
                mainActivity.activePlaylistEditor = null
                subtitle = "${playlist.size} Items"
                dialog.dismiss()
            }
        }

        val content = LinearLayout(mainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = 50; rightMargin = 50; topMargin = 80; bottomMargin = 30
            }
        }

        content.addView(TextView(mainActivity).apply {
            text = "PLAYLIST EDITOR"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 30)
        })

        val listContainer = LinearLayout(mainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        fun refreshList() {
            listContainer.removeAllViews()
            synchronized(playlist) {
                if (playlist.isEmpty()) {
                    listContainer.addView(TextView(mainActivity).apply {
                        text = "Playlist is empty."; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0,50,0,50)
                    })
                    return
                }

                playlist.forEachIndexed { i, item ->
                    val row = LinearLayout(mainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 15f }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }
                        setPadding(20, 20, 20, 20)
                    }

                    val infoCol = LinearLayout(mainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }

                    infoCol.addView(TextView(mainActivity).apply {
                        text = "${i + 1}. ${item.name}"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD)
                    })

                    val isVid = item.isVideo
                    val durMax = if (isVid) 100 else 300
                    val durMin = if (isVid) 10 else 5
                    val durCur = if (isVid) (item.durationVal * 100).toInt() else (item.durationVal * 10).toInt()

                    val durLabel = TextView(mainActivity).apply {
                        text = "Duration: " + if(isVid) "$durCur%" else "${durCur/10f}s"
                        setTextColor(Color.LTGRAY); textSize = 10f; setPadding(0, 10, 0, 0)
                    }
                    val durSeek = SeekBar(mainActivity).apply {
                        max = durMax - durMin; progress = durCur - durMin
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                                val realVal = p + durMin
                                if (isVid) item.durationVal = realVal / 100f else item.durationVal = realVal / 10f
                                durLabel.text = "Duration: " + if(isVid) "$realVal%" else "${realVal/10f}s"
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    infoCol.addView(durLabel); infoCol.addView(durSeek)

                    val xfCur = (item.crossfade * 10).toInt()
                    val xfLabel = TextView(mainActivity).apply {
                        text = "Crossfade: ${xfCur/10f}s"; setTextColor(Color.LTGRAY); textSize = 10f; setPadding(0, 10, 0, 0)
                    }
                    val xfSeek = SeekBar(mainActivity).apply {
                        max = 150; progress = xfCur
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                                item.crossfade = p / 10f
                                xfLabel.text = "Crossfade: ${p/10f}s"
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    infoCol.addView(xfLabel); infoCol.addView(xfSeek)
                    row.addView(infoCol)

                    val btnCol = LinearLayout(mainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(120, -2) }

                    btnCol.addView(Button(mainActivity).apply {
                        text = "▲"; setTextColor(Color.WHITE); background = null; setPadding(0,0,0,0); layoutParams = LinearLayout.LayoutParams(-1, 60)
                        setOnClickListener {
                            synchronized(playlist) {
                                if (i > 0) {
                                    java.util.Collections.swap(playlist, i, i - 1)
                                    forceResync()
                                    refreshList()
                                }
                            }
                        }
                    })
                    btnCol.addView(Button(mainActivity).apply {
                        text = "▼"; setTextColor(Color.WHITE); background = null; setPadding(0,0,0,0); layoutParams = LinearLayout.LayoutParams(-1, 60)
                        setOnClickListener {
                            synchronized(playlist) {
                                if (i < playlist.size - 1) {
                                    java.util.Collections.swap(playlist, i, i + 1)
                                    forceResync()
                                    refreshList()
                                }
                            }
                        }
                    })
                    btnCol.addView(Button(mainActivity).apply {
                        text = "✕"; setTextColor(Color.parseColor("#FF6666")); background = null; setPadding(0,0,0,0); layoutParams = LinearLayout.LayoutParams(-1, 60)
                        setOnClickListener {
                            synchronized(playlist) {
                                playlist.removeAt(i)
                                subtitle = "${playlist.size} Items"
                                forceResync()
                                refreshList()
                            }
                        }
                    })
                    row.addView(btnCol)
                    listContainer.addView(row)
                }
            }
        }

        // Link the auto-refresh callback
        onPlaylistUpdated = { mainActivity.runOnUiThread { refreshList() } }
        refreshList()

        val scroller = ScrollView(mainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(listContainer)
        }
        content.addView(scroller)

        val addBtn = Button(mainActivity).apply {
            text = "ADD MEDIA"
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0066CC")); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(-1, 120).apply { topMargin = 20 }
            setOnClickListener {
                MediaPickerDialog(mainActivity) { uris ->
                    val newItems = uris.map { uri ->
                        val mimeType = mainActivity.contentResolver.getType(uri) ?: "application/octet-stream"
                        val isImage = mimeType.startsWith("image")
                        // Using the now-public helper from the activity
                        val name = mainActivity.getFileNameFromUri(uri)
                        PlaylistItem(uri, !isImage, name, if (isImage) 3.0f else 1.0f, 1.0f)
                    }
                    addItems(newItems)
                }.show()
            }
        }
        content.addView(addBtn)

        rootLayout.addView(content); rootLayout.addView(closeBtn)
        dialog.setContentView(rootLayout)
        dialog.setOnDismissListener { mainActivity.hideSystemUI() }
        dialog.show()
    }

    override fun onRemove() {
        mainHandler.removeCallbacks(playbackChecker)
        mainActivity.runOnUiThread {
            try {
                playerA?.stop(); playerA?.release()
                playerB?.stop(); playerB?.release()
            } catch(e: Exception) {}
        }
    }
}

class RtspSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity,
    private val exoPlayer: ExoPlayer
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {
    override fun onRemove() {
        try {
            exoPlayer.stop()
            exoPlayer.release()
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}

class FeedbackSourceControl(
    id: String,
    label: String,
    sourceId: String,
    mainActivity: MainActivity
) : SourcePropertyControl(id, label, 0, sourceId, mainActivity) {

    override fun addExtraControls(panel: LinearLayout, context: Context) {
        val channel = mainActivity.getRendererSource(sourceId) ?: return

        // Tap point selector
        panel.addView(TextView(context).apply {
            text = "FEEDBACK TAP POINT"; textSize = 10f; setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
        })

        // Build list of tap points from the effect chain
        val effectChain = mainActivity.effectChain
        val tapOptions = mutableListOf<Pair<String, String>>() // id to display name
        effectChain.effects.forEach { effect ->
            tapOptions.add(Pair(effect.id, effect.name))
        }

        val currentTap = channel.feedbackTapEffectId
        val foundIndex = tapOptions.indexOfFirst { it.first == currentTap }
        val currentIndex = if (foundIndex >= 0) foundIndex else tapOptions.size - 1

        val spinner = Spinner(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 10f
                setStroke(1, Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 110).apply { topMargin = 5; bottomMargin = 10 }
        }

        val displayNames = tapOptions.map { it.second }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.setSelection(currentIndex)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                (view as? TextView)?.apply { setTextColor(Color.WHITE); textSize = 13f }
                val selectedId = tapOptions[position].first
                channel.feedbackTapEffectId = selectedId
                subtitle = "Tap: ${tapOptions[position].second}"
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        panel.addView(spinner)

        // Delay slider (1-60 frames)
        panel.addView(TextView(context).apply {
            text = "FRAME DELAY"; textSize = 10f; setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
        })

        val delayLabel = TextView(context).apply {
            text = "1 frame (~17ms)"; textSize = 12f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val delaySeekBar = SeekBar(context).apply {
            max = 59  // 0-59 maps to 1-60
            progress = channel.feedbackDelay - 1
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val frames = progress + 1
                    channel.feedbackDelay = frames
                    channel.setFeedbackBufferSize(frames)
                    val ms = frames * 17  // ~60fps
                    val memMB = frames * 8  // ~8MB per frame at 1920x1080 RGBA
                    delayLabel.text = "$frames frame${if (frames > 1) "s" else ""} (~${ms}ms, ~${memMB}MB)"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(delaySeekBar)
        panel.addView(delayLabel)

        // Add standard controls (inject after, blend mode, remove)
        super.addExtraControls(panel, context)
    }

    override fun onRemove() {
        val channel = mainActivity.getRendererSource(sourceId)
        mainActivity.glView.queueEvent { channel?.releaseFeedbackBuffer() }
    }
}

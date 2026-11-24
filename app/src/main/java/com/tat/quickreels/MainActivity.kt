package com.tat.quickreels

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import android.media.MediaPlayer
import android.content.ContentResolver
import androidx.media3.transformer.Transformer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var plateUri: Uri? = null
    private var player: ExoPlayer? = null
    private lateinit var prefs: SharedPreferences
    private val bgExecutor = Executors.newFixedThreadPool(4)

    private var exportWidth = 1080
    private var exportHeight = 1920

    private lateinit var rootSwitcher: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var videoSeekBar: SeekBar
    private lateinit var videoSeekOverlay: LinearLayout
    private lateinit var videoSeekTime: TextView
    private var isUserSeeking = false
    private lateinit var canvas: FrameLayout
    private lateinit var canvasTitleTop: TextView
    private lateinit var videoLayer: FrameLayout
    private lateinit var videoView: PlayerView
    private lateinit var plateView: ImageView
    private lateinit var previewText: TextView
    private lateinit var textBackgroundView: View

    private lateinit var tabButtons: List<Button>
    private lateinit var tabContainers: List<View>

    private lateinit var recentVideosGrid: GridLayout
    private lateinit var overlayFilesGrid: GridLayout
    private lateinit var folderNameLabel: TextView

    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var posXSeek: SeekBar
    private lateinit var posYSeek: SeekBar
    private var isUpdatingSliders = false

    private lateinit var captionInput: EditText
    private lateinit var fontSizeLabel: TextView
    private lateinit var lineSpacingLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var charCounter: TextView

    private var fontSize = 20f
    private var lineSpacingMult = 1.1f
    private var textColor = Color.WHITE
    private var hasShadow = true
    private var hasBackground = false
    private var bgColor = Color.BLACK
    private var bgAlphaPercent = 60
    private var textAlignment = Gravity.CENTER
    private var canvasPosition = "Center"
    private var canvasZoomFactor = 1.0f

    private var customTypeface: Typeface? = null
    private var availableFonts = mutableListOf<String>()
    private var fontFileMap = mutableMapOf<String, File>()
    private var fontAssetMap = mutableMapOf<String, String>()

    private lateinit var playPauseBtn: Button
    private lateinit var sideResetBtn: Button
    private lateinit var sideZoomInBtn: Button
    private lateinit var sideZoomOutBtn: Button

    private lateinit var curTimeText: TextView
    private lateinit var durTimeText: TextView
    private lateinit var bottomTimeText: TextView

    private lateinit var bPlayBtn: Button
    private lateinit var bResetBtn: Button
    private lateinit var bZoomInBtn: Button
    private lateinit var bZoomOutBtn: Button

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var exportBtn: Button
    private lateinit var sideControlContainer: LinearLayout
    private lateinit var bottomControlRow: LinearLayout

    private lateinit var columnBContainer: FrameLayout
    private val importedVideoUris = mutableListOf<Uri>()
    private var currentVideoList = mutableListOf<Triple<Uri, String, Long>>()
    private var videoGridSize = "Medium"
    private var updatingVideoGrid = false

    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private var sourceDurationMs = 0L
    private var isExporting = false
    private var activeTransformer: Transformer? = null

    private lateinit var trimStartSeek: SeekBar
    private lateinit var trimEndSeek: SeekBar
    private lateinit var trimStartLabel: TextView
    private lateinit var trimEndLabel: TextView
    private lateinit var trimDurationLabel: TextView

    private var overlayPlayer: ExoPlayer? = null
    private var overlayMimeType = ""
    private var chromaKeyEnabled = false
    private var chromaKeyColor = Color.GREEN
    private var chromaKeyFeather = 0.12f
    private var chromaKeyEdgeSoftness = 0.10f

    private var downloaderPanel: com.tat.quickreels.downloader.DownloaderPanel? = null

    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        uris ->
        if (!uris.isNullOrEmpty()) {
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                if (!importedVideoUris.contains(u)) {
                    importedVideoUris.add(0, u)
                }
            }
            saveImportedVideos()
            renderVideoGridFromUris(importedVideoUris)
            loadVideo(uris[0])
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // কিছু provider persistable permission দেয় না।
            }

            applyOverlayUri(uri)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("overlay_folder_uri", treeUri.toString()).apply()
                loadOverlayFolder(treeUri)
            } catch (_: Exception) {
                Toast.makeText(this, "Android security restricts direct root folder access.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        result ->
        val fullAccess = result[Manifest.permission.READ_MEDIA_VIDEO] == true ||
        (Build.VERSION.SDK_INT < 33 && result[Manifest.permission.READ_EXTERNAL_STORAGE] == true)
        val selectedOnly = Build.VERSION.SDK_INT >= 34 &&
        result["android.permission.READ_MEDIA_VISUAL_USER_SELECTED"] == true
        if (!fullAccess && selectedOnly) {
            Toast.makeText(this, "শুধু নির্বাচিত ভিডিওর অনুমতি দেওয়া হয়েছে। সব ভিডিও দেখতে Permission থেকে Allow all videos নির্বাচন করুন।", Toast.LENGTH_LONG).show()
        }
        loadRecentGalleryVideos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tat_quick_reels_prefs", Context.MODE_PRIVATE)
        videoGridSize = prefs.getString("video_grid_size", "Medium") ?: "Medium"
        player = ExoPlayer.Builder(this).build()

        scanAvailableFonts()
        loadSavedTextSettings()

        rootSwitcher = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#101012"))
        }
        setContentView(rootSwitcher)

        exportWidth = 1080
        exportHeight = 1920
        showMainEditorUI()
    }

    private fun scanAvailableFonts() {
        availableFonts.clear()
        fontFileMap.clear()
        fontAssetMap.clear()

        try {
            val assetManager = assets
            val assetList = assetManager.list("fonts")
            if (assetList != null) {
                for (assetName in assetList) {
                    if (assetName.endsWith(".ttf", true) || assetName.endsWith(".otf", true)) {
                        availableFonts.add(assetName)
                        fontAssetMap[assetName] = "fonts/$assetName"
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val dir1 = File(Environment.getExternalStorageDirectory(), "Fonts/tat_fonts")
            val dir2 = File(Environment.getExternalStorageDirectory(), "Fonts")
            val targetDir = if (dir1.exists()) dir1 else dir2
            if (targetDir.exists()) {
                targetDir.listFiles()?.filter {
                    it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true))
                }?.forEach {
                    file ->
                    availableFonts.add(file.name)
                    fontFileMap[file.name] = file
                }
            }
        } catch (_: Exception) {}

        if (availableFonts.isEmpty()) {
            availableFonts.add("SolaimanLipi / Default")
        }

        try {
            if (fontAssetMap.isNotEmpty()) {
                val firstKey = fontAssetMap.keys.first()
                customTypeface = Typeface.createFromAsset(assets, fontAssetMap[firstKey])
            } else if (fontFileMap.isNotEmpty()) {
                val firstKey = fontFileMap.keys.first()
                customTypeface = Typeface.createFromFile(fontFileMap[firstKey])
            } else {
                customTypeface = Typeface.createFromAsset(assets, "fonts/SolaimanLipi_Bold.ttf")
            }
        } catch (_: Exception) {}
    }

    private fun saveSavedTextSettings() {
        prefs.edit().apply {
            putString("text_caption", captionInput.text.toString())
            putFloat("text_font_size", fontSize)
            putFloat("text_line_spacing", lineSpacingMult)
            putInt("text_color", textColor)
            putBoolean("text_shadow", hasShadow)
            putBoolean("text_bg_on", hasBackground)
            putInt("text_bg_color", bgColor)
            putInt("text_bg_alpha", bgAlphaPercent)
            putString("canvas_position", canvasPosition)
            putFloat("text_trans_x", previewText.translationX)
            putFloat("text_trans_y", previewText.translationY)
            apply()
        }
    }

    private fun loadSavedTextSettings() {
        fontSize = prefs.getFloat("text_font_size", 20f)
        lineSpacingMult = prefs.getFloat("text_line_spacing", 1.1f)
        textColor = prefs.getInt("text_color", Color.WHITE)
        hasShadow = prefs.getBoolean("text_shadow", true)
        hasBackground = prefs.getBoolean("text_bg_on", false)
        bgColor = prefs.getInt("text_bg_color", Color.BLACK)
        bgAlphaPercent = prefs.getInt("text_bg_alpha", 60)
        canvasPosition = prefs.getString("canvas_position", "Center") ?: "Center"
    }

    private fun saveImportedVideos() {
        val array = JSONArray()
        importedVideoUris.forEach {
            array.put(it.toString())
        }
        prefs.edit().putString("saved_imported_videos", array.toString()).apply()
    }

    private fun loadSavedImportedVideos(): Boolean {
        val str = prefs.getString("saved_imported_videos", null) ?: return false
        return try {
            val array = JSONArray(str)
            importedVideoUris.clear()
            for (i in 0 until array.length()) {
                importedVideoUris.add(Uri.parse(array.getString(i)))
            }
            importedVideoUris.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, strokeHex: String? = null, radiusDp: Int = 8): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun showMainEditorUI() {
        rootSwitcher.removeAllViews()

        val mainSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#16161A", "#2A2A32", 12)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        canvasTitleTop = TextView(this).apply {
            text = "TAT Quick reels"
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            textSize = 11f
            visibility = View.GONE
        }
        leftPanel.addView(canvasTitleTop, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(1))
        })

        val previewAndSideControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }




        previewBox = FrameLayout(this)
        canvas = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }

        videoLayer = FrameLayout(this)
        videoView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        videoLayer.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        canvas.addView(videoLayer, FrameLayout.LayoutParams(-1, -1))
        enableTransform(videoView, videoLayer, isVideo = true)

        plateView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_XY
        }
        canvas.addView(plateView, FrameLayout.LayoutParams(-1, -1))

        textBackgroundView = View(this).apply {
            visibility = View.GONE
            val a = (bgAlphaPercent * 255 / 100).coerceIn(0, 255)
            setBackgroundColor(Color.argb(a, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
        }
        canvas.addView(textBackgroundView, FrameLayout.LayoutParams(-1, dp(36), Gravity.CENTER))

        previewText = TextView(this).apply {
            textSize = fontSize
            gravity = textAlignment
            setTextColor(textColor)
            text = prefs.getString("text_caption", "") ?: ""
            if (customTypeface != null) typeface = customTypeface else setTypeface(null, Typeface.BOLD)
            if (hasShadow) setShadowLayer(6f, 2f, 2f, Color.BLACK) else setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setLineSpacing(0f, lineSpacingMult)

            translationX = prefs.getFloat("text_trans_x", 0f)
            translationY = prefs.getFloat("text_trans_y", 0f)
        }
        canvas.addView(
            previewText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        enableTransform(previewText, previewText, isVideo = false)

        previewBox.addView(canvas, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        // Playback seek bar overlay: preview/player-এর জায়গা কমাবে না
        videoSeekOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setBackgroundColor(Color.parseColor("#CC111111"))
            visibility = View.GONE
        }
        videoSeekTime = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 8f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        videoSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player?.duration ?: 0L
                        val position = duration.coerceAtLeast(0L) * progress / 1000L
                        videoSeekTime.text = "${formatMs(position)} / ${formatMs(duration.coerceAtLeast(0L))}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        player?.seekTo(duration * (sb?.progress ?: 0) / 1000L)
                    }
                    isUserSeeking = false
                }
            })
        }
        videoSeekOverlay.addView(videoSeekTime, LinearLayout.LayoutParams(-1, -2))
        videoSeekOverlay.addView(videoSeekBar, LinearLayout.LayoutParams(-1, dp(24)))
        previewBox.addView(
            videoSeekOverlay,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        )

        previewAndSideControls.addView(previewBox, LinearLayout.LayoutParams(0, -1, 1f))

        sideControlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(1), 0, 0, 0)
        }

        playPauseBtn = Button(this).apply {
            text = "▶"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                togglePlayPause()
            }
        }
        sideResetBtn = Button(this).apply {
            text = "↺"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                resetVideoAndTextTransform()
            }
        }
        sideZoomInBtn = Button(this).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                adjustCanvasPreviewZoom(true)
            }
        }
        sideZoomOutBtn = Button(this).apply {
            text = "-"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                adjustCanvasPreviewZoom(false)
            }
        }

        curTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
        }
        durTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 8f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
        }

        sideControlContainer.addView(playPauseBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            setMargins(0, 0, 0, dp(1))
        })
        sideControlContainer.addView(sideResetBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            setMargins(0, 0, 0, dp(1))
        })
        sideControlContainer.addView(sideZoomInBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            setMargins(0, 0, 0, dp(1))
        })
        sideControlContainer.addView(sideZoomOutBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            setMargins(0, 0, 0, dp(3))
        })
        sideControlContainer.addView(curTimeText)
        sideControlContainer.addView(durTimeText)

        previewAndSideControls.addView(sideControlContainer, LinearLayout.LayoutParams(-2, -1))
        leftPanel.addView(previewAndSideControls, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, 0)
        }
        bPlayBtn = Button(this).apply {
            text = "▶"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                togglePlayPause()
            }
        }
        bResetBtn = Button(this).apply {
            text = "↺"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                resetVideoAndTextTransform()
            }
        }
        bZoomInBtn = Button(this).apply {
            text = "+"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                adjustCanvasPreviewZoom(true)
            }
        }
        bZoomOutBtn = Button(this).apply {
            text = "-"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", "#3E3E4C", 6)
            setOnClickListener {
                adjustCanvasPreviewZoom(false)
            }
        }
        bottomTimeText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 8.5f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
        }
        bottomControlRow.addView(bPlayBtn, LinearLayout.LayoutParams(dp(30), dp(28)).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        bottomControlRow.addView(bResetBtn, LinearLayout.LayoutParams(dp(30), dp(28)).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        bottomControlRow.addView(bZoomInBtn, LinearLayout.LayoutParams(dp(30), dp(28)).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        bottomControlRow.addView(bZoomOutBtn, LinearLayout.LayoutParams(dp(30), dp(28)).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        bottomControlRow.addView(bottomTimeText, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(2), 0, dp(1), 0)
        })
        leftPanel.addView(bottomControlRow, LinearLayout.LayoutParams(-1, -2))

        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }

        val tabBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#16161A", "#2A2A32", 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val tableHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }

        val columnA = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tabIcons = listOf("⬇", "🎬", "⚙", "🖼", "✏")
        val tButtons = mutableListOf<Button>()
        tabIcons.forEachIndexed {
            index, icon ->
            val b = Button(this).apply {
                text = icon
                textSize = 12f
                setTextColor(Color.WHITE)
                setOnClickListener {
                    selectTab(index)
                }
            }
            tButtons.add(b)
            val lp = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            }
            columnA.addView(b, lp)
        }
        tabButtons = tButtons

        columnBContainer = FrameLayout(this).apply {
            setPadding(dp(1), 0, 0, 0)
        }

        tableHeaderRow.addView(columnA, LinearLayout.LayoutParams(0, -2, 72f))
        tableHeaderRow.addView(columnBContainer, LinearLayout.LayoutParams(0, -2, 28f))
        tabBox.addView(tableHeaderRow, LinearLayout.LayoutParams(-1, -2))

        val tabContentHolder = FrameLayout(this).apply {
            setPadding(dp(1), dp(3), dp(1), dp(1))
        }

        val downloadTabView = buildDownloadTab()
        val mediaTabView = buildMediaTab()
        val motionTabView = buildMotionTab()
        val overlayTabView = buildOverlayTab()
        val textTabView = buildTextTab()

        tabContainers = listOf(downloadTabView, mediaTabView, motionTabView, overlayTabView, textTabView)
        tabContainers.forEach {
            v ->
            tabContentHolder.addView(v, FrameLayout.LayoutParams(-1, -1))
        }

        tabBox.addView(tabContentHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        rightPanel.addView(tabBox, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#16161A", "#2A2A32", 12)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }

        progressText = TextView(this).apply {
            text = "Status: Ready (HD 1080p)"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        bottomBox.addView(progressText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        bottomBox.addView(progressBar, LinearLayout.LayoutParams(-1, dp(6)).apply {
            setMargins(0, dp(1), 0, dp(2))
        })

        val actionBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        listOf(
            "9:16" to Pair(1080, 1920),
            "1:1" to Pair(1080, 1080),
            "16:9" to Pair(1920, 1080)
        ).forEach {
            (label, dims) ->
            val btn = Button(this).apply {
                text = label
                textSize = 8.5f
                setTextColor(Color.WHITE)
                background = roundedBg("#25252D", null, 4)
                setOnClickListener {
                    setAspectRatio(dims.first, dims.second)
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(30), 0.7f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            }
            actionBtnsRow.addView(btn, lp)
        }

        val clearOverlayBtn = Button(this).apply {
            text = "✖ Overlay"
            textSize = 8.5f
            setTextColor(Color.WHITE)
            background = roundedBg("#282830", null, 6)

            setOnClickListener {
                // Canvas-এ যোগ করা overlay video preview থাকলে সরান।
                for (i in canvas.childCount - 1 downTo 0) {
                    val child = canvas.getChildAt(i)
                    if (child is PlayerView && child.player === overlayPlayer) {
                        child.player = null
                        canvas.removeViewAt(i)
                    }
                }

                // Overlay video player বন্ধ ও release করুন।
                overlayPlayer?.release()
                overlayPlayer = null

                // Image overlay সরান।
                plateUri = null
                plateView.setImageDrawable(null)
                plateView.visibility = View.GONE

                // Overlay selection সংক্রান্ত state মুছুন।
                overlayMimeType = ""
                prefs.edit().remove("last_overlay_uri").apply()

                Toast.makeText(
                    this@MainActivity,
                    "Overlay cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        actionBtnsRow.addView(clearOverlayBtn, LinearLayout.LayoutParams(0, dp(30), 0.8f).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })

        exportBtn = Button(this).apply {
            text = "⬇ Export"
            textSize = 8.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#B71C1C", "#FF5252", 6)
            setOnClickListener {
                if (isExporting) {
                    cancelVideoExport()
                } else {
                    startVideoExport()
                }
            }
        }
        actionBtnsRow.addView(exportBtn, LinearLayout.LayoutParams(0, dp(30), 1.2f).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })

        bottomBox.addView(actionBtnsRow, LinearLayout.LayoutParams(-1, -2))
        rightPanel.addView(bottomBox, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(3), 0, 0)
        })

        mainSplit.addView(leftPanel, LinearLayout.LayoutParams(0, -1, 38f))
        mainSplit.addView(rightPanel, LinearLayout.LayoutParams(0, -1, 62f))
        rootSwitcher.addView(mainSplit, FrameLayout.LayoutParams(-1, -1))

        previewBox.post {
            setAspectRatio(exportWidth, exportHeight)
        }

        selectTab(0)
        requestGalleryPermissionAndLoad()
        restoreSavedOverlayState()
        setupPlayerProgressTicker()
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                playPauseBtn.text = "▶"
                bPlayBtn.text = "▶"
                videoSeekOverlay.visibility = View.GONE
            } else {
                it.play()
                playPauseBtn.text = "⏸"
                bPlayBtn.text = "⏸"
                videoSeekOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun adjustCanvasPreviewZoom(zoomIn: Boolean) {
        canvasZoomFactor = if (zoomIn) (canvasZoomFactor + 0.1f).coerceAtMost(2.0f) else (canvasZoomFactor - 0.1f).coerceAtLeast(0.5f)
        canvas.scaleX = canvasZoomFactor
        canvas.scaleY = canvasZoomFactor
    }

    private fun setupPlayerProgressTicker() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        handler.post(object : Runnable {
            override fun run() {
                player?.let {
                    exo ->
                    var position = exo.currentPosition.coerceAtLeast(0L)

                    val hasValidTrim =
                    sourceDurationMs > 0L && trimEndMs > trimStartMs

                    // Trim range-এর বাইরে গেলে preview playback-কে range-এর মধ্যে রাখুন।
                    if (hasValidTrim) {
                        when {
                            position < trimStartMs -> {
                                exo.seekTo(trimStartMs)
                                position = trimStartMs
                            }

                            position >= trimEndMs -> {
                                if (exo.isPlaying) {
                                    exo.seekTo(trimStartMs)
                                    position = trimStartMs
                                } else {
                                    exo.seekTo(trimEndMs)
                                    position = trimEndMs
                                }
                            }
                        }
                    }

                    // Trim সক্রিয় থাকলে নির্বাচিত অংশের duration দেখান।
                    val duration = if (hasValidTrim) {
                        trimEndMs - trimStartMs
                    } else {
                        exo.duration.coerceAtLeast(0L)
                    }

                    // Seek bar ও সময় trim-এর শুরু থেকে গণনা করুন।
                    val positionInTrim = if (hasValidTrim) {
                        (position - trimStartMs).coerceIn(0L, duration)
                    } else {
                        position.coerceIn(0L, duration)
                    }

                    val currentTime = formatMs(positionInTrim)
                    val durationTime = formatMs(duration)

                    curTimeText.text = currentTime
                    durTimeText.text = durationTime
                    bottomTimeText.text = "$currentTime / $durationTime"

                    videoSeekOverlay.visibility =
                    if (exo.isPlaying) View.VISIBLE else View.GONE

                    if (!isUserSeeking) {
                        videoSeekTime.text = "$currentTime / $durationTime"
                        videoSeekBar.progress = if (duration > 0L) {
                            ((positionInTrim * 1000L) / duration)
                            .toInt()
                            .coerceIn(0, 1000)
                        } else {
                            0
                        }
                    }
                }

                handler.postDelayed(this, 200)
            }
        })
    }

    private fun formatMs(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / 1000) / 60
        return String.format("%02d:%02d", min, sec)
    }

    // ==================== NEW TAB: DOWNLOAD (REAL API CONNECTED) ====================
    private fun buildDownloadTab(): View {
        return com.tat.quickreels.downloader.DownloaderPanel(this, this) {
            downloadedUri ->
            if (!importedVideoUris.contains(downloadedUri)) {
                importedVideoUris.add(0, downloadedUri)
                saveImportedVideos()
                renderVideoGridFromUris(importedVideoUris)
            }
        }.also {
            downloaderPanel = it
        }
    }

    // ==================== TAB 1: MEDIA ====================
    private fun buildMediaTab(): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        recentVideosGrid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        container.addView(recentVideosGrid, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(container)
        recentVideosGrid.addOnLayoutChangeListener {
            _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if ((right - left) != (oldRight - oldLeft)) updateVideoGridColumns()
        }
        recentVideosGrid.post {
            updateVideoGridColumns()
        }
        return scroll
    }

    private fun renderColumnBForMedia(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val importBtn = Button(this).apply {
            text = "📁+"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = roundedBg("#2E2E38", "#FF4D4D", 6)
            setOnClickListener {
                videoPicker.launch("video/*")
            }
        }
        val refreshBtn = Button(this).apply {
            text = "🔄"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", null, 6)
            setOnClickListener {
                importedVideoUris.clear()
                prefs.edit().remove("saved_imported_videos").apply()
                loadRecentGalleryVideos()
            }
        }
        val sizeBtn = Button(this).apply {
            text = "⋮"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", null, 6)
            setOnClickListener {
                val sizes = arrayOf("Small", "Medium", "Large")
                val selected = sizes.indexOf(videoGridSize).coerceAtLeast(0)
                android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Video thumbnail size")
                .setSingleChoiceItems(sizes, selected) {
                    dialog, which ->
                    videoGridSize = sizes[which]
                    prefs.edit().putString("video_grid_size", videoGridSize).apply()
                    updateVideoGridColumns()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            }
        }
        row.addView(importBtn, LinearLayout.LayoutParams(dp(22), dp(28)).apply {
            setMargins(0, 0, dp(1), 0)
        })
        row.addView(refreshBtn, LinearLayout.LayoutParams(dp(22), dp(28)).apply {
            setMargins(0, 0, dp(1), 0)
        })
        row.addView(sizeBtn, LinearLayout.LayoutParams(dp(20), dp(28)))
        return row
    }

    private fun updateVideoGridColumns() {
        if (!::recentVideosGrid.isInitialized || updatingVideoGrid) return
        val availableWidth = recentVideosGrid.width.takeIf {
            it > 0
        } ?: return
        val itemWidth = when (videoGridSize) {
            "Small" -> dp(48)
            "Large" -> dp(84)
            else -> dp(64)
        }
        val horizontalMargins = dp(2)
        val columns = (availableWidth / (itemWidth + horizontalMargins)).coerceAtLeast(1)

        updatingVideoGrid = true
        try {
            recentVideosGrid.columnCount = columns
            for (i in 0 until recentVideosGrid.childCount) {
                val child = recentVideosGrid.getChildAt(i)
                val lp = child.layoutParams as? GridLayout.LayoutParams ?: continue
                lp.width = itemWidth
                lp.height = (itemWidth * 0.68f).toInt()
                child.layoutParams = lp
            }
            recentVideosGrid.requestLayout()
        } finally {
            updatingVideoGrid = false
        }
    }

    private fun requestGalleryPermissionAndLoad() {
        if (loadSavedImportedVideos()) {
            renderVideoGridFromUris(importedVideoUris)
            if (importedVideoUris.isNotEmpty()) loadVideo(importedVideoUris[0])
            return
        }

        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val hasFullAccess = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (hasFullAccess) {
            loadRecentGalleryVideos()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun loadRecentGalleryVideos() {
        if (!::recentVideosGrid.isInitialized) return
        recentVideosGrid.removeAllViews()

        bgExecutor.execute {
            val videoList = mutableListOf<Triple<Uri, String, Long>>()
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            try {
                val collections = listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.INTERNAL_CONTENT_URI)
                for (col in collections) {
                    contentResolver.query(col, projection, null, null, sortOrder)?.use {
                        cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "Video"
                            val dur = cursor.getLong(durCol)
                            val contentUri = ContentUris.withAppendedId(col, id)
                            if (!videoList.any {
                                it.first == contentUri
                            }) {
                                videoList.add(Triple(contentUri, name, dur))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            runOnUiThread {
                renderVideoGridFromTuples(videoList)
            }
        }
    }

    private fun renderVideoGridFromUris(uris: List<Uri>) {
        renderVideoGridFromTuples(uris.map {
            Triple(it, "Video", 0L)
        })
    }

    private fun renderVideoGridFromTuples(videoList: List<Triple<Uri, String, Long>>) {
        currentVideoList = videoList.toMutableList()
        recentVideosGrid.removeAllViews()
        if (videoList.isEmpty()) {
            recentVideosGrid.addView(TextView(this).apply {
                text = "No videos available."
                setTextColor(Color.GRAY)
                textSize = 9f
            })
            return
        }

        val thumbWidth = when (videoGridSize) {
            "Small" -> dp(48)
            "Large" -> dp(84)
            else -> dp(64)
        }

        videoList.forEachIndexed {
            index, (uri, name, initialDuration) ->
            val itemFrame = FrameLayout(this).apply {
                background = roundedBg("#22222A", "#383844", 6)
                setPadding(dp(1), dp(1), dp(1), dp(1))
                isClickable = true
                setOnClickListener {
                    loadVideo(uri)
                }
            }
            val thumbView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            itemFrame.addView(thumbView, FrameLayout.LayoutParams(-1, -1))

            val durationBadge = TextView(this).apply {
                text = if (initialDuration > 0L) formatMs(initialDuration) else ""
                textSize = 8f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#99000000"))
                setPadding(dp(3), dp(1), dp(3), dp(1))
                visibility = if (initialDuration > 0L) View.VISIBLE else View.GONE
            }
            itemFrame.addView(durationBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

            val lp = GridLayout.LayoutParams().apply {
                width = thumbWidth
                height = (thumbWidth * 0.68f).toInt()
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            recentVideosGrid.addView(itemFrame, lp)

            bgExecutor.execute {
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(240, 180), null)
                    else null
                } catch (_: Exception) {
                    null
                }

                var duration = initialDuration
                if (duration <= 0L) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this, uri)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (_: Exception) {
                        duration = 0L
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {}
                    }
                }
                val resolvedDuration = duration
                runOnUiThread {
                    if (bmp != null) thumbView.setImageBitmap(bmp)
                    if (resolvedDuration > 0L) {
                        durationBadge.text = formatMs(resolvedDuration)
                        durationBadge.visibility = View.VISIBLE
                        val currentIndex = currentVideoList.indexOfFirst {
                            it.first == uri
                        }
                        if (currentIndex >= 0) {
                            val old = currentVideoList[currentIndex]
                            currentVideoList[currentIndex] = Triple(old.first, old.second.ifBlank {
                                name
                            }, resolvedDuration)
                        }
                    }
                }
            }
        }
        recentVideosGrid.post {
            updateVideoGridColumns()
        }
    }

    // ==================== TAB 2: MOTION ====================
    private fun buildMotionTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }

        zoomLabel = TextView(this).apply {
            text = "Zoom: 100%"
            textSize = 9f
            setTextColor(Color.WHITE)
        }
        col.addView(zoomLabel)

        zoomSeek = SeekBar(this).apply {
            max = 375
            progress = 75
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (isUpdatingSliders) return
                    val scale = (p + 25) / 100f
                    videoLayer.scaleX = scale
                    videoLayer.scaleY = scale
                    zoomLabel.text = "Zoom: ${(scale * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(zoomSeek, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(2))
        })

        col.addView(TextView(this).apply {
            text = "Horizontal Position"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
        })
        posXSeek = SeekBar(this).apply {
            max = 1000
            progress = 500
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (isUpdatingSliders) return
                    val maxTrans = (canvas.width / 2).toFloat()
                    if (maxTrans > 0) {
                        videoLayer.translationX = (p - 500) / 500f * maxTrans
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(posXSeek, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(2))
        })

        col.addView(TextView(this).apply {
            text = "Vertical Position"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
        })
        posYSeek = SeekBar(this).apply {
            max = 1000
            progress = 500
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (isUpdatingSliders) return
                    val maxTrans = (canvas.height / 2).toFloat()
                    if (maxTrans > 0) {
                        videoLayer.translationY = (p - 500) / 500f * maxTrans
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(posYSeek, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#30303A"))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(0, dp(8), 0, dp(6))
        })

        col.addView(TextView(this).apply {
            text = "Video Trim"
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        trimStartLabel = TextView(this).apply {
            text = "Start: 00:00"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        col.addView(trimStartLabel)

        trimStartSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser || sourceDurationMs <= 0) return

                    trimStartMs =
                    (sourceDurationMs * progress / 1000L)
                    .coerceAtMost(trimEndMs - 100L)
                    .coerceAtLeast(0L)

                    player?.seekTo(trimStartMs)
                    updateTrimUi()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(
            trimStartSeek,
            LinearLayout.LayoutParams(-1, dp(28))
        )

        trimEndLabel = TextView(this).apply {
            text = "End: 00:00"
            textSize = 8f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        col.addView(trimEndLabel)

        trimEndSeek = SeekBar(this).apply {
            max = 1000
            progress = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser || sourceDurationMs <= 0) return

                    trimEndMs =
                    (sourceDurationMs * progress / 1000L)
                    .coerceAtLeast(trimStartMs + 100L)

                    updateTrimUi()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(
            trimEndSeek,
            LinearLayout.LayoutParams(-1, dp(28))
        )

        trimDurationLabel = TextView(this).apply {
            text = "Original: 00:00 • Trimmed: 00:00 • Result: 00:00"
            textSize = 8f
            setTextColor(Color.parseColor("#FFD54F"))
            setPadding(0, dp(3), 0, dp(3))
        }
        col.addView(trimDurationLabel)
        scroll.addView(col)
        return scroll
    }

    private fun updateTrimUi() {
        if (!::trimStartLabel.isInitialized) return
        if (!::trimEndLabel.isInitialized) return
        if (!::trimDurationLabel.isInitialized) return

        val original = sourceDurationMs
        val trimmed = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        val removed = (original - trimmed).coerceAtLeast(0L)

        trimStartLabel.text = "Start: ${formatMs(trimStartMs)}"
        trimEndLabel.text = "End: ${formatMs(trimEndMs)}"

        trimDurationLabel.text =
        "Original: ${formatMs(original)} • " +
        "Trimmed: ${formatMs(removed)} • " +
        "Result: ${formatMs(trimmed)}"
    }

    private fun syncMotionSlidersFromView() {
        if (!::zoomSeek.isInitialized || !::posXSeek.isInitialized) return
        isUpdatingSliders = true
        val pct = (videoLayer.scaleX * 100).toInt().coerceIn(25, 400)
        zoomSeek.progress = pct - 25
        zoomLabel.text = "Zoom: $pct%"

        val maxTransX = (canvas.width / 2).toFloat()
        if (maxTransX > 0) {
            posXSeek.progress = ((videoLayer.translationX / maxTransX) * 500f + 500f).toInt().coerceIn(0, 1000)
        }
        val maxTransY = (canvas.height / 2).toFloat()
        if (maxTransY > 0) {
            posYSeek.progress = ((videoLayer.translationY / maxTransY) * 500f + 500f).toInt().coerceIn(0, 1000)
        }
        isUpdatingSliders = false
    }

    // ==================== TAB 3: OVERLAY ====================
    private fun buildOverlayTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(TextView(this).apply {
            text = "Chroma Key"
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val chromaSwitch = Switch(this).apply {
            text = "Remove background"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener {
                _, checked ->
                chromaKeyEnabled = checked
            }
        }
        col.addView(chromaSwitch)

        val colorBtn = Button(this).apply {
            text = "Key Color: Green"
            textSize = 8f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", null, 6)

            setOnClickListener {
                val names = arrayOf("Green", "Blue", "Red")
                val colors = intArrayOf(
                    Color.GREEN,
                    Color.BLUE,
                    Color.RED
                )

                android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Chroma Key Color")
                .setItems(names) {
                    _, index ->
                    chromaKeyColor = colors[index]
                    text = "Key Color: ${names[index]}"
                }
                .show()
            }
        }
        col.addView(colorBtn)

        col.addView(TextView(this).apply {
            text = "Feather"
            textSize = 8f
            setTextColor(Color.WHITE)
        })

        val featherSeek = SeekBar(this).apply {
            max = 400
            progress = 120
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        sb: SeekBar?,
                        p: Int,
                        fromUser: Boolean
                    ) {
                        chromaKeyFeather = p / 1000f
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                }
            )
        }
        col.addView(featherSeek)

        col.addView(TextView(this).apply {
            text = "Edge Softness"
            textSize = 8f
            setTextColor(Color.WHITE)
        })

        val softnessSeek = SeekBar(this).apply {
            max = 400
            progress = 100
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        sb: SeekBar?,
                        p: Int,
                        fromUser: Boolean
                    ) {
                        chromaKeyEdgeSoftness = p / 1000f
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                }
            )
        }
        col.addView(softnessSeek)

        folderNameLabel = TextView(this).apply {
            text = "No folder"
            textSize = 8f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(2), 0, dp(2), dp(1))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        col.addView(folderNameLabel)

        overlayFilesGrid = GridLayout(this).apply {
            columnCount = 4
        }
        col.addView(overlayFilesGrid, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(col)
        return scroll

    }

    private fun renderColumnBForOverlay(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val folderBtn = Button(this).apply {
            text = "📁"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = roundedBg("#2E2E38", "#FF4D4D", 6)
            setOnClickListener {
                folderPicker.launch(null)
            }
        }
        val singleOverlayBtn = Button(this).apply {
            text = "🖼"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = roundedBg("#25252D", null, 6)
            setOnClickListener {
                imagePicker.launch(arrayOf("image/*", "video/*"))
            }
        }
        row.addView(folderBtn, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            setMargins(0, 0, dp(1), 0)
        })
        row.addView(singleOverlayBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    private fun restoreSavedOverlayState() {
        val savedFolder = prefs.getString("overlay_folder_uri", null)
        if (savedFolder != null) {
            try {
                loadOverlayFolder(Uri.parse(savedFolder))
            } catch (_: Exception) {}
        }
        val lastOverlay = prefs.getString("last_overlay_uri", null)
        if (lastOverlay != null) {
            try {
                applyOverlayUri(Uri.parse(lastOverlay), savePref = false)
            } catch (_: Exception) {}
        }
    }

    private fun loadOverlayFolder(treeUri: Uri) {
        if (!::overlayFilesGrid.isInitialized) return
        overlayFilesGrid.removeAllViews()

        val docTree = DocumentFile.fromTreeUri(this, treeUri)
        folderNameLabel.text = docTree?.name ?: "Selected"

        bgExecutor.execute {
            val files = docTree?.listFiles()?.filter {
                file ->
                val name = file.name?.lowercase().orEmpty()
                val mime = file.type?.lowercase().orEmpty()

                file.isFile && (
                    mime.startsWith("image/") ||
                    mime.startsWith("video/") ||
                    name.endsWith(".gif") ||
                    name.endsWith(".png") ||
                    name.endsWith(".jpg") ||
                    name.endsWith(".jpeg") ||
                    name.endsWith(".webp") ||
                    name.endsWith(".mp4") ||
                    name.endsWith(".mov") ||
                    name.endsWith(".webm") ||
                    name.endsWith(".mkv")
                )
            }.orEmpty()

            runOnUiThread {
                if (files.isEmpty()) {
                    overlayFilesGrid.addView(TextView(this).apply {
                        text = "No PNG found."
                        setTextColor(Color.GRAY)
                        textSize = 9f
                    })
                    return@runOnUiThread
                }

                files.forEach {
                    file ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = roundedBg("#22222A", "#3A3A46", 6)
                        setPadding(dp(1), dp(1), dp(1), dp(1))
                        isClickable = true
                        setOnClickListener {
                            applyOverlayUri(file.uri)
                        }
                    }

                    val img = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    card.addView(img, LinearLayout.LayoutParams(dp(50), dp(32)))

                    val lp = GridLayout.LayoutParams().apply {
                        width = dp(58)
                        height = dp(42)
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                    overlayFilesGrid.addView(card, lp)

                    bgExecutor.execute {
                        val bmp = try {
                            contentResolver.openInputStream(file.uri)?.use {
                                stream ->
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = 4
                                }
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (bmp != null) {
                            runOnUiThread {
                                img.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyOverlayUri(uri: Uri, savePref: Boolean = true) {
        try {
            plateUri = uri

            val mime =
            contentResolver.getType(uri)?.lowercase()
            ?: uri.lastPathSegment?.lowercase().orEmpty()

            overlayMimeType = when {
                mime.contains("video") -> "video"
                mime.contains("gif") -> "gif"
                mime.contains("png") -> "png"
                mime.contains("jpeg") ||
                mime.contains("jpg") -> "jpg"
                mime.contains("webp") -> "webp"
                else -> "image"
            }

            if (overlayMimeType == "video") {
                overlayPlayer?.release()
                overlayPlayer = ExoPlayer.Builder(this).build()
                overlayPlayer?.repeatMode =
                androidx.media3.common.Player.REPEAT_MODE_ONE
                overlayPlayer?.setMediaItem(MediaItem.fromUri(uri))
                overlayPlayer?.prepare()
                overlayPlayer?.play()

                val overlayView = androidx.media3.ui.PlayerView(this).apply {
                    player = overlayPlayer
                    useController = false
                    resizeMode =
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }

                canvas.removeView(plateView)
                plateView.visibility = View.GONE

                val lp = FrameLayout.LayoutParams(-1, -1)

                canvas.removeView(overlayView)
                canvas.addView(overlayView, lp)
            } else {
                plateView.setImageURI(uri)
                plateView.visibility = View.VISIBLE
            }

            if (savePref) {
                prefs.edit()
                .putString("last_overlay_uri", uri.toString())
                .apply()
            }

            Toast.makeText(
                this,
                "Overlay selected",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Overlay load failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // ==================== TAB 4: TEXT ====================
    private fun buildTextTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(1), dp(3), dp(4))
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 8.5f
            setTextColor(Color.parseColor("#CCCCCC"))
            setTypeface(null, Typeface.BOLD)
        }

        fun smallButton(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 8.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg("#2E2E38", "#3E3E4C", 4)
            minHeight = dp(26)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                action()
            }
        }

        fun divider() {
            col.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#30303A"))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
        }

        fun chooseColor(title: String, names: Array<String>, colors: IntArray, onPick: (Int) -> Unit) {
            android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) {
                _, which -> onPick(colors[which])
            }
            .show()
        }

        val textBoxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 100f
        }

        captionInput = EditText(this).apply {
            hint = "এখানে আপনার টেক্সট লিখুন..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 10.5f
            minLines = 2
            maxLines = 3
            background = roundedBg("#22222A", "#3A3A44", 6)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setText(prefs.getString("text_caption", "") ?: "")
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        textBoxRow.addView(captionInput, LinearLayout.LayoutParams(0, -2, 85f).apply {
            setMargins(0, 0, dp(2), 0)
        })

        val pasteBtn = smallButton("📋") {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this@MainActivity).toString()
                    captionInput.setText(text)
                    captionInput.setSelection(captionInput.text.length)
                    Toast.makeText(this@MainActivity, "Pasted", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            }
        }
        textBoxRow.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(28), 15f))

        col.addView(textBoxRow)

        charCounter = TextView(this).apply {
            text = "${captionInput.text.length}/200"
            textSize = 7.5f
            setTextColor(Color.GRAY)
            gravity = Gravity.END
        }
        col.addView(charCounter)

        captionInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                previewText.text = value
                charCounter.text = "${value.length}/200"
                updateTextBackgroundFrame()
                saveSavedTextSettings()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        divider()

        col.addView(label("Font & Color Options"))

        val fontColorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 100f
        }

        val fontSpinner = Spinner(this)
        fontSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, availableFonts
        )
        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position < availableFonts.size) {
                    val selectedName = availableFonts[position]
                    try {
                        if (fontAssetMap.containsKey(selectedName)) {
                            val path = fontAssetMap[selectedName]!!
                            customTypeface = Typeface.createFromAsset(assets, path)
                        } else if (fontFileMap.containsKey(selectedName)) {
                            val file = fontFileMap[selectedName]!!
                            customTypeface = Typeface.createFromFile(file)
                        }
                        previewText.typeface = customTypeface
                    } catch (_: Exception) {}
                }
            }
        }
        fontColorRow.addView(fontSpinner, LinearLayout.LayoutParams(0, dp(28), 46f).apply {
            setMargins(0, 0, dp(2), 0)
        })

        val colorBtn = smallButton("🎨") {
            chooseColor(
                "Text Color",
                arrayOf("White", "Yellow", "Red", "Green", "Cyan", "Black"),
                intArrayOf(Color.WHITE, Color.YELLOW, Color.RED, Color.GREEN, Color.CYAN, Color.BLACK)
            ) {
                textColor = it
                previewText.setTextColor(textColor)
                saveSavedTextSettings()
            }
        }
        fontColorRow.addView(colorBtn, LinearLayout.LayoutParams(0, dp(28), 27f).apply {
            setMargins(0, 0, dp(2), 0)
        })

        val selectiveColorBtn = smallButton("✂🎨") {
            applyColorToSelection()
        }
        fontColorRow.addView(selectiveColorBtn, LinearLayout.LayoutParams(0, dp(28), 27f))

        col.addView(fontColorRow)

        divider()

        fontSizeLabel = label("Size: ${fontSize.toInt()}")
        col.addView(fontSizeLabel)
        val sizeSeek = SeekBar(this).apply {
            max = 99
            progress = (fontSize.toInt() - 1).coerceIn(0, 99)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fontSize = (p + 1).toFloat()
                    previewText.textSize = fontSize
                    fontSizeLabel.text = "Size: ${fontSize.toInt()}"
                    updateTextBackgroundFrame()
                    saveSavedTextSettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(sizeSeek, LinearLayout.LayoutParams(-1, dp(28)))

        lineSpacingLabel = label("SP (Line Spacing): ${String.format("%.2f", lineSpacingMult)}")
        col.addView(lineSpacingLabel)
        val spacingSeek = SeekBar(this).apply {
            max = 199
            progress = ((lineSpacingMult - 0.01f) * 100f).toInt().coerceIn(0, 199)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    lineSpacingMult = 0.01f + p / 100f
                    previewText.setLineSpacing(0f, lineSpacingMult)
                    lineSpacingLabel.text = "SP (Line Spacing): ${String.format("%.2f", lineSpacingMult)}"
                    updateTextBackgroundFrame()
                    saveSavedTextSettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(spacingSeek, LinearLayout.LayoutParams(-1, dp(28)))

        divider()

        val effectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val shadowCheck = CheckBox(this).apply {
            text = "Shadow"
            textSize = 8.5f
            setTextColor(Color.WHITE)
            isChecked = hasShadow
            setOnCheckedChangeListener {
                _, checked ->
                hasShadow = checked
                if (checked) previewText.setShadowLayer(6f, 2f, 2f, Color.BLACK)
                else previewText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                saveSavedTextSettings()
            }
        }
        effectRow.addView(shadowCheck, LinearLayout.LayoutParams(-1, -2))
        col.addView(effectRow)

        divider()

        col.addView(label("BG Color & Opacity"))
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bgCheck = CheckBox(this).apply {
            text = "BG On"
            textSize = 8.5f
            setTextColor(Color.WHITE)
            isChecked = hasBackground
            setOnCheckedChangeListener {
                _, checked ->
                hasBackground = checked
                updateTextBackgroundFrame()
                saveSavedTextSettings()
            }
        }
        bgRow.addView(bgCheck, LinearLayout.LayoutParams(dp(60), -2))

        bgRow.addView(smallButton("🎨 BG") {
            chooseColor(
                "Background Color",
                arrayOf("Black", "Dark Gray", "Navy", "Red", "White"),
                intArrayOf(Color.BLACK, Color.DKGRAY, Color.parseColor("#0A192F"), Color.RED, Color.WHITE)
            ) {
                bgColor = it
                hasBackground = true
                bgCheck.isChecked = true
                updateTextBackgroundFrame()
                saveSavedTextSettings()
            }
        }, LinearLayout.LayoutParams(0, dp(28), 1f))

        col.addView(bgRow)

        opacityLabel = label("Opacity: ${bgAlphaPercent}%")
        col.addView(opacityLabel)

        val opacitySeek = SeekBar(this).apply {
            max = 100
            progress = bgAlphaPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    bgAlphaPercent = p
                    opacityLabel.text = "Opacity: ${bgAlphaPercent}%"
                    updateTextBackgroundFrame()
                    saveSavedTextSettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(opacitySeek, LinearLayout.LayoutParams(-1, dp(28)))

        divider()

        val alignPosRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val alignCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        alignCol.addView(label("Text Alignment"))
        val alignButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf("⬅" to Gravity.START, "⏺" to Gravity.CENTER, "➡" to Gravity.END).forEach {
            (icon, grav) ->
            alignButtons.addView(smallButton(icon) {
                textAlignment = grav
                previewText.gravity = grav
                updateTextBackgroundFrame()
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                setMargins(dp(1), dp(1), dp(1), 0)
            })
        }
        alignCol.addView(alignButtons)
        alignPosRow.addView(alignCol, LinearLayout.LayoutParams(0, -2, 1f))

        val posCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, 0, 0)
        }
        posCol.addView(label("Canvas Position"))
        val posButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf("L", "C", "R").forEach {
            posSymbol ->
            val actualPos = when(posSymbol) {
                "L" -> "Left"; "C" -> "Center"; else -> "Right"
            }
            posButtons.addView(smallButton(posSymbol) {
                canvasPosition = actualPos
                applyCanvasPosition()
                saveSavedTextSettings()
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                setMargins(dp(1), dp(1), dp(1), 0)
            })
        }
        posCol.addView(posButtons)
        alignPosRow.addView(posCol, LinearLayout.LayoutParams(0, -2, 1f))

        col.addView(alignPosRow)

        scroll.addView(col)
        return scroll
    }

    private fun applyColorToSelection() {
        val start = captionInput.selectionStart
        val end = captionInput.selectionEnd
        if (start < 0 || end <= start) {
            Toast.makeText(this, "First select text in input box", Toast.LENGTH_SHORT).show()
            return
        }
        val colors = arrayOf("Yellow", "Red", "Green", "Cyan", "White")
        val vals = arrayOf(Color.YELLOW, Color.RED, Color.GREEN, Color.CYAN, Color.WHITE)
        android.app.AlertDialog.Builder(this)
        .setTitle("Selection Color")
        .setItems(colors as Array<CharSequence>) {
            _, which ->
            val chosen = vals[which]
            val span = SpannableString(previewText.text)
            span.setSpan(ForegroundColorSpan(chosen), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            previewText.text = span
        }
        .show()
    }

    private fun applyCanvasPosition() {
        val lp = previewText.layoutParams as? FrameLayout.LayoutParams ?: return
        when (canvasPosition) {
            "Left" -> {
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                previewText.translationX = dp(6).toFloat()
            }
            "Right" -> {
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                previewText.translationX = -dp(6).toFloat()
            } else -> {
                lp.gravity = Gravity.CENTER
                previewText.translationX = 0f
            }
        }
        previewText.layoutParams = lp
        updateTextBackgroundFrame()
        saveSavedTextSettings()
    }

    private fun updateTextBackgroundFrame() {
        if (!hasBackground) {
            textBackgroundView.visibility = View.GONE
            return
        }
        textBackgroundView.visibility = View.VISIBLE
        val alpha = (bgAlphaPercent * 255 / 100).coerceIn(0, 255)
        textBackgroundView.setBackgroundColor(Color.argb(alpha, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))

        previewText.post {
            val h = previewText.height.coerceAtLeast(dp(20))
            val lp = textBackgroundView.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.height = h + dp(4)
                lp.gravity = (previewText.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER
                textBackgroundView.layoutParams = lp
            }
        }
    }

    private fun selectTab(index: Int) {
        tabContainers.forEachIndexed {
            i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabButtons.forEachIndexed {
            i, btn ->
            btn.background = if (i == index) {
                roundedBg("#3A2323", "#FF4D4D", 6)
            } else {
                roundedBg("#22222A", null, 6)
            }
        }

        columnBContainer.removeAllViews()
        when (index) {
            1 -> columnBContainer.addView(renderColumnBForMedia())
            3 -> columnBContainer.addView(renderColumnBForOverlay())
        }
    }

    private fun setAspectRatio(w: Int, h: Int) {
        exportWidth = w
        exportHeight = h

        val availW = (previewBox.width - dp(2)).coerceAtLeast(100)
        val availH = (previewBox.height - dp(2)).coerceAtLeast(100)
        val targetRatio = w.toFloat() / h.toFloat()

        var cw = availW
        var ch = (cw / targetRatio).toInt()
        if (ch > availH) {
            ch = availH
            cw = (ch * targetRatio).toInt()
        }

        canvas.layoutParams = FrameLayout.LayoutParams(cw, ch, Gravity.CENTER)
        canvas.requestLayout()

        if (w == 1080 && h == 1920) {
            canvasTitleTop.visibility = View.GONE
            sideControlContainer.visibility = View.VISIBLE
            bottomControlRow.visibility = View.GONE
        } else {
            canvasTitleTop.visibility = View.VISIBLE
            sideControlContainer.visibility = View.GONE
            bottomControlRow.visibility = View.VISIBLE
        }
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        trimStartMs = 0L
        sourceDurationMs = getMediaDuration(uri)

        trimEndMs = sourceDurationMs

        player?.setMediaItem(
            MediaItem.fromUri(uri),
            trimStartMs
        )
        player?.prepare()
        player?.playWhenReady = false

        playPauseBtn.text = "▶"
        bPlayBtn.text = "▶"

        progressText.text = "Video Loaded ✓ (${exportWidth}x${exportHeight} HD)"
        updateTrimUi()

        if (::trimStartSeek.isInitialized) {
            trimStartSeek.progress = 0
            trimEndSeek.progress = if (sourceDurationMs > 0) 1000 else 0
        }
    }

    private fun getMediaDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun resetVideoAndTextTransform() {
        videoLayer.translationX = 0f
        videoLayer.translationY = 0f
        videoLayer.scaleX = 1f
        videoLayer.scaleY = 1f
        previewText.translationX = 0f
        previewText.translationY = 0f
        previewText.scaleX = 1f
        previewText.scaleY = 1f
        textBackgroundView.translationX = 0f
        textBackgroundView.translationY = 0f
        textBackgroundView.scaleX = 1f
        textBackgroundView.scaleY = 1f
        canvasZoomFactor = 1.0f
        canvas.scaleX = 1.0f
        canvas.scaleY = 1.0f
        syncMotionSlidersFromView()
        saveSavedTextSettings()
    }

    private fun createExactExportOverlay(): Bitmap {
        val output = Bitmap.createBitmap(exportWidth, exportHeight, Bitmap.Config.ARGB_8888)
        val drawCanvas = Canvas(output)
    
        val cw = canvas.width.coerceAtLeast(1).toFloat()
        val ch = canvas.height.coerceAtLeast(1).toFloat()
        val wasVideoVisible = videoLayer.visibility
        val oldCanvasBackground = canvas.background
    
        plateView.visibility = View.VISIBLE
        videoLayer.visibility = View.INVISIBLE
        canvas.background = null
        canvas.setBackgroundColor(Color.TRANSPARENT)
    
        drawCanvas.save()
        drawCanvas.scale(exportWidth.toFloat() / cw, exportHeight.toFloat() / ch)
        canvas.draw(drawCanvas)
        drawCanvas.restore()
    
        canvas.background = oldCanvasBackground
        videoLayer.visibility = wasVideoVisible
    
        return output
    }

    private fun cancelVideoExport() {
        val transformer = activeTransformer
        activeTransformer = null
        isExporting = false
    
        transformer?.cancel()
    
        progressBar.progress = 0
        progressText.text = "Export cancelled"
        exportBtn.text = "⬇ Export"
        exportBtn.background = roundedBg("#B71C1C", "#FF5252", 6)
        exportBtn.isEnabled = true
    }

    
    private fun startVideoExport() {
        if (isExporting) {
            cancelVideoExport()
            return
        }
    
        val selectedVideo = videoUri
        if (selectedVideo == null) {
            Toast.makeText(
                this,
                "Please select a video first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
    
        isExporting = true
        exportBtn.text = "■ Stop / Cancel"
        exportBtn.background = roundedBg("#8E0000", "#FF5252", 6)
        exportBtn.isEnabled = true
    
        try {
            player?.pause()
            playPauseBtn.text = "▶"
            bPlayBtn.text = "▶"
    
            val overlay = createExactExportOverlay()
            val cw = canvas.width.coerceAtLeast(1).toFloat()
            val ch = canvas.height.coerceAtLeast(1).toFloat()
    
            activeTransformer = VideoExporter.export(
                context = this,
                videoUri = selectedVideo,
                overlay = overlay,
                exportWidth = exportWidth,
                exportHeight = exportHeight,
                normTransX = videoLayer.translationX / cw,
                normTransY = videoLayer.translationY / ch,
                scaleX = videoLayer.scaleX,
                scaleY = videoLayer.scaleY,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                chromaKeyEnabled = chromaKeyEnabled,
                chromaKeyColor = chromaKeyColor,
                chromaKeyFeather = chromaKeyFeather,
                chromaKeyEdgeSoftness = chromaKeyEdgeSoftness,
                onProgress = { pct ->
                    runOnUiThread {
                        progressBar.progress = pct
                        progressText.text = "HD Exporting... $pct%"
                    }
                },
                onStatus = { msg, finished ->
                    runOnUiThread {
                        // Cancel-এর পর আসা callback-কে উপেক্ষা করুন।
                        if (!isExporting) return@runOnUiThread
    
                        progressText.text = msg
    
                        if (finished) {
                            isExporting = false
                            activeTransformer = null
                            exportBtn.text = "⬇ Export"
                            exportBtn.background =
                                roundedBg("#B71C1C", "#FF5252", 6)
                            exportBtn.isEnabled = true
                        }
                    }
                }
            )
        } catch (e: Exception) {
            isExporting = false
            activeTransformer = null
            exportBtn.text = "⬇ Export"
            exportBtn.background = roundedBg("#B71C1C", "#FF5252", 6)
            exportBtn.isEnabled = true
    
            Toast.makeText(
                this,
                "Export failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun enableTransform(touchView: View, target: View, isVideo: Boolean) {
        var lastX = 0f
        var lastY = 0f
        val parent = target.parent as? ViewGroup

        fun keepVisible() {
            val pw = parent?.width ?: return
            val ph = parent.height
            val w = target.width.toFloat()
            val h = target.height.toFloat()
            if (pw <= 0 || ph <= 0 || w <= 0 || h <= 0) return

            val sx = target.scaleX
            val sy = target.scaleY
            val margin = 8f

            val minX = margin - target.left - (w + w * sx) / 2f
            val maxX = pw - margin - target.left - (w - w * sx) / 2f
            val minY = margin - target.top - (h + h * sy) / 2f
            val maxY = ph - margin - target.top - (h - h * sy) / 2f

            target.translationX = target.translationX.coerceIn(minOf(minX, maxX), maxOf(minX, maxX))
            target.translationY = target.translationY.coerceIn(minOf(minY, maxY), maxOf(minY, maxY))

            if (target == previewText && hasBackground) {
                textBackgroundView.translationX = target.translationX
                textBackgroundView.translationY = target.translationY
                textBackgroundView.scaleX = target.scaleX
                textBackgroundView.scaleY = target.scaleY
            }
            if (target == previewText) {
                saveSavedTextSettings()
            }
        }

        val detector = ScaleGestureDetector(
            touchView.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = (target.scaleX * detector.scaleFactor).coerceIn(0.25f, 4f)
                    target.scaleX = scale
                    target.scaleY = scale
                    if (target == previewText && hasBackground) {
                        textBackgroundView.scaleX = scale
                        textBackgroundView.scaleY = scale
                    }
                    keepVisible()
                    if (isVideo) syncMotionSlidersFromView()
                    return true
                }
            }
        )

        touchView.setOnTouchListener {
            _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchView.parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!detector.isInProgress && event.pointerCount == 1) {
                        target.translationX += event.rawX - lastX
                        target.translationY += event.rawY - lastY
                        if (target == previewText && hasBackground) {
                            textBackgroundView.translationX = target.translationX
                            textBackgroundView.translationY = target.translationY
                        }
                        keepVisible()
                        if (isVideo) syncMotionSlidersFromView()
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchView.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    override fun onDestroy() {
        overlayPlayer?.release()
        overlayPlayer = null
        player?.release()
        player = null
        downloaderPanel?.dispose()
        downloaderPanel = null
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        downloaderPanel?.handleIncomingIntent(intent)
    }
}
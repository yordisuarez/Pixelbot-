package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingUIService : Service() {

    companion object {
        private const val TAG = "FloatingUIService"
        private const val CHANNEL_ID = "novaborn_bot_hud_channel"
        private const val NOTIFICATION_ID = 1042
        private const val LOOP_INTERVAL_MS = 100L

        const val ACTION_START = "com.example.action.START_FLOATING_HUD"
        const val ACTION_STOP = "com.example.action.STOP_FLOATING_HUD"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenAnalyzer = LocalScreenAnalyzer()
    private var activeShipConfig: Int = 1

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var minimizedPill: LinearLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var pillIndicator: View? = null
    private var mapSpinner: Spinner? = null
    private var alienSpinner: Spinner? = null
    private var keyBoxCheckBox: CheckBox? = null
    private var toggleBotButton: Button? = null
    private var statusTextView: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensityDpi: Int = 0

    private var botLoopJob: Job? = null
    private var isBotRunning: Boolean = false
    private var isMinimized: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection detenido por el sistema.")
            stopBotLoop("Captura de pantalla detenida por el sistema.")
            releaseProjectionResources()
            BotStateRepository.setOverlayState(running = overlayView != null, hasProjection = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BotStateRepository.loadPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Faltan credenciales de MediaProjection para iniciar FloatingUIService.")
            BotStateRepository.updateStatus(
                message = "Screen capture permission required",
                error = "Launch the HUD from the main app to grant MediaProjection permission.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithProjectionType()
        setupMediaProjection(resultCode, resultData)
        ensureOverlayAttached()
        BotStateRepository.setOverlayState(running = true, hasProjection = mediaProjection != null)

        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        releaseProjectionResources()
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection devolvió null.")
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)
        createOrResizeVirtualDisplay()
    }

    private fun createOrResizeVirtualDisplay() {
        val projection = mediaProjection ?: return
        val metrics = resources.displayMetrics
        val newWidth = metrics.widthPixels.coerceAtLeast(320)
        val newHeight = metrics.heightPixels.coerceAtLeast(320)
        val newDensity = metrics.densityDpi.coerceAtLeast(160)

        if (virtualDisplay != null && newWidth == captureWidth && newHeight == captureHeight) {
            return
        }

        virtualDisplay?.release()
        imageReader?.close()

        captureWidth = newWidth
        captureHeight = newHeight
        captureDensityDpi = newDensity

        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "NovabornScreenCapture",
            captureWidth,
            captureHeight,
            captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
        Log.d(TAG, "VirtualDisplay configurado: ${captureWidth}x${captureHeight} @ ${captureDensityDpi}dpi")
    }

    @SuppressLint("InflateParams")
    private fun ensureOverlayAttached() {
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)
        overlayView = view

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }

        bindOverlayViews(view)
        windowManager.addView(view, layoutParams)
    }

    private fun bindOverlayViews(root: View) {
        minimizedPill = root.findViewById(R.id.layout_minimized_pill)
        expandedPanel = root.findViewById(R.id.layout_expanded_panel)
        pillIndicator = root.findViewById(R.id.view_pill_indicator)
        mapSpinner = root.findViewById(R.id.spinner_map_target)
        alienSpinner = root.findViewById(R.id.spinner_alien_target)
        keyBoxCheckBox = root.findViewById(R.id.checkbox_collect_key_boxes)
        toggleBotButton = root.findViewById(R.id.btn_toggle_bot)
        statusTextView = root.findViewById(R.id.tv_bot_status)

        val header = root.findViewById<LinearLayout>(R.id.layout_floating_header)
        val minimizeBtn = root.findViewById<ImageButton>(R.id.btn_minimize_expand)
        val closeBtn = root.findViewById<ImageButton>(R.id.btn_close_overlay)

        val mapAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_alien,
            MAP_TARGETS,
        )
        mapAdapter.setDropDownViewResource(R.layout.item_spinner_alien)
        mapSpinner?.adapter = mapAdapter

        val alienAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_alien,
            ALIEN_TARGETS,
        )
        alienAdapter.setDropDownViewResource(R.layout.item_spinner_alien)
        alienSpinner?.adapter = alienAdapter

        val initialState = BotStateRepository.state.value
        val initialMapIdx = MAP_TARGETS.indexOf(initialState.targetMap).coerceAtLeast(0)
        mapSpinner?.setSelection(initialMapIdx, false)
        val initialIdx = ALIEN_TARGETS.indexOf(initialState.targetAlien).coerceAtLeast(0)
        alienSpinner?.setSelection(initialIdx, false)
        keyBoxCheckBox?.isChecked = initialState.collectKeyBoxes

        mapSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val chosenMap = MAP_TARGETS.getOrElse(position) { MAP_TARGETS.first() }
                BotStateRepository.setTargetMap(this@FloatingUIService, chosenMap)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        alienSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val chosen = ALIEN_TARGETS.getOrElse(position) { ALIEN_TARGETS.first() }
                BotStateRepository.setTargetAlien(this@FloatingUIService, chosen)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        keyBoxCheckBox?.setOnCheckedChangeListener { _, isChecked ->
            BotStateRepository.setCollectKeyBoxes(this@FloatingUIService, isChecked)
        }

        toggleBotButton?.setOnClickListener {
            if (isBotRunning) {
                stopBotLoop("Status: Paused by user")
            } else {
                startBotLoop()
            }
        }

        minimizeBtn.setOnClickListener {
            setMinimized(true)
        }

        closeBtn.setOnClickListener {
            stopBotLoop("Status: HUD closed")
            stopSelf()
        }

        attachDragListener(header, onTap = null)
        minimizedPill?.let { pill ->
            attachDragListener(pill, onTap = { setMinimized(false) })
        }

        updateControlVisuals()
    }

    private fun setMinimized(minimized: Boolean) {
        isMinimized = minimized
        expandedPanel?.visibility = if (minimized) View.GONE else View.VISIBLE
        minimizedPill?.visibility = if (minimized) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(target: View, onTap: (() -> Unit)?) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = initialY + deltaY
                        overlayView?.let { windowManager.updateViewLayout(it, layoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startBotLoop() {
        if (isBotRunning) return
        if (BotAccessibilityService.instance == null) {
            updateStatusText(
                "Status: Enable Accessibility Service first.",
                error = "Accessibility service is not connected.",
            )
            return
        }
        if (mediaProjection == null) {
            updateStatusText(
                "Status: Screen capture inactive.",
                error = "Restart the HUD from the main app.",
            )
            return
        }

        isBotRunning = true
        activeShipConfig = 1
        BotStateRepository.setBotActive(true, "Status: Scanning screen locally (100ms)...")
        updateControlVisuals()

        botLoopJob = serviceScope.launch {
            while (isActive && isBotRunning) {
                val accessibility = BotAccessibilityService.instance
                if (accessibility == null) {
                    stopBotLoop("Status: Accessibility disconnected")
                    break
                }

                val frameBitmap = withContext(Dispatchers.Default) {
                    captureLatestBitmap()
                }

                if (frameBitmap == null) {
                    updateStatusText("Status: Waiting for game frame...")
                    delay(LOOP_INTERVAL_MS)
                    continue
                }

                val snapshotState = BotStateRepository.state.value
                val decision = try {
                    withContext(Dispatchers.Default) {
                        screenAnalyzer.analyzeFrame(
                            frame = frameBitmap,
                            targetMap = snapshotState.targetMap,
                            targetAlien = snapshotState.targetAlien,
                            collectKeyBoxes = snapshotState.collectKeyBoxes,
                            currentConfig = activeShipConfig,
                        )
                    }
                } finally {
                    if (!frameBitmap.isRecycled) {
                        frameBitmap.recycle()
                    }
                }

                if (!isActive || !isBotRunning) break

                BotStateRepository.recordDecision(decision)
                val statusStr = "Status: ${decision.accion.uppercase()} (Cfg ${decision.config})\n${decision.razon}"
                statusTextView?.text = statusStr

                if (decision.accion == "cambiar_config") {
                    activeShipConfig = decision.config
                }

                val extraDelayMs = accessibility.executeDecision(
                    decision = decision,
                    calibration = snapshotState.calibration,
                )
                if (extraDelayMs > 0L) {
                    updateStatusText("Status: Opening key box (waiting 3.2s)...")
                    delay(extraDelayMs)
                }

                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    private fun stopBotLoop(reason: String) {
        isBotRunning = false
        botLoopJob?.cancel()
        botLoopJob = null
        BotStateRepository.setBotActive(false, reason)
        statusTextView?.text = reason
        updateControlVisuals()
    }

    private fun updateStatusText(message: String, error: String? = null) {
        BotStateRepository.updateStatus(message, error)
        statusTextView?.text = message
    }

    private fun updateControlVisuals() {
        toggleBotButton?.apply {
            text = if (isBotRunning) {
                getString(R.string.overlay_btn_pause)
            } else {
                getString(R.string.overlay_btn_start)
            }
            setBackgroundResource(
                if (isBotRunning) {
                    R.drawable.bg_floating_button_pause
                } else {
                    R.drawable.bg_floating_button_start
                }
            )
        }
        pillIndicator?.setBackgroundResource(
            if (isBotRunning) {
                R.drawable.bg_floating_button_pause
            } else {
                R.drawable.bg_floating_button_start
            }
        )
    }

    private fun captureLatestBitmap(): Bitmap? {
        createOrResizeVirtualDisplay()
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val paddedWidth = captureWidth + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(
                paddedWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888,
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == captureWidth) {
                fullBitmap
            } else {
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, captureWidth, captureHeight)
                fullBitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturando frame de MediaProjection", e)
            null
        } finally {
            image.close()
        }
    }

    private fun releaseProjectionResources() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando recursos de MediaProjection", e)
        }
    }

    override fun onDestroy() {
        stopBotLoop("Status: Service stopped")
        serviceScope.cancel()
        releaseProjectionResources()
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Error removiendo vista flotante", e)
            }
            overlayView = null
        }
        BotStateRepository.setOverlayState(running = false, hasProjection = false)
        super.onDestroy()
    }
}

package com.example.jpsubtitle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "jp_subtitle_capture"
        private const val NOTIFICATION_ID = 771
    }

    private lateinit var projection: MediaProjection
    private lateinit var imageReader: ImageReader
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var wm: WindowManager
    private var virtualDisplay: VirtualDisplay? = null
    private var subtitleView: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null
    private var lastSource = ""
    private var lastUpdate = 0L
    private val processing = AtomicBoolean(false)
    private val koreanRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private val translators = mutableMapOf<String, Translator>()
    private var lastTranslated = ""

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        workerThread = HandlerThread("subtitle-ocr").also { it.start() }
        worker = Handler(workerThread.looper)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("자막 번역을 준비하고 있습니다"))
        showSubtitle("字幕を検出しています…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, worker)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (processing.compareAndSet(false, true)) {
                worker.post {
                    try { processImage(image) }
                    catch (_: Exception) { }
                    finally {
                        image.close()
                        processing.set(false)
                    }
                }
            } else image.close()
        }, worker)
        virtualDisplay = projection.createVirtualDisplay(
            "JapaneseSubtitleOCR", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, worker
        )
        return START_STICKY
    }

    private fun processImage(image: Image) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 650) return
        lastUpdate = now

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = android.graphics.Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        var cropped: android.graphics.Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(buffer)
            cropped = android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val input = InputImage.fromBitmap(cropped, 0)

            val korean = try { Tasks.await(koreanRecognizer.process(input)) } catch (_: Exception) { null }
            val latin = try { Tasks.await(latinRecognizer.process(input)) } catch (_: Exception) { null }
            val candidates = listOfNotNull(korean, latin)
                .flatMap { result -> result.textBlocks.mapNotNull { block ->
                    val box = block.boundingBox ?: return@mapNotNull null
                    if (box.centerY() < image.height * 0.55f) null
                    else Triple(block.text.trim(), box, box.bottom)
                } }
                .filter { it.first.isNotBlank() && it.first.length <= 160 }
                .distinctBy { it.first }
                .sortedBy { it.third }

            val candidate = candidates.lastOrNull() ?: return
            val source = candidate.first
            if (source == lastSource || source.length < 1) return
            val language = try {
                Tasks.await(languageIdentifier.identifyLanguage(source))
            } catch (_: Exception) { "und" }
            val sourceCode = when (language) {
                "ko" -> com.google.mlkit.nl.translate.TranslateLanguage.KOREAN
                "en" -> com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
                else -> return
            }
            if (source == lastTranslated) return
            lastSource = source
            val translator = translators.getOrPut(sourceCode) {
                Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(sourceCode)
                    .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                    .build())
            }
            val translated = try {
                Tasks.await(translator.downloadModelIfNeeded())
                Tasks.await(translator.translate(source))
            } catch (_: Exception) { "" }
            if (translated.isNotBlank()) {
                lastTranslated = source
                val bounds = candidate.second
                val overlayWidth = (bounds.width() + 48).coerceIn(image.width / 3, image.width)
                val centerOffset = bounds.centerX() - image.width / 2
                showSubtitle(translated, centerOffset, bounds.top.coerceIn(
                    (image.height * 0.55f).toInt(), image.height - 1
                ), overlayWidth)
            }
        } finally {
            cropped?.recycle()
            padded.recycle()
        }
    }

    private fun showSubtitle(text: String, x: Int = 0, y: Int = -1, width: Int = -1) {
        if (!Settings.canDrawOverlays(this)) return
        worker.post {
            try {
                var view = subtitleView
                if (view == null) {
                    view = TextView(this).apply {
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setPadding(12, 5, 12, 5)
                        setBackgroundColor(0xDD000000.toInt())
                        elevation = 20f
                    }
                    val type = if (Build.VERSION.SDK_INT >= 26)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE
                    subtitleParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    }
                    subtitleView = view
                    wm.addView(view, subtitleParams)
                }
                view.text = text
                val params = subtitleParams!!
                params.width = if (width > 0) width.coerceAtMost(resources.displayMetrics.widthPixels) else WindowManager.LayoutParams.MATCH_PARENT
                params.x = x
                params.y = if (y >= 0) y else (resources.displayMetrics.heightPixels * 0.78f).toInt()
                wm.updateViewLayout(view, params)
            } catch (_: Exception) { }
        }
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("日本語字幕オーバーレイ")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "字幕翻訳", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader.close() } catch (_: Exception) { }
        try { projection.stop() } catch (_: Exception) { }
        try { subtitleView?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { koreanRecognizer.close() } catch (_: Exception) { }
        try { latinRecognizer.close() } catch (_: Exception) { }
        try { languageIdentifier.close() } catch (_: Exception) { }
        translators.values.forEach { try { it.close() } catch (_: Exception) { } }
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

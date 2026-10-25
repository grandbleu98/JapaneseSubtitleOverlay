package com.example.jpsubtitle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val requestCapture = 301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "日本語字幕オーバーレイ\n한국어·영어 → 일본어 실시간 자막"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "\n기존 화면 자막을 OCR로 읽어 일본어로 번역합니다.\n음성 인식은 사용하지 않습니다.\n\n1) 다른 앱 위에 표시 권한 허용\n2) 화면 캡처 허용\n3) 유튜브/영화 앱으로 이동"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val overlayButton = Button(this).apply {
            text = "1. 오버레이 권한 설정"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            }
        }
        val startButton = Button(this).apply {
            text = "2. 자막 번역 시작"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    overlayButton.performClick()
                    return@setOnClickListener
                }
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
            }
        }
        layout.addView(title)
        layout.addView(info)
        layout.addView(overlayButton)
        layout.addView(startButton)
        setContentView(layout)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, i)
            moveTaskToBack(true)
        }
    }
}

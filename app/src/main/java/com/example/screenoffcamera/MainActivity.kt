package com.example.screenoffcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 省電力カメラ
 *  - 起動すると画面は真っ黒＋最小輝度（OLED端末ではほぼ消灯と同等の消費電力）
 *  - 音量＋ : 撮影（シャッター音＋短い振動で撮れたことを通知）
 *  - 音量− : プレビュー表示 / 省電力表示 の切替（構図確認用）
 *  - 電源ボタン : OSの仕様上アプリから横取りできないため、押すと通常どおり画面OFF＝撮影終了
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var blackCover: View
    private lateinit var statusText: TextView

    private var imageCapture: ImageCapture? = null
    private val shutterSound = MediaActionSound()
    private val handler = Handler(Looper.getMainLooper())

    private var dimMode = true          // true = 省電力（黒画面）
    private var isCapturing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        blackCover = findViewById(R.id.blackCover)
        statusText = findViewById(R.id.statusText)

        // カメラ使用中に勝手に画面OFFされるとセッションが切れるため点灯は維持（輝度は最小）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        applyDimMode()
        hideHintLater()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                // 省電力寄り：連写よりも1枚あたりの処理を軽くする
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "カメラ起動失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- ボタン操作 ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) takePhoto()
                true // 音量変更を抑止
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    dimMode = !dimMode
                    applyDimMode()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun applyDimMode() {
        val lp = window.attributes
        if (dimMode) {
            blackCover.visibility = View.VISIBLE
            lp.screenBrightness = 0.01f      // ほぼ最小輝度
        } else {
            blackCover.visibility = View.GONE
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true

        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.JAPAN)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenOffCamera")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    vibrate(40)
                    flashStatus("保存しました")
                }

                override fun onError(e: ImageCaptureException) {
                    isCapturing = false
                    vibrate(300)   // 長い振動＝失敗
                    flashStatus("失敗: ${e.message}")
                }
            })
    }

    // ---- 補助 ----
    private fun vibrate(ms: Long) {
        @Suppress("DEPRECATION")
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun flashStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        hideHintLater()
    }

    private fun hideHintLater() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ statusText.visibility = View.INVISIBLE }, 2500)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release()
        handler.removeCallbacksAndMessages(null)
    }
}

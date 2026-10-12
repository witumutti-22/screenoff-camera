package com.example.screenoffcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 省電力カメラ（写真）＋ 動画モード
 *
 *  写真モード（省電力）: 画面は常に真っ黒＋最小輝度
 *    - 音量＋（短押し） : 撮影（シャッター音＋振動）
 *    - 音量−           : アウト ⇔ イン カメラ切替
 *    - 音量＋（長押し） : 動画モードへ切替
 *
 *  動画モード: 画面に映像を表示（省電力ではない）
 *    - 音量＋（短押し） : 録画 開始／停止（開始音・停止音あり）
 *    - 録画中は赤い「REC 録画中」を常時表示
 *    - 音量−           : カメラ切替（録画中は不可）
 *    - 音量＋（長押し） : 写真モードへ戻る
 *
 *  ※ 電源ボタンはOS仕様によりアプリから使用不可。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var blackCover: View
    private lateinit var statusText: TextView
    private lateinit var recIndicator: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null

    private val shutterSound = MediaActionSound()
    private val handler = Handler(Looper.getMainLooper())

    private var useFront = false
    private var videoMode = false
    private var isCapturing = false
    private var audioGranted = false

    private var longFiredUp = false
    private var longFiredDown = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
            if (result[Manifest.permission.CAMERA] == true) startCamera()
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
        recIndicator = findViewById(R.id.recIndicator)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        shutterSound.load(MediaActionSound.START_VIDEO_RECORDING)
        shutterSound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        applyMode()
        hideHintLater()

        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        audioGranted = mic == PackageManager.PERMISSION_GRANTED
        if (cam == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = if (useFront)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            if (videoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.bindToLifecycle(this, selector, preview, videoCapture)
            } else {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(this, selector, preview, imageCapture)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "カメラ起動失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyMode() {
        val lp = window.attributes
        if (videoMode) {
            // 映像を表示（録画していることが分かる作り）
            blackCover.visibility = View.GONE
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            // 省電力：常に真っ黒・最小輝度
            blackCover.visibility = View.VISIBLE
            lp.screenBrightness = 0.01f
        }
        window.attributes = lp
    }

    // ---- ボタン操作 ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) { event.startTracking(); longFiredUp = false }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) { event.startTracking(); longFiredDown = false }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            longFiredUp = true
            toggleMode()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!longFiredUp) primaryAction()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!longFiredDown) switchCamera()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun primaryAction() {
        if (videoMode) toggleRecording() else takePhoto()
    }

    private fun toggleMode() {
        if (recording != null) return   // 録画中はモード変更しない
        videoMode = !videoMode
        applyMode()
        bindCamera()
        vibrate(40)
        flashStatus(if (videoMode) "動画モード" else "写真モード（省電力）")
    }

    private fun switchCamera() {
        if (isCapturing || recording != null) return
        useFront = !useFront
        bindCamera()
        vibrate(40)
        flashStatus(if (useFront) "インカメラ" else "アウトカメラ")
    }

    // ---- 写真 ----
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
                    vibrate(300)
                    flashStatus("失敗: ${e.message}")
                }
            })
    }

    // ---- 動画 ----
    private fun toggleRecording() {
        val vc = videoCapture ?: return
        val current = recording
        if (current != null) {
            current.stop()
            recording = null
            return
        }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenOffCamera")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        try {
            var pending = vc.output.prepareRecording(this, output)
            if (audioGranted) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> {
                        playStartTone()   // 低い「どぅ」
                        recIndicator.visibility = View.VISIBLE
                        vibrate(60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        recIndicator.visibility = View.GONE
                        vibrate(60)
                        if (ev.hasError()) flashStatus("録画失敗: ${ev.error}")
                        else flashStatus("保存しました")
                        recording = null
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "録画開始失敗: ${e.message}", Toast.LENGTH_LONG).show()
            recording = null
        }
    }

    /** 低くて短い「どぅ」という開始音を生成して鳴らす（220→150Hzへわずかに下げる） */
    private fun playStartTone() {
        Thread {
            try {
                val sampleRate = 44100
                val durationMs = 200
                val n = sampleRate * durationMs / 1000
                val buf = ShortArray(n)
                val fStart = 220.0
                val fEnd = 150.0
                var phase = 0.0
                for (i in 0 until n) {
                    val p = i.toDouble() / n
                    val freq = fStart + (fEnd - fStart) * p          // 下降する音程
                    phase += 2.0 * Math.PI * freq / sampleRate
                    val attack = Math.min(1.0, i / (sampleRate * 0.008))
                    val decay = Math.exp(-3.5 * p)
                    val env = attack * decay
                    buf[i] = (Math.sin(phase) * env * Short.MAX_VALUE * 0.85).toInt().toShort()
                }
                @Suppress("DEPRECATION")
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    n * 2, AudioTrack.MODE_STATIC
                )
                track.write(buf, 0, n)
                track.setNotificationMarkerPosition(n)
                track.setPlaybackPositionUpdateListener(
                    object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(t: AudioTrack?) {
                            try { t?.release() } catch (_: Exception) {}
                        }
                        override fun onPeriodicNotification(t: AudioTrack?) {}
                    })
                track.play()
            } catch (_: Exception) {
                // 生成に失敗したらシステム音にフォールバック
                shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
            }
        }.start()
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
        handler.postDelayed({ statusText.visibility = View.INVISIBLE }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        shutterSound.release()
        handler.removeCallbacksAndMessages(null)
    }
}

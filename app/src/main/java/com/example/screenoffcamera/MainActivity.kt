package com.example.screenoffcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
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

    // 音量−の連続タップ検出（2回で隠しフォルダ全消去）
    private var downTapCount = 0
    private var lastDownTapAt = 0L

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
        // 画面下のナビゲーションバー領域まで黒で塗りつぶす（白い帯を消す）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        shutterSound.load(MediaActionSound.START_VIDEO_RECORDING)
        shutterSound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        applyMode()
        hideHintLater()

        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        audioGranted = mic == PackageManager.PERMISSION_GRANTED
        if (cam == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(storagePermissions())
    }

    override fun onResume() {
        super.onResume()
        ensureAllFilesAccess()
    }

    /** カメラ・マイクに加え、API29以下では保存用の書き込み権限も一緒に要求する */
    private fun storagePermissions(): Array<String> {
        val base = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            base.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return base.toTypedArray()
    }

    /** API30以上で「全ファイルアクセス」が未許可なら設定画面を開いて促す */
    private fun ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "隠しフォルダ保存には「全ファイルアクセス」を許可してください", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
            }
        }
    }

    /** 本体ストレージ直下の隠しフォルダ .ScreenOffCamera（ギャラリー非表示・アプリ削除でも残る） */
    private fun hiddenDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), ".ScreenOffCamera")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) try { nomedia.createNewFile() } catch (_: Exception) {}
        return dir
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
        // 写真・動画とも省電力のため黒画面・最小輝度を基本にする。
        // 動画は録画中だけ画面を明るくして赤いREC表示を見せる（録画を隠さないため）。
        blackCover.visibility = View.VISIBLE
        setBrightness(0.01f)
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
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
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            longFiredDown = true
            switchCamera()   // 下長押し: イン/アウト切替
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
                if (!longFiredDown) handleDownTap()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 音量−の短押し。素早く2回連続で押したら隠しフォルダを全消去、それ以外は状態チェック振動 */
    private fun handleDownTap() {
        val now = System.currentTimeMillis()
        downTapCount = if (now - lastDownTapAt <= 800L) downTapCount + 1 else 1
        lastDownTapAt = now
        if (downTapCount >= 2) {
            downTapCount = 0
            deleteAllHidden()
            return
        }
        statusVibrate()
    }

    /** 隠しフォルダ .ScreenOffCamera の中身（写真・動画）を全削除する。取り消し不可 */
    private fun deleteAllHidden() {
        if (recording != null) {
            recording?.stop()
            recording = null
        }
        var count = 0
        try {
            val dir = hiddenDir()
            dir.listFiles()?.forEach { f ->
                if (f.name != ".nomedia") {
                    shred(f)
                    if (f.delete()) count++
                }
            }
            // 隠しフォルダ自体も削除（.nomedia を消してから空のフォルダを削除）
            File(dir, ".nomedia").delete()
            dir.delete()
        } catch (_: Exception) {}
        vibrate(800)   // メッセージは出さず振動のみ
        // 少し待ってからアンインストール画面を開く（振動を感じられるように）
        handler.postDelayed({ uninstallSelf() }, 400L)
    }

    /** 自分自身のアンインストール画面を開く。OSの仕様で確認ダイアログは必ず出る（無言削除は不可） */
    private fun uninstallSelf() {
        try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    /** 削除前に、ファイルと同じサイズのランダムデータで中身を上書きする（復元を困難にする） */
    private fun shred(f: File) {
        try {
            val len = f.length()
            if (len <= 0L) return
            val buf = ByteArray(64 * 1024)
            val rnd = java.util.Random()
            java.io.RandomAccessFile(f, "rws").use { raf ->
                var written = 0L
                while (written < len) {
                    rnd.nextBytes(buf)
                    val chunk = minOf(buf.size.toLong(), len - written).toInt()
                    raf.write(buf, 0, chunk)
                    written += chunk
                }
                raf.fd.sync()
            }
        } catch (_: Exception) {}
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
        val photoFile = File(hiddenDir(), "IMG_$name.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    isCapturing = false
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
        val videoFile = File(hiddenDir(), "VID_$name.mp4")
        val output = FileOutputOptions.Builder(videoFile).build()

        try {
            var pending = vc.output.prepareRecording(this, output)
            if (audioGranted) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> {
                        vibrate(60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        stopRecBlink()
                        recIndicator.visibility = View.GONE
                        setBrightness(0.01f)  // 録画終了で再び暗く
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
    /** 今の状態を振動で知らせる: 写真モード=短1回 / 動画待機=短2回 / 録画中=長1回 */
    private fun statusVibrate() {
        @Suppress("DEPRECATION")
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val effect = when {
            recording != null -> VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)
            videoMode -> VibrationEffect.createWaveform(longArrayOf(0, 60, 150, 60), -1)
            else -> VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    private fun vibrate(ms: Long) {
        @Suppress("DEPRECATION")
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // 録画中、赤いREC表示を点滅させる（録画を視覚的に強調）
    private val blinkRunnable = object : Runnable {
        override fun run() {
            recIndicator.alpha = if (recIndicator.alpha > 0.5f) 0.25f else 1f
            handler.postDelayed(this, 500)
        }
    }
    private fun startRecBlink() {
        recIndicator.alpha = 1f
        handler.postDelayed(blinkRunnable, 500)
    }
    private fun stopRecBlink() {
        handler.removeCallbacks(blinkRunnable)
        recIndicator.alpha = 1f
    }

    private val hideHintRunnable = Runnable { statusText.visibility = View.INVISIBLE }

    private fun flashStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        hideHintLater()
    }

    private fun hideHintLater() {
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        shutterSound.release()
        handler.removeCallbacksAndMessages(null)
    }
}

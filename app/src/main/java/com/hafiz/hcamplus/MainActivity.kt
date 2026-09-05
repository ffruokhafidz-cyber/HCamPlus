package com.hafiz.hcamplus

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hafiz.hcamplus.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var b: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var mode = "Photo"
    private var timer = 0
    private var jpegQuality = 95
    private var exposure = 0
    private var zoom = 1f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startCameraIfReady() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupModes()
        b.timerButton.setOnClickListener { timer = when(timer){0->3;3->5;5->10;else->0}; updateTimer() }
        b.timerLabel.setOnClickListener { b.timerButton.performClick() }
        b.configButton.setOnClickListener { showConfigDialog() }
        b.shutter.setOnClickListener { captureOrRecord() }
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray()) else startCameraIfReady()
    }

    private fun startCameraIfReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(jpegQuality)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            camera?.cameraControl?.setExposureCompensationIndex(exposure)
            camera?.cameraControl?.setZoomRatio(zoom)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupModes() {
        listOf("Photo","Photo Live","Video","Portrait","HDR").forEach { name ->
            val x = Button(this).apply {
                text = name; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { setMode(name) }
            }
            b.modeRow.addView(x, LinearLayout.LayoutParams(-2, 52).apply { marginEnd=6 })
        }
        setMode("Photo")
    }

    private fun setMode(newMode:String) {
        mode=newMode
        b.status.text = "● $mode"
        b.shutter.setImageResource(if(mode=="Video") android.R.drawable.ic_media_play else android.R.drawable.ic_menu_camera)
    }

    private fun updateTimer() {
        b.timerButton.text="⏱ ${timer}s"; b.timerLabel.text="Timer ${timer}s"
    }

    private fun captureOrRecord() {
        if(mode=="Video") { toggleVideo(); return }
        if(timer>0) {
            b.status.text="● ${timer}s"
            Handler(Looper.getMainLooper()).postDelayed({ if(!isFinishing()) captureStill() }, timer*1000L)
        } else captureStill()
    }

    private fun captureStill() {
        val capture=imageCapture ?: return
        val name="HCAM_"+SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())+".jpg"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            if(Build.VERSION.SDK_INT>=29) put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/HCam+")
        }
        val out=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values).build()
        capture.takePicture(out,executor,object:ImageCapture.OnImageSavedCallback{
            override fun onError(e:ImageCaptureException){ runOnUiThread{b.status.text="● CAPTURE ERROR"} }
            override fun onImageSaved(r:ImageCapture.OutputFileResults){ runOnUiThread{b.status.text="● SAVED • $mode"} }
        })
    }

    private fun toggleVideo() {
        val vc=videoCapture ?: return
        if(recording!=null) {
            recording?.stop(); recording=null; b.status.text="● VIDEO SAVED"; return
        }
        val name="HCAM_"+SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())+".mp4"
        val values=ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME,name); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
            if(Build.VERSION.SDK_INT>=29) put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/HCam+")
        }
        val output=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        val pending=vc.output.prepareRecording(this,output)
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
            pending.withAudioEnabled()
        recording=pending.start(ContextCompat.getMainExecutor(this)){ e ->
            when(e){
                is VideoRecordEvent.Start -> b.status.text="● RECORDING"
                is VideoRecordEvent.Finalize -> { recording=null; b.status.text=if(e.hasError())"● VIDEO ERROR" else "● VIDEO SAVED" }
            }
        }
    }

    private fun showConfigDialog() {
        val options=arrayOf("Default","Natural","High Detail","Night")
        AlertDialog.Builder(this).setTitle("Select Config").setItems(options){_,which->
            when(which){0->{jpegQuality=95;exposure=0;zoom=1f};1->{jpegQuality=92;exposure=0};2->{jpegQuality=100;exposure=0};3->{jpegQuality=100;exposure=-1}}
            camera?.cameraControl?.setExposureCompensationIndex(exposure)
            camera?.cameraControl?.setZoomRatio(zoom)
            imageCapture?.setJpegQuality(jpegQuality)
            b.status.text="● CONFIG • ${options[which]}"
        }.setNegativeButton("Cancel",null).show()
    }

    override fun onDestroy(){ super.onDestroy(); recording?.stop(); executor.shutdown() }
}
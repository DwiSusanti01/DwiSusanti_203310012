package com.dwisusanti.akseskameradwisusanti

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import java.util.concurrent.ExecutorService

import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.PermissionChecker
import com.dwisusanti.akseskameradwisusanti.databinding.ActivityMainBinding
import com.dwisusanti.akseskameradwisusanti.ml.MobilenetV110224Quant
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

typealias LumaListener = (luma: Double) -> Unit


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    lateinit var bitmap: Bitmap
    lateinit var imgview: ImageView

    //diimplementasikan untuk memeriksa izin kamera, memulai kamera, mengatur tombol onClickListener()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        imgview = findViewById(R.id.imageView)
        val fileName = "labels.txt"
        val inputString = application.assets.open(fileName).bufferedReader().use { it.readText() }
        var townList = inputString.split("\n")

        var tv: TextView = findViewById(R.id.textView)

        var select: Button = findViewById(R.id.button)

        select.setOnClickListener(View.OnClickListener {
            var intent: Intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"

            startActivityForResult(intent, 100)

        })

        var predict: Button = findViewById(R.id.button2)
        predict.setOnClickListener(View.OnClickListener {

            var resized: Bitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val model = MobilenetV110224Quant.newInstance(this)

            val inputFeature0 =
                TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.UINT8)

            var tbuffer = TensorImage.fromBitmap(resized)
            var byteBuffer = tbuffer.buffer

            inputFeature0.loadBuffer(byteBuffer)

            val outputs = model.process(inputFeature0)
            val outputFeature0 = outputs.outputFeature0AsTensorBuffer

            var max = getMax(outputFeature0.floatArray)

            tv.setText(townList[max])
            model.close()
        })
        var share: Button = findViewById(R.id.button4)
        predict.setOnClickListener(View.OnClickListener {
        })

        // Meminta izin kamera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Menyiapkan untuk mengambil tombol pengambilan foto dan video
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    //Untuk mengambil foto, takePhoto() metode yang dipanggil saat tombol Ambil foto ditekan
    private fun takePhoto() {
        // Dapatkan referensi stabil dari kasus penggunaan pengambilan gambar yang dapat dimodifikasi
        val imageCapture = imageCapture ?: return

        // membuat nilai konten MediaStore untuk menampung gambar
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Membuat objek opsi output yang berisi file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Menyiapkan pendengar pengambilan gambar, yang dipicu setelah foto
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                ////Jika pengambilan gambar gagal atau penyimpanan pengambilan gambar gagal,
                // tambahkan kasus kesalahan untuk mencatat bahwa itu gagal.
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
                //Jika pengambilan tidak gagal, foto berhasil diambil! Simpan foto ke file yang kita buat sebelumnya,
                // berikan toast untuk memberi tahu pengguna bahwa itu berhasil, dan cetak pernyataan log.
                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }
    // Mengimplementasikan kasus penggunaan VideoCapture, termasuk mulai dan berhenti menangkap
    private fun captureVideo() {
        //untuk memeriksa apakah kasus penggunaan VideoCapture telah dibuat
        val videoCapture = this.videoCapture ?: return
        //Nonaktifkan UI hingga tindakan permintaan diselesaikan oleh CameraX
        viewBinding.videoCaptureButton.isEnabled = false

        //Jika ada rekaman aktif yang sedang berlangsung, hentikan dan lepaskan arus recording
        val curRecording = recording
        if (curRecording != null) {
            // untuk menghentikan sesi perekaman saat ini
            curRecording.stop()
            recording = null
            return
        }

        // membuat dan memulai sesi rekaman baru
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues) //Setel video yang dibuat contentValues ke MediaStoreOutputOptions.Builder
            .build()
        // Konfigurasikan opsi output ke Recorderdari VideoCapture<Recorder>
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
                ////mengaktifkan perekaman audio
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) ==
                    PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
                //mulai rekaman baru iniVideoRecordEvent dan daftarkan pendengar lambda
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    // perekaman permintaan dimulai oleh perangkat kamera
                    is VideoRecordEvent.Finalize -> {
                        //mengalihkan tombol "Stop Capture" kembali ke "Start Capture", dan aktifkan kembali
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(
                                TAG, "Video capture ends with error: " +
                                        "${recordEvent.error}"
                            )
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        // Ini digunakan untuk digunakan untuk mengikat siklus hidup kamera kita ke LifecycleOwnerdalam proses aplikasi
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Digunakan untuk mengikat siklus hidup kamera ke pemilik siklus hidup
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Inisialisasi Previewobjek
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }
            // membuat VideoCapture kasus penggunaan
            val recorder = Recorder.Builder()
                //memungkinkan CameraX mengambil resolusi yang didukung jika yang diperlukan Quality.
                // HIGHESTtidak didukung dengan imageCapturekasus penggunaan.
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            imageCapture = ImageCapture.Builder().build()
            ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            // Pilih kamera belakang sebagai default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // dalam tryblok untuk menyertakan kasus penggunaan baru
            try {
                // Kasus penggunaan yang tidak mengikat sebelum rebinding
                cameraProvider.unbindAll()

                // Mengikat kasus penggunaan ke kamera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)
            //catchblok untuk login jika ada kegagalan
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    //mengimplementasikan ImageAnalysis.Analyzer antarmuka (Menerapkan kasus penggunaan ImageAnalysis)
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Memundurkan buffer ke nol
            val data = ByteArray(remaining())
            get(data)   // Menyalin buffer ke dalam array byte
            return data // Mengembalikan array byte
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            imgview.setImageURI(data?.data)

            var uri: Uri? = data?.data
            bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
        }

        fun getMax(arr: FloatArray): Int {
            var ind = 0
            var min = 0.0f

            for (i in 0..1000) {
                if (arr[i] > min) {
                    ind = i
                    min = arr[i]
                }
            }
            return ind
        }
    }

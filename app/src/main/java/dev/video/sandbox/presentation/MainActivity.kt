package dev.video.sandbox.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.video.sandbox.video.VideoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File


private const val REQUEST_TAKE_GALLERY_VIDEO = 1
class MainActivity : AppCompatActivity() {

    private var videoProcessor: VideoProcessor? = null


    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkWritePermisstion()

        setContent {
            MainScreen(viewModel, onPickImageClick = ::pickVideo )
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }
    private fun checkWritePermisstion() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {

            }
            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
        }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun pickVideo() {
        val intent = Intent().apply {
            type = "video/*"
            action = Intent.ACTION_GET_CONTENT
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(
            Intent.createChooser(intent, "Select Video"),
            REQUEST_TAKE_GALLERY_VIDEO
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_TAKE_GALLERY_VIDEO) {
                val selectedUri: Uri? = data?.data

                if (selectedUri != null) {
                    MainScope().launch {
                        //         delay(2000)
                        testProcess(selectedUri)
                        //viewModel.sendVideo(selectedUri, true)
                    }
                }
            }
        }
    }

    private fun testProcess(contentUri: Uri) {
        val ext = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val dcim = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "test01"
        )

        val root = dcim
        val outputPath = File(root, "1.mp4")
        if (!root.exists()) {
            root.mkdirs()
        }
        if (outputPath.exists()) {
            outputPath.delete()
        }

        videoProcessor = VideoProcessor(this,
            inputUri = contentUri,
            outputFile = outputPath,
            withAudio = true)
        GlobalScope.launch {
            videoProcessor?.process()?.flowOn(Dispatchers.Main)?.collect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoProcessor?.release()
    }
}
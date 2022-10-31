package dev.camera.sandbox.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.camera.sandbox.video.MediaTrackInfo
import dev.camera.sandbox.video.VideoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File


private const val REQUEST_TAKE_GALLERY_VIDEO = 1
class MainActivity : AppCompatActivity() {

    private var videoProcessor: VideoProcessor? = null

    private val hasPermission = MutableStateFlow(false)

    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            if (hasPermission.collectAsState().value) {
                MainScreen(viewModel, onPickImageClick = ::pickVideo )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = ::requestPermission) {
                        Text("Request permission")
                    }
                }
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                hasPermission.value = true
            }

            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
            }

            else -> {
               requestPermission()
            }
        }
    }

    private fun requestPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            12
        )
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
                        //testProcess(selectedUri)
                        viewModel.sendVideo(selectedUri, false)
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
            CoroutineScope(Dispatchers.Main),
            contentUri,
            outputPath,
            true)
        GlobalScope.launch {
            videoProcessor?.process()?.flowOn(Dispatchers.Main)?.collect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoProcessor?.release()
    }

    private fun MediaExtractor.pickMediaTrack(prefix: String): MediaTrackInfo? {
        var result: MediaTrackInfo? = null
        for (index in 0 until trackCount) {
            val format = getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                result = MediaTrackInfo(
                    trackIndex = index,
                    mime = mime,
                    format = format
                )
                break
            }
        }

        return result
    }
}
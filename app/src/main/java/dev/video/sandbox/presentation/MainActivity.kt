package dev.video.sandbox.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

        setContent {
            MainScreen(viewModel, onPickImageClick = ::pickVideo )
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
}
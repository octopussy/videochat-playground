package dev.camera.sandbox.video.storage

import android.graphics.Bitmap
import java.io.File

sealed interface VideoState {
    object Initial : VideoState
    object ProcessingError : VideoState

    sealed interface Processing : VideoState {
        object Initialization : Processing
        data class InProgress(val progress: Float, val thumbBitmap: Bitmap?) : Processing
    }

    data class Uploading(val progress: Float, val thumbBitmap: Bitmap?) : VideoState

    data class Ready(val file: File) : VideoState
}
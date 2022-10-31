package dev.camera.sandbox.video.storage

import kotlinx.coroutines.flow.StateFlow

interface VideoHandler {
    fun retry()
    val state: StateFlow<VideoState>
}
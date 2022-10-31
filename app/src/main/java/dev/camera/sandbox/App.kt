package dev.camera.sandbox

import android.app.Application
import dev.camera.sandbox.video.storage.VideoManager
import timber.log.Timber

class App : Application() {

    companion object {
        lateinit var instance: App
    }

    lateinit var videoManager: VideoManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        Timber.plant(Timber.DebugTree())

        videoManager = VideoManager(this)
    }

}
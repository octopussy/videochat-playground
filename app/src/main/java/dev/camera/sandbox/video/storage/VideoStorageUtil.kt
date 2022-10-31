package dev.camera.sandbox.video.storage

import dev.camera.sandbox.video.HashUtil
import java.io.File

object VideoStorageUtil {

    fun calculateHash(file: File): String {
        if (!file.exists()) {
            throw IllegalStateException("File not found '$file'")
        }
        return HashUtil.calculateMD5(file)
    }

}
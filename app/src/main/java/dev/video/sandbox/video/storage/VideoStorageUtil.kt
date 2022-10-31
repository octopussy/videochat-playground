package dev.video.sandbox.video.storage

import dev.video.sandbox.video.HashUtil
import java.io.File

object VideoStorageUtil {

    fun calculateHash(file: File): String {
        if (!file.exists()) {
            throw IllegalStateException("File not found '$file'")
        }
        return HashUtil.calculateMD5(file)
    }

}
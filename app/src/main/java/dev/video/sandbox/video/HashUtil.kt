package dev.video.sandbox.video

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

object HashUtil {

    fun calculateMD5(context: Context, contentUri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(contentUri)
            ?: throw IllegalArgumentException("$contentUri not found")

        return calculateMD5(inputStream)
    }

    fun calculateMD5(file: File): String {
        return calculateMD5(FileInputStream(file))
    }

    private fun calculateMD5(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var read: Int

        return try {
            while (inputStream.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
            val md5sum: ByteArray = digest.digest()
            val bigInt = BigInteger(1, md5sum)
            var output: String = bigInt.toString(16)
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0')
            output
        } catch (e: IOException) {
            throw RuntimeException("Unable to process file for MD5", e)
        } finally {
            try {
                inputStream.close()
            } catch (e: IOException) {
                Timber.e("Exception on closing MD5 input stream", e)
            }
        }
    }
}
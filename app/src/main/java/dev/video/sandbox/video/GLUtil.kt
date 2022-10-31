package dev.video.sandbox.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import java.nio.IntBuffer

fun makeBitmapFromGlPixels(w: Int, h: Int, rotationDeg: Int): Bitmap {
    val intArr = IntArray(w * h)
    val intBuf = IntBuffer.wrap(intArr).apply {
        position(0)
    }
    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, intBuf)

    (0 until h).forEach { y ->
        (0 until w).forEach { x ->
            // swap lines
            if (y < h/2) {
                val tmp = intArr[y * w + x]
                intArr[y * w + x] = intArr[(h - y - 1) * w + x]
                intArr[(h - y - 1) * w + x] = tmp
            }

            // convert color
            val pix = intArr[y * w + x]
            val pb = pix shr 16 and 0xff
            val pr = pix shl 16 and 0x00ff0000
            val pix1 = pix and -0xff0100 or pr or pb
            intArr[y * w + x] = pix1
        }
    }
    val original = Bitmap.createBitmap(intArr, w, h, Bitmap.Config.ARGB_8888)

    if (rotationDeg == 0) {
        return original
    }

    val matrix = Matrix()
    matrix.postRotate(rotationDeg.toFloat())
    val scaledBitmap = Bitmap.createScaledBitmap(original, w, h, true)
    return Bitmap.createBitmap(
        scaledBitmap,
        0,
        0,
        scaledBitmap.width,
        scaledBitmap.height,
        matrix,
        true
    )
}
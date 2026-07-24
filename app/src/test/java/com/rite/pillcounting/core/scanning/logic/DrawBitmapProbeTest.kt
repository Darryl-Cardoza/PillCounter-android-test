package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DrawBitmapProbeTest {
    @Test
    fun `probe exact letterbox sequence`() {
        val src = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        val out = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.BLACK)
        matrix.reset()
        matrix.postScale(1f, 1f)
        matrix.postTranslate(0f, 0f)
        canvas.drawBitmap(src, matrix, paint)

        println("PROBE exact-sequence pixel=${Integer.toHexString(out.getPixel(320,320))}")
        println("PROBE matrix=$matrix isIdentity=${matrix.isIdentity}")

        // Now via ImagePreprocessor/Letterbox actual call
        val result = Letterbox.preprocess(src, 640)
        println("PROBE letterbox-actual pixel=${Integer.toHexString(result.getPixel(320,320))}")
    }
}

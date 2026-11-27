package com.aitv.launcher.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/** 生成二维码位图，供电视端“扫码上传”使用。 */
object QrCode {

    fun encode(text: String, sizePx: Int): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            val dark = Color.rgb(20, 16, 13)
            val light = Color.rgb(255, 252, 247)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) dark else light)
                }
            }
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}

package com.slurrycalc.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Генерация QR-кода для передачи заданий (UI-слой: использует android.graphics).
 */
object TaskQr {

    /** Надёжно ли помещается текст в QR версии <=30 с L-коррекцией (~<=2500 симв.) */
    fun fitsReliable(text: String): Boolean = text.length <= 2400

    fun encode(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                  com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}

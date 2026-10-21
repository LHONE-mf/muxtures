package com.slurrycalc.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Генерация QR-кода для передачи задания «с телефона на телефон» камерой.
 * Используется ZXing core (без Android-зависимостей) + ручной вывод в Bitmap ARGB_8888,
 * чтобы не тянуть тяжёлые зависимости и иметь контроль над размером/тихой зоной.
 */
object TaskQr {

    /**
     * @return Bitmap с QR или null, если данные не влезают в QR разумного размера.
     * @param maxModuleSize px — размер модуля; итоговая сторона ~ (modules + 2*quiet)*maxModuleSize
     */
    fun encode(text: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    pixels[off + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        } catch (_: Exception) {
            null // данные слишком длинные для QR — используйте шаринг файла/текста
        }
    }

    /** Проверяет, влезает ли base64payload в QR надёжно (см. лимиты TaskTransfer) */
    fun fitsReliable(b64: String): Boolean = b64.length <= TaskTransfer.QR_RELIABLE_B64
    fun fitsMax(b64: String): Boolean = b64.length <= TaskTransfer.QR_MAX_B64
}

package com.hisabbaki.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object UpiQr {

    /**
     * Standard UPI deep link. Only a payment request: the actual payment is
     * confirmed inside the user's UPI app; Hishabbaki never marks it paid by itself.
     */
    fun buildUri(upiId: String, payeeName: String, amountPaise: Long, note: String): String {
        val base = StringBuilder("upi://pay?pa=").append(upiId)
        if (payeeName.isNotBlank()) base.append("&pn=").append(java.net.URLEncoder.encode(payeeName, "UTF-8"))
        if (amountPaise > 0) {
            base.append("&am=").append(java.math.BigDecimal.valueOf(amountPaise, 2).toPlainString())
        }
        if (note.isNotBlank()) base.append("&tn=").append(java.net.URLEncoder.encode(note, "UTF-8"))
        base.append("&cu=INR")
        return base.toString()
    }

    fun bitmap(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}

package com.rjkyker.shizukuai.broker.barcode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

object BarcodeLabelGenerator {
    private val formats = mapOf(
        "QR" to BarcodeFormat.QR_CODE,
        "QR_CODE" to BarcodeFormat.QR_CODE,
        "CODE_128" to BarcodeFormat.CODE_128,
        "CODE_39" to BarcodeFormat.CODE_39,
        "EAN_13" to BarcodeFormat.EAN_13,
        "EAN_8" to BarcodeFormat.EAN_8,
        "UPC_A" to BarcodeFormat.UPC_A,
        "DATA_MATRIX" to BarcodeFormat.DATA_MATRIX,
        "PDF_417" to BarcodeFormat.PDF_417,
        "AZTEC" to BarcodeFormat.AZTEC
    )

    fun generate(context: Context, request: JSONObject): JSONObject {
        val value = request.optString("value", request.optString("data", ""))
        val requestedFormat = request.optString("format", "QR_CODE").uppercase(Locale.ROOT)
        val format = formats[requestedFormat]
            ?: return error("unsupported barcode format")
        if (value.isBlank()) return error("value is required")
        if (value.length > 4096) return error("value exceeds 4096 characters")

        val matrixFormat = format
        val matrixDefault = matrixFormat in setOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC)
        val defaultWidth = if (matrixDefault) 512 else 1024
        val defaultHeight = if (matrixDefault) 512 else 256
        val width = request.optInt("width", defaultWidth)
        val height = request.optInt("height", defaultHeight)
        if (width !in 64..2048 || height !in 64..2048 || width.toLong() * height.toLong() > 2_500_000L) {
            return error("image dimensions are outside the supported range")
        }

        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = request.optInt("margin", 2).coerceIn(0, 16)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            if (format == BarcodeFormat.QR_CODE) {
                hints[EncodeHintType.ERROR_CORRECTION] = when (request.optString("errorCorrection", "M").uppercase(Locale.ROOT)) {
                    "L" -> ErrorCorrectionLevel.L
                    "Q" -> ErrorCorrectionLevel.Q
                    "H" -> ErrorCorrectionLevel.H
                    else -> ErrorCorrectionLevel.M
                }
            }
            val matrix = MultiFormatWriter().encode(value, format, width, height, hints)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
            val outputDirectory = context.getExternalFilesDir("barcode-labels") ?: File(context.filesDir, "barcode-labels")
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                bitmap.recycle()
                return error("could not create barcode output directory")
            }
            val filename = "bgx-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().take(8) + ".png"
            val file = File(outputDirectory, filename)
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IllegalStateException("PNG encoding failed")
                }
            }
            bitmap.recycle()
            JSONObject().put("ok", true)
                .put("value", value)
                .put("format", format.name)
                .put("width", width)
                .put("height", height)
                .put("path", file.absolutePath)
                .put("bytes", file.length())
                .put("verify_by_scanning", true)
                .put("scope", "LOCAL_LABEL_IMAGE_ONLY")
        } catch (e: Exception) {
            error(e.message ?: "barcode generation failed")
        }
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)
}

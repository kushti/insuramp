package p2pgate.backend.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * QR rasterizer for the seller-meeting dashboard (`specs/operator-backend.md`
 * §9): encodes a `p2pgate://` payload (see `:apps:core:dealprotocol`'s
 * `QrPayload`) as a PNG. Uses ZXing's `core` only — the PNG itself is rendered
 * from the [com.google.zxing.common.BitMatrix] via `javax.imageio`, so no
 * `javase` dependency is needed.
 */
object QrCodes {

    /** [payload] → black-on-white PNG of [size]×[size] px encoding it as a QR. */
    fun png(payload: String, size: Int = 512): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) BLACK else WHITE)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}

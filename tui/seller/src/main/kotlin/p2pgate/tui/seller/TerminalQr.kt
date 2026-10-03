package p2pgate.tui.seller

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The handoff QR the buyer scans at the meeting, rendered for a terminal.
 *
 * The backend already rasterizes the payload to a PNG (`/v1/dashboard/deals/{id}
 * /handoff/qr.png`); a seller standing at a counter with a phone in one hand and
 * a keyboard in the other needs it *on the screen they are already looking at*,
 * so the payload is re-rendered here as half-block characters. Decoding is
 * deliberately not implemented — the phone is the reader, and a phone camera is
 * far more forgiving of the aliasing a terminal's font introduces than any
 * software decoder would be. A scannable rendering comes from the PNG endpoint
 * (e.g. `curl -H "Authorization: Bearer $P2P_OPERATOR_KEY" .../qr.png -o q.png`).
 *
 * Two rows per character line using the upper-half block `▀`: a module set in the
 * top row paints the block, a module set in the bottom row paints it inverted, so
 * the square stays square instead of stretching two-to-one.
 */
internal object TerminalQr {

    private const val QUIET_ZONE = 2
    private val FILLED = '█'
    private val TOP_HALF = '▀'
    private val BOTTOM_HALF = '▄'
    private val EMPTY = ' '

    /**
     * [modules] is a square bit matrix, row-major, `true` = dark — ZXing's
     * `BitMatrix` shape. Returns one string per two matrix rows.
     */
    fun render(modules: List<Boolean>, width: Int): List<String> {
        require(width > 0 && modules.size == width * width) {
            "expected a ${width}x$width bit matrix, got ${modules.size} bits"
        }
        val size = width + QUIET_ZONE * 2
        fun dark(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= size || y >= size) return false
            val mx = x - QUIET_ZONE
            val my = y - QUIET_ZONE
            if (mx < 0 || my < 0 || mx >= width || my >= width) return false
            return modules[my * width + mx]
        }

        val lines = mutableListOf<String>()
        var y = 0
        while (y < size) {
            val sb = StringBuilder(size)
            for (x in 0 until size) {
                val top = dark(x, y)
                val bottom = dark(x, y + 1)
                sb.append(
                    when {
                        top && bottom -> FILLED
                        top -> TOP_HALF
                        bottom -> BOTTOM_HALF
                        else -> EMPTY
                    },
                )
            }
            lines += sb.toString().trimEnd()
            y += 2
        }
        return lines
    }

    /**
     * Renders [payload] — the `p2pgate://handoff?m=…` string the backend returns
     * from the signing endpoint — as QR text. ZXing's `QRCodeWriter` is the same
     * encoder `QrCodes` uses server-side, so the two renderings carry identical
     * modules; only the presentation differs.
     */
    fun renderPayload(payload: String, quietZone: Int = QUIET_ZONE): List<String> {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)
        val width = matrix.width
        val modules = ArrayList<Boolean>(width * width)
        for (y in 0 until width) for (x in 0 until width) modules += matrix.get(x, y)
        return render(modules, width)
    }

}

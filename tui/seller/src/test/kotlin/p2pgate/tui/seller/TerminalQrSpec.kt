package p2pgate.tui.seller

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The meeting QR rendered to terminal text. The property that matters is that a
 * phone can read it, and there is no phone here — so the two strongest available
 * proxies are asserted instead:
 *
 *  1. **every module maps to the glyph its encoder bit implies** — a rendering
 *     that is inverted, shifted or transposed fails even though it still looks
 *     like a QR;
 *  2. **the text decodes back to the payload** through ZXing, closing the loop
 *     end to end (the raster step mirrors `ApiSpec.decodeQr`, since a scanner
 *     sees pixels, not modules).
 *
 * Two module rows are packed per character line with half-block glyphs so the
 * code stays square; [toMatrix] mirrors that packing exactly, which is where the
 * parity of a module's row decides which half of its glyph it occupies.
 */
class TerminalQrSpec {

    /** [TerminalQr]'s own margin, in modules. */
    private val quietZone = 2

    private fun matrixOf(payload: String): BitMatrix =
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)

    private fun bitsOf(matrix: BitMatrix): List<Boolean> =
        (0 until matrix.height).flatMap { y -> (0 until matrix.width).map { x -> matrix.get(x, y) } }

    @Test
    fun `a rendered QR has one line per two matrix rows`() {
        val matrix = matrixOf(PAYLOAD)
        val lines = TerminalQr.render(bitsOf(matrix), matrix.width)
        // Two margin columns/rows on each side, then one line per two rows.
        assertEquals((matrix.height + quietZone * 2 + 1) / 2, lines.size)
    }

    @Test
    fun `every module maps to the glyph its encoder bit implies`() {
        val matrix = matrixOf(PAYLOAD)
        val lines = TerminalQr.renderPayload(PAYLOAD)

        // Lines are trimmed on the right, so read past the end as blank.
        fun glyphAt(mx: Int, my: Int): Char =
            lines[(my + quietZone) / 2].getOrElse(mx + quietZone) { ' ' }

        // The renderer's own margin is blank all the way round.
        assertEquals(' ', glyphAt(-1, -1))
        assertEquals(' ', glyphAt(matrix.width, matrix.height))

        for (my in 0 until matrix.height) {
            // The module's own half: top when its padded row is even, else bottom.
            val topHalf = (my + quietZone) % 2 == 0
            for (mx in 0 until matrix.width) {
                val glyph = glyphAt(mx, my)
                val ownHalfDark =
                    if (topHalf) glyph == '█' || glyph == '▀' else glyph == '█' || glyph == '▄'
                assertEquals(
                    matrix.get(mx, my),
                    ownHalfDark,
                    "module ($mx,$my) rendered as $ownHalfDark at line ${(my + quietZone) / 2}",
                )
            }
        }
    }

    @Test
    fun `only the block characters and blanks are used`() {
        val lines = TerminalQr.renderPayload(PAYLOAD)
        val used = lines.flatMap { it.toList() }.toSet()
        val allowed = setOf('█', '▀', '▄', ' ')
        assertTrue(used.all { it in allowed }, "unexpected glyphs: ${used - allowed}")
    }

    @Test
    fun `the terminal rendering scans back to the same payload`() {
        val matrix = matrixOf(PAYLOAD)
        val rebuilt = toMatrix(TerminalQr.renderPayload(PAYLOAD), matrix.width, matrix.height)
        assertEquals(
            bits(matrix),
            bits(rebuilt, inset = MARGIN),
            "the grid read back out of the text differs from the encoder's",
        )
        assertEquals(PAYLOAD, decode(rebuilt))
    }

    @Test
    fun `a matrix that is not square is rejected rather than rendered wrong`() {
        val error = runCatching { TerminalQr.render(List(5) { false }, 8) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, error.toString())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The inverse of `TerminalQr.render`: half-block characters back into a
     * module grid, with the margin restored so a reader can find the edges.
     *
     * The renderer packs two rows into one character line, so module row [my]
     * lives in the glyph at line `(my + quietZone) / 2` — in its *top* half when
     * the padded row is even and its *bottom* half when it is odd. Reading the
     * wrong half of every other row still produces something that looks like a
     * QR, which is exactly the trap this function has to avoid.
     */
    private fun toMatrix(lines: List<String>, width: Int, height: Int): BitMatrix {
        val out = BitMatrix(width + MARGIN * 2, height + MARGIN * 2)
        for (my in 0 until height) {
            val paddedRow = my + quietZone
            val line = lines[paddedRow / 2]
            val topHalf = paddedRow % 2 == 0
            for (mx in 0 until width) {
                val glyph = line.getOrElse(mx + quietZone) { ' ' }
                val dark = if (topHalf) glyph == '█' || glyph == '▀' else glyph == '█' || glyph == '▄'
                if (dark) out.set(mx + MARGIN, my + MARGIN)
            }
        }
        return out
    }

    /** A matrix's bits as `#`/`.` text, optionally skipping a margin, for diffing. */
    private fun bits(matrix: BitMatrix, inset: Int = 0): String = buildString {
        for (y in inset until matrix.height - inset) {
            for (x in inset until matrix.width - inset) append(if (matrix.get(x, y)) '#' else '.')
        }
    }

    /**
     * Decodes a module grid the way a phone would: rasterized to pixels first,
     * because `HybridBinarizer` needs a real image to threshold. 8 px per module
     * is well past what any scanner needs.
     */
    private fun decode(matrix: BitMatrix): String {
        val scale = 8
        val pixels = IntArray(matrix.width * scale * (matrix.height * scale)) { i ->
            val x = i % (matrix.width * scale)
            val y = i / (matrix.width * scale)
            val moduleX = x / scale
            val moduleY = y / scale
            if (matrix.get(moduleX, moduleY)) BLACK else WHITE
        }
        val source = RGBLuminanceSource(matrix.width * scale, matrix.height * scale, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {

        /** A payload in the real shape — long enough to force a mid-size version. */
        const val PAYLOAD = "p2pgate://handoff?m=eyJkZWFsSWQiOiJhYmMiLCJhbW91bnQiOjUwMDAwMDAwMH0"
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Modules of quiet zone [toMatrix] adds so a reader can find the edges. */
        const val MARGIN = 4
    }
}
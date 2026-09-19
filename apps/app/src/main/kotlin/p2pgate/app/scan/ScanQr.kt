package p2pgate.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.google.zxing.integration.android.IntentIntegrator
import p2pgate.app.R

/**
 * QR scan contract over ZXing's CaptureActivity (`com.journeyapps:zxing-android-embedded`,
 * the §8.6 privacy default: pure, offline, camera-based). Returns the raw
 * payload string, or null when the scan was cancelled.
 */
object ScanQr : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        IntentIntegrator(context as Activity).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setOrientationLocked(true)
            setPrompt(context.getString(R.string.scan_qr_prompt))
            setBeepEnabled(false)
        }.createScanIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) {
            IntentIntegrator.parseActivityResult(resultCode, intent)?.contents
        } else {
            null
        }
}

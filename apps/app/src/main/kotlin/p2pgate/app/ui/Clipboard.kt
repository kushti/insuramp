package p2pgate.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Copies [text] under [label] to the system clipboard.
 *
 * Used for the deal recovery link (`specs/android-app.md` §5) — the one string
 * a buyer has to get out of the app by hand. The label is what Android shows in
 * the clipboard toast, so it says what the text is rather than "Copied".
 */
fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

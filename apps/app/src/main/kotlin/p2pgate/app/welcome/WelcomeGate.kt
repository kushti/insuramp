package p2pgate.app.welcome

/**
 * The once-after-install welcome gate. Pure logic over two injected closures
 * (no Android types) so it is JVM-testable; MainActivity binds them to the
 * `p2pgate_settings` SharedPreferences. The flag is written ONLY on dismiss
 * ("Get started") — a first launch that dies before dismissing shows the
 * welcome again.
 */
class WelcomeGate(
    private val seen: () -> Boolean,
    private val mark: () -> Unit,
) {
    /** True on first launch after install; false once [markSeen] ran. */
    fun shouldShow(): Boolean = !seen()

    /** Persist dismissal — the welcome never shows again. */
    fun markSeen() = mark()

    companion object {
        /** Key in the `p2pgate_settings` SharedPreferences. */
        const val PREF_KEY = "welcome_seen"
    }
}

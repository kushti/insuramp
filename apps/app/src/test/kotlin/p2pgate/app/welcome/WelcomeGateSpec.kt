package p2pgate.app.welcome

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The once-after-install welcome gate (the SharedPreferences plumbing is
 * injected; the show/skip/mark logic is what is tested here).
 */
class WelcomeGateSpec {

    /** A fake pref store with the same shape as the `welcome_seen` flag. */
    private class FakePrefs(var seen: Boolean = false)

    private fun gate(prefs: FakePrefs) = WelcomeGate(
        seen = { prefs.seen },
        mark = { prefs.seen = true },
    )

    @Test
    fun `flag unset — the welcome shows`() {
        assertTrue(gate(FakePrefs()).shouldShow())
    }

    @Test
    fun `flag set — the welcome is skipped`() {
        assertFalse(gate(FakePrefs(seen = true)).shouldShow())
    }

    @Test
    fun `markSeen persists across reads and recreation`() {
        val prefs = FakePrefs()
        val first = gate(prefs)
        assertTrue(first.shouldShow())
        first.markSeen()
        // A fresh gate instance over the same store = a new process.
        assertFalse(gate(prefs).shouldShow())
    }

    @Test
    fun `the flag lives under the welcome_seen key in p2pgate_settings`() {
        assertEquals("welcome_seen", WelcomeGate.PREF_KEY)
    }
}

package p2pgate.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.module

/**
 * The seller-dashboard static app shell (specs/seller-dashboard.md §1): the
 * html/js/css under `/dashboard/` is served by the same Ktor deployable. The
 * shell is public — it renders nothing without a token, and the API itself
 * stays operator-token-gated (covered by ApiSpec's auth tests).
 */
class DashboardStaticSpec {

    private fun env() = TestEnv(operatorKey = "op-secret")

    private fun ApplicationTestBuilder.setup(env: TestEnv) {
        application { module(env.app) }
    }

    @Test
    fun `dashboard app shell is served at the dashboard root`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { followRedirects = false }
        val root = client.get("/dashboard/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals(ContentType.Text.Html, root.contentType()?.withoutParameters())
        // no-cache: a stale cached shell is exactly how the dashboard renders
        // blank in a browser (old index referencing a deleted app.js).
        assertEquals("no-cache", root.headers[HttpHeaders.CacheControl])
        val markup = root.bodyAsText()
        assertTrue(markup.contains("login-form"), "app markup: login gate")
        assertTrue(markup.contains("meeting-view"), "app markup: meeting screen")
        assertTrue(markup.contains("app.js"), "app markup loads app.js")

        // `/dashboard` (no trailing slash) redirects rather than serving the
        // shell: the shell's asset URLs are relative, and at the slash-less
        // path the browser resolves them against the server root
        // (app.js → /app.js → 404 → both views stay hidden, a blank page).
        val bare = client.get("/dashboard")
        assertEquals(HttpStatusCode.Found, bare.status)
        assertEquals("/dashboard/", bare.headers[HttpHeaders.Location])
        val index = client.get("/dashboard/index.html")
        assertEquals(HttpStatusCode.OK, index.status)
        assertEquals(markup, index.bodyAsText())
    }

    @Test
    fun `dashboard assets are served with their content types`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val js = client.get("/dashboard/app.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertEquals(ContentType("text", "javascript"), js.contentType()?.withoutParameters())
        assertTrue(js.bodyAsText().contains("/v1/events"), "app.js wires the events socket")

        val css = client.get("/dashboard/styles.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertEquals(ContentType.Text.CSS, css.contentType()?.withoutParameters())
        assertTrue(css.bodyAsText().contains("meeting-card"), "styles cover the meeting screen")
        assertEquals("no-cache", js.headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", css.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `unknown dashboard subpath 404s`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        assertEquals(HttpStatusCode.NotFound, client.get("/dashboard/no-such-asset.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/dashboard/deadbeef/deeper").status)
    }
}

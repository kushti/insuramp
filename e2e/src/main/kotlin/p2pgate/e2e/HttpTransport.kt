package p2pgate.e2e

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * JDK `java.net.http` transport for the e2e harness — the same
 * injectable-transport pattern as [p2pgate.ergo.ExplorerChainSource] (whose
 * GET seam the harness feeds via [get]), extended with a POST for the
 * explorer's tx-submission endpoint and cross-origin GETs for the faucet
 * (paths are full URLs, as the core transport expects).
 */
class HttpTransport(private val timeoutSeconds: Long = 30) {

    private val client: HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    class HttpException(val status: Int, val url: String, body: String) :
        RuntimeException("HTTP $status for $url: ${body.take(300)}")

    /** GET [url]; non-2xx throws [HttpException] (mirrors the core transport). */
    fun get(url: String): String = request("GET", url, null)

    /** POST [body] as application/json to [url]. */
    fun postJson(url: String, body: String): String = request("POST", url, body)

    private fun request(method: String, url: String, body: String?): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
        if (body != null) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.GET()
        }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() / 100 != 2) throw HttpException(resp.statusCode(), url, resp.body())
        return resp.body()
    }
}

package caloo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Serveur web de Caloo : choisit l'extension d'après le nom de domaine (en-tête Host). */
class WebServer(cfg: Config, private val store: ExtensionStore) {
    private val server = HttpServer.create(InetSocketAddress(cfg.httpBind, cfg.httpPort), 64).apply {
        executor = Executors.newFixedThreadPool(16)
        createContext("/") { handle(it) }
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            val host = (ex.requestHeaders.getFirst("Host") ?: "").substringBefore(':').lowercase()
            val ext = store.match(host)
            if (ext == null) homePage(ex) else Actions.get(ext.action)!!.http(ext, ex)
        } catch (e: Exception) {
            runCatching { Web.send(ex, 500, "text/plain; charset=utf-8", "Caloo : erreur (${e.message})") }
        } finally {
            ex.close()
        }
    }

    /** Page affichée quand on ouvre l'IP de Caloo directement : liste des sites. */
    private fun homePage(ex: HttpExchange) {
        val links = store.all.filter { it.enabled && Actions.get(it.action)?.dns(it) == DnsAnswer.Self }
            .mapNotNull { e -> e.domains.firstOrNull { !it.startsWith("*.") }?.let { e to it } }
            .joinToString("") { (e, d) -> "<li><a href=\"http://${Web.esc(d)}/\">${Web.esc(d)}</a> <small>${Web.esc(e.name)} · ${e.action}</small></li>" }
        Web.send(
            ex, 404, "text/html; charset=utf-8",
            """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Caloo</title><style>body{font:16px system-ui;max-width:40rem;margin:2rem auto;padding:0 1rem}</style>
            <h1>Caloo</h1><p>Aucune extension pour ce nom. Sites disponibles :</p><ul>$links</ul>""".trimIndent(),
        )
    }
}

object Web {
    private val MIME = mapOf(
        "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json", "txt" to "text/plain; charset=utf-8", "xml" to "application/xml",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon", "avif" to "image/avif",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav",
        "pdf" to "application/pdf", "wasm" to "application/wasm", "webmanifest" to "application/manifest+json",
    )

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun send(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.set("Content-Type", type)
        val head = ex.requestMethod == "HEAD" || bytes.isEmpty()
        ex.sendResponseHeaders(code, if (head) -1 else bytes.size.toLong())
        if (!head) ex.responseBody.write(bytes)
        ex.close()
    }

    fun json(ex: HttpExchange, code: Int, body: Any) = send(ex, code, "application/json; charset=utf-8", body.toString())

    fun redirect(ex: HttpExchange, location: String, code: Int) {
        ex.responseHeaders.set("Location", location)
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }

    fun sendFile(ex: HttpExchange, f: File, code: Int) {
        ex.responseHeaders.set("Content-Type", MIME[f.extension.lowercase()] ?: "application/octet-stream")
        ex.responseHeaders.set("Cache-Control", "no-cache")
        val len = f.length()
        if (ex.requestMethod == "HEAD" || len == 0L) {
            ex.sendResponseHeaders(code, -1)
        } else {
            ex.sendResponseHeaders(code, len)
            f.inputStream().use { it.copyTo(ex.responseBody) }
        }
        ex.close()
    }
}

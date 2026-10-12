package caloo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * API de gestion utilisée par l'app Android (accessible uniquement par le tunnel WireGuard).
 *   GET    /api/status
 *   GET    /api/extensions
 *   PUT    /api/extensions/{nom}          corps = manifest JSON
 *   DELETE /api/extensions/{nom}
 *   PUT    /api/extensions/{nom}/site     corps = fichier .zip
 *   GET    /api/log
 *   POST   /api/reload
 */
class ApiServer(private val cfg: Config, private val store: ExtensionStore, private val log: QueryLog) {
    private val started = System.currentTimeMillis()
    private val token = cfg.token.toByteArray()
    private val server = HttpServer.create(InetSocketAddress(cfg.apiBind, cfg.apiPort), 16).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/api/") { handle(it) }
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            val auth = (ex.requestHeaders.getFirst("Authorization") ?: "").removePrefix("Bearer ").toByteArray()
            if (!MessageDigest.isEqual(auth, token)) return Web.json(ex, 401, err("token invalide"))
            route(ex)
        } catch (e: NoSuchElementException) {
            Web.json(ex, 404, err(e.message))
        } catch (e: IllegalArgumentException) {
            Web.json(ex, 400, err(e.message))
        } catch (e: Exception) {
            runCatching { Web.json(ex, 500, err(e.message)) }
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange) {
        val parts = ex.requestURI.path.removePrefix("/api/").trim('/').split('/').filter { it.isNotEmpty() }
        val m = ex.requestMethod
        when {
            m == "GET" && parts == listOf("status") -> Web.json(ex, 200, status())
            m == "GET" && parts == listOf("extensions") -> Web.json(ex, 200, JSONArray(store.all.map { it.toJson() }))
            m == "GET" && parts == listOf("log") -> Web.json(ex, 200, log.toJson())
            m == "POST" && parts == listOf("reload") -> {
                store.reload()
                Web.json(ex, 200, status())
            }
            parts.size == 2 && parts[0] == "extensions" && m == "PUT" -> {
                val body = read(ex.requestBody, 64 * 1024)
                val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) {
                    throw IllegalArgumentException("JSON invalide : ${e.message}")
                }
                store.save(parts[1], json)
                Web.json(ex, 200, store.get(parts[1])?.toJson() ?: JSONObject())
            }
            parts.size == 2 && parts[0] == "extensions" && m == "DELETE" -> {
                store.delete(parts[1])
                Web.json(ex, 200, JSONObject().put("ok", true))
            }
            parts.size == 3 && parts[0] == "extensions" && parts[2] == "site" && m == "PUT" -> {
                store.putSite(parts[1], ex.requestBody)
                Web.json(ex, 200, store.get(parts[1])?.toJson() ?: JSONObject())
            }
            else -> Web.json(ex, 404, err("route inconnue"))
        }
    }

    private fun status() = JSONObject()
        .put("version", VERSION)
        .put("uptime", (System.currentTimeMillis() - started) / 1000)
        .put("extensions", store.all.size)
        .put("queries", log.total.get())
        .put("actions", JSONArray(Actions.names()))
        .put("errors", JSONArray(store.errors))
        .put("selfIp", cfg.selfIp)

    private fun err(msg: String?) = JSONObject().put("error", msg ?: "erreur")

    private fun read(input: InputStream, max: Int): ByteArray {
        val bytes = input.readNBytes(max + 1)
        if (bytes.size > max) throw IOException("corps trop gros")
        return bytes
    }
}

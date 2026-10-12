package caloo

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Ce que le DNS doit répondre pour un domaine. */
sealed interface DnsAnswer {
    /** Pas de règle : on demande au DNS amont. */
    data object Forward : DnsAnswer
    /** Domaine inexistant (NXDOMAIN). */
    data object Block : DnsAnswer
    /** IP de Caloo (le serveur web de Caloo répondra). */
    data object Self : DnsAnswer
    data class Ip(val addr: InetAddress) : DnsAnswer
    data class Upstream(val servers: List<String>) : DnsAnswer
}

/**
 * Une action = un type d'extension. Pour ajouter un nouveau type :
 * écrire un `object MonAction : Action` et l'enregistrer dans Actions. Le reste du noyau ne change pas.
 */
interface Action {
    /** Vérifie le manifest (lève une exception avec un message clair si invalide). */
    fun validate(ext: Extension) {}
    fun dns(ext: Extension): DnsAnswer
    /** Requête web reçue par Caloo pour un domaine de cette extension. */
    fun http(ext: Extension, ex: HttpExchange) {
        Web.send(ex, 404, "text/plain; charset=utf-8", "Caloo : l'extension « ${ext.name} » ne sert pas de pages web.")
    }
}

object Actions {
    private val map = ConcurrentHashMap<String, Action>()

    fun register(name: String, action: Action) { map[name] = action }
    fun get(name: String): Action? = map[name]
    fun names(): List<String> = map.keys.sorted()

    init {
        register("block", BlockAction)
        register("ip", IpAction)
        register("dns", DnsAction)
        register("site", SiteAction)
        register("proxy", ProxyAction)
        register("redirect", RedirectAction)
    }
}

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6 = Regex("^[0-9a-fA-F:.]+$")

/** Parse une IP littérale sans jamais faire de requête DNS. */
fun ipLiteral(s: String): InetAddress? {
    val t = s.trim()
    if (!(IPV4.matches(t) && t.split('.').all { it.toInt() <= 255 }) && !(':' in t && IPV6.matches(t))) return null
    return try { InetAddress.getByName(t) } catch (e: Exception) { null }
}

private fun httpUrl(target: String): URI {
    val u = try { URI(target) } catch (e: Exception) { null }
    require(u != null && u.scheme in setOf("http", "https") && !u.host.isNullOrEmpty()) {
        "target doit être une URL, ex. http://192.168.1.20:3000"
    }
    return u
}

/** Bloque le domaine. */
object BlockAction : Action {
    override fun dns(ext: Extension) = DnsAnswer.Block
}

/** Répond une IP fixe (ex. une machine de ton réseau). target = "192.168.1.20" */
object IpAction : Action {
    override fun validate(ext: Extension) {
        requireNotNull(ipLiteral(ext.target)) { "target doit être une adresse IP, ex. 192.168.1.20" }
    }
    override fun dns(ext: Extension) = DnsAnswer.Ip(ipLiteral(ext.target)!!)
}

/** Demande à un autre DNS. target = "1.1.1.1" ou "1.1.1.1, 8.8.8.8" */
object DnsAction : Action {
    private fun servers(ext: Extension) = ext.target.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    override fun validate(ext: Extension) {
        val s = servers(ext)
        require(s.isNotEmpty() && s.all { ipLiteral(it.substringBefore(':')) != null || ipLiteral(it) != null }) {
            "target doit être une ou plusieurs IP de DNS, ex. 1.1.1.1, 8.8.8.8"
        }
    }
    override fun dns(ext: Extension) = DnsAnswer.Upstream(servers(ext))
}

/**
 * Sert le dossier site/ de l'extension (HTML/CSS/JS).
 * options : "spa": true → renvoie index.html pour les chemins inconnus (applis React/Vue…)
 */
object SiteAction : Action {
    override fun dns(ext: Extension) = DnsAnswer.Self

    override fun http(ext: Extension, ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return Web.send(ex, 405, "text/plain", "Méthode non autorisée")
        val root = File(ext.dir, "site").canonicalFile
        if (!root.isDirectory) return Web.send(ex, 404, "text/plain; charset=utf-8", "Caloo : aucun site envoyé pour « ${ext.name} ».")
        val raw = ex.requestURI.rawPath ?: "/"
        val path = try { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (e: Exception) {
            return Web.send(ex, 400, "text/plain", "Chemin invalide")
        }
        var f = File(root, path).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) return Web.send(ex, 403, "text/plain", "Interdit")
        if (f.isDirectory) {
            if (!raw.endsWith("/")) return Web.redirect(ex, "$raw/", 301)
            f = File(f, "index.html")
        }
        if (!f.isFile && ext.options.optBoolean("spa")) f = File(root, "index.html")
        if (f.isFile) return Web.sendFile(ex, f, 200)
        val notFound = File(root, "404.html")
        if (notFound.isFile) Web.sendFile(ex, notFound, 404) else Web.send(ex, 404, "text/plain", "Introuvable")
    }
}

/** Transmet les requêtes web à un autre serveur. target = "http://192.168.1.20:3000" */
object ProxyAction : Action {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val hopHeaders = setOf(
        "host", "connection", "content-length", "expect", "upgrade", "keep-alive",
        "transfer-encoding", "te", "trailer", "proxy-connection", "proxy-authorization",
    )

    override fun validate(ext: Extension) { httpUrl(ext.target) }
    override fun dns(ext: Extension) = DnsAnswer.Self

    override fun http(ext: Extension, ex: HttpExchange) {
        val query = ex.requestURI.rawQuery?.let { "?$it" } ?: ""
        val uri = URI(ext.target.trimEnd('/') + (ex.requestURI.rawPath ?: "/") + query)
        val body = ex.requestBody.readBytes()
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .method(ex.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        ex.requestHeaders.forEach { (k, values) ->
            if (k.lowercase() !in hopHeaders) values.forEach { v -> runCatching { req.header(k, v) } }
        }
        runCatching { req.header("X-Forwarded-Host", ex.requestHeaders.getFirst("Host") ?: "") }
        runCatching { req.header("X-Forwarded-For", ex.remoteAddress.address.hostAddress) }

        val resp = try {
            client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            return Web.send(ex, 502, "text/plain; charset=utf-8", "Caloo proxy : ${ext.target} injoignable (${e.message})")
        }
        resp.headers().map().forEach { (k, values) ->
            if (!k.startsWith(":") && k.lowercase() !in hopHeaders) values.forEach { ex.responseHeaders.add(k, it) }
        }
        val len = resp.headers().firstValueAsLong("content-length").orElse(-1)
        val code = resp.statusCode()
        val noBody = ex.requestMethod == "HEAD" || code == 204 || code == 304 || len == 0L
        ex.sendResponseHeaders(code, if (noBody) -1 else if (len > 0) len else 0)
        resp.body().use { input -> if (!noBody) input.copyTo(ex.responseBody) }
        ex.close()
    }
}

/**
 * Redirige le navigateur vers une autre adresse. target = "https://exemple.com"
 * options : "keepPath": false → ignore le chemin demandé ; "permanent": true → code 301
 */
object RedirectAction : Action {
    override fun validate(ext: Extension) { httpUrl(ext.target) }
    override fun dns(ext: Extension) = DnsAnswer.Self

    override fun http(ext: Extension, ex: HttpExchange) {
        val keepPath = ext.options.optBoolean("keepPath", true)
        val query = ex.requestURI.rawQuery?.let { "?$it" } ?: ""
        val location = if (keepPath) ext.target.trimEnd('/') + (ex.requestURI.rawPath ?: "/") + query else ext.target
        Web.redirect(ex, location, if (ext.options.optBoolean("permanent")) 301 else 302)
    }
}

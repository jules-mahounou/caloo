package caloo

import org.json.JSONObject
import java.io.File

/** Configuration du serveur, lue depuis /etc/caloo/config.json. */
data class Config(
    val dnsBind: String,
    val dnsPort: Int,
    val httpBind: String,
    val httpPort: Int,
    val apiBind: String,
    val apiPort: Int,
    /** IP renvoyée par le DNS pour les extensions servies par Caloo (site, proxy, redirect). */
    val selfIp: String,
    val upstream: List<String>,
    val extensionsDir: File,
    val token: String,
    val maxSiteMb: Int,
) {
    companion object {
        fun load(file: File): Config {
            val j = JSONObject(file.readText())
            val up = j.optJSONArray("upstream")
            val token = j.getString("token")
            require(token.length >= 16) { "token trop court (16 caractères minimum)" }
            return Config(
                dnsBind = j.optString("dnsBind", "10.8.0.1"),
                dnsPort = j.optInt("dnsPort", 53),
                httpBind = j.optString("httpBind", "10.8.0.1"),
                httpPort = j.optInt("httpPort", 80),
                apiBind = j.optString("apiBind", "10.8.0.1"),
                apiPort = j.optInt("apiPort", 8053),
                selfIp = j.optString("selfIp", "10.8.0.1"),
                upstream = if (up == null) listOf("1.1.1.1", "9.9.9.9") else List(up.length()) { up.getString(it) },
                extensionsDir = File(j.optString("extensionsDir", "/var/lib/caloo/extensions")),
                token = token,
                maxSiteMb = j.optInt("maxSiteMb", 100),
            )
        }
    }
}

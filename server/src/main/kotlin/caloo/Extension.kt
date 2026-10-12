package caloo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Une extension = un dossier dans extensionsDir :
 *   <nom>/manifest.json   la règle (domaines, action, cible…)
 *   <nom>/site/           fichiers statiques (seulement pour l'action "site")
 */
class Extension(
    val name: String,
    val dir: File,
    val enabled: Boolean,
    val priority: Int,
    val domains: List<String>,
    val action: String,
    val target: String,
    val ttl: Int,
    val options: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("enabled", enabled)
        .put("priority", priority)
        .put("domains", JSONArray(domains))
        .put("action", action)
        .put("target", target)
        .put("ttl", ttl)
        .put("options", options)
        .put("hasSite", File(dir, "site").isDirectory)

    companion object {
        private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val DOMAIN = Regex("^(\\*\\.)?([a-z0-9_-]{1,63}\\.)*[a-z0-9_-]{1,63}$")

        fun validName(n: String) = NAME.matches(n)

        fun fromJson(name: String, dir: File, j: JSONObject): Extension {
            require(validName(name)) { "nom invalide « $name » (minuscules, chiffres, - et _)" }
            val d = j.optJSONArray("domains") ?: JSONArray()
            val domains = List(d.length()) { d.getString(it).trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }
            domains.forEach { require(DOMAIN.matches(it)) { "domaine invalide « $it »" } }
            val actionName = j.optString("action", "site")
            val action = requireNotNull(Actions.get(actionName)) {
                "action inconnue « $actionName » (possibles : ${Actions.names().joinToString()})"
            }
            val ext = Extension(
                name = name,
                dir = dir,
                enabled = j.optBoolean("enabled", true),
                priority = j.optInt("priority", 0).coerceIn(-1000, 1000),
                domains = domains,
                action = actionName,
                target = j.optString("target", "").trim(),
                ttl = j.optInt("ttl", 60).coerceIn(0, 86400),
                options = j.optJSONObject("options") ?: JSONObject(),
            )
            action.validate(ext)
            return ext
        }
    }
}

/** Charge les extensions depuis le disque et trouve celle qui correspond à un domaine. */
class ExtensionStore(val root: File, private val maxSiteBytes: Long) {
    @Volatile var all: List<Extension> = emptyList()
        private set
    @Volatile var errors: List<String> = emptyList()
        private set
    private var signature = ""

    @Synchronized
    fun reload(): List<String> {
        root.mkdirs()
        val errs = mutableListOf<String>()
        all = dirs().mapNotNull { dir ->
            val mf = File(dir, "manifest.json")
            if (!mf.isFile) return@mapNotNull null
            try {
                Extension.fromJson(dir.name, dir, JSONObject(mf.readText()))
            } catch (e: Exception) {
                errs += "${dir.name} : ${e.message}"
                null
            }
        }
        errors = errs
        signature = currentSignature()
        return errs
    }

    /** Recharge si un manifest a été modifié à la main sur le serveur. */
    fun reloadIfChanged() {
        if (currentSignature() != signature) reload()
    }

    private fun dirs() = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()

    private fun currentSignature() = dirs().joinToString("|") {
        val mf = File(it, "manifest.json")
        "${it.name}:${mf.lastModified()}:${mf.length()}"
    }

    /** Priorité la plus haute d'abord, puis le domaine le plus précis (exact > *.long > *.court). */
    fun match(host: String): Extension? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        var best: Extension? = null
        var bestScore = Long.MIN_VALUE
        for (e in all) {
            if (!e.enabled) continue
            for (p in e.domains) {
                val precision = when {
                    p == h -> 10_000 + p.length
                    p.startsWith("*.") && h.endsWith(p.substring(1)) -> p.length
                    else -> continue
                }
                val score = e.priority * 100_000L + precision
                if (score > bestScore) {
                    bestScore = score
                    best = e
                }
            }
        }
        return best
    }

    fun get(name: String) = all.firstOrNull { it.name == name }

    private fun dirOf(name: String): File {
        require(Extension.validName(name)) { "nom invalide « $name » (minuscules, chiffres, - et _)" }
        return File(root, name)
    }

    @Synchronized
    fun save(name: String, manifest: JSONObject) {
        val dir = dirOf(name)
        manifest.remove("name")
        manifest.remove("hasSite")
        Extension.fromJson(name, dir, manifest) // valide avant d'écrire
        dir.mkdirs()
        val tmp = File(dir, ".manifest.json.tmp")
        tmp.writeText(manifest.toString(2))
        if (!tmp.renameTo(File(dir, "manifest.json"))) throw IOException("écriture impossible")
        reload()
    }

    @Synchronized
    fun delete(name: String) {
        val dir = dirOf(name)
        if (!dir.exists()) throw NoSuchElementException("extension inconnue « $name »")
        dir.deleteRecursively()
        reload()
    }

    /** Remplace le dossier site/ par le contenu d'un .zip (protégé contre les chemins « ../ »). */
    @Synchronized
    fun putSite(name: String, zip: InputStream) {
        val dir = dirOf(name)
        if (!File(dir, "manifest.json").isFile) throw NoSuchElementException("crée d'abord l'extension « $name »")
        val tmp = File(dir, ".site.tmp").apply { deleteRecursively(); mkdirs() }
        val base = tmp.canonicalFile
        var total = 0L
        var count = 0
        try {
            ZipInputStream(zip).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (++count > 20_000) throw IOException("trop de fichiers dans le zip")
                    val out = File(base, entry.name).canonicalFile
                    if (!out.path.startsWith(base.path + File.separator)) throw IOException("chemin interdit : ${entry.name}")
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxSiteBytes) throw IOException("site trop gros (max ${maxSiteBytes / 1_048_576} Mo)")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            if (count == 0) throw IOException("zip vide ou invalide")
            // Si le zip contient un seul dossier racine (ex. « monsite/index.html »), on le remonte.
            val kids = base.listFiles().orEmpty()
            val content = if (kids.size == 1 && kids[0].isDirectory && !File(base, "index.html").exists()) kids[0] else base
            val site = File(dir, "site")
            site.deleteRecursively()
            if (!content.renameTo(site)) throw IOException("impossible d'installer le site")
        } finally {
            tmp.deleteRecursively()
        }
        reload()
    }
}

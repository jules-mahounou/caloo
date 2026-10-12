package caloo

import java.io.File
import java.net.BindException
import kotlin.system.exitProcess
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val VERSION = "1.0.0"

fun main(args: Array<String>) {
    val cfg = Config.load(File(args.firstOrNull() ?: "/etc/caloo/config.json"))
    val store = ExtensionStore(cfg.extensionsDir, cfg.maxSiteMb * 1_048_576L)
    store.reload().forEach { System.err.println("Extension ignoree : $it") }

    val log = QueryLog()
    val dns = open("le DNS", cfg.dnsBind, cfg.dnsPort) { DnsServer(cfg, store, log) }.also { it.start() }
    val web = open("le serveur web", cfg.httpBind, cfg.httpPort) { WebServer(cfg, store) }.also { it.start() }
    val api = open("l'API", cfg.apiBind, cfg.apiPort) { ApiServer(cfg, store, log) }.also { it.start() }

    // Les modifications faites à la main sur le serveur sont prises en compte toutes seules.
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(
        { runCatching { store.reloadIfChanged() } }, 5, 5, TimeUnit.SECONDS,
    )

    println(
        "Caloo $VERSION - ${store.all.size} extension(s) - DNS ${cfg.dnsBind}:${cfg.dnsPort} - " +
            "web ${cfg.httpBind}:${cfg.httpPort} - API ${cfg.apiBind}:${cfg.apiPort}",
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        api.stop()
        web.stop()
        dns.stop()
    })
    Thread.currentThread().join()
}

/** Ouvre un serveur, avec un message clair si le port est déjà pris ou l'adresse absente. */
private fun <T> open(what: String, host: String, port: Int, create: () -> T): T = try {
    create()
} catch (e: BindException) {
    System.err.println("ERREUR : impossible d'ouvrir $what sur $host:$port (${e.message}).")
    System.err.println("Un autre programme utilise ce port, ou l'interface WireGuard n'est pas active.")
    exitProcess(1)
}

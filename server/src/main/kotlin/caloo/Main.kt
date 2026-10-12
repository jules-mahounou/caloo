package caloo

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val VERSION = "1.0.0"

fun main(args: Array<String>) {
    val cfg = Config.load(File(args.firstOrNull() ?: "/etc/caloo/config.json"))
    val store = ExtensionStore(cfg.extensionsDir, cfg.maxSiteMb * 1_048_576L)
    store.reload().forEach { System.err.println("Extension ignoree : $it") }

    val log = QueryLog()
    val dns = DnsServer(cfg, store, log).also { it.start() }
    val web = WebServer(cfg, store).also { it.start() }
    val api = ApiServer(cfg, store, log).also { it.start() }

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

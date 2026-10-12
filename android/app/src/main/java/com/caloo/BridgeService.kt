package com.caloo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Pont UDP → TCP pour WireGuard quand l'hébergeur bloque l'UDP.
 * WireGuard (Endpoint = 127.0.0.1:51820) envoie ses paquets ici ; on les relaie au serveur Caloo en TCP,
 * chacun précédé de sa longueur sur 2 octets, et inversement.
 */
class BridgeService : Service() {

    companion object {
        const val LOCAL_PORT = 51820
        const val ACTION_STOP = "com.caloo.STOP_BRIDGE"
        private const val CHANNEL = "bridge"

        @Volatile var running = false
            private set
        @Volatile var state = "arrêté"
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var stopping = false
    @Volatile private var wgAddr: SocketAddress? = null
    private var worker: Thread? = null
    private var udp: DatagramSocket? = null
    @Volatile private var tcp: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val target = getSharedPreferences("caloo", MODE_PRIVATE).getString("bridge", "")!!.trim()
        if (target.isEmpty() || !target.contains(':')) {
            state = "adresse du pont manquante (Réglages)"
            stopSelf()
            return START_NOT_STICKY
        }
        showNotification("Connexion à $target…")
        if (worker == null) {
            stopping = false
            running = true
            worker = thread(name = "caloo-bridge") { loop(target) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        state = "arrêté"
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun loop(target: String) {
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toIntOrNull() ?: 8443
        val local = try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_PORT)).apply { soTimeout = 1000 }
        } catch (e: IOException) {
            state = "port local $LOCAL_PORT indisponible : ${e.message}"
            stopSelf()
            return
        }
        udp = local
        val buf = ByteArray(65535)
        while (!stopping) {
            try {
                state = "connexion à $target…"
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 10_000)
                tcp = s
                state = "connecté à $target"
                showNotification("Pont actif vers $target")
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

                // serveur → WireGuard
                thread(name = "caloo-bridge-in") {
                    try {
                        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                        while (true) {
                            val n = input.readUnsignedShort()
                            val b = ByteArray(n)
                            input.readFully(b)
                            wgAddr?.let { local.send(DatagramPacket(b, n, it)) }
                        }
                    } catch (e: IOException) {
                        // connexion coupée
                    }
                    runCatching { s.close() }
                }

                // WireGuard → serveur
                while (!stopping && !s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        local.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    wgAddr = p.socketAddress
                    out.writeShort(p.length)
                    out.write(p.data, 0, p.length)
                    out.flush()
                }
            } catch (e: IOException) {
                if (!stopping) state = "déconnecté (${e.message}), nouvel essai…"
            }
            runCatching { tcp?.close() }
            if (!stopping) {
                showNotification("Reconnexion…")
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
        }
        runCatching { local.close() }
    }

    private fun showNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pont TCP", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_logo_fg)
            .setContentTitle("Caloo · pont TCP")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}

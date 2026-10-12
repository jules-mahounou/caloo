package caloo

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Pont TCP → UDP pour WireGuard, quand l'hébergeur bloque l'UDP entrant.
 * Chaque paquet UDP voyage dans le flux TCP sous la forme : 2 octets de longueur + le paquet.
 * L'app Caloo fait l'opération inverse sur le téléphone.
 * Sans danger : WireGuard ignore tout paquet qui n'est pas signé avec les clés d'un appareil connu.
 */
class TcpBridge(bind: String, port: Int, target: String) {
    private val server = ServerSocket(port, 16, InetAddress.getByName(bind))
    private val target = InetSocketAddress(
        InetAddress.getByName(target.substringBeforeLast(':')),
        target.substringAfterLast(':').toInt(),
    )
    private val active = AtomicInteger()

    fun start() {
        thread(name = "bridge", isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (active.incrementAndGet() > MAX_CLIENTS) {
                    active.decrementAndGet()
                    runCatching { s.close() }
                    continue
                }
                thread(name = "bridge-client", isDaemon = true) {
                    try { serve(s) } finally { active.decrementAndGet() }
                }
            }
        }
    }

    fun stop() = server.close()

    private fun serve(sock: Socket) = sock.use {
        sock.tcpNoDelay = true
        sock.soTimeout = 120_000 // WireGuard envoie un « keepalive » toutes les 25 s
        DatagramSocket().use { udp ->
            udp.connect(target)
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            thread(name = "bridge-udp", isDaemon = true) {
                val buf = ByteArray(65535)
                try {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        udp.receive(p)
                        synchronized(out) {
                            out.writeShort(p.length)
                            out.write(buf, 0, p.length)
                            out.flush()
                        }
                    }
                } catch (e: IOException) {
                    // socket fermée : fin de la connexion
                }
                runCatching { sock.close() }
            }
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            try {
                while (true) {
                    val n = input.readUnsignedShort()
                    val b = ByteArray(n)
                    input.readFully(b)
                    udp.send(DatagramPacket(b, n))
                }
            } catch (e: IOException) {
                // client déconnecté
            }
        }
    }

    companion object {
        const val MAX_CLIENTS = 16
    }
}

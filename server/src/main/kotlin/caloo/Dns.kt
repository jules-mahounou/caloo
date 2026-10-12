package caloo

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Lecture/écriture minimale de messages DNS (une seule question, réponses A/AAAA). */
object DnsMsg {
    class Question(val name: String, val type: Int, val end: Int)

    const val A = 1
    const val AAAA = 28
    const val ANY = 255

    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun question(q: ByteArray): Question? {
        if (q.size < 17) return null
        if (q[2].toInt() and 0x80 != 0) return null // c'est une réponse, pas une question
        if (u16(q, 4) != 1) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= q.size) return null
            val len = q[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) sb.append((q[i + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        if (i + 4 > q.size) return null
        return Question(sb.toString().lowercase(), u16(q, i), i + 4)
    }

    /** rcode : 0 = OK, 2 = SERVFAIL, 3 = NXDOMAIN. */
    fun reply(q: ByteArray, qs: Question, rcode: Int, addr: InetAddress?, ttl: Int): ByteArray {
        val withAnswer = addr != null && when (qs.type) {
            A -> addr is Inet4Address
            AAAA -> addr is Inet6Address
            ANY -> true
            else -> false
        }
        val out = ByteArrayOutputStream()
        out.write(q, 0, 2) // même identifiant
        out.write(0x84 or (q[2].toInt() and 0x01)) // QR + AA, RD recopié
        out.write(0x80 or (rcode and 0x0F)) // RA
        out.write(byteArrayOf(0, 1, 0, if (withAnswer) 1 else 0, 0, 0, 0, 0))
        out.write(q, 12, qs.end - 12)
        if (withAnswer) {
            val raw = addr!!.address
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            w16(out, if (raw.size == 4) A else AAAA)
            w16(out, 1)
            w16(out, ttl ushr 16)
            w16(out, ttl and 0xFFFF)
            w16(out, raw.size)
            out.write(raw)
        }
        return out.toByteArray()
    }

    private fun w16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}

/** Journal des dernières requêtes (pour l'app). */
class QueryLog(private val max: Int = 300) {
    private val items = ArrayDeque<JSONObject>()
    val total = AtomicLong()

    fun add(client: InetAddress, name: String, type: Int, ext: String?) {
        total.incrementAndGet()
        val item = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("client", client.hostAddress)
            .put("name", name)
            .put("type", when (type) { DnsMsg.A -> "A"; DnsMsg.AAAA -> "AAAA"; else -> type.toString() })
            .put("ext", ext ?: "")
        synchronized(items) {
            items.addLast(item)
            while (items.size > max) items.removeFirst()
        }
    }

    fun toJson(): JSONArray = synchronized(items) { JSONArray(items.reversed()) }
}

/** Serveur DNS (UDP + TCP). Applique les extensions, sinon transmet au DNS amont. */
class DnsServer(private val cfg: Config, private val store: ExtensionStore, private val log: QueryLog) {
    private val bind = InetAddress.getByName(cfg.dnsBind)
    private val udp = DatagramSocket(InetSocketAddress(bind, cfg.dnsPort))
    private val tcp = ServerSocket(cfg.dnsPort, 32, bind)
    private val pool = Executors.newFixedThreadPool(32)
    private val self = InetAddress.getByName(cfg.selfIp)

    fun start() {
        thread(name = "dns-udp", isDaemon = true) {
            val buf = ByteArray(4096)
            while (!udp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (e: IOException) { break }
                val data = p.data.copyOf(p.length)
                val from = p.socketAddress
                val client = p.address
                pool.execute {
                    val r = resolve(data, client, overTcp = false) ?: return@execute
                    runCatching { udp.send(DatagramPacket(r, r.size, from)) }
                }
            }
        }
        thread(name = "dns-tcp", isDaemon = true) {
            while (!tcp.isClosed) {
                val s = try { tcp.accept() } catch (e: IOException) { break }
                pool.execute { serveTcp(s) }
            }
        }
    }

    fun stop() {
        udp.close()
        tcp.close()
        pool.shutdownNow()
    }

    private fun serveTcp(s: Socket) = s.use {
        it.soTimeout = 10_000
        val input = DataInputStream(it.getInputStream())
        val output = DataOutputStream(it.getOutputStream())
        while (true) {
            val len = try { input.readUnsignedShort() } catch (e: IOException) { break }
            val q = ByteArray(len)
            try { input.readFully(q) } catch (e: IOException) { break }
            val r = resolve(q, it.inetAddress, overTcp = true) ?: break
            output.writeShort(r.size)
            output.write(r)
            output.flush()
        }
    }

    fun resolve(q: ByteArray, client: InetAddress, overTcp: Boolean): ByteArray? {
        val qs = DnsMsg.question(q)
        val opcode = if (q.size > 2) (q[2].toInt() shr 3) and 0x0F else -1
        if (qs == null || opcode != 0) return forward(q, cfg.upstream, overTcp)

        val ext = store.match(qs.name)
        val answer = ext?.let { Actions.get(it.action)?.dns(it) } ?: DnsAnswer.Forward
        log.add(client, qs.name, qs.type, ext?.name)
        val ttl = ext?.ttl ?: 0
        return when (answer) {
            DnsAnswer.Forward -> forward(q, cfg.upstream, overTcp)
            DnsAnswer.Block -> DnsMsg.reply(q, qs, 3, null, ttl)
            DnsAnswer.Self -> DnsMsg.reply(q, qs, 0, self, ttl)
            is DnsAnswer.Ip -> DnsMsg.reply(q, qs, 0, answer.addr, ttl)
            is DnsAnswer.Upstream -> forward(q, answer.servers, overTcp)
        } ?: DnsMsg.reply(q, qs, 2, null, 0)
    }

    private fun address(server: String): InetSocketAddress {
        val colons = server.count { it == ':' }
        return if (colons == 1) InetSocketAddress(ipLiteral(server.substringBefore(':')), server.substringAfter(':').toInt())
        else InetSocketAddress(ipLiteral(server), 53)
    }

    private fun forward(q: ByteArray, servers: List<String>, overTcp: Boolean): ByteArray? {
        for (server in servers) {
            try {
                val addr = address(server)
                val r = if (overTcp) forwardTcp(q, addr) else forwardUdp(q, addr)
                if (r != null && r.size >= 12 && r[0] == q[0] && r[1] == q[1]) return r
            } catch (e: Exception) {
                // serveur suivant
            }
        }
        return null
    }

    private fun forwardUdp(q: ByteArray, addr: InetSocketAddress): ByteArray? = DatagramSocket().use { s ->
        s.soTimeout = 2500
        s.send(DatagramPacket(q, q.size, addr))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        buf.copyOf(p.length)
    }

    private fun forwardTcp(q: ByteArray, addr: InetSocketAddress): ByteArray? = Socket().use { s ->
        s.connect(addr, 3000)
        s.soTimeout = 5000
        DataOutputStream(s.getOutputStream()).apply { writeShort(q.size); write(q); flush() }
        val input = DataInputStream(s.getInputStream())
        ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
    }
}

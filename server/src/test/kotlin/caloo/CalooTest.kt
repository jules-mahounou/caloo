package caloo

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalooTest {
    private fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        return out.toByteArray()
    }

    private fun store(): ExtensionStore {
        val dir = Files.createTempDirectory("caloo").toFile()
        return ExtensionStore(dir, 10_000_000)
    }

    private fun manifest(action: String, target: String, vararg domains: String, priority: Int = 0) =
        JSONObject().put("action", action).put("target", target).put("domains", JSONArray(domains.toList())).put("priority", priority)

    @Test
    fun `lit la question et construit une réponse A`() {
        val q = query("monsite.home", DnsMsg.A)
        val qs = DnsMsg.question(q)!!
        assertEquals("monsite.home", qs.name)
        val r = DnsMsg.reply(q, qs, 0, InetAddress.getByName("10.8.0.1"), 60)
        assertEquals(0x12, r[0].toInt())
        assertEquals(1, DnsMsg.u16(r, 6)) // une réponse
        assertEquals(listOf(10, 8, 0, 1), r.takeLast(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `AAAA vers une IPv4 donne une réponse vide`() {
        val q = query("monsite.home", DnsMsg.AAAA)
        val r = DnsMsg.reply(q, DnsMsg.question(q)!!, 0, InetAddress.getByName("10.8.0.1"), 60)
        assertEquals(0, DnsMsg.u16(r, 6))
    }

    @Test
    fun `le domaine exact et la priorité gagnent`() {
        val s = store()
        s.save("large", manifest("block", "", "*.home"))
        s.save("precis", manifest("ip", "192.168.1.20", "nas.home"))
        s.save("prioritaire", manifest("ip", "192.168.1.30", "*.lan", priority = 5))
        s.save("autre", manifest("ip", "192.168.1.40", "x.lan"))
        assertEquals("precis", s.match("nas.home")?.name)
        assertEquals("large", s.match("tv.home")?.name)
        assertEquals("prioritaire", s.match("x.lan")?.name)
        assertNull(s.match("home"))
        assertNull(s.match("google.com"))
    }

    @Test
    fun `refuse les manifests invalides`() {
        val s = store()
        assertFailsWith<IllegalArgumentException> { s.save("../evil", manifest("block", "", "a.home")) }
        assertFailsWith<IllegalArgumentException> { s.save("x", manifest("ip", "pas-une-ip", "a.home")) }
        assertFailsWith<IllegalArgumentException> { s.save("x", manifest("inconnue", "", "a.home")) }
        assertFailsWith<IllegalArgumentException> { s.save("x", manifest("block", "", "mauvais domaine")) }
    }

    @Test
    fun `installe un site zip et bloque les chemins dangereux`() {
        val s = store()
        s.save("site", manifest("site", "", "site.home"))
        fun zip(vararg files: Pair<String, String>) = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()) } }
        }.toByteArray()

        s.putSite("site", ByteArrayInputStream(zip("monsite/index.html" to "ok", "monsite/css/a.css" to "a{}")))
        assertTrue(File(s.root, "site/site/index.html").isFile) // dossier racine remonté
        assertTrue(File(s.root, "site/site/css/a.css").isFile)

        assertFailsWith<java.io.IOException> { s.putSite("site", ByteArrayInputStream(zip("../../pirate.txt" to "x"))) }
        assertTrue(File(s.root, "site/site/index.html").isFile) // l'ancien site est intact
    }
}

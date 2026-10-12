package com.caloo

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Client de l'API du serveur Caloo (joignable via le tunnel WireGuard). */
class Api(private val base: String, private val token: String) {

    fun call(method: String, path: String, json: JSONObject? = null): String {
        val c = open(method, path)
        if (json != null) {
            val body = json.toString().toByteArray()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        return read(c)
    }

    fun upload(path: String, input: InputStream): String {
        val c = open("PUT", path)
        c.doOutput = true
        c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/zip")
        c.setChunkedStreamingMode(64 * 1024)
        c.outputStream.use { input.copyTo(it) }
        return read(c)
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val c = URL(base.trim().trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun read(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) {
                throw IOException(runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "erreur HTTP $code")
            }
            return text
        } finally {
            c.disconnect()
        }
    }
}

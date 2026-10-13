package com.restopos.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for the Firebase Realtime Database REST API.
 * (No Firebase SDK or google-services.json needed.)
 */
class Rtdb(base: String, private val auth: String) {
    private val b = base.trim().trimEnd('/')

    class Res(val code: Int, val body: String, val etag: String?)

    private fun url(path: String, query: String): URL {
        val q = StringBuilder()
        if (query.isNotBlank()) q.append(query)
        if (auth.isNotBlank()) {
            if (q.isNotEmpty()) q.append("&")
            q.append("auth=").append(URLEncoder.encode(auth, "UTF-8"))
        }
        return URL("$b/$path.json" + if (q.isNotEmpty()) "?$q" else "")
    }

    private fun fail(code: Int): Nothing {
        val why = when (code) {
            401, 403 -> "permission denied, check database rules"
            404 -> "database not found, check the URL"
            else -> "error"
        }
        throw IOException("HTTP $code ($why)")
    }

    /** GET. Pass the previous [etag] to get a cheap 304 when nothing changed. */
    fun get(path: String, etag: String? = null, query: String = ""): Res {
        val c = url(path, query).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.useCaches = false
            c.setRequestProperty("X-Firebase-ETag", "true")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            val code = c.responseCode
            if (code == 304) return Res(304, "", etag)
            if (code !in 200..299) fail(code)
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return Res(code, body, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /** PUT a JSON value at [path]. Use "null" to delete. */
    fun put(path: String, json: String) {
        val c = url(path, "").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) fail(code)
            c.inputStream.close()
        } finally {
            c.disconnect()
        }
    }
}

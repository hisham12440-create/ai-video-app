package com.montageai.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMs: Int = 180_000,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        mime: String,
        readTimeoutMs: Int = 600_000,
    ): String {
        val boundary = "----montage" + System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setChunkedStreamingMode(0)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) {
                    w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                }
                w(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; " +
                        "filename=\"${file.name}\"\r\nContent-Type: $mime\r\n\r\n"
                )
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: ${text.take(400)}")
        return text
    }
}

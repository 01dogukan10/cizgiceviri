package com.dogukan.cizgi

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun indir(url: String, referer: String? = null): ByteArray {
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        if (referer != null) rb.header("Referer", referer)
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body!!.bytes()
        }
    }

    /** Sayfadaki çizgi roman resimlerini sırayla bulur. */
    fun resimBul(url: String): List<String> {
        val html = String(indir(url), Charsets.UTF_8)
        val doc = Jsoup.parse(html, url)
        val ok = Regex("\\.(jpe?g|png|webp)(\\?.*)?$", RegexOption.IGNORE_CASE)
        val yasak = Regex("logo|avatar|banner|icon|thumb|sprite|ads?/", RegexOption.IGNORE_CASE)
        return doc.select("img").mapNotNull { e ->
            val k = listOf("data-src", "data-lazy-src", "data-original", "src")
                .map { e.absUrl(it) }.firstOrNull { it.isNotBlank() }
            k?.takeIf { ok.containsMatchIn(it) && !yasak.containsMatchIn(it) }
        }.distinct()
    }
}

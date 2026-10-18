package com.dogukan.cizgi

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

object Ceviri {
    private val onbellek = HashMap<String, String>()

    fun cevir(metin: String, kaynak: String, hedef: String): String? {
        val anahtar = "$kaynak|$hedef|$metin"
        synchronized(onbellek) { onbellek[anahtar] }?.let { return it }
        val u = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("sl", kaynak)
            .addQueryParameter("tl", hedef).addQueryParameter("dt", "t")
            .addQueryParameter("q", metin).build()
        repeat(2) {
            try {
                Net.http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (r.isSuccessful) {
                        val a = JSONArray(r.body!!.string()).getJSONArray(0)
                        val sb = StringBuilder()
                        for (i in 0 until a.length()) sb.append(a.getJSONArray(i).getString(0))
                        val s = sb.toString().trim()
                        if (s.isNotEmpty()) { synchronized(onbellek) { onbellek[anahtar] = s }; return s }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

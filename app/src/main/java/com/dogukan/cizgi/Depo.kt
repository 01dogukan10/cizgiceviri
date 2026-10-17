package com.dogukan.cizgi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Kayit(val url: String, val baslik: String, val kategori: String, val dil: String)

/** Ayarlar, kategoriler ve kayıtlı linkler (SharedPreferences). */
class Depo(ctx: Context) {
    private val p = ctx.getSharedPreferences("cizgi", Context.MODE_PRIVATE)

    var klasorUri: String?
        get() = p.getString("klasor", null)
        set(v) { p.edit().putString("klasor", v).apply() }
    var tema: Int
        get() = p.getInt("tema", 0)
        set(v) { p.edit().putInt("tema", v).apply() }
    var renk: Int
        get() = p.getInt("renk", 0)
        set(v) { p.edit().putInt("renk", v).apply() }

    fun kategoriler(): List<String> = try {
        val a = JSONArray(p.getString("kategoriler", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun kategorileriKaydet(l: List<String>) {
        p.edit().putString("kategoriler", JSONArray(l).toString()).apply()
    }

    fun kayitlar(): List<Kayit> = try {
        val a = JSONArray(p.getString("kayitlar", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Kayit(o.getString("u"), o.getString("b"), o.optString("k"), o.optString("d", "LATIN"))
        }
    } catch (e: Exception) { emptyList() }

    fun kayitlariKaydet(l: List<Kayit>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("u", it.url).put("b", it.baslik).put("k", it.kategori).put("d", it.dil)) }
        p.edit().putString("kayitlar", a.toString()).apply()
    }
}
